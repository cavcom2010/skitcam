# Milestone 12D — unified video UI

Validation date: **8 November 2026**  
Device: **Moto G54 5G `ZY22KJQLTV`**, Android 15 / API 35, 1080×2400  
Branch: **`feature/m12d-unified-video-ui`**, created from `main` at `10e13cc`  
Production package: **`com.calvinmazhindu.skitcam`**, 0.13.0 / versionCode 17 (not replaced, cleared or uninstalled)  
Validation package: **`com.calvinmazhindu.skitcam.m12cvalidation`**

## Phase 0 — branch result

- M12C is already merged: `main`, `origin/main` and `feature/m12c-ui-transformation`
  all contained `10e13cc` before this work.
- The working tree was clean, so `feature/m12d-unified-video-ui` was created from
  `main`. No M12C branch was merged again.
- No production application ID, version, signing configuration or APK was changed.

## Shared UI architecture

New reusable presentation layer under `ui/video/`:

| Component | Responsibility |
|---|---|
| `ImmersiveVideoScaffold.kt` | Full-window ink surface + safe-area content shared by Review, Trim and Play |
| `VideoSurface.kt` | Aspect-fitted Media3 `PlayerView`; caller owns the player and policy |
| `VideoTransportControls.kt` | Shared Play/Pause, seek and current/total time |
| `VideoProgressBar.kt` | Shared accessible seek bar |
| `VideoScreenHeader.kt` | Shared title/subtitle and Close/Cancel placement |
| `VideoPlaybackState.kt` | Read-only player state polling; never owns or mutates a player |
| `VideoTimeFormat.kt` | Shared clip, clock and precise time formatting |

`SegmentReviewController`, `TrimViewModel` and `SkitPlaybackController` remain
separate workflows. No player engine was added or merged.

## Implementation summary

### Review Segment

- Preserved the approved M12C immersive geometry and thumbnail shelf.
- Replaced the Media3 built-in controller with the shared Compose transport
  overlay inside the same video bounds. Review video did not shrink.
- Added a selected-thumbnail indicator: warm outer border + raised surface, with
  `Selected` in the accessibility state description.
- Removed the repeated “Kept” / “Discarded” text from segment cards. Status is now
  a green border for kept and a red border for discarded; discarded thumbnails
  are also dimmed so status is not colour-only. Status remains in each card’s
  content description for screen readers.
- Rebalanced actions: Play is primary, Trim/Play from here are secondary, Discard
  is now an outlined destructive action instead of the dominant filled button.
- Kept Keep/Discard, Restore, Undo, Play from here, anchored playback, shelf
  actions and return to the paused recording UI.

### Trim Segment

- Removed the fixed `180.dp` preview. The preview is aspect-fitted from
  `Segment.media` and fills the available area.
- Portrait: video above the editor panel. Landscape: video beside a capped,
  scrollable editor rail.
- New timeline with frame thumbnails, highlighted retained region, playhead,
  separate start/end handles with 48 dp targets, and the existing zoom/pan
  actions.
- Primary controls: shared Play/Pause, Reset Trim, Cancel, Save.
- Millisecond text fields and ±100 ms controls moved into an expandable
  **Precision controls** section; the functionality remains.
- User-facing time labels are now readable (`Start 00:00.300 · End 00:03.200`,
  `Retained 00:02.900`). Internal end-exclusive semantics are unchanged.
- `TrimRange`, trim persistence and the save path were not changed.

### Play Whole Skit

- Replaced the floating panel with the shared immersive surface.
- Large aspect-fitted video; header shows `Play skit`, `Clip X of Y · Segment N`,
  and Close.
- Shared Play/Pause, seek, current/total time; compact loop chip retains its
  `Loop from clip N` / `Loop off` labels.
- Replay, finished state, errors and Back navigation preserved.
- `SkitPlaybackController` gained only a read-only `videoAspect` presentation
  value from `onVideoSizeChanged`. Playlist, order, clip boundaries, anchoring
  and loop logic are untouched.
- `MainActivity` now treats playback and Trim as immersive video UI: the camera
  header and dock are hidden while active and restored afterwards. CameraX stays
  bound under the overlay.

### Trim duration warning investigation

An on-device probe recorded three fresh clips and compared:

- `MediaMetadataRetriever` container duration,
- Media3 presentation duration,
- MediaExtractor video-track duration,
- the recorder’s historical duration,
- and the saved trim source duration.

Observed result (`validation-m12d/trim-duration-probe.json`):

| Clip | Retriever vs player | Retriever vs video track | Recorder vs file |
|---|---:|---:|---:|
| 1 | 0 ms | 0 ms | ~34 ms |
| 2 | 0 ms | 3 ms | ~36 ms |
| 3 | 0 ms | 10 ms | ~44 ms |

The warning is therefore a metadata-rounding/tolerance issue, not a corrupt trim.
A **100 ms tolerance** now suppresses the warning for expected container/track and
legacy recorder differences. Genuine mismatches still produce the warning, and
Save continues to validate the range exactly against the observed presentation
duration.

## Device measurements

Captured by `video/UnifiedVideoLayoutDeviceTest`, which renders actual clips,
boots each screen, records exact player/viewport bounds and saves screenshots.
Portrait viewport is 1080×2163 px (432×865 dp); landscape is 2163×1010 px
(865×404 dp). Default font scale on this device is 1.3.

| Screen | Orientation | Font | Rendered video (px) | Viewport coverage |
|---|---|---|---:|---:|
| Review | Portrait | 1.3× | 927×1648 | 65.4% |
| Trim | Portrait | 1.3× | 660×1173 | 33.1% |
| Play | Portrait | 1.3× | 1030×1831 | 80.7% |
| Review | Landscape | 1.3× | 1633×919 | 68.7% |
| Trim | Landscape | 1.3× | 1323×744 | 45.1% |
| Play | Landscape | 1.3× | 1556×875 | 62.3% |
| Review | Portrait | 2.0× | 889×1580 | 60.1% |
| Trim | Portrait | 2.0× | 562×999 | 24.0% |
| Play | Portrait | 2.0× | 1030×1831 | 80.7% |
| Review | Landscape | 2.0× | 1532×862 | 60.4% |
| Trim | Landscape | 2.0× | 1223×688 | 38.5% |
| Play | Landscape | 2.0× | 1515×852 | 59.1% |

The M12C review baseline was 305×542 px portrait (7.1% coverage) and 676×380 px
landscape (11.8%). M12D review is **927×1648 px portrait** and **1633×919 px
landscape**, so the approved video size was not reduced.

Every captured surface matched its recorded aspect ratio within 2%. All checked
controls were inside the safe viewport, had at least a 48 dp target, and did not
overlap. The trimmed portrait preview is intentionally smaller than Review
because the timeline and editor occupy space; it remains aspect-correct and the
editor panel scrolls at large fonts.

## Test results

Commands used:

```sh
./gradlew -PvalidationSuffix=.m12cvalidation :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --offline

adb shell am instrument -w -r -e class <class>[#method] \
  com.calvinmazhindu.skitcam.m12cvalidation.test/androidx.test.runner.AndroidJUnitRunner
```

| Suite | Result |
|---|---|
| JVM unit tests | **BUILD SUCCESSFUL**; includes new time-format and trim-tolerance tests |
| Debug + android-test APK | **BUILD SUCCESSFUL** |
| `lintDebug` | **BUILD SUCCESSFUL**; 0 errors, 41 warnings (one more than the M12C 40-warning set) |
| `UnifiedVideoLayoutDeviceTest` | **OK** for portrait/landscape at 1.3×, 1.5× and 2.0×; Back navigation test **OK** |
| `ReviewLayoutDeviceTest` | **OK** portrait and landscape (M12C bounds still met) |
| `PauseEnvironmentDeviceTest` | **OK (4 tests)** after retry; device rotation was flaky, not the UI |
| `TrimDeviceTest` | **OK (4 tests)** with rotation override enabled |
| `M12ADeviceTest#a`, `#b`, `#c`, `#d`, `#g` | **OK** — review, release before RESUME, portrait/landscape playback, Play from here and anchored loop |
| `ArrangementDeviceTest#a` | **OK** — Arrange preview still plays through the shared surface |
| `CameraUxDeviceTest#g` | **OK** — dock/header hidden during review, restored after Close |
| `TrimDurationProbeDeviceTest` | **OK** — evidence captured |

The M12A playback test now explicitly closes immersive playback and verifies the
player is released before RESUME, because the dock is hidden during playback.

Device rotation note: this Moto build does not always honour
`wm user-rotation lock 1` unless `wm fixed-to-user-rotation enabled` is set. The
layout test sets and restores this itself; one full-class run left the device in
forced landscape and was corrected manually. Test reruns pass.

## Production media integrity

Production was not installed, cleared or exercised by this validation package.

| Scope | M12C baseline | Now | Missing | Pre-existing changed | Additive |
|---|---:|---:|---:|---:|---:|
| Production private `files/**` | 1,438 | 1,455 | 0 | 1 non-media marker (`files/profileInstalled`) | 17 session artifacts |
| `/sdcard/Movies/SkitCam` | 615 | 697 | 0 | 0 | 82 validation videos |
| `/sdcard/Pictures/SkitCam` | 293 | 326 | 0 | 0 | 33 validation photos |

Every pre-existing recording/photo hash is unchanged. The additive files are
from validation recordings/exports, not overwrites of production media.

## Evidence

- `.validation/m12d/after-{portrait,landscape}-{review,trim,play}.png`
- `.validation/m12d/after-large{1.5,2.0}-{portrait,landscape}-{review,trim,play}.png`
- `.validation/m12d/unified-video-layout-*.json` — exact viewport, player and
  rendered-video bounds, aspect and coverage.
- `.validation/m12d/trim-duration-probe.json`
- `.validation/m12d/private-files-after.sha256`
- `.validation/m12d/public-{movies,pictures}-after.sha256`

## Validation APK

Built with:

```sh
./gradlew -PvalidationSuffix=.m12cvalidation :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`  
Install:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Optional instrumentation APK:
`app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

## Files changed / added

**Added**
- `ui/video/ImmersiveVideoScaffold.kt`
- `ui/video/VideoSurface.kt`
- `ui/video/VideoTransportControls.kt`
- `ui/video/VideoProgressBar.kt`
- `ui/video/VideoScreenHeader.kt`
- `ui/video/VideoPlaybackState.kt`
- `ui/video/VideoTimeFormat.kt`
- `androidTest/.../video/UnifiedVideoLayoutDeviceTest.kt`
- `androidTest/.../trim/TrimDurationProbeDeviceTest.kt`
- `test/.../ui/video/VideoTimeFormatTest.kt`
- `test/.../trim/TrimDurationToleranceTest.kt`

**Updated**
- `review/PausedEnvironment.kt`
- `trim/TrimScreen.kt`
- `trim/TrimViewModel.kt`
- `playback/SkitPlaybackScreen.kt`
- `playback/SkitPlaybackController.kt`
- `MainActivity.kt`
- `androidTest/.../m12a/M12ADeviceTest.kt`
- `androidTest/.../trim/TrimDeviceTest.kt`

**Removed**
- `review/DurationText.kt` and its old test, replaced by the shared formatter.

## Unresolved / notes

- Trim preview is necessarily smaller than Review at 2.0× font; the timeline and
  precision controls remain reachable by scrolling.
- Colour borders are supplemented by dimming and accessibility descriptions so
  status is not conveyed by colour alone.
- The existing “preview is approximate” trim wording remains in the precision
  section; frame-accurate trimming was not claimed.
- Physical rotation/TalkBack traversal on a second session is still recommended.

No commit, merge or push has been made.
