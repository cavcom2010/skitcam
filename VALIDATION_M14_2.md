# M14.2 — Segment summary and playback navigation

- **Branch:** `feature/m14.2-segment-navigation`, based on `f32ddd7` (the M14.1 Review surface-attachment fix).
- **Device:** Motorola Moto G54 5G, Android 15 / API 35 (`ZY22KJQLTV`).
- **Validation application:** `com.calvinmazhindu.skitcam.m142validation`. The Review regressions used the separate `.reviewcheck` package. No production APK was replaced and no existing recordings or Gallery items were deleted. The live recording tests added disposable exports to Gallery.

## Changes

- `review/SegmentSummaryBar.kt` and `review/PausedEnvironment.kt`: replace the text inside the existing paused strip with proportional green and amber fills. Counts come from the saved session. Labels shorten or disappear only when they no longer fit, and one non-interactive accessibility node always exposes the full summary.
- `playback/PlaybackSegmentRail.kt`, `playback/SkitPlaybackScreen.kt`, and `playback/SkitPlaybackController.kt`: show the existing kept Media3 playlist as a scrollable rail, centre the active pill when the clip changes, and let pills seek within that playlist without resetting it. The existing subtitle still identifies the source segment, and **Play from here** still starts and loops at its selected suffix.
- `app/src/main/res/values/strings.xml`: adds summary and pill labels. The recording engine, Review controller, video surface, export pipeline and application IDs are unchanged.
- Tests: `SegmentSummaryTest.kt`, `SegmentSummaryBarDeviceTest.kt`, and `SegmentNavigationDeviceTest.kt`. Updated two `M12ADeviceTest.kt` selectors that still looked for labels from before the icon-first UI.

## Verification

```sh
./gradlew -PvalidationSuffix=.m142validation \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --offline
```

- JVM: **289 passed**, including six proportion cases and summary derivation after saved-state round trip.
- Debug lint: **0 errors, 36 warnings**.
- Moto G54, [`SegmentNavigationDeviceTest#verifyRecoveredSession`](.validation/m14.2/device-portrait.log): recorded ten disposable clips on the baseline, installed M14.2 over the isolated package and reopened that same session. Checked **8/2 → 9/1 → 8/2**, the colour split and unchanged strip/button bounds. Checked chronological transition while playing, first/middle/last centring, manual swipe without snap-back, forward/backward jumps, pause intent, replay, reordered and anchored playback, and 9:16 framing. The original ten source hashes matched before and after. The eight-clip export was readable from its published URI and fully decoded with FFmpeg. The AAC and H.264 tracks both start at zero and end about 13 ms apart ([stream report](.validation/m14.2/export-streams.json)); this is not a subjective lip-sync check.
- Moto G54, [`SegmentNavigationDeviceTest#landscapeLargeText`](.validation/m14.2/device-landscape.log) and [`SegmentSummaryBarDeviceTest`](.validation/m14.2/device-narrow.log): checked landscape at 2.0× system text with a disposable 16:9 MP4 fixture, and a narrow test strip with 99/1, 1/99, all-discarded and empty states. Checked full accessibility descriptions when labels could not fit.
- M14.1 Review regressions: both `TrimPreviewFlashDeviceTest` cases and all three `ThumbnailReadabilityDeviceTest` cases passed ([frame retention](.validation/m14.2/device-repeatedTapsKeepPresentedFrameAndDoNotReprepare.log), [trim/switch/export](.validation/m14.2/device-dragPlaybackSaveResetSwitchAndExport.log), [thumbnails](.validation/m14.2/device-thumbnail.log)).
- Playback and audio regressions: five passed, covering the portrait preview, anchored suffix loops, returning to recording after closing playback, built-in microphone metering, and export. The first regression run caught an obsolete `RESUME` text selector; the [rerun using the current icon label](.validation/m14.2/device-anchor-resume-loop.log) passed. The [other three cases](.validation/m14.2/device-playback-audio-regression.log) passed in the first run.

**Total on M14.2: 13 passing device cases.** The baseline screenshot capture was a separate test on the pre-M14.2 APK.

## Screenshots and APK

| Screen | Before | After |
|---|---|---|
| Paused camera | [8 kept · 10 clips](.validation/m14.2/before-summary.png) | [80/20 bar](.validation/m14.2/after-summary.png) · [after restoring one](.validation/m14.2/after-restore.png) |
| Play Skit | [Clip 4 of 8 · Segment 5](.validation/m14.2/before-playback.png) | [Centred pill 4](.validation/m14.2/after-playback.png) |
| Landscape, 2.0× text | — | [Summary](.validation/m14.2/landscape-large-summary.png) · [Playback](.validation/m14.2/landscape-large-playback.png) |

The baseline Play Skit screenshot shows the old layout immediately after a paused seek; the video area is black in that capture. Two earlier baseline playback starts failed with a device MediaCodec error; the capture and the M14.2 navigation test succeeded on later runs. M14.2 does not change codec or Review surface setup.

[Device-tested APK](.validation/m14.2/SkitCam-M14.2-validation.apk) · [Test APK](.validation/m14.2/SkitCam-M14.2-validation-test.apk) · [SHA-256](.validation/m14.2/apks.sha256). These are local, Git-ignored artifacts.

The validation build does not appear in the launcher. To open it on the Moto G54:

```sh
adb -s ZY22KJQLTV shell am start -n \
  com.calvinmazhindu.skitcam.m142validation/com.calvinmazhindu.skitcam.MainActivity
```

**Owner acceptance:** Device checks used ADB and instrumentation. Have the owner try the ten-clip workflow, listen to playback and the export, and check the spoken TalkBack labels before accepting. Accessibility node semantics were inspected on-device; live TalkBack speech was not checked. Nothing has been merged or published.
