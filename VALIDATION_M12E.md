# Milestone 12E — inline trim in Review

Validation date: **8 November 2026**  
Device: **Moto G54 5G `ZY22KJQLTV`**, Android 15 / API 35, 1080×2400  
Branch: **`feature/m12e-inline-trim-review`**, created from `feature/m12d-unified-video-ui` at `0eef4ce`  
Production package: **`com.calvinmazhindu.skitcam`**, 0.13.0 / versionCode 17 (not replaced, cleared or uninstalled)  
Validation package: **`com.calvinmazhindu.skitcam.m12cvalidation`**

## Result

The separate Trim screen has been deleted. Review Segment is now the only place a
clip is trimmed. `TrimViewModel`, `TrimRange`, source verification and the
existing `saveTrim` path remain as the headless, non-destructive persistence
layer. No recording, export, session schema or CameraX code was changed.

### Inline trim UI

- The playback progress slider is replaced by a thick trim bar over the video.
- Full source track: neutral gray.
- Retained range: **green**.
- Trimmed-out ranges: **orange**.
- Playhead: thin light line.
- Start/end handles: white grip pills with `‹ ›` arrows on the same bar, at both
  ends of the green range. Each has a 48 dp touch target.
- Dragging a handle pauses playback and updates the retained range preview.
- Tap/drag inside the green range seeks within the retained clip.
- Play/Pause and current/total time remain in the same overlay.
- Save trim and Reset trim appear only when there is a changed/trimmed range.
- The old standalone Trim header, Cancel/Save screen, precision fields, zoom/pan,
  frame-thumbnail timeline and separate overlay were removed.

### Frame accuracy

- Handle movement snaps to the recorded frame cadence from
  `SegmentMediaFacts.averageFps`, falling back to 30 fps when unavailable:
  `frameStepMs = round(1000 / fps)` bounded to 1–100 ms.
- Accessibility custom actions on both handles move them by exactly one frame.
- `TrimRange.start/end` still enforce the existing minimum retained duration and
  exclusive-end semantics.
- This is average-cadence frame stepping; variable-frame-rate clips are not
  claimed to be frame-exact.

### Unsaved changes

- Saving is explicit via **Save trim**.
- Closing Review, switching to another segment, or Back with a changed draft
  shows “Discard unsaved trim?” with **Discard** and **Keep editing**.
- Play from here is hidden while a trim draft is dirty, so an unsaved draft can
  never silently leak into whole-skit playback.
- A valid save updates the baseline and keeps Review open.
- Reset trim restores the full source and remains dirty until saved.

## Architecture

- `trim/TrimScreen.kt` deleted.
- `TrimViewModel` now exposes `baseline`, `dirty` and `saveInPlace`; it no longer
  closes itself after a save. Persistence/verification behaviour is otherwise
  unchanged.
- `review/PausedEnvironment.kt` opens the selected segment with
  `fullSource = true`, resolves source facts/player duration, clips the preview to
  the saved draft, and renders the inline trim bar.
- New `ui/video/VideoTrimBar.kt` provides the track, playhead, arrow grips,
  frame snapping and accessible frame actions.
- `ui/video/VideoTransportControls.kt` accepts an optional progress slot; Review
  uses the trim bar, Play Whole Skit keeps the standard seek bar.
- `MainActivity` no longer composes `TrimScreen`; review remains the only trim
  surface.

## Test results

```sh
./gradlew -PvalidationSuffix=.m12cvalidation :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --offline

adb shell am instrument -w -r -e class <class>[#method] \
  com.calvinmazhindu.skitcam.m12cvalidation.test/androidx.test.runner.AndroidJUnitRunner
```

| Suite | Result |
|---|---|
| JVM unit tests | **BUILD SUCCESSFUL**; includes frame-step/snap and trim-tolerance tests |
| Debug + android-test APK | **BUILD SUCCESSFUL** |
| `lintDebug` | **BUILD SUCCESSFUL**; 0 errors, 42 warnings |
| `TrimDeviceTest` | **OK (4 tests)** — drag/save, dirty keep-editing/discard, reset, source-byte preservation, landscape save |
| `UnifiedVideoLayoutDeviceTest` | **OK** portrait/landscape and 2.0×; Back navigation **OK** |
| `ReviewLayoutDeviceTest` | **OK** portrait and landscape with inline trim track/handles |
| `PauseEnvironmentDeviceTest` | **OK (4 tests)** |
| `M12ADeviceTest#a/#b/#c/#d/#g` | **OK** — review, release before RESUME, portrait/landscape playback, Play from here, anchored loop |
| `ArrangementDeviceTest#a` | **OK** |
| `CameraUxDeviceTest#g` | **OK** on retry |
| `TrimDurationProbeDeviceTest` | **OK** — duration evidence still collected through inline Review |

Device rotation was again flaky on this Moto build; landscape suites were retried
with `wm fixed-to-user-rotation enabled` and physical/forced landscape. The
phone was restored to portrait/default rotation after validation.

## Review transport corrections

A follow-up inspection found and fixed:

- Play/Pause and time could freeze after a clip reached its end because
  `rememberVideoPlaybackState` stopped polling on `STATE_ENDED`. Polling now
  continues while the player exists.
- The playhead was a low-contrast 2 dp gray line and was easily mistaken for
  the white handle grips. It is now a warm 3 dp line with a round knob, drawn
  above the green/orange track. The white pills with `‹ ›` remain the drag
  handles.
- Transport time now advances in tenths for short clips (`00:02.1 / 00:03.1`)
  and is sourced from the moving source position (`trimStart + player position`).
- A 48 dp **Jump to segment start** button was added before Play/Pause. It seeks
  the clipped player to its beginning and preserves the current play/pause
  state.
- At large fonts or narrow widths the transport stacks: buttons/time on one row
  and the trim track full-width below. This fixes the 2.0× case where the track
  previously collapsed to zero width.

The device test now asserts the Pause icon while playing, the Play icon while
paused, advancing time text, and the jump-to-start position.

## Layout measurements

Review and Play video bounds are unchanged from M12D; the trim bar is an overlay
inside the video, so it does not reduce preview size.

| Screen | Orientation | Font | Rendered video | Viewport coverage |
|---|---|---|---:|---:|
| Review | Portrait | 1.3× | 927×1648 px | 65.4% |
| Play | Portrait | 1.3× | 1030×1831 px | 80.7% |
| Review | Landscape | 1.3× | 1633×919 px | 68.7% |
| Play | Landscape | 1.3× | 1556×875 px | 62.3% |
| Review | Portrait | 2.0× | 889×1580 px | 60.1% |
| Play | Portrait | 2.0× | 1030×1831 px | 80.7% |
| Review | Landscape | 2.0× | 1532×862 px | 60.4% |
| Play | Landscape | 2.0× | 1515×852 px | 59.1% |

The inline trim track and handles were asserted inside the safe viewport with
48 dp handle targets. The retained/trimmed colours and handle arrows are visible
in the evidence screenshots.

## Production media integrity

Production application/data was not installed, cleared or exercised by this
validation package.

| Scope | M12D | M12E | Missing | Changed | Added |
|---|---:|---:|---:|---:|---:|
| Production private `files/**` | 1,455 | 1,455 | 0 | 0 | 0 |
| `/sdcard/Movies/SkitCam` | 697 | 735 | 0 | 0 | 38 validation videos |
| `/sdcard/Pictures/SkitCam` | 326 | 326 | 0 | 0 | 0 |

Every pre-existing production file hash is identical.

## Evidence

- `.validation/m12e/inline-trim-portrait.png` (warm playhead, arrows, jump/pause)
- `.validation/m12e/inline-trim-landscape.png`
- `.validation/m12e/inline-{portrait,landscape}-{review,play}.png`
- `.validation/m12e/inline-large2.0-{portrait,landscape}-{review,play}.png`
- `.validation/m12e/unified-video-layout-*.json`
- `.validation/m12e/private-files-after.sha256`
- `.validation/m12e/public-{movies,pictures}-after.sha256`

## Validation APK

```sh
./gradlew -PvalidationSuffix=.m12cvalidation :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Remaining notes

- Precision millisecond fields, zoom/pan and frame-thumbnail editing are
  intentionally gone.
- Frame stepping follows average recorded cadence; variable-frame-rate sources
  are approximated.
- The existing duration-metadata tolerance and exact save verification remain
  unchanged.
- Physical-rotation/TalkBack traversal on a second session is still recommended.
- Rotation remains flaky on this Moto build under repeated automation; landscape
  suites pass when the display is actually locked to landscape.

No commit, merge or push has been made.
