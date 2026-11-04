#!/usr/bin/env python3
"""Read-only, streaming verification of a SkitCam ZIP (Python 3, standard library)."""
import argparse
import hashlib
import json
import re
import zipfile


def verify(path, zip_only=False):
    with zipfile.ZipFile(path) as archive:
        infos = archive.infolist()
        names = [i.filename for i in infos]
        if len(names) != len(set(names)):
            raise ValueError("Duplicate archive entries")
        for info in infos:
            if (not re.fullmatch(r"[A-Za-z0-9_./-]+", info.filename)
                    or any(p in ("", ".", "..") for p in info.filename.split("/"))
                    or info.filename.startswith("/") or info.is_dir()
                    or info.compress_type != zipfile.ZIP_STORED):
                raise ValueError("Unexpected archive path or compression method")
        if not {"session.json", "checksums.sha256"}.issubset(names):
            raise ValueError("Missing manifest/checksums")
        if any(archive.getinfo(n).file_size > 4 * 1024 * 1024
               for n in ("session.json", "checksums.sha256")):
            raise ValueError("Metadata is too large")
        checksum_text = archive.read("checksums.sha256").decode("utf-8")
        expected = {}
        for line in checksum_text.splitlines():
            digest, sep, name = line.partition("  ")
            if not sep or not re.fullmatch(r"[0-9a-f]{64}", digest) or name in expected:
                raise ValueError("Invalid checksum manifest")
            expected[name] = digest
        if set(expected) != set(names) - {"checksums.sha256"}:
            raise ValueError("Checksum coverage does not match archive entries")
        sizes = {}
        for info in infos:
            digest = hashlib.sha256()
            length = 0
            with archive.open(info) as stream:
                while chunk := stream.read(65536):
                    digest.update(chunk)
                    length += len(chunk)
            if length != info.file_size or (info.filename in expected
                    and digest.hexdigest() != expected[info.filename]):
                raise ValueError(f"Checksum/length mismatch: {info.filename}")
            sizes[info.filename] = length
        manifest = json.loads(archive.read("session.json"))
        if not zip_only:
            if manifest.get("format") != "skitcam-portable-project" or manifest.get("schemaVersion") != 1:
                raise ValueError("Unsupported portable project format")
            if manifest["finishedVideo"]["path"] != "Finished_Video.mp4":
                raise ValueError("Invalid finished-video reference")
            if expected["Finished_Video.mp4"] != manifest["finishedVideo"]["sha256"]:
                raise ValueError("Finished-video hash disagrees with manifest")
            segments = manifest["segments"]
            ids = [s["id"] for s in segments]
            order = manifest["playbackOrder"]
            if len(ids) != len(set(ids)) or len(order) != len(set(order)) or set(order) != {s["id"] for s in segments if s["kept"]}:
                raise ValueError("Invalid segment identity/playback order")
            if [i["segmentId"] for i in manifest["finishedTimeline"]] != order:
                raise ValueError("Finished timeline differs from saved playback order")
            paths = {"Finished_Video.mp4", "session.json", "checksums.sha256"}
            for segment in segments:
                name = segment["originalPath"]
                if segment["originalIncluded"]:
                    if name is None or not name.startswith("Original_Segments/") or expected[name] != segment["originalSha256"]:
                        raise ValueError("Original reference/hash is invalid")
                    paths.add(name)
                elif name is not None or segment["originalSha256"] is not None:
                    raise ValueError("Excluded original has a required media reference")
                trim = segment["savedTrim"]
                if trim is not None and (not all(type(trim[k]) is int for k in ("sourceDurationMs", "trimStartMs", "trimEndMs"))
                        or not 0 <= trim["trimStartMs"] < trim["trimEndMs"] <= trim["sourceDurationMs"]):
                    raise ValueError("Invalid saved trim")
            if paths != set(names):
                raise ValueError("Archive contains unexpected/missing project files")
        return {"verified": True, "entries": len(names), "payloadBytes": sum(sizes.values()),
                "schemaVersion": manifest.get("schemaVersion"), "format": manifest.get("format")}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive")
    parser.add_argument("--zip-only", action="store_true", help="Integrity only; for generated ZIP64 fixtures")
    args = parser.parse_args()
    print(json.dumps(verify(args.archive, args.zip_only), indent=2))
