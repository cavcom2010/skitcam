# Milestone 12F — UI polish and consistency

Validation date: **15 November 2026**  
Device: **Moto G54 5G `ZY22KJQLTV`**, Android 15 / API 35, 1080×2400  
Branch: **`feature/m12f-ui-polish`**, created from `main` at `53b55e5`  
Production package: **`com.calvinmazhindu.skitcam`**, 0.13.0 / versionCode 17
(not installed over, cleared or uninstalled)  
Validation package: **`com.calvinmazhindu.skitcam.m12cvalidation`**

## Result

The M12F presentation pass is complete. Recording, export, trim persistence,
arrangement persistence and media-integrity code were not changed. Review remains
the only trim surface; there is no new Trim screen.

### Paused camera

- Confirmed the circular overlay is the transient tap-to-focus ring
  (`CameraModel.focus`, drawn by `CameraPreview`), not a recording control.
- AF metering, focus locking, expiry and camera gestures are unchanged. Only the
  stale ring is hidden when the session becomes Paused, so it cannot sit beneath
  the paused count/shelf text.
- The count now has a one-line constraint, explicit padding and a live-region
  semantic description.
- At >1.5× font scale the fixed-size RESUME circle uses a play glyph instead of
  overflowing “RESUME” text. The mode coin hides its inner label but keeps its
  icon, 64 dp target and spoken mode description.
- Device test asserts the count does not intersect RESUME/STOP and the mode coin
  does not intersect RESUME, at 2.0× font on a 360×640 dp viewport.

### Arrange

- Cards are single-row and compact: 44 dp thumbnail, inline title/duration,
  48 dp Play/Preview, Move up and Move down targets, and the existing 48 dp drag
  handle.
- Long-press drag, edge auto-scroll, Preview, Cancel/Done, stale-draft protection
  and custom accessibility Move up/down actions are unchanged.
- A device test asserts card pitch ≤ 76 dp and 48 dp action targets.
- Arrange shows kept playback order only, so every card has the green check mark
  and green thumbnail border.

### Segment status and selection

- Green remains kept; discarded now uses the same orange family as trimmed-out
  trim ranges instead of the previous pink/error colour.
- Added small non-colour cues: a green check mark for kept and an orange minus
  for discarded. Accessible card descriptions still announce “Kept” or
  “Discarded”; the mark is decorative and does not duplicate the announcement.
- Selection is a separate warm outline and tint, outside the green/orange status
  border, so selected Kept and selected Discarded remain distinguishable.

### Inline trim

- Portrait Review now lifts the transport/trim overlay out of the fitted video and
  spans the full safe screen width. The preview itself keeps its exact aspect-fit
  bounds.
- The trim track owns a full-width row in portrait; Play, jump-to-start and the
  track no longer share the narrow video width.
- Landscape keeps the existing in-video overlay.
- Current/total time was removed from Review in both orientations, by request.
  Play skit and Arrange preview keep their existing time readout.
- Track visual: neutral source, orange trimmed-out ranges with subtle hatching,
  green retained range with outline, warm playhead and white grip pills.
- Drag handles keep 48 dp touch targets. Each grip now shows one inward arrow:
  - tapping the left/start grip moves the trim start forward one frame;
  - tapping the right/end grip moves the trim end backward one frame;
  - dragging still moves either boundary continuously;
  - frame stepping uses the same average-FPS step as the accessibility actions.
- Playhead preservation fix: rebuilding the clipped preview used to reset
  ExoPlayer to zero, which made the marker jump to the green range's start.
  `SegmentReviewController.clip` now accepts an absolute source position and
  seeks the rebuilt clip to the matching relative position. The marker is held
  steady while that seek is pending and is clamped only if the edit removes it
  from the new range. Start-step, end-step and Reset trim therefore preserve the
  playhead; dragging still updates the boundary.
- No save, reset, verification, duration-tolerance or trim-boundary behaviour was
  changed.

### Playback/layout

- Review still uses the M12E immersive header, actions and clip shelf.
- Video remains aspect-fit. At 2.0× on the 360×640 dp test viewport, Review video
  retained 298×529 px (aspect 0.5625) and used 49.6% of the available height;
  area coverage is naturally lower because a 9:16 frame is width-limited.
- Play skit keeps its existing aspect-fit layout and still shows its own timer.
- Very narrow playback overlays stack the time onto a second line rather than
  clipping it; normal and standard 2.0× layouts keep the one-row transport.

## Test results

```sh
./gradlew -PvalidationSuffix=.m12cvalidation \
  :app:testDebugUnitTest :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:lintDebug
```

| Suite | Result |
|---|---|
| JVM unit tests | **240 passed**, 0 failed |
| Debug + android-test APKs | **BUILD SUCCESSFUL** |
| `lintDebug` | **BUILD SUCCESSFUL**, 0 errors, 44 warnings |
| `TrimDeviceTest` | **OK (6 tests)** — playhead preservation across start/end steps and Reset, frame-step taps, drag/save/reset, dirty prompt, Discard/Restore without saving draft, source-byte preservation, landscape save |
| `ArrangementDeviceTest` | **OK (4 tests)** — compact pitch, 48 dp controls, drag, auto-scroll, Preview, Done/Cancel, recreation, stale guard |
| `PauseEnvironmentDeviceTest` | **OK (4 tests)**; landscape case passed on rotation retry |
| `CameraUxDeviceTest#h` | **OK** at 2.0× / 360×640 dp — focus ring retired, count and circles clear |
| `ReviewLayoutDeviceTest` | **OK** portrait and landscape, standard and 2.0×, plus small 1.0× and 2.0× portrait |
| `UnifiedVideoLayoutDeviceTest` | **OK** standard portrait/landscape, 2.0× portrait, and 2.0× small portrait (Review + Play) |

Landscape automation intermittently hit the previously documented Moto rotation
flake before any M12F assertion. Those suites passed when retried with the display
actually fixed to landscape. The final Trim suite passed all six tests, including
the new playhead regression. The phone was restored to native size, 400 dpi, 1.0×
font and portrait/default rotation after validation.

## Layout measurements

| Screen | Viewport | Video | Trim track |
|---|---:|---:|---:|
| Review portrait, 1.0× | 1080×2163 px | 927×1648 px (unchanged) | 1040×120 px (96.3% width) |
| Review portrait, 2.0× small | 720×1067 px | 298×529 px | 688×96 px (95.6% width) |
| Review landscape, 1.0× | 2163×1080 px equivalent | 1633×919 px (unchanged) | in-video overlay |

Trim handles measured 120 px wide at 2.5× density = 48 dp.

## Production media integrity

The production app was never launched, installed over, cleared or uninstalled.

| Scope | Before | After | Missing | Changed | Added |
|---|---:|---:|---:|---:|---:|
| Production private `files/**` | 1,544 | 1,547 | 0 | 2 | 3 |
| `/sdcard/Movies/SkitCam` + `/sdcard/Pictures/SkitCam` | 1,077 | 1,132 | 0 | 0 | 55 |

No pre-existing production **media** file was changed or removed. During the
session the production app completed an export for its pre-existing 14 November
session `1f7fe50c…`: that added one production MP4 plus three private export
artifacts and updated its `session.json` and `profileInstalled`. This was not
performed by the validation package (which cannot write the production UID), and
the production APK was never installed, cleared or uninstalled. It is recorded
here rather than hidden. The remaining 54 public additions are validation
exports.

The previously installed `.m12cvalidation` APKs used another debug signing key, so
Android refused an in-place update. Only that validation package and its test
package were uninstalled and reinstalled. Production installation time/version
remain `2026-11-01 00:52:17`, 0.13.0 / 17.

## Evidence

- `.validation/m12f/validation-m12c-review/m12f-evidence-portrait-portrait.png`
  (full-width portrait trim, no timer)
- `.validation/m12f/validation-m12c-review/m12f-fullwidth-small-font2-portrait-portrait.png`
  (2.0× / 360×640 dp Review)
- `.validation/m12f/validation-m12d/m12f-final-small-font2-portrait-{review,play}.png`
- `.validation/m12f/validation-m4/*-portrait-drag.png` (compact Arrange)
- `.validation/m12f/validation-m4/*-arrange-preview.png`
- `.validation/m12f/validation-m4/*-portrait-discarded-status.png`
- `.validation/m12f/validation-m4/*-portrait-kept-status.png`
- `.validation/m12f/validation-m10/paused-count-and-dock.png` (2.0× small)
- `.validation/m12f/validation-m12e/*-portrait-inline-trim.png`
- `.validation/m12f/validation-m12e/*-portrait-trim-handle-step.png`
- `.validation/m12f/validation-m12e/*-playhead-preserved-start.png`
- `.validation/m12f/validation-m12e/*-playhead-preserved-reset.png`
- `.validation/m12f/private-{before,after}.sha256`
- `.validation/m12f/public-{before,after}.sha256`

## Validation APK

```sh
./gradlew -PvalidationSuffix=.m12cvalidation :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Installed APK SHA-256:
`e710e9884a636e3a24d2f912932b24b16f1135197e5b1b58384555d49804720d`

## Remaining

- Visual inspection and approval by the product owner.
- A second manual rotation/TalkBack pass is still recommended.
- Frame stepping follows average recorded cadence; variable-frame-rate media is
  approximated.
- Play skit intentionally retains its timer; only Review removes it.

No merge into `main`, push, or production installation has been performed.
