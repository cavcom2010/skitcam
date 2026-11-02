#!/usr/bin/env python3
"""Read-only, bounded MP4 timing inspection. Requires ffprobe; no source writes.

Use on short diagnostic fixtures. Keeps only moov (<=16 MiB) and ffprobe metadata
in memory, never compressed video payloads or decoded video frames.
"""
import argparse
import hashlib
import json
import struct
import subprocess
from fractions import Fraction
from pathlib import Path


def boxes(data, start, end):
    while start + 8 <= end:
        size, kind = struct.unpack_from('>I4s', data, start)
        header = 8
        if size == 1:
            size = struct.unpack_from('>Q', data, start + 8)[0]
            header = 16
        if size == 0:
            size = end - start
        if size < header or start + size > end:
            raise ValueError('Invalid MP4 box bounds')
        yield kind.decode('latin1'), start + header, start + size
        start += size


def timing_boxes(data, start=0, end=None, path=''):
    for kind, a, b in boxes(data, start, len(data) if end is None else end):
        name = path + '/' + kind
        block = data[a:b]
        if kind in ('trak', 'mdia', 'minf', 'stbl', 'edts'):
            yield from timing_boxes(data, a, b, name)
        elif kind in ('mvhd', 'mdhd'):
            version = block[0]
            offset = 20 if version else 12
            scale = struct.unpack_from('>I', block, offset)[0]
            duration = struct.unpack_from('>Q' if version else '>I', block, offset + 4)[0]
            yield {'box': name, 'timescale': scale, 'duration_ticks': duration}
        elif kind == 'elst':
            version = block[0]
            count = struct.unpack_from('>I', block, 4)[0]
            fmt = '>Qqhh' if version else '>Iihh'
            length = struct.calcsize(fmt)
            edits = [struct.unpack_from(fmt, block, 8 + i*length) for i in range(count)]
            yield {'box': name, 'edits': edits,
                   'convention': '[movie-duration ticks, media-time ticks (-1=empty), rate integer, rate fraction]'}
        elif kind in ('stts', 'ctts'):
            count = struct.unpack_from('>I', block, 4)[0]
            fmt = '>Ii' if kind == 'ctts' and block[0] == 1 else '>II'
            rows = [struct.unpack_from(fmt, block, 8 + i*8) for i in range(count)]
            yield {'box': name, 'entries': rows}
        elif kind in ('sgpd', 'sbgp'):
            yield {'box': name, 'prefix_hex': block[:64].hex()}


def moov(path):
    with path.open('rb') as source:
        length = path.stat().st_size
        position = 0
        while position + 8 <= length:
            source.seek(position)
            size, kind = struct.unpack('>I4s', source.read(8))
            header = 8
            if size == 1:
                size = struct.unpack('>Q', source.read(8))[0]
                header = 16
            if size == 0:
                size = length-position
            if size < header or position+size > length:
                raise ValueError('Invalid top-level MP4 box')
            if kind == b'moov':
                if size > 16*1024*1024:
                    raise ValueError('Diagnostic moov limit exceeded')
                return source.read(size-header)
            position += size
    raise ValueError('Missing moov')


def inspect(path):
    with path.open('rb') as source:
        digest = hashlib.file_digest(source, 'sha256').hexdigest()
    result = json.loads(subprocess.check_output([
        'ffprobe', '-v', 'error', '-show_streams', '-show_packets', '-show_frames',
        '-of', 'json', str(path)]))
    # ffprobe combines packet/frame results when both are requested.
    entries = result.get('packets_and_frames', [])
    frames = result.get('frames', [x for x in entries if x.get('type') == 'frame'])
    packets = result.get('packets', [x for x in entries if x.get('type') == 'packet'])
    tracks = []
    for stream in result['streams']:
        index = stream['index']
        selected = [f for f in frames if f['stream_index'] == index and 'pts' in f]
        time_base = Fraction(stream['time_base'])
        maximum_end = None
        for frame in selected:
            duration = frame.get('duration')
            if stream['codec_type'] == 'audio' and 'nb_samples' in frame:
                end = Fraction(frame['pts'])*time_base + Fraction(frame['nb_samples'], int(stream['sample_rate']))
            elif duration is not None:
                end = Fraction(frame['pts'] + duration)*time_base
            else:
                continue
            maximum_end = max(maximum_end, end) if maximum_end is not None else end
        tracks.append({'stream': stream, 'frames': selected,
                       'presentation_end_seconds': float(maximum_end) if maximum_end is not None else None,
                       'packets': [p for p in packets if p['stream_index'] == index]})
    return {'path': str(path), 'sha256': digest, 'boxes': list(timing_boxes(moov(path))), 'tracks': tracks}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('files', nargs='+', type=Path)
    args = parser.parse_args()
    print(json.dumps([inspect(path) for path in args.files], indent=2))
