#!/usr/bin/env python3
"""Run inline-shelf device validation against the separate validation package only.

Build/install validation app and test APKs, then seed files/inline-inputs/{red,green,blue}.mp4
with one-second colour/tone fixtures before running. Never clears/uninstalls any package.
"""
import argparse
import pathlib
import re
import subprocess


PACKAGE = "com.calvinmazhindu.skitcam.m12finlinevalidation"
TEST_CLASS = "com.calvinmazhindu.skitcam.arrange.InlineReorderDeviceTest"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default=str(pathlib.Path.home() / "Android/Sdk/platform-tools/adb"))
    parser.add_argument("--serial", default="ZY22KJQLTV")
    parser.add_argument("--output", default=".validation/m12f-inline")
    parser.add_argument("--match", default=".*", help="Run only matching case names (regular expression).")
    options = parser.parse_args()
    output = pathlib.Path(options.output)
    output.mkdir(parents=True, exist_ok=True)
    adb = [options.adb, "-s", options.serial]

    def shell(*command):
        return subprocess.run(adb + ["shell", *command], check=True, capture_output=True, text=True).stdout.strip()

    def case(name, method, count=3, landscape=False, recover=False):
        if not re.search(options.match, name):
            return
        command = adb + ["shell", "am", "instrument", "-w", "-r",
                         "-e", "class", f"{TEST_CLASS}#{method}",
                         "-e", "count", str(count), "-e", "landscape", str(landscape).lower(),
                         "-e", "font", active_font,
                         "-e", "recover", str(recover).lower(),
                         f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"]
        result = subprocess.run(command, capture_output=True, text=True, timeout=240)
        text = result.stdout + result.stderr
        (output / f"{name}.log").write_text(text)
        success = result.returncode == 0 and re.search(r"OK \(1 test\)", text) is not None
        print(f"{name}: {'PASS' if success else 'FAIL'} (font {active_font})", flush=True)
        if not success:
            print(text, flush=True)
            raise RuntimeError(f"Validation failed: {name}")

    original_font = shell("settings", "get", "system", "font_scale")
    active_font = original_font
    try:
        for landscape in (False, True):
            orientation = "landscape" if landscape else "portrait"
            for count in (1, 3):
                case(f"{orientation}-{count}-tap-done", "tapLongPressCancelDoneAndPlayback", count, landscape)
            for count in (3, 20, 100):
                case(f"{orientation}-{count}-drag", "dragAutoScrollAndNavigationGuard", count, landscape)
            case(f"{orientation}-100-scroll", "scrollDoesNotUnlock", 100, landscape)
            case(f"{orientation}-20-top-scroll", "topEdgeAutoScroll", 20, landscape)
        font_cases = [f"{pose}-font2-{kind}" for pose in ("portrait", "landscape") for kind in ("tap-done", "drag")]
        if any(re.search(options.match, name) for name in font_cases):
            shell("settings", "put", "system", "font_scale", "2.0")
            active_font = "2.0"
        for landscape in (False, True):
            orientation = "landscape" if landscape else "portrait"
            case(f"{orientation}-font2-tap-done", "tapLongPressCancelDoneAndPlayback", 3, landscape)
            case(f"{orientation}-font2-drag", "dragAutoScrollAndNavigationGuard", 20, landscape)
        if active_font != original_font:
            shell("settings", "put", "system", "font_scale", original_font)
            active_font = original_font
        case("checkpoint-before-death", "checkpointForProcessDeath")
        if re.search(options.match, "checkpoint-after-force-stop"):
            shell("am", "start", "-W", "-n", f"{PACKAGE}/com.calvinmazhindu.skitcam.MainActivity")
            shell("am", "force-stop", PACKAGE)
        case("checkpoint-after-force-stop", "recoverAfterProcessDeath", recover=True)
        case("accessible-navigation-save-cancel", "accessibleUnlockAndNavigationSaveDiscard")
        case("resume-after-reorder", "resumeAfterReorderRecordsValidNewSegment")
        case("export-order", "reorderedMp4ContainsBlueThenRedAndNoDiscardedGreen")
    finally:
        shell("settings", "put", "system", "font_scale", original_font)


if __name__ == "__main__":
    main()
