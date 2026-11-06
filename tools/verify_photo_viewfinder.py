#!/usr/bin/env python3
"""Compare whole PreviewView/JPEG frames and unchanged capture facts.

Requires Pillow, numpy and opencv-python-headless (desktop validation only).
Inputs are directories produced by CameraRotationDeviceTest's Photo layout case.
No source image is cropped or overwritten. EXIF and front-preview mirroring are
normalised, then entire images are uniformly fitted into equal black canvases.
Feature registration measures framing drift; it is NOT applied to the images or
used to inflate the reported whole-frame correlation.
"""

import argparse
import hashlib
import json
import math
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageOps


def canvas(image, size=(960, 720)):
    fitted = ImageOps.contain(image.convert("RGB"), size, Image.Resampling.LANCZOS)
    result = Image.new("RGB", size, "black")
    result.paste(fitted, ((size[0] - fitted.width) // 2, (size[1] - fitted.height) // 2))
    return result


def compare(preview, jpeg):
    first = np.asarray(preview.convert("L"))
    second = np.asarray(jpeg.convert("L"))
    correlation = float(np.corrcoef(first.ravel(), second.ravel())[0, 1])
    sift = cv2.SIFT_create(nfeatures=4000)
    key1, desc1 = sift.detectAndCompute(first, None)
    key2, desc2 = sift.detectAndCompute(second, None)
    if desc1 is None or desc2 is None:
        raise ValueError("No detailed scene: cannot establish JPEG crop equivalence")
    pairs = cv2.BFMatcher().knnMatch(desc1, desc2, k=2)
    good = [a for pair in pairs if len(pair) == 2 for a, b in [pair] if a.distance < .7 * b.distance]
    if len(good) < 20:
        raise ValueError(f"Too few scene correspondences: {len(good)}")
    source = np.float32([key1[m.queryIdx].pt for m in good])
    destination = np.float32([key2[m.trainIdx].pt for m in good])
    matrix, mask = cv2.estimateAffinePartial2D(source, destination, method=cv2.RANSAC, ransacReprojThreshold=3)
    if matrix is None:
        raise ValueError("Cannot measure framing transform")
    selected = mask.ravel().astype(bool)
    points = source[selected]
    scale = math.hypot(matrix[0, 0], matrix[1, 0])
    rotation = math.degrees(math.atan2(matrix[1, 0], matrix[0, 0]))
    center = np.array([first.shape[1] / 2, first.shape[0] / 2])
    moved_center = matrix[:, :2] @ center + matrix[:, 2]
    offset = (moved_center - center) / np.array([first.shape[1], first.shape[0]])
    span = np.ptp(points, axis=0) / np.array([first.shape[1], first.shape[0]])
    return dict(wholeFrameCorrelation=correlation, matches=len(good), inliers=int(selected.sum()),
                scale=scale, rotationDegrees=rotation, centerOffsetFraction=offset.tolist(),
                inlierSpanFraction=span.tolist(), previewToJpegMatrix=matrix.tolist())


def verify(baseline, candidate, output, supplements=()):
    output.mkdir(parents=True, exist_ok=True)
    rows = []
    failures = []
    samples={path.name:path for folder in [candidate,*supplements] for path in folder.glob("photo-layout-*.jpg")}
    for jpeg_path in sorted(samples.values(),key=lambda path:path.name):
        name = jpeg_path.stem
        folder=jpeg_path.parent
        before = json.loads((baseline / f"{name}-capture.json").read_text())
        after = json.loads((folder / f"{name}-capture.json").read_text())
        for key in ["cameraId", "scaleType", "preview", "capture", "zoomRatio", "linearZoom", "sensorActiveArray", "sensorOrientation"]:
            if before[key] != after[key]:
                failures.append(f"{name}: changed {key}: {before[key]} -> {after[key]}")
        if after["scaleType"] != "FIT_CENTER" or abs(after["zoomRatio"] - 1) > .01:
            failures.append(f"{name}: not FIT_CENTER at actual 1x")
        old_facts = json.loads((baseline / f"{name}-jpeg.json").read_text())
        facts = json.loads((folder / f"{name}-jpeg.json").read_text())
        for key in ["width", "height", "orientation"]:
            if old_facts[key] != facts[key]:
                failures.append(f"{name}: changed JPEG {key}")
        with Image.open(jpeg_path) as stored:
            stored.load()
            if stored.size != (facts["width"], facts["height"]):
                failures.append(f"{name}: decoded dimensions disagree")
            if stored.getexif().get(274, 1) != facts["orientation"]:
                failures.append(f"{name}: decoded EXIF disagrees")
            oriented=ImageOps.exif_transpose(stored)
            size=(720,960) if oriented.height>oriented.width else (960,720)
            jpeg = canvas(oriented,size)
        with Image.open(folder / f"{name}-capture-preview.png") as bitmap:
            image = ImageOps.mirror(bitmap) if "Front" in name else bitmap
            preview = canvas(image,size)
        metrics = compare(preview, jpeg)
        # Measure near-identity framing over a broad scene interval. Allow small
        # asynchronous handheld movement, ISP differences and integer crop rounding.
        checks = dict(scale=abs(metrics["scale"] - 1) <= .03,
                      rotation=abs(metrics["rotationDegrees"]) <= 2,
                      center=max(abs(v) for v in metrics["centerOffsetFraction"]) <= .04,
                      coverage=min(metrics["inlierSpanFraction"]) >= .55,
                      inliers=metrics["inliers"] >= 20)
        if not all(checks.values()):
            failures.append(f"{name}: crop equivalence checks {checks}")
        sheet = Image.new("RGB", (size[0]*2, size[1]+40), "#202024")
        sheet.paste(preview, (0, 40));sheet.paste(jpeg, (size[0], 40))
        draw = ImageDraw.Draw(sheet)
        draw.text((10, 10), f"{name}: complete PreviewView (front unmirrored)", fill="white")
        draw.text((size[0]+10, 10), "Complete JPEG, EXIF applied (no registration warp)", fill="white")
        sheet.save(output / f"{name}-comparison.jpg", quality=90)
        row = dict(name=name, evidence=str(folder),jpeg=facts, framing=metrics, checks=checks,
                   boundsBefore=[before[k] for k in ["viewX", "viewY", "viewWidth", "viewHeight"]],
                   boundsAfter=[after[k] for k in ["viewX", "viewY", "viewWidth", "viewHeight"]],
                   jpegSha256=hashlib.sha256(jpeg_path.read_bytes()).hexdigest())
        rows.append(row)
        print(name, json.dumps(metrics), flush=True)
    if len(rows) != 6:
        failures.append(f"Expected rear/front × portrait/two landscape JPEGs, found {len(rows)}")
    result = dict(cases=rows, failures=failures)
    (output / "analysis.json").write_text(json.dumps(result, indent=2) + "\n")
    if failures:
        raise AssertionError("\n".join(failures))
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("candidate", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--supplement", type=Path, action="append",default=[],
                        help="Explicit recaptured samples; original evidence is retained")
    args = parser.parse_args()
    verify(args.baseline, args.candidate, args.output,args.supplement)
