# Immersive video viewfinder — SkitCam 0.11.1

Branch **`fix/immersive-video-viewfinder`**, based on Milestone 11 commit
**`011760e`** (0.11.0). Provisional release **0.11.1 / versionCode 14**.
Validation: **4–5 November 2026**, Moto G54 5G **`ZY22KJQLTV`**, Android 15 / API 35,
1080×2400, density 400. No commit or push.

## Confirmed cause

The checked-out code was inspected before editing and four baseline framing tests
were run against the installed, isolated 0.11.0 application. This is a layout
constraint, not excess camera zoom:

1. `MainActivity.kt` padded `CameraPreview` by the measured toolbar **and bottom
   dock** heights in portrait. Dock content changes between idle/recording/PAUSE.
2. `CameraPreview.kt` passed the remaining width/height to
   `ViewfinderGeometry.fit()`, using actual bound recording dimensions/rotation.
3. Fitting 9:16 into less than 1920 px of height cannot reach a 1080 px width.
   The baseline idle image was **933×1658**, with approximately **74 px** side
   margins. The sampled recording image narrowed further to **877×1559**.

`FIT_CENTER`, matching Preview/Recorder stream aspects, default zoomRatio **1.0**,
linearZoom **0**, and full Camera2 active-array crop regions were already correct.
The 0.10.1 framing correction is retained.

## Small layout correction

- Portrait **Skit Video** reserves the measured toolbar height, horizontal safe
  insets and bottom safe inset. It **does not subtract the dock height** from the
  image region. On this Moto: `2400 - 237 - 120 = 2043 px` available height with
  three-button navigation; 9:16 fits at **1080×1920**.
- Portrait video is top-centred below the toolbar. The complete fitted frame
  extends beneath the bottom controls. Its dimensions no longer depend on the
  recording/PAUSE dock's height. An undersized window still uses the original
  aspect-preserving fit; full width is never forced by stretching or cropping.
- The existing transparent-to-black bottom gradient (maximum alpha 0.8) now
  overlays the live image for control readability. No extra opaque panel or
  stronger gradient was necessary. Header and landscape gradients are retained.
- The portrait-video dock participates in input hit testing, including its empty
  areas. It consumes remaining input in the final pointer-event pass, after
  children handle their buttons/scrolling, so dock gaps cannot focus/pinch the
  image underneath. The device test probes a point verified to be inside both
  the dock and the actual live PreviewView.
- Review/thumbnails and snackbars still reserve measured toolbar/dock space.
  Trim/arrange/export overlays retain their existing placement and state gates.
- Photo retains the same padding, centring, 4:3 geometry, controls and input
  behaviour. Landscape retains its existing centred fit and side dock.

No CameraX binding/profile, resolution selector, Recorder, codec, FPS,
stabilisation, zoom policy, session schema, MP4 assembly, Gallery or portable
project-export production code was changed. No new dependency or permission.

### Measured geometry

Raw on-screen PreviewView bounds, `(x, y, width, height)` in pixels:

| Case | 0.11.0 baseline | 0.11.1 |
|---|---|---|
| Rear portrait idle | (74, 237, 933, 1658) | **(0, 237, 1080, 1920)** |
| Front portrait idle | (74, 237, 933, 1658) | **(0, 237, 1080, 1920)** |
| Rear portrait recording | (102, 245, 877, 1559) | **(0, 237, 1080, 1920)** |
| Rear landscape idle | (344, 190, 1333, 750) | (344, 190, 1333, 750) |
| Front landscape idle | (344, 190, 1333, 750) | (344, 190, 1333, 750) |
| Rear/front portrait Photo | (0, 240, 1080, 1440) | (0, 240, 1080, 1440) |

Both portrait lenses retained 1080×1920 during recording, PAUSE, RESUME and after
automatic export/camera return. The gesture-navigation / **2.0× font-scale** runs
also measured (0, 237, 1080, 1920); their bottom system inset was **60 px**, versus
**120 px** with three-button navigation. Measurements are for the sampled normal
toolbar; longer warnings or other window geometry can change available height.

## Screenshots

These links reference retained **local, Git-ignored validation evidence**, matching
the project's prior validation convention. Original full-resolution PNGs, actual
PreviewView bitmaps and diagnostic JSON are retained beside the media; the JPEGs
below are reduced copies for convenient inspection.

| Portrait before | Portrait after |
|---|---|
| ![Portrait baseline with side margins](.validation/immersive/screens/portrait-before.jpg) | ![Full-width portrait with controls over the fitted image](.validation/immersive/screens/portrait-after.jpg) |

| Recording | Paused / thumbnails |
|---|---|
| ![Full-width recording](.validation/immersive/screens/portrait-recording.jpg) | ![Paused portrait controls and thumbnail strip](.validation/immersive/screens/portrait-paused.jpg) |

![Landscape retains its fitted image and side dock](.validation/immersive/screens/landscape.jpg)

| Gesture navigation, 2.0× text, review | Gesture navigation, 2.0× text, landscape |
|---|---|
| ![Large-text review above the recording dock](.validation/immersive/screens/gesture-large-text-review.jpg) | ![Large-text landscape targets](.validation/immersive/screens/gesture-large-text-landscape.jpg) |

Screenshots were inspected for side bars, aspect, gradient readability and overlay
collisions. Landscape sensor pose was injected for recordings while display
rotation was overridden; the stationary phone's room scene therefore appears
sideways. The existing orientation warning in idle screenshots is truthful.

## Preview versus saved MP4

Dedicated source and finished-video copies were independently decoded with FFmpeg:
**20 MP4 copies**, covering four baseline cases and six corrected cases (front/rear,
portrait/landscape, plus portrait EIS On for both lenses). Six corrected comparison
sheets were visually inspected for matching ceiling, doorway, curtains and
foreground edges. Front-preview images were unmirrored for analysis; saved video
retains its existing unmirrored behaviour.

Full-image luminance correlation after resizing equally, using the actual
recording-time PreviewView bitmap and an independently decoded source frame:

| Corrected case | Preview/source correlation |
|---|---:|
| Rear portrait, EIS Off | 0.999588 |
| Front portrait, EIS Off | 0.999338 |
| Rear landscape, EIS Off | 0.999459 |
| Front landscape, EIS Off | 0.999252 |
| Rear portrait, EIS On | 0.999059 |
| Front portrait, EIS On | 0.995865 |

The full frame, rather than a cropped comparison region, was compared. The lower
baseline front-portrait correlation (0.876409) reflects a visibly moving person
between the two sampled times; its comparison sheet is retained too. These are
framing checks, not synchronised pixel-identity or optical field-of-view claims.

Before/after ffprobe comparisons matched video/audio codec, profile, level,
dimensions, pixel format, reported colour fields, audio sample rate and channels.
Default FHD footage remains **H.264 / 1920×1080 + AAC / 48 kHz / stereo**, with the
existing portrait display matrix or landscape rotation. Auto FPS remains Auto;
scene-dependent cadence, bitrate and encoded bytes are not expected to be identical
between separate recordings. CameraX and Camera2 diagnostics show zoom **1.0**,
linearZoom **0**, matching full stream crop rectangles, and unchanged default
sensor crop/stabilisation facts. EIS-On framing was also checked without silently
disabling it. There is no evidence of new stretching, preview crop or digital zoom.

The additional 2.0×-text gesture-navigation runs recorded, paused, resumed, exported
and returned on both lenses, with the same geometry assertions. Their media and
screenshots are retained separately; the 20-file decode count above excludes them.

## Tests and builds

**185 JVM tests passed**, zero failures/errors/skips. Two additional geometry cases
cover recovering full width from the reduced-height layout and honest fitting when
the safe region is too short.

**74 distinct post-change Android cases passed** on the Moto:

| Selected cases | Count |
|---|---:|
| Framing, both EIS lenses, actual video touch focus/pinch, zoom continuity | 8 |
| Camera UX, overlaid-dock input/targets, automatic return and save recovery | 9 |
| Photo/modes/permissions/focus/timer and Gallery | 12 |
| Recording lifecycle, paused review/Keep/Discard and arrange | 14 |
| Trim, including Activity recreation and retained restart fixture | 4 |
| Audio and video profiles, FHD/60 segmented and 15-second landscape | 8 |
| Exact paused-profile recovery across isolated-app force-stop/restart | 2 |
| Export journals, leading-audio-gap handling and STOP workflow | 10 |
| Production timing fixtures | 1 |
| Portable project picker/options and journal persistence | 5 |
| Finished MP4 playback/release | 1 |
| **Total** | **74** |

Four of those case identities were also repeated under actual gesture navigation
and 2.0× system text: portrait framing on both lenses, portrait overlay targets,
and landscape/quick/settings targets. These repetitions, four baseline cases,
setup/owned-checkpoint completion utilities and earlier failed attempts are not
counted again. Final selected outcomes contain no assumption skips. Explicit
48 dp target/safe-area and non-overlap assertions cover primary controls and
review actions; review is awaited in Media3 STATE_READY before its final screenshot.

All **15 timing renders** (failed/passed/zero/offset/trimmed fixtures, three each)
were accepted by the unchanged production verifier. Its **100 ms** limit remains.
The real Downloads picker created a verified portable ZIP from the existing
isolated M11 fixture (saved trims, discarded segment and arranged order), followed
by a distinct new recording/export. Independent desktop verification and
`unzip -t` accepted all five archive entries; original/finished media hashes and
metadata matched. This regression does not repeat M11's >4 GiB ZIP64 stress test.

Production `testDebugUnitTest`, `assembleDebug` and `lint --offline` passed.
Lint: **0 errors / 9 existing warnings** (AndroidGradlePluginVersion,
NewerVersionAvailable, five UsableSpace and two UseKtx). No new warning/suppression.
Isolated application and Android-test APK assembly also passed.

### Harness corrections and retained failures

- The new target assertion initially read the Arrange **text** node, rather than
  its clickable ancestor. It now checks the actual button target. A later player
  readiness assertion initially accessed Media3 from the instrumentation thread;
  it now reads playback state on main. Both were test-only corrections.
- An early large-text review screenshot showed the existing playback-error
  presentation. Final runs explicitly await a ready review player and passed at
  normal/large text; their screenshots replace no original failure artifact.
- Existing video tests could accept a previous session's Completed export before
  the current export started, then request a new profile too early. Their wait now
  requires the current session ID, settled export service, handled camera-return
  receipt and actual rear-camera binding. No production export/profile change.
- Initial profile runs also exposed saved SD preferences instead of the fixture's
  expected FHD setup, and a short FHD/60 sample outside the unchanged 55–65 fps
  assertion. Explicit profile preparation and the final runs passed without
  loosening FPS assertions. The exact-profile process fixture was prepared in FHD
  before requesting FHD/60, using normal profile changes.
- The first navigation-mode probe ran before Android propagated the overlay
  change. The harness now waits for mode 2 before testing; restoration is in a
  `finally` block. Font **1.3**, three-button navigation and rotation **lock 0**
  were restored and verified afterwards.

## Preservation and delivery

Fresh before/after SHA-256 manifests cover **1,788 pre-existing session files**:
main app 779, M9 validation 229, M10 268, framing validation 238, M11 validation 274;
plus **383 pre-existing SkitCam Gallery files**. Final audit: **zero missing and
zero changed**. Public Gallery now has 460 files; the isolated M11 session scope
has 606. Test recordings were made through `.m11validation`, not the main app.
Old M7.1/M8 apps have no accessible user-0 data directory; their errors are recorded
as exclusions, never counted as file hashes. Other Android user profiles are outside
the audit.

During the run, **11 new main-app session files** appeared independently of the
isolated test commands, bringing its count to 790. Read-only inspection found two
completed sessions and a newer **paused front-camera session**. Every baseline
main-app file and all 11 newly observed files matched between the closing audits.
The paused session was left intact at the end of the initial validation. The main
application was initially not replaced or launched for a deployment smoke test;
the subsequent installation is recorded below. No uninstall/data clear or
original-media cleanup was performed. The validation-only test APK was restored
to its prior disabled state so its simulated DocumentsProvider stays out of the
ordinary destination picker; all its data remains.

Delivery APK: **`app/build/outputs/apk/debug/app-debug.apk`**, production package
`com.calvinmazhindu.skitcam`, 0.11.1 / 14. SHA-256:

```text
211810f23ca55fb7cc0a7a86d1d6a8a193e6424568c986fb1994b2e03d2fecc9
```

The candidate first ran on the physical Moto in the isolated app, followed by the
main-app installation and smoke check below.

### Main-app installation — 5 November 2026, 08:17 UTC

After the user queried the missing main-app update, the latest session was checked
again. It had since reached **Completed**, with one front-camera segment and no
pending capture. A new deployment-specific hash baseline was taken immediately
before installation, covering **792 main-app session files and 461 Gallery files**.

The exact production APK above was installed successfully with:

```bash
adb -s ZY22KJQLTV install -r app/build/outputs/apk/debug/app-debug.apk
```

The main app cold-launched successfully as **0.11.1 / code 14**. Its ordinary
front-camera Skit Video choice was restored, START was enabled, and the preview
accessibility bounds were **[0,237][1080,2157]**, confirming the full-width
**1080×1920** viewfinder. `dumpsys media.camera` identified the production package
as the active camera-1 client. The screenshot was visually inspected:

![Installed main-app full-width front-camera preview](.validation/immersive/deployment/main-0.11.1.png)

After installation and launch, **all 792 session files and all 461 Gallery files
matched their fresh baseline hashes**, with no missing/changed files or count
changes. The latest completed session JSON also matched byte-for-byte. No recording,
resume or export was requested for this deployment smoke check. Evidence is under
`.validation/immersive/deployment/`: before/after manifests, `audit.json`, session
snapshots, screenshot, accessibility XML/bounds, package version and camera dump.

## Reproduction and evidence

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lint --offline
./gradlew -PvalidationSuffix=.m11validation :app:assembleDebug :app:assembleDebugAndroidTest --offline
adb -s ZY22KJQLTV shell am instrument -w -r -e class <selected-class-or-methods> \
  com.calvinmazhindu.skitcam.m11validation.test/androidx.test.runner.AndroidJUnitRunner
python3 tools/verify_portable_project.py <copied-project.zip>
```

The existing isolated app/test package must be installed/enabled and fixture
preconditions supplied; do not run recording fixtures against production. The
timing test uses the retained dedicated `files/validation-m71/inputs` copies and
`-e kinds failed,passed,zero,offset,trimmed`. Profile checkpoint/recovery and save
checkpoint/recovery run in separate invocations with force-stop of only the
isolated app between them.

Ignored `.validation/immersive/` contains fresh manifests, raw before/after
screenshots/PreviewView bitmaps, copied MP4s, decoded frames/comparison sheets,
ffprobe reports, build/instrumentation logs (including failures), 74-case accounting,
JVM/lint summary and the verified ZIP. Key files: `geometry.json`,
`media-analysis.json`, `summary.json`, `final-audit.json`, `build-delivery.log` and
`configuration-dock-final.log`. Diagnostic collection/analysis scripts are retained
there too. No prior validation fixture was deleted.

## Remaining limitations

- Full width does **not** mean the entire frame is unobstructed. At normal idle
  the image extends from y=237 to **2157**; the dock starts around y=1895, so its
  transparent gradient and controls overlap approximately **262 image rows**.
  PAUSE also intentionally overlays thumbnails/review. The actual complete image
  is still fitted, not cropped; buttons obscure parts of it. This is the explicit
  tradeoff for the requested immersive control overlay.
- If safe width/aspect requires more height than is available, letterboxing can
  remain. For example, 1080×1700 available at 9:16 fits **956.25×1700**, not a
  cropped 1080-wide image. Other hardware, split-screen sizes, unusually large
  warnings and more extreme accessibility settings are unverified.
- Landscape retains honest framing and its existing margins/side dock. Photo and
  video still use different sensor/aspect intervals. Front preview remains mirrored.
  EIS/OIS can change genuine recorded framing; this UI fix does not disable them.
- Physical hardware and accessibility-node/input tests are not a full in-hand
  rotation study, TalkBack user audit, motion-stabilisation or perceptual A/V quality
  assessment. Saved-frame comparisons establish sampled framing, not universally
  identical frames or encoded bytes.

## Changed files

- `MainActivity.kt`, `CameraPreview.kt`: portrait-video layout and dock hit testing.
- `app/build.gradle.kts`: 0.11.1 / 14.
- `ViewfinderGeometryTest.kt`: two layout-threshold regression cases.
- `VideoFramingDeviceTest.kt`, `CameraUxDeviceTest.kt`: measured state geometry,
  safe controls, real overlapping-dock tap and review readiness assertions.
- `VideoDeviceTest.kt`: current-session export/return and actual-lens waits.
- README and this validation record.
