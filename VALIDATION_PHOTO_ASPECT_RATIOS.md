# Compact Photo aspect selector and Moto G54 production deployment

Validation: **6 November 2026**, Moto G54 5G **`ZY22KJQLTV`**, Android 15 / API 35,
1080×2400, density 400, font 1.3, three-button navigation. Working branch:
**`deploy/photo-landscape-moto-g54`**. Local `main`, `origin/main` and the live remote
`main` were verified at **`9f88384a60c923e999250af7fe00b860a781c8e6`** before work
and at closing inspection. The working tree was initially clean. No reset,
checkout overwrite, uninstall or application-data clear was used. Deployment and
validation completed before the user requested a commit; nothing was pushed.

The original deployment installed the exact landscape-Photo fix. During that
verification the user requested a visible Photo ratio selector, specified that it
must avoid UI clutter, and chose **Full = available viewfinder area**. The final
installed build therefore includes the **selector implementation on this branch**,
installed from the working tree before committing, in addition to `9f88384`;
that base commit alone does not contain it.

## UI and capture behavior

- The existing Photo header is now one compact **`Photo · 4:3 ▾`** control.
  Tapping it opens a four-item dropdown: **1:1, 4:3, 16:9, Full**. Only the current
  selection remains visible; there is no extra toolbar row or permanent strip.
- The selection is Photo-only and persists across lenses and app recreation.
  Missing/unknown preference values restore **4:3**.
- **Full** fills the available preview rectangle, not the phone's 20:9 screen
  ratio. It keeps the separate shutter dock and safe insets.
- The original 4:3 `ViewPort.FIT` path, highest-resolution/fallback selection,
  maximize-quality capture and JPEG quality 100 are retained.
- Other ratios use a shared centered CameraX ViewPort crop for Preview and
  ImageCapture. `PreviewView.FIT_CENTER` remains in use: selected shapes crop the
  sensor interval intentionally, never stretch the image. Saved JPEG pixel
  dimensions consequently change for non-default shapes.
- Ratio changes are disabled during countdown/capture/save and do not apply in
  Video mode. Camera gestures are disabled while the dropdown is open.
- Preset framing retains its binding through ordinary rotations. Full rebinds
  only for a changed crop interval; natural-display crop coordinates avoid an
  unnecessary rebind between opposite landscape directions.
- Full's portrait area uses structural control heights, excluding temporary
  focus/save/countdown text. Those messages can overlay the frame but cannot
  change its crop. A pending countdown/capture holds its existing crop through a
  rotation and adopts the new Full rectangle only after saving.

Implementation: `photo/PhotoAspectRatio.kt`, `photo/PhotoAspectSelector.kt`,
`CameraModel.kt`, `PhotoModel.kt`, `CameraPreview.kt` and `MainActivity.kt`.
Recording/session/export production code and the sensor orientation policy were
not edited. Version metadata remains **0.11.2 / code 15**; APK hash, rather than
version text, identifies the installed build.

## Production installation and recording guard

Before each replacement, read-only inspection checked session metadata, the
latest session selected by the store's **mtime-based** recovery rule, Photo jobs,
the latest export journal and production services. All **184 recording journals
were Completed**, the latest had no pending segment, and no production service
was running. No active or paused production recording was interrupted. Historical
Completed metadata/journals were retained unchanged.

The production APK was built **without `validationSuffix`**, checked as package
`com.calvinmazhindu.skitcam`, and its signing-certificate SHA-256 matched the
previous installed APK. Installation used **`adb install -r`**. The installed
`base.apk` hash was then independently compared with the built artifact.

| Artifact | SHA-256 |
|---|---|
| Initially installed build, before this task | `9cc0a477273a36b70ce13dc08b4dc087b2c89863ce455e7331d0aa76e88eff12` |
| First deployment, exact `9f88384` layout fix | `31c445963a626ac53d3c2b3a2d9569afce91b6d1b05a7b3702cf2ed4d9a9c1ab` |
| **Final installed build, including requested ratio selector** | **`6611115449c85e7ac5d43efdb43b061cbc8bc9806fcc017e059db79ba52eaa24`** |

Delivery artifact: **`app/build/outputs/apk/debug/app-debug.apk`**. Both installs
preserved application data and the signing identity.

## Actual production PreviewView measurements

Each measurement combines the accessibility image rectangle with the **native
`androidx.camera.view.PreviewView` hierarchy dimensions**, plus the display dump
and active production camera client. Native width/height agreed with the image
rectangle. Camera IDs **0 / 1** confirmed rear/front clients respectively.

Bounds are `(x, y, width, height)` in physical pixels; each row passed on **both
production camera lenses**:

| Mode / pose | Display | PreviewView bounds |
|---|---|---|
| Photo 4:3, portrait | 1080×2400 | **(0, 346, 1080, 1440)** |
| Photo 4:3, landscape top left / rotation 1 | 2400×1080 | **(335, 70, 1347, 1010)** |
| Photo 4:3, landscape top right / rotation 3 | 2400×1080 | **(338, 70, 1347, 1010)** |
| Skit Video, portrait | 1080×2400 | **(0, 237, 1080, 1920)** |
| Skit Video, landscape top left | 2400×1080 | **(117, 74, 1783, 1003)** |
| Skit Video, landscape top right | 2400×1080 | **(120, 74, 1783, 1003)** |

This confirms the requested approximately **1347×1010** Photo utilization on the
installed production app, rather than only in an isolated test build. The Photo
portrait origin differs from the earlier isolated record because of available
toolbar/dock space; the 1080×1440 fitted dimensions are unchanged.

### Production JPEG orientation

Six new default-ratio photos were taken through the production shutter and copied
from their newly created Gallery paths. Their bytes matched the PhotoStore
publication SHA-256; Pillow independently decoded dimensions and applied EXIF.

| Lens | Stored dimensions, all three poses | Portrait EXIF | Top-left landscape EXIF | Top-right landscape EXIF |
|---|---|---:|---:|---:|
| Rear | **4096×3072** | **6** | **1** | **3** |
| Front | **2302×1740** | **8** | **1** | **3** |

Portrait displays tall after EXIF, both landscapes display wide. Outputs remain
unmirrored. Three additional production rear-portrait captures verified the new
options and matching selected shape:

| Selection | PreviewView bounds | Stored JPEG | Displayed JPEG after EXIF |
|---|---|---|---|
| 1:1 | (0, 526, 1080, 1080) | 3072×3072 | 3072×3072 |
| 16:9 | (74, 237, 933, 1658) | 4096×2304 | 2304×4096 |
| Full | (0, 237, 1080, 1658) | 4096×2668 | 2668×4096 |

The production selector was then returned to **4:3**. No production START,
PAUSE, RESUME or STOP command was issued for this verification. Video preview
dimensions were checked on both lenses in all three poses; recording/export was
exercised separately in the isolated app.

## Screenshots

Retained local, Git-ignored evidence is linked as in prior validation records:

| Compact four-item menu | Full, available portrait area |
|---|---|
| ![Production ratio dropdown](.validation/photo-ratios/screens/production-menu.jpg) | ![Production Full Photo](.validation/photo-ratios/screens/production-full.jpg) |

| Production rear Photo, top-left landscape | Production front Photo, top-right landscape |
|---|---|
| ![Rear landscape Photo](.validation/photo-ratios/screens/production-rear-landscape.jpg) | ![Front landscape Photo](.validation/photo-ratios/screens/production-front-landscape.jpg) |

## Tests and retained failures

- **204 JVM tests passed**, zero failures/errors/skips, including eight new
  ratio/default restoration, preset orientation, Full area, invalid geometry,
  crop-key and capture-freezing tests.
- Production `testDebugUnitTest`, `assembleDebug`, `lint --offline` and isolated
  app/test APK assembly passed. Lint: **0 errors / 10 existing warnings**, no new
  suppression or warning.
- The isolated ratio-menu/capture/persistence case passed twice. It captures all
  four ratios on both lenses, validates Preview/ImageCapture crop aspect and
  saved JPEG aspect, rejects disallowed changes, checks the selection after
  Activity recreation and fresh preference loading, and verifies that focus
  feedback cannot rebind or resize Full framing. The selector is absent in Video.
- The physical landscape matrix produced **16 checked JPEGs**: four choices ×
  two lenses × two directions. Every sample passed its native preview/capture
  crop and JPEG-aspect assertions before the combined test's timer stage.
- That initial combined invocation **failed** waiting eight seconds for the
  requested physical turn during the countdown. Its log and successful samples
  are retained; the whole invocation is not counted as passed.
- The timer step was rerun separately with a readiness prompt and either
  landscape direction accepted. It **passed**: the ImageCapture identity and
  natural crop remained stable through rotation, the ratio control was disabled,
  and the JPEG saved successfully. Its 4096×2668 / EXIF 1 output matched the frozen
  portrait Full interval viewed in landscape (aspect approximately 1.5352).
- Existing camera lifecycle/zoom recreation and STOP → two-segment verified
  export → Gallery → Play → Share → camera return cases passed in the isolated
  app. The selected final device logs contain **5 passing invocations / 4 distinct
  methods**, with no assumption skips, in addition to the retained failed attempt.
- **33 isolated JPEG copies** are retained, including successful landscape samples
  from that initial invocation and the final timer proof. These are crop/aspect
  checks, not a fresh calibrated optical or whole-frame pixel-correlation study.

The production collection script initially stopped a metadata archive at an
unmatched optional `.bak` pattern. It consequently failed to observe a newly
saved Photo, even though capture succeeded. The collector now includes existing
paths only and checks the archive exit status. Full original-file hash manifests
were unaffected. The original archive did not contain a full preference backup;
the final camera-choice comparison uses the subset explicitly observed in the
read-only predeployment inspection (Photo/rear, Video/rear, grid on), with its
provenance retained. Extra captures from early interaction/retries were kept.

Not repeated: the full portable-project ZIP/picker suite, 15-render timing
investigation, broad large-text/navigation-mode matrix, other phones or Android
16 large-screen modes. Pixel quality, calibrated optical FOV and TalkBack
usability are not claimed. Full intentionally crops to its chosen rectangle;
default 4:3 capture dimensions remain as above.

## Preservation and final state

Fresh SHA-256 baselines were taken before the first production replacement and
again before the selector replacement. At closing audit:

| Scope | Before first install | Final | Changed/missing original files |
|---|---:|---:|---:|
| Production session/photo-job files | **873** | **884** | **0** |
| Existing public SkitCam Gallery files | **528** | **574** | **0** |

The later pre-selector baselines (**874 private / 564 public**) also matched
byte-for-byte with zero missing/changed files. Added Gallery entries are retained
verification photos/isolated output and intervening new captures. None was
deleted to make the counts match. All **184 production recording journals**
remained byte-for-byte unchanged; no production recording was created.

An additional fresh isolated-validation audit matched every baseline file in the
M11, M9, M10 and framing scopes. Its production 874→884 and public 529→574 counts
use the slightly later baseline taken when ratio implementation began; all
changed/missing lists were empty.

The app was left upright in **rear Photo, 4:3**, with **Video's rear lens preference
and grid enabled** preserved. Auto-rotate remained **ON**, `user_rotation=0`,
`wm user-rotation=free`, font **1.3**, navigation mode **0**: no global setting was
changed. The new ratio preference is now explicitly 4:3, and recent-media
information naturally points to the newly saved verification photos.

Audits cover Android user-0 production/accessible isolated data and the shared
SkitCam Gallery folders, not other Android user profiles.

## Evidence and reproduction

`.validation/production-photo-install/evidence/` contains original and later hash
manifests, metadata archives, certificate/package checks, original APK backup,
installed APK hashes, native view/window/camera-client evidence, screenshots,
nine explicitly decoded production JPEGs, and `audit.json` / settings snapshots.
`.validation/photo-ratios/` holds build/test logs, `summary.json`, isolated
screenshots/bitmaps/JPEGs, the failed timer attempt and passing retry, final audit
manifests and reduced screenshots.

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lint --offline
./gradlew -PvalidationSuffix=.m11validation :app:assembleDebug :app:assembleDebugAndroidTest --offline
# Install app/test APKs only in the existing isolated package for instrumentation.
adb -s ZY22KJQLTV shell am instrument -w -r \
  -e class com.calvinmazhindu.skitcam.camera.CameraRotationDeviceTest#photoRatioMenuCaptureAndPersistence \
  -e rotationRun <unique-evidence-folder> \
  com.calvinmazhindu.skitcam.m11validation.test/androidx.test.runner.AndroidJUnitRunner
```

`guidedPhotoRatiosAndTimerRotation` requires `-e physical true`, real guided phone
movements and a unique evidence folder; `-e timerOnly true` runs its timed stage
alone. Production replacement requires a fresh idle/session/save/service check,
matching signatures and production-package verification before `adb install -r`.
Do not run isolated recording fixtures against production or alter global
rotation to manufacture measurement results.
