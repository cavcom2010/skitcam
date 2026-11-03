# Video framing correction — SkitCam 0.10.1

Branch `fix/video-framing`, based on committed Milestone 10 (`8e212b6`).
Milestone 11 remains separate. No commit/push, uninstall, data clear or original-media
cleanup was performed. Validation: 3 November 2026, Moto G54 5G `ZY22KJQLTV`, API 35.

## Confirmed cause

This was not increased digital zoom in the measured cases. Both lenses reported
CameraX zoomRatio **1.0**, linearZoom **0**, Camera2 result zoomRatio **1.0**, and a
full active-array SCALER_CROP_REGION. Rear camera ID was **0**, front **1**. Rear
focal length was **4.266 mm**, front **3.27 mm**. Electronic stabilisation was Off
in the default baseline. Rear optical stabilisation remained device-managed/On.

Two independent display/configuration problems combined:

1. Video used `FILL_CENTER` inside the entire display, while Photo used `FIT_CENTER`
   inside a fitted 4:3 viewfinder. The phone's 1080×2400 portrait screen has a
   different aspect from the recording. Filling it necessarily hides image edges.
2. Preview had no aspect selector: it negotiated a near-4:3 stream while Recorder
   selected 1920×1080 (16:9). Merely changing to FIT showed the full *wrong-aspect*
   preview, including a different scene interval from the saved video.

Measured idle geometry (raw stream dimensions precede display rotation):

| Lens/layout | Before preview stream | Before view bounds | After preview stream | After view bounds | Capture, before/after |
|---|---|---|---|---|---|
| Rear portrait | 1600×1200 | 1080×2400 | 1920×1080 | 933×1658 | 1920×1080 |
| Front portrait | 1440×1088 | 1080×2400 | 1920×1080 | 933×1658 | 1920×1080 |
| Rear landscape | 1600×1200 | 2400×1080 | 1920×1080 | 1333×750 | 1920×1080 |
| Front landscape | 1440×1088 | 2400×1080 | 1920×1080 | 1333×750 | 1920×1080 |

Bounds vary with toolbar/control content, insets and font scale. During the sampled
portrait recording the fitted view was 881×1567; it retained the same aspect.
Changing available UI space does not rebind CameraX or change the recording.

For the rear portrait baseline, rotating 1600×1200 gives a 1200×1600 image. Filling
1080×2400 scales it to 1800×2400 and displays only 60% of its width. A matching
16:9 recording uses approximately 75% of the 4:3 sensor's corresponding dimension.
Thus the old display hid approximately another 20% of the recorded width, about
1.25× apparent magnification relative to the saved frame. These are aspect/crop
calculations from measured sizes, not calibrated optical-angle measurements.

The original baseline source and finished MP4 contain scene edges absent from the
full-screen preview. The defect therefore exists before export; the exporter does
not introduce the preview crop. No capture/export algorithm was changed.

## Small correction

- `CameraPreview.kt` obtains the CameraX-supported video quality's dimensions using
  `QualitySelector.getResolution`. Its Preview ResolutionSelector requests the
  corresponding 4:3 or 16:9 aspect. FPS capability queries still include this exact
  Preview/VideoCapture combination. Recorder settings and profile checks are intact.
- Both modes use `FIT_CENTER`. Video fits a centered frame derived from **actual
  bound capture dimensions and rotation**, within the measured toolbar/control
  space. No phone-shaped ViewPort, stretching, codec/FPS/quality changes or capture
  crop was added. Photo's ImageCapture, 4:3 ViewPort, JPEG and mirroring policies
  are unchanged.
- `ViewfinderGeometry` centralises finite, positive, aspect-preserving sizing and
  handles rotated/non-16:9 sources. Focus/grid/pinch coordinates remain local to
  the visible fitted image, not its outer dark bars.
- `CameraModel` explicitly applies 1.0× on startup and mode/lens changes, observes
  real ZoomState, and gates readiness until the binding's zoom request succeeds.
  Deliberate zoom survives same-choice Activity recreation/rebinding and ordinary
  PAUSE/RESUME. Zoom is not added to preferences or immutable session metadata.
  Generation/request tokens prevent superseded callbacks from restoring old UI.
  Observers are removed on release/clear; zoom failure exposes an error/retry.
- `FramingDiagnostics` keeps only current passive stream/zoom/crop/stabilisation
  facts. It owns no frames, file history or Camera2 control requests. Its callback
  delegates existing video monitoring and rejects observations from old bindings.
- Version 0.10.1 / code 12. Validation builds have a generic label and hidden
  launcher. Existing test guards allow the separate `.framingvalidation` app.

Official API references consulted:
[PreviewView scale types](https://developer.android.com/reference/androidx/camera/view/PreviewView.ScaleType),
[preview scaling](https://developer.android.com/media/camera/camerax/preview),
[QualitySelector](https://developer.android.com/reference/androidx/camera/video/QualitySelector),
[AspectRatioStrategy](https://developer.android.com/reference/androidx/camera/core/resolutionselector/AspectRatioStrategy).
CameraX 1.5.3 APIs were also checked in the installed library. No dependency update.

## Preview versus actual MP4

Dedicated recordings were created in `.framingvalidation`, not production sessions.
The first segment recorded at least five seconds; screenshot capture extended the
actual source durations to roughly 7.6–8.7 seconds in the corrected runs. A second
segment exercised PAUSE/RESUME before STOP. Default tests covered both lenses and
both layouts; separate portrait runs enabled existing supported EIS on each lens.

FFmpeg independently decoded **20 MP4 copies**: source and finished output for four
baseline and six corrected cases. ffprobe found AVC video plus AAC audio; FHD
capture/output stayed 1920×1080 with portrait ±90° display matrices or landscape
0°. Auto cadence remained variable (not a fixed-FPS claim). Corrected finished
track duration differences were about **11–31 ms** by ffprobe's container-duration
interpretation; production verification independently accepted each output with
its unchanged **100 ms** rules. Both source and export had nonzero decoded audio.
These observations are not a perceptual speech/lip-sync assessment.

Decoded frames at two seconds were compared with actual PreviewView bitmaps while
recording. The six comparison sheets were visually inspected for matching room,
door, ceiling and foreground edges. Front preview is mirrored; source/export are
unmirrored, as before. After mirroring the front preview for analysis, full-image
luminance correlation on equally sized decoded/preview images was:

| Corrected case | Preview versus source correlation |
|---|---:|
| Rear portrait, EIS Off | 0.999401 |
| Rear landscape, EIS Off | 0.999147 |
| Front portrait, EIS Off | 0.999740 |
| Front landscape, EIS Off | 0.999730 |
| Rear portrait, EIS On | 0.998335 |
| Front portrait, EIS On | 0.997413 |

This supports closely matching framing for these static scenes, not universal
pixel identity, frame-accurate timing or an optical field-of-view calibration.
EIS On was confirmed in Camera2 results; hardware stabilisation may still crop or
shift other scenes. No stabilisation preference was silently disabled.

## Automated validation

**68 distinct post-fix Android cases passed** in the selected final batches, with
no skips in those final selections. Repeated runs, baseline cases, earlier failed
attempts and the two named test-checkpoint completion utilities are excluded.

| Device cases | Count |
|---|---:|
| New framing/zoom/video gestures, including both EIS-On lenses | 8 |
| Photo/modes/permissions/focus/zoom/gallery return | 11 |
| M10 save return, UI, background publication and process restart | 8 |
| Gallery browsing | 1 |
| Recording lifecycle | 6 |
| PAUSE/review/Keep/Discard | 4 |
| Arrangement | 4 |
| Trim, including saved trim across process death | 4 |
| Audio | 1 |
| Video profiles, including FHD/60 and 15-second landscape | 7 |
| Export journal/recovery | 5 |
| Leading-audio-gap consumer | 4 |
| Timing fixtures: five inputs × three renders | 1 |
| STOP → export → camera → gallery → Play/Share | 1 |
| Finished-player prepare/play/release/restart | 1 |
| Frozen-profile recovery across separate processes | 2 |
| **Total** | **68** |

All **15 production timing renders passed** within their single device test.
Explicit isolated-app process termination tested saved-publication recovery and
paused FHD/60 profile restoration before RESUME. A separate saved-trim checkpoint
survived force-stop/restart: segment metadata, playback order, capture profile and
source hashes were unchanged, and its 300 ms trim start reached the completed
export specification. The finished content URI then prepared/played/released in
Media3. New sessions recorded after successful automatic camera return.

These are automated interactions using real CameraX/microphone/MediaStore on the
Motorola, not manual in-hand usability, TalkBack or perceptual lip-sync testing.

JVM: **162 passed**, zero failures/errors/skips (153 existing plus nine new cases).
New tests cover rotated video/SD geometry, portrait/landscape fitting, invalid
geometry, profile aspect selection, mode/lens zoom resets and same-choice zoom
continuity. The new Android tests cover real framing, recording/export, zoom
recreation/rapid requests, video touch focus/pinch and letterbox taps.

Build/lint: production `assembleDebug` and `lint` passed; **0 errors, 9 existing
warnings**. Warning IDs remain AndroidGradlePluginVersion, NewerVersionAvailable,
five UsableSpace occurrences and two UseKtx occurrences. No new warning or
suppression was introduced. Isolated APK and Android-test assembly also passed. Isolated lint has 0 errors /
10 warnings: the same nine plus the already-known validation-only UnusedResources
warning for app_name, since the hidden app uses a literal validation label.

### Investigation/test errors and resolutions

- Initial diagnostic compilation used ResolutionInfo on base UseCase; it is
  exposed by concrete Preview/ImageCapture/VideoCapture classes. Corrected without
  a library change. A nullable export-journal test read was made explicit.
- Initial baseline: two portrait cases passed; two landscape cases failed because
  the new harness retained the old Activity around rotation. Refreshing its
  Activity reference and awaiting streaming fixed the harness. Both landscape
  baseline reruns passed; initial logs/media remain retained.
- First camera regression batch: immediate `find("Settings")` after an accessibility
  click raced Compose rendering. The test now waits for the Settings/Close nodes,
  as the other overlay tests do. Its failure left its owned test session PAUSED;
  five later cases correctly refused to interrupt it. A named, isolated checkpoint
  was completed through normal STOP/export without deleting files; the six UX
  cases then passed. No production session was used to repair test state.
- The standalone trim restart fixture initially timed out waiting for video after
  the recovery tests correctly restored the saved Photo preference. The recovered
  session and export were Completed; no camera binding error was reported. The
  fixture was prepared through an explicit Photo → Video transition, rather than
  changing production preference/recovery behaviour. Initial/retry logs remain.
- The first fixture-upload command had shell quoting that prevented extraction;
  explicit `tar -C files` succeeded. Original timing fixtures were not modified.

## Data preservation

The validated baseline contains **1,265 existing session files**: production 768,
M9 validation 229, M10 validation 268; plus **272 existing SkitCam gallery files**.
The initial total of 1,267 incorrectly counted two `run-as` error lines as files.
Old M8/M7.1 control apps were already inaccessible at baseline; the user confirmed
removing old test apps. Those error entries are excluded, not reported as hashes.
No uninstall/data-clear command was issued in this work.

The final post-install SHA-256 audit matched **all 1,265 baseline session files
and all 272 baseline gallery files**, with zero missing/changed files. Production
still has exactly **768 session files**; M9 **229** and M10 **268**, unchanged. The
public SkitCam folders now contain **324 files**: the baseline 272 plus 52 new test
photo/video publications. No production recording/session was created for testing.
The patch APK was installed over the main app and launched successfully; its
ordinary saved Photo mode was restored, and its real camera client/viewfinder was
observed. Video was then selected explicitly for an additional live-preview smoke
check, without recording or changing a capture profile. Its actual visible preview
bounds were **933×1658**, the label **1080p · Auto**, and START was enabled. The
original Photo preference was restored after the check. Session schema, source chronology,
Keep/Discard, order, trims, profile/orientation rules, MP4 publication and A/V
verification code remain unchanged.

## Commands and evidence

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lint --offline
./gradlew -PvalidationSuffix=.framingvalidation :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lint --offline
adb -s ZY22KJQLTV shell am instrument -w -r -e class <selected-class-or-methods> com.calvinmazhindu.skitcam.framingvalidation.test/androidx.test.runner.AndroidJUnitRunner
python3 .validation/framing/audit.py final
```

Ignored `.validation/framing/` contains before/after JSON, original screenshots,
PreviewView bitmaps, copied MP4s, comparison sheets, ffprobe/decode/audio results,
correlation measurements, build/test logs and SHA-256 audit manifests. No original
failure fixture was modified or deleted.

## Remaining differences and limitations

- Photo remains 4:3; 16:9 video normally crops a different sensor interval. They
  are not expected to have identical framing. This fix removes extra screen-fill
  cropping, not normal sensor/aspect or stabilisation differences.
- The front selfie preview remains mirrored and the recording unmirrored. This
  affects handedness, not magnification.
- Portrait/landscape layouts were tested on real hardware using a temporarily
  overridden display rotation and known pose arguments, restored after tests.
  The phone remained facing real room scenes; these tests do not establish manual
  in-hand rotation/sensor gestures or calibrated lens angles. User confirmation of
  preferred framing in ordinary handheld filming remains useful.
- Other camera hardware and unsupported preview-aspect fallbacks are unverified.
  Actual CameraX capabilities/stream choices can differ. EIS/OIS and 4:3 versus
  16:9 sensor readout may change the genuine recorded field of view.
- No TalkBack/large-font manual audit or motion-stabilisation quality assessment
  was performed for this focused fix. No portable-project-export code was added.
