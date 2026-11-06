# Landscape Photo viewfinder utilization

Branch **`fix/photo-landscape-viewfinder`**, based on latest `main` / `origin/main`
**`8cb1aff`** (0.11.2). The branch already existed at that commit when this work
started. Validation: **6 November 2026**, Moto G54 5G **`ZY22KJQLTV`**, Android 15 /
API 35, 1080×2400, density 400, font scale 1.3, three-button navigation.
Auto-rotate remained **off**, `user_rotation=0`, `wm user-rotation=lock 0`.

## Cause and scoped correction

`MainActivity.kt` already let landscape Skit Video extend behind its header and
bottom mode controls. Landscape Photo still subtracted the header and a 56 dp
bottom band before fitting its 4:3 image. Both Photo lenses consequently measured
**1000×750**, with unused height above and below the fitted image.

The production change is confined to **`MainActivity.kt`**:

- Both landscape modes use the available safe-area height behind the header and
  bottom mode controls. Photo now uses the same existing overlay placement as
  Video, while retaining its own **4:3 `FIT_CENTER`** image.
- The existing transparent-to-black header/mode-row scrims (maximum alpha .65)
  and final-pass pointer blocking now apply to landscape Photo too. Child
  controls handle their input first; gaps do not focus or pinch the image.
- The right-side shutter dock, its width, end/vertical safe insets and existing
  horizontal gradient remain as before. The image does not extend into the dock.
- Portrait Photo and both Video layout branches retain their previous geometry.

`CameraPreview.kt`, `ViewfinderGeometry.kt`, ImageCapture/PhotoStore, the sensor
orientation controller/policy, manifest, recording/session and export production
code are unchanged. In particular, the Photo `UseCaseGroup`, 4:3 `ViewPort.FIT`,
highest-available resolution selection/fallback, maximize-quality capture,
JPEG quality 100, rotation and mirroring configuration are retained. No version
bump, app dependency or permission was added.

## Fresh before/after measurements

The baseline ran the new measurement harness against the unmodified production
layout before applying the UI patch. Both baseline and candidate used the isolated
**`.m11validation`** package, real physical rotation and actual JPEG capture.

PreviewView bounds are `(x, y, width, height)` in physical screen pixels:

| Case, both lenses | Before | After |
|---|---|---|
| Photo landscape, top edge left / display rotation 1 | (450, 190, 1000, 750) | **(335, 70, 1347, 1010)** |
| Photo landscape, top edge right / display rotation 3 | (452, 190, 1000, 750) | **(338, 70, 1347, 1010)** |
| Portrait Photo, before and after each rotation cycle | (0, 240, 1080, 1440) | **(0, 240, 1080, 1440)** |
| Portrait Skit Video | (0, 237, 1080, 1920), prior record | (0, 237, 1080, 1920) |
| Landscape Skit Video, idle/recording/paused | 1783×1003, prior record | 1783×1003 |

Photo uses **all 1010 px of safe height** on this device, versus 750 px before.
Its fitted area grows by approximately **81.4%**. The 1347 px width is the rounded
4:3 fit, not an attempt to stretch Photo across a 2400 px landscape screen.
Pillarboxing remains intentionally. The small x change between rotations follows
the navigation/cutout insets.

Both lenses retain the same Activity and ImageCapture object through
portrait → left landscape → right landscape → portrait. Each capture is at actual
CameraX **zoomRatio 1.0**, linearZoom 0. Deliberate 1.5× zoom survives the next
physical rotation and is then restored to 1.0×. Idle `FULL_SENSOR` is unchanged.

### Screenshots

Links refer to retained **local, Git-ignored evidence**, following the existing
validation records. Full-resolution screenshots, native PreviewView bitmaps and
diagnostic JSON are retained beside these reduced JPEGs.

| Rear Photo before | Rear Photo after |
|---|---|
| ![Rear Photo before](.validation/photo-landscape/screens/photo-before-Rear-270.jpg) | ![Rear Photo expanded to safe height](.validation/photo-landscape/screens/photo-after-Rear-270.jpg) |

| Front Photo before, opposite landscape | Front Photo after, opposite landscape |
|---|---|
| ![Front Photo before](.validation/photo-landscape/screens/photo-before-Front-90.jpg) | ![Front Photo expanded in opposite landscape](.validation/photo-landscape/screens/photo-after-Front-90.jpg) |

The baseline and candidate both display an existing isolated-app **Retry Save**
fixture warning. It was retained, not repaired or removed by this layout work.
All new captured JPEGs published successfully. Some screenshots include physical
rotation toasts; the native PreviewView bitmap excludes Compose controls/toasts.

## Viewfinder-to-JPEG crop equivalence and unchanged output

`guidedPhotoLandscapeViewfinder` captures native PreviewView bitmaps and real
shutter-button JPEGs on both lenses in portrait and both landscape directions.
It checks against the baseline's Preview/ImageCapture resolution, crop rectangles
and rotation, then independently validates JPEG dimensions and EXIF.

- Rear JPEG storage: **4096×3072**, unchanged.
- Front JPEG storage: **2302×1740**, unchanged.
- EXIF orientation matches the corresponding baseline for each lens/pose.
- Rear Preview/ImageCapture streams remain 1600×1200 and 4096×3072, with full
  stream crop rectangles.
- The existing front Preview is 1440×1088. ImageCapture is 2320×1740 with the
  existing `[9,0][2311,1740]` crop, producing 2302×1740 JPEGs. These slightly
  non-4:3 native dimensions are retained; no attempt was made to change capture
  quality or normalize the JPEG by cropping it further.
- Camera2 active-array and requested/result crop observations at 1.0× are
  retained in the diagnostics. The native stream facts match before/after.

The desktop verifier, **`tools/verify_photo_viewfinder.py`**, applies JPEG EXIF,
unmirrors front **preview copies only**, and uniformly contains each complete image
in an equal-aspect canvas. It never crops comparison images, stretches them to
fit or warps one onto the other. Whole-frame luminance correlations below are
computed **before registration**. SIFT correspondences and a similarity transform
measure residual scale/rotation/offset, rather than adjusting away a crop.

All six final sampled comparisons passed the same preselected limits: scale
within 3%, rotation within 2°, center offset within 4% of each image dimension,
at least 20 inliers and inlier coverage over at least 55% of both dimensions.

| Lens / pose | Whole-frame correlation | Preview→JPEG scale | Inliers |
|---|---:|---:|---:|
| Rear portrait | 0.997906 | 1.000450 | 595 |
| Rear landscape, top left, steady recapture | 0.883484 | 0.993588 | 379 |
| Rear landscape, top right | 0.722310 | 0.997746 | 435 |
| Front portrait, stationary recapture | 0.944163 | 1.007524 | 129 |
| Front landscape, top left | 0.867470 | 1.003848 | 70 |
| Front landscape, top right | 0.878581 | 1.007870 | 91 |

This supports matching sampled scene intervals and no added crop/magnification;
it is not a pixel-identity or calibrated optical field-of-view claim. Handheld
movement, scene/person movement, autofocus/ISP differences and asynchronous
preview/shutter timing explain why correlation alone is insufficient.

Complete unwarped comparison sheets were inspected for scene-edge agreement.
They are retained under `.validation/photo-landscape/analysis-verified/`.
`analysis.json` names the exact source directory for each final sample.

### Retained measurement failures

- An initial rear landscape sample moved vertically by about **5.8%**, beyond the
  4% limit. It was recaptured with the phone braced; the final sample passed.
- The first front portrait scene did not provide the required vertical feature
  coverage. An immediate recapture also moved during repositioning. A delayed,
  stationary reference passed with approximately 96%/97% feature spans.
- The first analysis version used a landscape canvas for portrait images, making
  its coverage interpretation inappropriate for portrait. Portrait now uses a
  portrait canvas; the final script treats both orientations consistently.
- Sampling now refreshes the native bitmap immediately before the real shutter
  tap, after accessibility/screenshot collection, reducing asynchronous skew.

No framing threshold was relaxed to accept a failed sample. Original samples and
their failed analysis reports remain under `analysis/`, `analysis-corrected/`,
`analysis-final/` and the original capture directories. Final passing evidence is
in **`analysis-verified/`**, with explicit rear/front recapture supplements.

## Control hit testing and focus/zoom

For both Photo lenses and both landscape directions, device assertions check:

- At least **48 dp** safe-area targets for Settings, Quick settings, shutter,
  Gallery, lens switching and both mode chips; pairwise target non-overlap.
- Actual touch shutter input publishes a valid JPEG; actual Quick settings and
  portrait mode-chip taps reach their controls.
- Injected taps/pinches in verified header/mode-row gaps that overlap the actual
  PreviewView do **not** change focus or zoom. Pillarbox taps do not focus.
- An exposed-image tap produces supported focus/metering feedback with the correct
  local image coordinates; a real two-pointer pinch changes observed CameraX
  zoom, which returns to 1.0× afterwards.

For example, in left landscape the shutter target was
`[1990,343][2190,543]`, Gallery `[2030,203][2150,323]`, and lens switching
`[2030,563][2150,683]`. The Photo frame ends at x=1682, keeping the right-side
dock separate. Front tap metering returned **Focus not confirmed** in a sampled
case; confirmed autofocus is not claimed for that lens. Target checks are not a
TalkBack usability audit or a claim that every control action was manually used.

## Tests and builds

| Check | Result |
|---|---|
| JVM suite, including two new 4:3 safe-height/width-limited cases | **196 passed**, 0 failures/errors/skips |
| Desktop verifier positive/negative controls | **3 passed**: identity, real 20% center crop, real 10% translation |
| New Photo baseline | 1 invocation passed, excluded from post-change count |
| Post-change Android checks | **16 passing invocations, 13 distinct methods**, plus 1 skipped optional case |
| Photo layout/touch/rotation/JPEG matrix | Both lenses, both landscape directions and portrait passed |
| Existing Activity recreation/zoom lifecycle test | Passed |
| Video preservation regression | Both lenses: portrait, both landscape directions, recording, PAUSE, RESUME, STOP/export, camera return passed |
| STOP two-segment export / Gallery / Play / Share / return | Passed |
| Export-journal recovery/idempotency and leading-audio-gap consumers | 9 cases passed |
| Production `testDebugUnitTest`, `assembleDebug`, `lint --offline` | Passed |
| Isolated app / Android-test APK assembly | Passed |

Lint: **0 errors / 10 existing warnings** (AndroidGradlePluginVersion,
NewerVersionAvailable, DiscouragedApi, five UsableSpace, two UseKtx). No new warning
or suppression. Only Photo behavior was changed; Video was exercised for
regression protection.

### Not executed / limits

- The standalone `FinishedPlaybackDeviceTest#publishedVideoPreparesPlaysAndReleases`
  was selected without its required `videoUri` fixture and **assumption-skipped**.
  It is not counted as passed. The separate STOP workflow did prepare/play the
  new finished MP4 and exercise Gallery/Share/camera return successfully.
- No new Auto-rotate-ON matrix, 2.0× text, gesture-navigation matrix, other device,
  split-screen, Android 16/foldable, TalkBack or thermal/codec stress run. The
  existing orientation policy is unchanged; this run used physical rotations
  with Auto-rotate OFF and three-button navigation.
- The 15-render timing investigation and portable project picker/ZIP stress tests
  were not repeated. Their production code is unchanged. Relevant JVM tests and
  the selected real recording/export regressions passed.
- No main-app installation/smoke test was performed for this patch. Testing used
  the isolated package; the production APK is built for delivery.

## Preservation and delivery

Before/after SHA-256 manifests include existing private **session and photo-job**
files plus public SkitCam pictures/movies. Every baseline hash matched:

| Scope | Before | After | Changed/missing baseline files |
|---|---:|---:|---:|
| Main app | 867 | 869 | 0 |
| M11 validation | 710 | 746 | 0 |
| M9 validation | 263 | 263 | 0 |
| M10 validation | 283 | 283 | 0 |
| Framing validation | 251 | 251 | 0 |
| Public Gallery | 507 | 527 | 0 |

All **2,374 private baseline files and 507 public baseline files** matched.
Two new main-app Photo job JSONs appeared independently while the isolated tests
ran; those paths are recorded in the report. Test code installs/runs only the
isolated package. No existing media, recording metadata or failed-photo fixture
was deleted, overwritten or cleared. New JPEG publication uses the unchanged
PhotoStore's normal verified staging-file cleanup.

All recorded global rotation/font/navigation settings matched their initial
values afterwards; no settings write was used. `main` remains at `8cb1aff`.

Production APK: **`app/build/outputs/apk/debug/app-debug.apk`**, package
`com.calvinmazhindu.skitcam`, unchanged version 0.11.2 / code 15. SHA-256:

```text
31c445963a626ac53d3c2b3a2d9569afce91b6d1b05a7b3702cf2ed4d9a9c1ab
```

## Reproduction and retained evidence

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lint --offline
./gradlew -PvalidationSuffix=.m11validation :app:assembleDebug :app:assembleDebugAndroidTest --offline

# On the baseline layout, with the measurement harness installed:
adb -s ZY22KJQLTV shell am instrument -w -r -e physical true \
  -e rotationRun photo-before -e photoBaseline true \
  -e class com.calvinmazhindu.skitcam.camera.CameraRotationDeviceTest#guidedPhotoLandscapeViewfinder \
  com.calvinmazhindu.skitcam.m11validation.test/androidx.test.runner.AndroidJUnitRunner

# On the candidate, retaining that baseline directory:
adb -s ZY22KJQLTV shell am instrument -w -r -e physical true \
  -e rotationRun photo-after -e photoBaselineRun photo-before \
  -e class com.calvinmazhindu.skitcam.camera.CameraRotationDeviceTest#guidedPhotoLandscapeViewfinder \
  com.calvinmazhindu.skitcam.m11validation.test/androidx.test.runner.AndroidJUnitRunner

# Desktop only; requires Pillow, numpy and opencv-python-headless:
python3 -m unittest discover -s tools -p test_verify_photo_viewfinder.py -v
python3 tools/verify_photo_viewfinder.py <baseline-evidence-directory> \
  <candidate-evidence-directory> <output-directory> \
  --supplement <explicit-recapture-directory>
```

The guided case requires a person to move the real phone when prompted. Baseline
and candidate runs require an idle isolated app; they refuse to interrupt an open
recording/export. For a steady sample, use a new `rotationRun` and
`-e photoSampleOnly true -e lens Rear|Front -e photoSamplePose 0|270|90`, optionally
`-e photoSteadySeconds 15`. Old evidence must be retained.

Ignored `.validation/photo-landscape/` holds fresh manifests/settings, baseline
and candidate screenshots/bitmaps/JPEGs, steady recaptures, Video regression media,
raw instrumentation/build logs, desktop reports/comparison sheets, `summary.json`,
`geometry.json`, `final-audit.json` and reduced `screens/`. Desktop dependencies
were installed in a temporary virtual environment, not added to the Android app.

Remaining visual tradeoff: overlays obscure some image rows, and 4:3 pillarboxing
remains necessary. Small windows may become width-limited; the image then fits
inside the available region instead of forcing full height by cropping/stretching.
