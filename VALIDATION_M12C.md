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
