# Camera screen rotation — SkitCam 0.11.2

Branch **`fix/camera-auto-rotation`**, based on the 0.11.1 commit **`d83ebd2`**.
Provisional release **0.11.2 / versionCode 15**.
Validation: **5 November 2026**, Moto G54 5G **`ZY22KJQLTV`**, Android 15 / API 35,
1080×2400, density 400, three-button navigation, font scale 1.3.
No commit or push.

The camera screen now follows physical device orientation in Skit Video and Photo
even when Android **Auto-rotate is off**: portrait, landscape with the top edge
left, and landscape with the top edge right. An active recording keeps the
orientation it started in. Landscape video also uses the full safe height behind
the gradient-backed toolbar and mode buttons, so the fitted image is no longer
confined to a short central band.

## Confirmed cause

Two separate causes were reproduced before editing:

1. **Idle orientation had no owner.** `RecordingControls.kt` requested
   `SCREEN_ORIENTATION_UNSPECIFIED` whenever no session was active. The
   orientation policy lived only inside the video controls, so Photo and the
   finishing/return path requested nothing. Rotating the phone therefore
   recreated the Activity, rebound the camera and discarded focus/controls.
2. **Landscape video reserved fixed bands.** `MainActivity.kt` padded the preview
   by the measured toolbar height and the bottom mode-row height in landscape.
   With the side dock, the 16:9 frame fitted into **1333×750 px** inside a
   **2400×1080 px** window — a visibly small band with unused space above and
   below. This was a layout constraint, not cropping or extra zoom.

`FIT_CENTER`, the 1.0× default zoom, full active-array crop regions and the
0.10.1/0.11.1 framing corrections were already correct and are retained.

## Implementation

- `AndroidManifest.xml` — `MainActivity` declares
  `android:screenOrientation="fullSensor"` and
  `android:configChanges="orientation|screenSize"`. Android updates the window
  and Compose configuration in place; the Activity and camera binding are not
  torn down.
- `CameraOrientationPolicy.kt` — pure decision table. Idle, recovered and
  completed states request `FULL_SENSOR`. `Starting`, `Recording`, `Finalising`,
  `Paused`, `Stopping` and `Error` keep the session's orientation. A finishing
  export holds the last pose. It also exposes `matchesDisplay` and
  `cameraMayBind`.
- `CameraOrientationController.kt` — the single Activity-owned pose/display
  observer. It records the filtered sensor pose and display rotation, applies the
  policy, and gates camera binding. A **cold landscape recovery** waits until
  Android applies the restored lock before a portrait Preview can bind.
- `MainActivity.kt` — owns the controller; updates it from the recording
  snapshot, refreshes on configuration change, validates the pose before START
  and passes the live display rotation to Photo capture.
- `RecordingControls.kt` — no longer sets manifest-level orientation itself.
  START is enabled only when the filtered pose and the camera preview rotation
  match the display. A mismatch shows the existing orientation guidance instead
  of starting a mixed-orientation recording.
- `CameraPreview.kt` / `CameraModel.kt` — while idle, rotation updates the
  existing binding (`preview`, `imageCapture`, `videoCapture`
  `targetRotation`). Before a session starts the binding is recreated once, so
  Recording/Pause/Stop and finishing keep one stable camera and capture profile.
  `CameraModel.rotated()` preserves zoom, exposure and focus hold.
- `PhotoModel.kt` — `take(seconds, rotation: () -> Int)` writes the display
  rotation at capture time, so JPEG EXIF follows the same policy.
- **Landscape layout** — landscape video now uses the full safe height. The
  toolbar and bottom mode row are gradient-backed overlays; both consume pointer
  events in the final pass so their empty gaps cannot focus or pinch the camera
  underneath. The side dock width adds the landscape safe-area end inset, fixing
  truncated RESUME/STOP targets. Photo keeps its previous padding, 4:3 geometry
  and input behaviour.

No CameraX binding/profile, resolution selector, Recorder, codec, FPS,
stabilisation, zoom default, session schema, MP4 assembly, Gallery or portable
project-export production code was changed. No new dependency or permission.

### Measured geometry

Raw on-screen PreviewView bounds, `(x, y, width, height)` in pixels:

| Case | Before viewport fix | After |
|---|---|---|
| Rear landscape idle | (284, 190, 1333, 750) | **(117, 74, 1783, 1003)** |
| Rear landscape recording | (284, 190, 1333, 750) | **(117, 74, 1783, 1003)** |
| Rear landscape paused | (284, 190, 1333, 750) | **(117, 74, 1783, 1003)** |
| Front landscape idle/recording/paused | (285, 190, 1333, 750) | **(117, 74, 1783, 1003)** |
| Either landscape direction | — | **(120, 74, 1783, 1003)** |
| Portrait idle/recording | (0, 237, 1080, 1920) | (0, 237, 1080, 1920) |
| Landscape Photo | (450, 190, 1000, 750) | (450, 190, 1000, 750) |
| Portrait Photo | (0, 240, 1080, 1440) | (0, 240, 1080, 1440) |

The landscape image now fills **92.9%** of the window height instead of **69.4%**;
the fitted area grows from 999,750 to 1,788,349 px (about **+79%**). Both lenses
and both landscape directions measured the same size at 1.0× zoom. The portrait
video image is unchanged at 1080×1920. A live dump of the installed main app in
landscape independently returned **`[117,74][1900,1077]`**.

The expanded-layout run was made with Auto-rotate as found (on). The earlier
rotation runs separately cover Auto-rotate **off** (rear and front video, both
Photo lenses) and **on** (rear and front video, both Photo lenses).

## Screenshots

These links reference retained **local, Git-ignored validation evidence**, as in
the project's prior validation records.

| Landscape rear recording | Landscape front recording |
|---|---|
| ![Expanded rear landscape frame](.validation/rotation/screens/landscape-expanded-rear.jpg) | ![Expanded front landscape frame](.validation/rotation/screens/landscape-expanded-front.jpg) |

| Landscape video, installed main app | Returned portrait |
|---|---|
| ![Main-app landscape frame](.validation/rotation/screens/landscape-expanded-main.jpg) | ![Portrait restored after STOP](.validation/rotation/screens/landscape-expanded-portrait.jpg) |

| Paused mismatch (recording stays landscape) | Paused after Activity recreation |
|---|---|
| ![Paused landscape mismatch](.validation/rotation/screens/paused-mismatch.jpg) | ![Paused landscape after recreation](.validation/rotation/screens/paused-recreated.jpg) |

The toolbar and bottom mode row overlay the image with gradients; the complete
fitted frame remains visible between them. No stretching or crop was introduced.

## Preview versus saved MP4

Source segments and finished videos were independently decoded with FFmpeg.
Whole-frame luminance correlation after equal resizing:

| Run | Auto-rotate | Lens | Pose | Source | Finished |
|---|---|---:|---:|---:|---:|
| `physical-off-rear-final` | off | rear | 270 | 0.999825 | 0.999783 |
| `physical-off-front` | off | front | 90 | 0.998584 | 0.998565 |
| `physical-on-rear` | on | rear | 270 | 0.999728 | 0.999712 |
| `physical-on-front` | on | front | 90 | 0.998369 | 0.998384 |

All saved landscape videos are **1920×1080**, H.264 High / AAC 48 kHz stereo,
with a display rotation whose `% 180 == 0`. Front files retain the existing
180° display matrix; preview images were unmirrored only for analysis. These are
framing checks, not synchronised pixel-identity claims.

Photo storage is unchanged: rear **4096×3072**, front **2302×1740**. EXIF
orientation is **1** for pose 270 and **3** for pose 90, for both lenses and both
Auto-rotate settings.

## Tests and builds

**194 JVM tests passed**, zero failures/errors/skips. That includes
`CameraOrientationPolicyTest` (policy, display matching, START gating and cold
recovery binding) and the new landscape-viewfinder threshold case in
`ViewfinderGeometryTest`.

**30 passed Android invocations (27 distinct methods)** on the Moto. The new
`guidedExpandedLandscapeFraming` case completed in **123.9 s**; it held portrait,
recorded ~5 s, paused, resumed, stopped/exported, turned to the other landscape
direction and returned to portrait for both lenses. Every landscape shot measured
1783×1003; portrait shots measured 1080×1920.

Selected coverage:

| Area | Included |
|---|---|
| Rotation policy, preview/capture rotation, real pose changes | Both lenses, both directions, Auto-rotate off/on |
| Idle START, mismatch protection, PAUSE/RESUME/STOP | Passed |
| Activity recreation while paused; process checkpoint/recovery | Passed, no extra segments |
| Cold landscape recovery from a portrait window | Passed |
| Photo both lenses, both directions, both settings | Passed |
| Large text (Compose 2.0×), safe insets and control targets | Passed |
| Audio/Gallery/export regressions, portable projects, finished player | Passed |
| Production timing fixtures | 15/15 accepted, 100 ms limit unchanged |

Production `testDebugUnitTest`, `assembleDebug` and `lint --offline` passed.
Lint: **0 errors / 10 warnings**. Nine are the pre-existing warnings; the tenth
is `DiscouragedApi` for the fixed `fullSensor` orientation (see limitations).

### Harness corrections and retained failures

- The first guided expanded-layout run timed out waiting for one specific
  landscape pose after the phone was turned the other way. The test now accepts
  **either** landscape direction, records which one, and waits for the other
  direction on the reverse turn. The retry passed; the failed log is retained.
- Audio, Export and Project device tests now read `wm user-rotation` and assert
  it is unchanged instead of writing it.
- The large-text case uses a Compose `LocalDensity` 2.0× override on
  `CameraScreen()`; system font scale was **1.3** throughout.
- One early timing run hit a transient Media3 `offset`-fixture codec exception.
  The clean rerun accepted all 15 renders; no production threshold was relaxed.

## Preservation and delivery

Fresh before/after SHA-256 manifests cover every existing session and public
media scope. Final audit: **zero missing and zero changed**.

| Scope | Before | After |
|---|---:|---:|
| Main app sessions | 819 | 823 |
| Public Gallery files | 472 | 495 |
| M11 validation sessions | 606 | 659 |
| M9 validation | 229 | 229 |
| M10 validation | 268 | 268 |
| Framing validation | 238 | 238 |

The added files are the new validation recordings; no original file was modified
or removed. No uninstall or data clear was performed. Afterwards the phone was
restored to its recorded baseline and verified: Auto-rotate **off**,
`user_rotation` **0**, `wm user-rotation` **lock 0**, font scale **1.3** and
three-button navigation **0**.

The production APK was installed in place over the main app and cold-launched as
**0.11.2 / versionCode 15**. The activity reported
`mCurrentAppOrientation=SCREEN_ORIENTATION_FULL_SENSOR`, the camera service listed
the production package as the active rear client, and the deployment audit again
matched **823 session files and 495 Gallery files** with no changes. The user
confirmed both the original rotation fix ("Video rotates correctly now") and the
expanded landscape frame ("Yes, it fills the height now").

Delivery APK: `app/build/outputs/apk/debug/app-debug.apk`, production package
`com.calvinmazhindu.skitcam`, 0.11.2 / 15. The SHA-256 of the installed artifact
is:

```text
9cc0a477273a36b70ce13dc08b4dc087b2c89863ce455e7331d0aa76e88eff12
```

Deployment evidence is under `.validation/rotation/deployment/` (before/after
hashes, `audit.json`, package/version dump, camera/window dumps, screenshot).

## Reproduction and evidence

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lint --offline
./gradlew -PvalidationSuffix=.m11validation :app:assembleDebug :app:assembleDebugAndroidTest --offline
adb -s ZY22KJQLTV shell am instrument -w -r -e physical true -e rotationRun <folder> \
  -e class com.calvinmazhindu.skitcam.camera.CameraRotationDeviceTest#<method> \
  com.calvinmazhindu.skitcam.m11validation.test/androidx.test.runner.AndroidJUnitRunner
```

The guided method requires a person to move the phone when the toasts ask. Do not
run recording fixtures against production. Ignored `.validation/rotation/`
contains per-run screenshots, PreviewView bitmaps, JSON diagnostics, copied
MP4/JPEGs, ffprobe/analysis output, build/instrumentation logs (including the
failed first attempt), `summary.json`, `geometry.json`, `media-analysis.json`,
audit manifests and deployment files.

## Remaining limitations

- **Android 16 orientation policy.** The app targets SDK 36, and Android 16
  ignores fixed screen-orientation restrictions for apps on large screens in most
  cases. Phones such as this Moto still honour `fullSensor`; behaviour under
  Android 16 large-screen/windowed modes is unverified. Lint reports
  `DiscouragedApi` for this reason.
- **Overlays cover part of the image.** Landscape video deliberately extends
  behind the gradient toolbar and mode row. The complete 16:9 frame is fitted,
  not cropped, but those controls obscure parts of it. This is the tradeoff for
  the larger viewport.
- **Large text is Compose-scoped.** The 2.0× case overrides composition density;
  it does not change the system font setting. Actual system 2.0× text on this
  device is still an unmeasured configuration.
- **Sampled states only.** Measurements cover idle, recording, paused, cold
  recovery, Activity recreation and returned portrait on one device. Split-screen,
  foldables, unusual cutouts, other OEM sensors and in-hand rotation smoothness
  are not covered.
- **Front preview remains mirrored**, unchanged from earlier milestones.
- Timing fixtures verify the existing 100 ms audio/video limit; they are not a
  general codec or thermal stress test.

## Changed files

- `app/src/main/AndroidManifest.xml`: `fullSensor` and orientation `configChanges`.
- `app/src/main/java/com/calvinmazhindu/skitcam/recording/CameraOrientationPolicy.kt`
  (new): pure policy and display/binding gates.
- `app/src/main/java/com/calvinmazhindu/skitcam/recording/CameraOrientationController.kt`
  (new): Activity-owned pose/display observer.
- `MainActivity.kt`: controller wiring, START validation, Photo rotation,
  landscape expanded layout and dock inset.
- `RecordingControls.kt`, `CameraPreview.kt`, `CameraModel.kt`,
  `PhotoModel.kt`, `OrientationTracker.kt`: in-place rotation, policy use and
  Photo rotation plumbing.
- `app/src/test/java/.../recording/CameraOrientationPolicyTest.kt` (new) and
  `camera/ViewfinderGeometryTest.kt`: policy and landscape-height tests.
- `app/src/androidTest/java/.../camera/CameraRotationDeviceTest.kt` (new) and
  audio/export/project harness rotation-safety edits.
- `app/build.gradle.kts`: 0.11.2 / 15.
- README and this validation record.
