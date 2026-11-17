#!/usr/bin/env python3
"""M13 presentation matrix. Uses generated fixtures and the exact isolated package only.

Build and install validation/test APKs first; seed inline-inputs/{red,green,blue}.mp4.
Each method gets a new instrumentation process and its own session. Never clears,
uninstalls, launches or force-stops production. Restores the original font scale.
"""
import argparse
import pathlib
import re
import subprocess

PACKAGE = "com.calvinmazhindu.skitcam.m13reviewstablevalidation"
TEST_CLASS = "com.calvinmazhindu.skitcam.ui.M13UiDeviceTest"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=str(pathlib.Path.home() / "Android/Sdk/platform-tools/adb"))
    parser.add_argument("--serial", default="ZY22KJQLTV")
    parser.add_argument("--output", default=".validation/m13-review-stable")
    parser.add_argument("--package", choices=[PACKAGE, "com.calvinmazhindu.skitcam.m13reviewvalidation",
                                             "com.calvinmazhindu.skitcam.m13validation"],
                        default=PACKAGE, help="Use a fresh validation package; fixtures must not replace an owner draft.")
    parser.add_argument("--match", default=".*")
    options = parser.parse_args()
    output = pathlib.Path(options.output)
    output.mkdir(parents=True, exist_ok=True)
    adb = [options.adb, "-s", options.serial]

    def shell(*command):
        return subprocess.run(adb + ["shell", *command], capture_output=True, text=True, check=True).stdout.strip()

    original_font = shell("settings", "get", "system", "font_scale")
    original_rotation = shell("wm", "user-rotation")
    original_fixed_rotation = shell("wm", "fixed-to-user-rotation")
    cases = []
    for font in (original_font, "2.0"):
        for landscape in (False, True):
            prefix = f"{'landscape' if landscape else 'portrait'}-{font}"
            for name, method in (
                ("paused-dialogs", "pausedAndReorderDialogs"),
                ("review-trim", "reviewTrimAndPlayback"),
                ("review-stable", "reviewTrimControlsNeverMovePreview"),
                ("export-gallery", "exportAndGalleryPresentation"),
            ):
                cases.append((f"{prefix}-{name}", method, font, landscape, False))
            if landscape:
                cases.append((f"{prefix}-idle-layout", "idleLayout", font, landscape, True))
            else:
                cases.append((f"{prefix}-capture-finish", "captureAndFinishFlow", font, landscape, True))
    failures = []
    active_font = original_font
    try:
        for name, method, font, landscape, idle in cases:
            if not re.search(options.match, name):
                continue
            if active_font != font:
                shell("settings", "put", "system", "font_scale", font)
                active_font = font
            command = adb + ["shell", "am", "instrument", "-w", "-r",
                             "-e", "class", f"{TEST_CLASS}#{method}",
                             "-e", "font", font, "-e", "landscape", str(landscape).lower(),
                             "-e", "idle", str(idle).lower(),
                             f"{options.package}.test/androidx.test.runner.AndroidJUnitRunner"]
            result = subprocess.run(command, capture_output=True, text=True, timeout=300)
            text = result.stdout + result.stderr
            (output / f"{name}.log").write_text(text)
            passed = result.returncode == 0 and re.search(r"OK \(1 test\)", text) is not None
            print(f"{name}: {'PASS' if passed else 'FAIL'}", flush=True)
            if not passed:
                failures.append(name)
                print(text, flush=True)
    finally:
        shell("settings", "put", "system", "font_scale", original_font)
        shell("wm", "user-rotation", *original_rotation.split())
        shell("wm", "fixed-to-user-rotation", original_fixed_rotation)
    if failures:
        raise SystemExit(f"Failed: {', '.join(failures)}")


if __name__ == "__main__":
    main()
