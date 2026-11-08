# Milestone 12C — UI transformation (Kimi Phase 1)

Validation date: **8 November 2026**  
Device: **Moto G54 5G `ZY22KJQLTV`**, Android 15 / API 35, 1080×2400  
Validation package: **`com.calvinmazhindu.skitcam.m12cvalidation`**  
Production package: **`com.calvinmazhindu.skitcam`**, 0.13.0 / versionCode 17 (not replaced, cleared or uninstalled)  
Branch: **`feature/m12c-ui-transformation`**, created from `main` at `a7264ae`  
Version names/codes were **not** changed.

## Reconciliation with current main

Kimi's plan was written at `4013175` (about 0.11.2). Current main already contained
M11, M12A and the 0.13.0 release. These assumptions were corrected before
implementation:

| Plan assumption | Current main reality | Action |
|---|---|---|
| Settings still shows `SkitCam · 0.10.0` | `BuildConfig.VERSION_NAME` already used | Kept; no change |
| Photo mode / aspect ratios are future work | Photo mode, timer, full-screen framing and ratio selector already exist | Preserved and restyled, not reimplemented |
| Photo timer leaks into Video settings | Still true in the full settings surface | Fixed with mode-contextual sections |
| Mode selector is a simple chip row | Portrait row + separate landscape row, dock height changed between modes | Replaced with one stable coin-toggle next to the shutter |
| Recording controls scroll and move | Still true; Pause/Stop close together | Pinned capture row; separate FINISH; fixed status height |
| Header/microphone layout is unstable | M12A already fixed this with a reserved meter row | Preserved; M12A header-stability test rerun |
| Export engine may need changes | M12B fast export and recovery are already in place | Export engine untouched |
| Notification permission is requested with no explanation | Still true | Explanation and visible denial added before the request |

## Implemented from Kimi Phase 1

### 1a — Design foundation

- Added `ui/theme/SkitCamTheme.kt` with semantic colours, typography, spacing,
  shapes and status tones. The identity is warm-rose on ink, not a copy of any
  stock camera.
- Added `ui/components/SkitCamComponents.kt`: section headings, status banners,
  primary/secondary actions, message/loading/permission panels and dialog titles.
- Applied `SkitCamTheme` to Main, Gallery, finished-video playback, Project Export
  and the playback surface.
- Added camera-shell, control, settings, permission and notification strings.
- Kept every touch target at least 48 dp and the shutter at 80 dp.

### 1b — Camera shell

New presentation components:

- `CameraHeader` — settings, lens/camera state, photo ratio selector, audio
  indicator and Quick settings. The M12A fixed-height audio slot is retained.
- `CameraModeToggle` — **one coin-flip button** that shows the mode you can switch
  to: “Video” while in Photo, “Photo” while in Video. The shutter stays in the
  same screen position across modes.
- `CaptureDockRow` / `PhotoShutter` / `PhotoControls` — leading gallery shortcut,
  coin mode toggle, shutter and lens switch in one stable row.
- `RecordingControls` — pinned capture row, separate labelled **FINISH** action,
  RESUME in the same shutter slot, fixed-height status area and polite
  announcements.
- `CameraModeToggle` is disabled during an open session, finalising, export or
  recovery lock. It is never hidden during those states, so the dock does not jump.
- `QuickControlsSheet` (`camera/CameraTools.kt`) is now a focused modal surface
  with a pinned title, Close action, `paneTitle`, focus request and predictable Back.
- “Hold upright” and raw state names were replaced with orientation-neutral,
  user-facing copy; a finalising state reads “Finishing current clip.”

### 1c — Permission and accessibility

- Camera access now shows an in-app rationale **before** the system prompt.
- Denied and permanently-denied camera states have a consistent explanation and
  Open settings action.
- Microphone guidance explains that audio is required for Skit Video and keeps the
  deliberate second START press after granting.
- Export finishing now explains the optional notification before requesting it and
  shows a visible message if notifications are denied.
- Controls are not rendered behind the camera-permission panel.
- Dialog focus, headings, live regions for countdown/save/recording/warnings, mode
  state descriptions, and 48 dp minimum targets were added.
- Large-font checks at 1.5× and 2.0× kept all camera controls on screen; Quick
  settings scrolls independently.

## Contextual quick settings

| Photo | Video | Advanced / full |
|---|---|---|
| Aspect ratio, timer, grid, zoom | Quality, frame-rate target, stabilisation, zoom | Exposure, focus/AF hold, white balance, audio routes, diagnostics |

Photo-only rows no longer appear in Video, and Video profile rows no longer appear
in Photo. Session/profile locks are still enforced by the existing models.

## Device validation

Run against the isolated `.m12cvalidation` package. Commands:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --offline
adb shell am instrument -w -r -e class com.calvinmazhindu.skitcam.camera.CameraUxDeviceTest \
  com.calvinmazhindu.skitcam.m12cvalidation.test/androidx.test.runner.AndroidJUnitRunner
```

| Suite | Result |
|---|---|
| `CameraUxDeviceTest` | **OK (9 tests)**; one recovery checkpoint assumption skipped as designed, background-save checkpoint passed |
| `M12ADeviceTest#h_idleToStartHeaderBoundsRemainStable` | **OK** — header bounds unchanged idle → starting → recording → paused |
| `M12ADeviceTest#a_contextReviewAndDirectShelfActions` | **OK** — review, Discard, Restore, metadata and source bytes preserved |
| `M12ADeviceTest#g_fiveSegmentUiLoopStopsAtSuffixAndReleasesForResume` | **OK** — anchored loop 3 → 4 → 5 → 3; RESUME releases player |
| `CameraRotationDeviceTest#currentPoseLifecycleAndControls` | **OK** — activity recreation, camera rebind, photo/video switch |
| `CameraRotationDeviceTest#photoRatioMenuCaptureAndPersistence` | **OK** — all ratios, JPEG framing, persistence, Video cannot edit Photo ratio |
| `ArrangementDeviceTest#a_touchDragCancelPreviewDoneAndRecord` | **OK** |
| `TrimDeviceTest#a_dragPreviewSaveCancelResetAndRecording` | **OK** |
| `lintDebug` | **BUILD SUCCESSFUL**; 0 errors, 40 warnings (unused string resources, dependency notices, existing usability/space warnings) |
| Unit tests / debug APK / android-test APK | **BUILD SUCCESSFUL** |

Manual checks on the Moto G54:

- Video → Photo and Photo → Video through the coin toggle.
- Shutter bounds identical in Photo and Video: `[562,1725][762,1925]`.
- Toggle bounds identical in both modes: `[318,1745][478,1905]`.
- Quick settings Video: quality/frame rate/stabilisation; no timer/grid/ratio.
- Quick settings Photo: ratio/timer/grid; no video profile rows.
- Camera rationale appears before the system prompt; denied state offers Allow/Open settings.
- Font scale 1.5× and 2.0×: primary controls stayed on screen and Quick settings scrolled.

## Before / after evidence

Before (current-main baseline dumps captured before implementation):

- `.validation/framing/main-photo-ui.xml`
- `.validation/framing/main-video-ui.xml`
- `.validation/m12b/landscape-review.png`
- `.validation/m12b/layout-header-contact.png`

After (captured on the validation package):

- `.validation/m12c/screens/after-video-portrait.png`
- `.validation/m12c/screens/after-photo-portrait.png`
- `.validation/m12c/screens/after-video-quick.png`
- `.validation/m12c/screens/after-photo-quick.png`
- `.validation/m12c/screens/after-camera-permission.png`
- Matching `.xml` hierarchy dumps are beside each screenshot.

## Production media preservation

Hashes were captured before any code change and again after device validation.

| Scope | Before | After | Pre-existing missing | Pre-existing changed | Additive |
|---|---:|---:|---:|---:|---:|
| Production private `files/**` | 1,438 | 1,438 | **0** | **0** | 0 |
| `/sdcard/Movies/SkitCam` | 615 | 633 | **0** | **0** | 18 validation videos |
| `/sdcard/Pictures/SkitCam` | 293 | 321 | **0** | **0** | 28 validation photos |

Baseline and after hash manifests:

- `.validation/m12c/private-files-before.sha256`
- `.validation/m12c/private-files-after.sha256`
- `.validation/m12c/public-movies-before.sha256`
- `.validation/m12c/public-movies-after.sha256`
- `.validation/m12c/public-pictures-before.sha256`
- `.validation/m12c/public-pictures-after.sha256`

The added public items were produced by validation-package test recordings and
exports; every pre-existing production recording remained byte-identical. No
production file was removed, overwritten or moved.

## Recording / export regressions

- CameraX preview bounds, crop calculations, orientation policy and aspect framing
  were not changed.
- START / PAUSE / RESUME / FINISH, segment shelf, Discard/Restore, Play, Play from
  here, anchored loop, arrangement, trim, automatic verified export, gallery return
  and media preservation all passed the suites above.
- The export/transcode/recovery engine was not modified.
- Pause and Finish are now spatially separate: pause stays in the 80 dp shutter
  slot, Finish is a labelled outlined action below it.

## Undersized segment review fix

Follow-up pass after inspecting the redesigned review on the Moto G54: the
portrait panel capped the preview at 45% of the remaining height with a 240 dp
maximum, so a 9:16 clip rendered as a tiny letterbox inside a large dark card,
with the live camera and capture dock still competing for attention.

### Implementation

- `review/PausedEnvironment.kt`
  - Replaced the floating review card with a full-screen `ImmersiveReview`
    overlay shown whenever a segment is selected from the paused shelf.
  - Video sizing now uses `BoxWithConstraints` + `ViewfinderGeometry.fit(...)`
    with the recorded aspect ratio from `Segment.media`
    (`width`/`height`/`rotation`), falling back to the ExoPlayer-reported
    `videoAspect` and then to the viewport orientation. The 240 dp cap and the
    45% height heuristic were removed everywhere, including `ReviewPanel`,
    which now aspect-fits the space it is given. `segment_player.xml` pins
    `app:resize_mode="fit"`; nothing is stretched or cropped.
  - Portrait: pinned header row with Close, full-width aspect-fitted video,
    playback state, `Trim`/`Discard`–`Restore`/`Play from here`, then a compact
    horizontal thumbnail shelf along the bottom.
  - Landscape: the video takes the full height minus padding; a capped-width
    (≤38% of the window, 200–240 dp by font scale) side rail holds the header,
    Close, playback state, actions and the thumbnail shelf in a lazy list.
  - Compact `ReviewThumbnailCard`/`ThumbnailMenu` variants preserve every shelf
    affordance (open, Discard/Restore, Play from here) and the existing
    accessibility descriptions, and the shelf remains visible while reviewing.
  - Durations display via the new `review/DurationText.kt`
    (`4411 → "4.4s"`, `61000 → "1:01"`); raw `… ms` text is gone.
- `MainActivity.kt`
  - Added `reviewing` (selection active, no arrangement/trim open). While
    reviewing, the camera header and the entire capture dock leave composition
    and the paused environment receives the full screen (safe-area padding
    happens inside the overlay, so the ink background covers the camera
    preview, including behind the system bars). Closing review restores the
    previous paused recording UI exactly; CameraX stays bound and visible under
    the opaque overlay.
- `review/SegmentReviewController.kt`
  - Tracks `videoAspect` from `onVideoSizeChanged` (pixel aspect + unapplied
    rotation) purely as a display fallback. No playback, decision or file
    behaviour changed.
- RecordingEngine, segment files, decisions/ordering, trim persistence,
  playback anchoring, the export pipeline and CameraX binding logic were not
  touched; PauseEnvironmentDeviceTest still asserts source-byte preservation
  and camera binding while reviewing.

### Device measurements (Moto G54, isolated `.m12cvalidation` package)

Captured by `review/ReviewLayoutDeviceTest`, which plays the clip, pauses on a
decoded frame, then dumps exact bounds to JSON and a screenshot.

Portrait viewport `0 117 1080 2280` (1080×2163 px, 432×865 dp); landscape
viewport `117 70 2280 1080` (2163×1010 px, 865×404 dp). Font scale 1.3 default.

| State | Orientation | Rendered video (px) | Video (dp) | Viewport coverage |
|---|---|---|---|---:|
| Before | Portrait | 305×542 | 122×217 | 7.1% |
| After | Portrait | 906×1611 | 362×644 | 62.5% |
| Before | Landscape | 676×380 | 270×152 | 11.8% |
| After | Landscape | 1633×919 | 653×368 | 68.7% |
| After | Portrait 1.5× | 903×1605 | 361×642 | 62.0% |
| After | Landscape 1.5× | 1633×919 | 653×368 | 68.7% |
| After | Portrait 2.0× | 856×1522 | 342×609 | 55.8% |
| After | Landscape 2.0× | 1532×862 | 613×345 | 60.4% |

The after-state video bounds equal the PlayerView bounds in every capture: the
container matches the recorded 9:16 / 16:9 ratio exactly, so there is no
internal letterboxing left. The rendered area grew ~5.4× in portrait and ~5.8×
in landscape. The test asserts the video and all controls stay inside the
viewport, controls are at least 48 dp, controls never overlap each other, and
coverage thresholds (≥55% portrait, ≥60% landscape at default font; relaxed for
2.0×) hold — all green at 1.3×, 1.5× and 2.0×. `Play from here` was 74 px tall
in the old landscape card (below 48 dp); it now meets the minimum everywhere.
Close is pinned top-right (portrait) or top of the rail (landscape) and remains
reachable while scrolling.

### Test results

```sh
./gradlew -PvalidationSuffix=.m12cvalidation :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --offline
adb shell am instrument -w -r -e class <class>[#method] \
  com.calvinmazhindu.skitcam.m12cvalidation.test/androidx.test.runner.AndroidJUnitRunner
```

| Suite | Result |
|---|---|
| Unit tests (`testDebugUnitTest`) | **BUILD SUCCESSFUL**; includes new `DurationTextTest` |
| Debug + android-test APK assembly | **BUILD SUCCESSFUL** |
| `lintDebug` | **BUILD SUCCESSFUL**; 0 errors, 40 warnings (unchanged set) |
| `ReviewLayoutDeviceTest` (new) | **OK** portrait, landscape, and 1.5×/2.0× font runs (baseline captured pre-change with `-e baseline true`) |
| `PauseEnvironmentDeviceTest` | **OK (4 tests)** — portrait review/play/Discard/Restore/Close, landscape strip + playback + resume with 8-clip scrolling shelf, corrupt/missing fallbacks, discard survives recreation; source bytes and camera binding re-asserted |
| `M12ADeviceTest#a_contextReviewAndDirectShelfActions` | **OK** |
| `M12ADeviceTest#g_fiveSegmentUiLoopStopsAtSuffixAndReleasesForResume` | **OK** — Play from here, anchored loop, RESUME releases the player |
| `TrimDeviceTest#a_dragPreviewSaveCancelResetAndRecording` | **OK** — Trim opens over review, preview, save, cancel, reset |
| `ArrangementDeviceTest#a_touchDragCancelPreviewDoneAndRecord` | **OK** — the reworked `ReviewPanel` still works in the Arrange preview |
| `CameraUxDeviceTest#g_portraitOverlayTargetsAndDockGapDoNotFocusCamera` | **OK** — updated to assert the dock and header are hidden during review, review controls do not overlap, the shelf is preserved, and the paused controls return after Close |

Environment note: during this pass the device stopped applying
`wm user-rotation lock 1` under the default `fixed-to-user-rotation` setting,
which initially failed the landscape runs. Landscape captures were taken with
`wm fixed-to-user-rotation enabled` (the `ReviewLayoutDeviceTest` sets and
restores it around each run) and the default was restored afterwards. This is a
device-side flag; no app behaviour depends on it.

Evidence (screenshots plus exact bounds JSON): `.validation/m12c/review/`
— `before-portrait|before-landscape` and
`after-portrait|after-landscape|after-large15-*|after-large20-*`, plus
`after-cameraux-portrait.png` (review) and `after-paused-restored.png`
(paused UI after Close).

## Remaining work

- Run the full guided physical rotation/photo-landscape matrix and TalkBack
  traversal on a second session; this build only had automated portrait checks and
  the existing landscape target-size test.
- Phase 2 (full-screen clip editor / draft model) and Phase 3 (library, privacy and
  storage management) remain deferred and require separate approval.
- Adaptive launcher icon, hosted privacy policy, Data Safety and release signing
  remain Phase 4 work.
- Some non-camera screens still use their original hardcoded colours even though
  they inherit the new theme; a later polish pass can replace the remaining
  literals.

No commit, push or merge has been made.
