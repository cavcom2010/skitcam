#!/usr/bin/env python3
"""Measure bounded A/V offsets for manually identified clap or flash cues in an MP4."""
import argparse
from array import array
from fractions import Fraction
import json
import math
from pathlib import Path
import subprocess
import sys


def probe(path, stream, entries):
    result = json.loads(subprocess.check_output([
        "ffprobe", "-v", "error", "-select_streams", stream, "-show_streams", "-show_frames",
        "-show_entries", entries, "-of", "json", str(path),
    ]))
    info, = result["streams"]
    return Fraction(info["time_base"]), result["frames"], info


def verdict(low, high):
    if low >= -0.1 and high <= 0.1:
        return "PASS"
    if low > 0.1 or high < -0.1:
        return "FAIL"
    return "INCONCLUSIVE"


def bounds(video, audio, padding):
    return audio[0] - padding - video[1], audio[1] + padding - video[0]


def milliseconds(interval):
    return [round(1000 * value, 3) for value in interval]


def audio_crossings(path, cues, rate, channels, time_base, frames):
    # Decode without resampling, downmixing, seeking, or shifting timestamps.
    # Tie each block's samples to its decoded frame PTS, retaining gaps/edit offsets.
    crossings = [None] * len(cues)
    command = ["ffmpeg", "-nostdin", "-v", "error", "-xerror", "-i", str(path),
               "-map", "0:a:0", "-c:a", "pcm_f32le", "-f", "f32le", "-"]
    with subprocess.Popen(command, stdout=subprocess.PIPE) as proc:
        try:
            previous_end = None
            previous_peak = None
            for frame in frames:
                count = int(frame["nb_samples"])
                start = int(frame["pts"]) * time_base
                end = start + Fraction(count, rate)
                if count <= 0 or (previous_end is not None and start < previous_end - time_base):
                    raise ValueError("Invalid or overlapping decoded audio timestamps")
                raw = proc.stdout.read(count * channels * 4)
                if len(raw) != count * channels * 4:
                    raise ValueError("Decoded PCM does not match probed frame sample counts")
                pcm = array("f", raw)
                if sys.byteorder != "little":
                    pcm.byteswap()
                for index, cue in enumerate(cues):
                    lower, upper = map(float, cue["audio_window_s"])
                    if crossings[index] is not None or float(end) <= lower or float(start) >= upper:
                        continue
                    first = max(0, math.ceil((lower - float(start)) * rate))
                    limit = min(count, math.ceil((upper - float(start)) * rate))
                    for sample in range(first, limit):
                        if max(map(abs, pcm[sample * channels:(sample + 1) * channels])) >= cue["threshold"]:
                            pos = start + Fraction(sample, rate)
                            previous_pos = pos - Fraction(1, rate)
                            if sample > first or (sample == 0 and previous_end == start
                                                  and previous_peak < cue["threshold"]
                                                  and float(previous_pos) >= lower):
                                crossings[index] = (float(previous_pos), float(pos))
                            else:
                                crossings[index] = "Crossing not bracketed at audio window or gap"
                            break
                previous_end = end
                previous_peak = max(map(abs, pcm[-channels:]))
            if proc.stdout.read(1) or proc.wait():
                raise ValueError("Decode failed or returned extra audio samples")
        finally:
            if proc.returncode is None:
                proc.kill()
                proc.wait()
    return crossings


def measure(path, document):
    cues = document["cues"]
    labels = [cue["label"] for cue in cues]
    if not labels or len(labels) != len(set(labels)):
        raise ValueError("Provide at least one cue with a unique label")
    for cue in cues:
        lower, upper = cue["audio_window_s"]
        values = (lower, upper, cue["threshold"], cue["padding_ms"])
        if (not all(math.isfinite(value) for value in values) or not lower < upper
                or not 0 < cue["threshold"] <= 1 or cue["padding_ms"] < 0):
            raise ValueError("Invalid cue window, threshold, or padding")
    vbase, video, _ = probe(path, "v:0", "stream=time_base:frame=pts")
    video_pts = [int(frame["pts"]) * vbase for frame in video]
    if any(b <= a for a, b in zip(video_pts, video_pts[1:])):
        raise ValueError("Non-increasing decoded video PTS")
    abase, audio, info = probe(
        path, "a:0", "stream=time_base,sample_rate,channels:frame=pts,nb_samples",
    )
    starts = audio_crossings(path, cues, int(info["sample_rate"]), int(info["channels"]), abase, audio)
    offsets = {}
    rows = []
    for cue, onset in zip(cues, starts):
        index = cue["first_contact_frame"]
        if type(index) is not int or not 0 < index < len(video_pts):
            raise ValueError("First-contact frame must follow an observed pre-contact frame")
        visual = (float(video_pts[index - 1]), float(video_pts[index]))
        label = cue["label"]
        row = {"label": label, "video_contact_bounds_ms": milliseconds(visual)}
        if not isinstance(onset, tuple):
            row.update(verdict="INCONCLUSIVE", reason=onset or "No threshold crossing in audio window")
        else:
            offset = bounds(visual, onset, cue["padding_ms"] / 1000)
            offsets[label] = offset
            row.update(audio_crossing_bounds_ms=milliseconds(onset), offset_bounds_ms=milliseconds(offset),
                       padding_ms=cue["padding_ms"], verdict=verdict(*offset))
        rows.append(row)
    drift = []
    for start, end in document.get("drift_pairs", []):
        if start not in labels or end not in labels:
            raise ValueError("Unknown cue in drift pair")
        value = None
        if start in offsets and end in offsets:
            a, b = offsets[start], offsets[end]
            value = milliseconds((b[0] - a[1], b[1] - a[0]))
        drift.append({"from": start, "to": end, "offset_change_bounds_ms": value})
    return {"convention": "audio minus video; positive = audio late", "cues": rows, "drift": drift}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mp4", type=Path)
    parser.add_argument("annotations", type=Path, help="JSON with CUE labels, decoded frame indices and audio windows")
    args = parser.parse_args()
    try:
        result = measure(args.mp4, json.loads(args.annotations.read_text()))
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"Could not measure cues: {error}\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
