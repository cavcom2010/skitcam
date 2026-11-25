#!/usr/bin/env python3
"""Collect local-only M15B.3.3 evidence for one named, disposable Moto session."""

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
from uuid import UUID


PACKAGE = "com.calvinmazhindu.skitcam.m15b33field"
ROOT = Path(__file__).resolve().parents[1] / ".validation/m15b33/field-jobs"


def expect(condition, message):
    if not condition:
        raise SystemExit(message)


def path_for_session(session):
    expect(str(UUID(session)) == session, "Use the exact validation session UUID.")
    return f"files/sessions/{session}"


def run(command, **kwargs):
    return subprocess.run(command, check=True, **kwargs)


def save(path, data):
    with path.open("x") as out:
        json.dump(data, out, indent=2)
        out.write("\n")


class FieldDevice:
    def __init__(self, serial):
        self.adb = ["adb", "-s", serial]
        expect(self.text("shell", "getprop", "ro.product.model").strip() == "moto g54 5G",
               "Not the expected Moto validation device.")
        packages = self.text("shell", "pm", "list", "packages", "--user", "0", PACKAGE).splitlines()
        expect({f"package:{PACKAGE}", f"package:{PACKAGE}.test"} <= set(packages),
               "The exact field validation APK pair is not installed.")

    def text(self, *args):
        return run([*self.adb, *args], stdout=subprocess.PIPE, text=True).stdout

    def read(self, path):
        return self.text("exec-out", "run-as", PACKAGE, "cat", path)

    def digest(self, path):
        digest = self.text("shell", "run-as", PACKAGE, "sha256sum", path).split()[0]
        expect(re.fullmatch(r"[0-9a-f]{64}", digest), "Invalid device SHA-256.")
        return digest

    def pull(self, path, local):
        with local.open("xb") as output:
            run([*self.adb, "exec-out", "run-as", PACKAGE, "cat", path], stdout=output)

    def snapshot(self, session):
        base = path_for_session(session)
        metadata = f"{base}/session.json"
        raw = self.read(metadata)
        data = json.loads(raw)
        expect(data["id"] == session and data["state"] in {"Paused", "Completed"},
               "Wait until the named disposable session is paused or finished.")
        expect(not data.get("pending"), "Wait for the latest take to finalise.")
        hashes = {}
        for clip in data["segments"]:
            clip_path = PurePosixPath(clip["path"])
            name = clip_path.name
            expect(re.fullmatch(r"segment_[0-9]+\.mp4", name) and name not in hashes and
                   str(clip_path.parent) in {f"/data/user/0/{PACKAGE}/{base}", f"/data/data/{PACKAGE}/{base}"},
                   "Unexpected segment path; refusing to read it.")
            hashes[name] = self.digest(f"{base}/{name}")
        expect(bool(hashes), "The named validation session has no confirmed sources.")
        expect(self.read(metadata) == raw, "The session changed during the snapshot; pause before collecting evidence.")
        return {"metadata": data, "sourceSha256": hashes}


def match_hashes(before, after, *, allow_appends=False):
    baseline, latest = before["sourceSha256"], after["sourceSha256"]
    expect((allow_appends or baseline.keys() == latest.keys()) and
           all(digest == latest.get(name) for name, digest in baseline.items()),
           "One or more confirmed validation sources changed; stop the run and preserve evidence.")


def decode(path):
    run(["ffmpeg", "-nostdin", "-v", "error", "-xerror", "-i", str(path),
         "-map", "0:v:0", "-map", "0:a:0", "-fps_mode", "passthrough",
         "-enc_time_base:v", "demux", "-f", "null", "-"])
    fields = "format=duration:stream=codec_name,width,height,sample_rate,channels,avg_frame_rate," \
        "start_time,duration,nb_frames:stream_side_data=rotation"
    return json.loads(run(["ffprobe", "-v", "error", "-show_entries", fields,
                           "-of", "json", str(path)], stdout=subprocess.PIPE).stdout)


def assert_frozen_edit(metadata, job):
    order = metadata["playbackOrder"]
    clips = {clip["id"]: clip for clip in metadata["segments"] if clip["kept"]}
    expect(len(order) == len(clips) and set(order) == clips.keys() and
           [item["id"] for item in job["spec"]] == order, "Frozen export order differs from the pre-Finish edit.")
    for item in job["spec"]:
        clip = clips[item["id"]]
        expected = clip.get("trim")
        expect(clip["path"] == item["path"], "Frozen export source differs from the pre-Finish edit.")
        if expected and (expected["trimStartMs"] or expected["trimEndMs"] != expected["sourceDurationMs"]):
            expect((item["start"], item["end"]) == (expected["trimStartMs"], expected["trimEndMs"]),
                   "Frozen export trim differs from the pre-Finish edit.")
        else:
            expect((item["start"], item["end"]) == (0, item["duration"]), "Unexpected trim on an unedited clip.")


def verify(device, args, directory):
    session, state = args.session, {"completed": "Completed", "cancelled": "Cancelled"}[args.action]
    base = path_for_session(session)
    before = json.loads((directory / "before-finish.json").read_text())
    expect(before["metadata"]["id"] == session, "This job label refers to a different session.")
    after = device.snapshot(session)
    job = json.loads(device.read(f"{base}/export/job.json"))
    expect(job["sessionId"] == session and job["state"] == state, "The named export has not settled in the requested state.")
    save(directory / f"{args.action}-originals.json", after)
    save(directory / f"{args.action}-job.json", job)
    match_hashes(before, after)
    if state == "Completed":
        assert_frozen_edit(before["metadata"], job)
    output = device.text("shell", "am", "instrument", "--user", "0", "-w", "-r",
                         "-e", "class", f"{PACKAGE.rsplit('.', 1)[0]}.export.FieldPublicationDeviceTest",
                         "-e", "fieldSession", session, "-e", "fieldState", state,
                         f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner")
    with (directory / f"{args.action}-device.log").open("x") as log:
        log.write(output)
    expect("OK (1 test)" in output.splitlines() and
           not re.search(r"^INSTRUMENTATION_STATUS_CODE: -[234]|^FAILURES!!!", output, re.M),
           "Publication assertion did not pass; preserve the scoped receipt.")
    receipt = {"state": state, "jobId": job["id"], "publicationRows": []}
    if state == "Completed":
        uri, digest = job["uri"], job["verifiedSha"]
        expect(re.fullmatch(r"content://media/external_primary/video/media/[0-9]+", uri),
               "Unexpected publication URI.")
        local = directory / "gallery-readback.mp4"
        device.pull(f"files/m15b3-published/{session}.mp4", local)
        with local.open("rb") as media:
            expect(hashlib.file_digest(media, "sha256").hexdigest() == digest, "Gallery digest mismatch; stop.")
        receipt["publicationRows"] = [{"uri": uri, "pending": False, "sha256": digest}]
        receipt["media"] = decode(local)
        save(directory / "performance.json", json.loads(device.read(f"{base}/export/performance.json")))
        if args.copy_sources:
            for name, original_digest in after["sourceSha256"].items():
                local = directory / name
                device.pull(f"{base}/{name}", local)
                with local.open("rb") as media:
                    expect(hashlib.file_digest(media, "sha256").hexdigest() == original_digest,
                           f"{name}: validation source pull did not match.")
                save(directory / f"{name}.probe.json", decode(local))
    save(directory / f"{args.action}-receipt.json", receipt)
    print(f"{args.label}: {state}, {len(receipt['publicationRows'])} scoped publication row(s); original hashes match.")


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("action", choices=("before-edits", "before-finish", "cancelled", "completed"))
    parser.add_argument("label")
    parser.add_argument("session", help="exact UUID from the isolated session, never a production ID")
    parser.add_argument("--copy-sources", action="store_true", help="pull and fully decode confirmed originals for cue analysis")
    args = parser.parse_args()
    path_for_session(args.session)
    expect(re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9-]{0,49}", args.label), "Invalid job label.")
    directory = ROOT / args.label
    directory.mkdir(parents=True, exist_ok=True)
    device = FieldDevice(args.serial)
    if args.action in {"before-edits", "before-finish"}:
        data = device.snapshot(args.session)
        prior = directory / "before-edits.json"
        if args.action == "before-finish" and prior.exists():
            earlier = json.loads(prior.read_text())
            expect(earlier["metadata"]["id"] == args.session, "This label refers to a different session.")
            match_hashes(earlier, data, allow_appends=True)
        save(directory / f"{args.action}.json", data)
        print(f"{args.label}: captured {len(data['sourceSha256'])} original hashes ({args.action}).")
    else:
        verify(device, args, directory)


if __name__ == "__main__":
    main()
