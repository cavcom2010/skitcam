# Photo aspect ratios, Full screen, and Moto G54 production deployment

Validation: **6 November 2026**, Moto G54 5G **`ZY22KJQLTV`**, Android 15 / API 35,
1080×2400, density 400, font 1.3, three-button navigation.
Working branch: **`deploy/photo-landscape-moto-g54`**, based on `9f88384`
(`f90856c` contains the earlier selector version). This corrected work is
**uncommitted**; nothing was pushed.

The stock **Motorola Camera** was used as the reference. In portrait it offers
**1:1, 3:4, 9:16, Full**; rotating the phone flips the presets to **1:1, 4:3,
16:9, Full**. Its 9:16 preview is **full width** (1080×1920) beneath the top bar,
with the bottom controls over the image. Its **Full** preview fills the entire
screen. Our app now follows the same model.

## Final behavior

| Phone orientation | Photo choices | Preview geometry |
|---|---|---|
| Portrait | **1:1 · 3:4 · 9:16 · Full** | 1:1 = 1080×1080; 3:4 = 1080×1440; 9:16 = **1080×1920 full width**; Full = **1080×2400** |
| Landscape | **1:1 · 4:3 · 16:9 · Full** | 1:1 = 1010×1010; 4:3 = 1347×1010; 16:9 = 1783×1003; Full = **2400×1080** |
| Skit Video, portrait | — | 1080×1920 (9:16) |
| Skit Video, landscape | — | 1783×1003 (16:9) |

- The selected ratio **flips automatically with orientation**: 3:4 ⇄ 4:3 and
  9:16 ⇄ 16:9. 1:1 is unchanged; Full always fills the current screen.
- **Full is not a named ratio and has no fixed number.** It is
  `PhotoFrame(availableWidth, availableHeight)` from the **runtime window**
  (`LocalWindowInfo.containerSize`). On this phone that is 1080×2400 portrait /
  2400×1080 landscape; another phone uses its own dimensions. The earlier
  reduced 9:20/20:9 form was only a mathematical simplification of this phone's
  window; it has been removed from the code and tests.
- **9:16 portrait uses the full width** below the header and extends behind the
  bottom dock, matching the stock camera. It is not fit by available height.
- **3:4 portrait** remains 1080×1440 (full width); **1:1** is 1080×1080.
- Full previews sit **behind** the gradient header, bottom mode/shutter controls
  and right-side landscape dock. Those overlays keep their safe insets,
  gradients and final-pass pointer blocking, so their gaps cannot focus or
  pinch the image.
- Default 4:3/3:4 still uses the original maximum-quality `ViewPort.FIT` path.
  Other choices use a shared centered CameraX crop for Preview and ImageCapture.
  `PreviewView.FIT_CENTER` remains: shapes crop the sensor interval; nothing is
  stretched.

## Why the first versions looked wrong

1. **9:16 was fit by height.** Portrait 16:9 was implemented as 9:16 but fitted
   to the available 1658 px height, producing 933×1658 with black side bars.
   The stock camera uses the full 1080 px width and 1920 px height. Corrected.
2. **Full used the available viewfinder**, not the screen (1080×1658). It now
   fills the whole runtime window.
3. **CameraX's internal TextureView overflows PreviewView.** For 1:1/16:9 the
   new view reported the correct bounds, but the old live 4:3 image remained
   visible outside them. Bitmap-vs-screen comparison confirmed the new
   PreviewView itself was correct; the oversize TextureView layer was not
   clipped. `Modifier.clipToBounds()` around the preview frame fixes it.
   `photoAspectPreviewClipRegression` now samples pixels outside the frame and
   fails if the camera image leaks there.
4. An interim “16:9 always wide” portrait implementation (1080×608) was rejected;
   portrait 9:16 is full width 1080×1920.

## Measured production results

Installed final build SHA-256:
**`0a8b310db51ffa5b90f9693806bb3574ce29f90ab445874731ddd71fb3b7dbcf`**
(package `com.calvinmazhindu.skitcam`, 0.11.2 / code 15).

PreviewView bounds `(x,y,width,height)`; both lenses agreed and the native
`PreviewView` size matched the accessibility rectangle:

| Ratio, portrait | Bounds | Rear JPEG | Front JPEG |
|---|---|---|---|
| 3:4 | (0, 346, 1080, 1440) | 4096×3072, EXIF 6 | 2302×1740, EXIF 8 |
| 1:1 | (0, 526, 1080, 1080) | 3072×3072, EXIF 6 | 1740×1740, EXIF 8 |
| 9:16 | **(0, 359, 1080, 1920)** | 4096×2304, EXIF 6 | 2302×1296, EXIF 8 |
| **Full** | **(0, 0, 1080, 2400)** | 4096×1844, EXIF 6 | 2302×1036, EXIF 8 |

| Ratio, landscape top-left | Bounds | Rear JPEG | Front JPEG |
|---|---|---|---|
| 4:3 | (335, 70, 1347, 1010) | 4096×3072, EXIF 1 | 2302×1740, EXIF 1 |
| 1:1 | (504, 70, 1010, 1010) | 3072×3072, EXIF 1 | 1740×1740, EXIF 1 |
| 16:9 | (117, 74, 1783, 1003) | 4096×2304, EXIF 1 | 2302×1296, EXIF 1 |
| **Full** | **(0, 0, 2400, 1080)** | 4096×1844, EXIF 1 | 2302×1036, EXIF 1 |

After EXIF orientation the 3:4/9:16/Full portrait JPEGs display tall, and all
landscape JPEGs display wide. Full’s saved dimensions are the **runtime screen
aspect** crop (on this phone 4096×1844 rear, 2302×1036 front); they are not a
fixed 9:20 constant. Video stayed 1080×1920 portrait and 1783×1003 landscape on
both lenses.

## Evidence and tests

- **205 JVM tests passed**, 0 failures/errors/skips, including runtime-window
  Full on four different device sizes, orientation labels, 9:16 full-width
  fitting, preset rotation, countdown freezing and invalid geometry.
- **Automatic device tests passed**: `photoAspectPreviewClipRegression`
  (portrait, both lenses, all four ratios, outside-frame pixel check) and
  `photoRatioMenuCaptureAndPersistence` (both lenses, all four ratios, matching
  JPEG dimensions/aspects, persistence, no Video selector, ratio held while
  saving).
- **Guided physical test passed**: both landscape directions, both lenses, all
  four ratios, plus Full countdown rotation. Full landscape measured 2400×1080;
  the countdown JPEG retained the expected frozen interval.
- **Production verification**: portrait all four ratios + Video on both lenses;
  landscape top-left all four ratios + Video on both lenses; final build Full
  verified at **1080×2400** and **2400×1080** on both lenses.
- Production `assembleDebug` and `lint --offline` passed: **0 errors / 10 existing
  warnings** (AndroidGradlePluginVersion, NewerVersionAvailable, DiscouragedApi,
  five UsableSpace, two UseKtx). No new warning or suppression.

### Retained failures/retries

- The first 9:16 implementation produced the side bars described above; replaced.
- The first Full implementation used the 1080×1658 available area; replaced.
- The interim 1080×608 “16:9” portrait was rejected by the user; final is 1080×1920.
- The TextureView leak was diagnosed from the view tree and fixed with
  `clipToBounds`; the regression test would have caught it.
- A production selector was transiently disabled while the previous photo
  finished saving; the test harness now waits for enabled controls.
- `focus finish` initially failed to restore the **Video lens** preference
  (`lens_SkitVideo`); the restore now selects both Video and Photo lens settings.
  The corrected audit passed.
- All failed logs, earlier builds and their screenshots are retained in the
  ignored `.validation/` directories.

## Preservation and final state

Fresh SHA-256 baselines were taken before the production replacement. Closing
audit: **0 changed or missing original files**.

| Scope | Before | After | Changed/missing | New verification files |
|---|---:|---:|---:|---:|
| Production sessions + photo jobs | 895 | 917 | **0** | 22 |
| Public SkitCam Gallery | 626 | 656 | **0** | 30 |

All **184 production recording journals** remained byte-for-byte unchanged; no
production recording was created. The additions are the verification photos;
none were deleted. The app was left in the user’s pre-test camera state:
**Photo · Front · 9:16**, Video lens **Rear**, grid **on**.
Settings were unchanged: Auto-rotate **on**, `user_rotation=0`,
`wm user-rotation=free`, font **1.3**, navigation **0**.

## Reproduction

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lint --offline
./gradlew -PvalidationSuffix=.m11validation :app:assembleDebug :app:assembleDebugAndroidTest --offline
adb -s ZY22KJQLTV shell am instrument -w -r \
  -e class com.calvinmazhindu.skitcam.camera.CameraRotationDeviceTest#photoAspectPreviewClipRegression \
  -e rotationRun <unique-folder> \
  com.calvinmazhindu.skitcam.m11validation.test/androidx.test.runner.AndroidJUnitRunner
```

The guided ratio test requires `-e physical true` and real physical movements.
Production installation requires the idle/session/service checks, matching
signing certificate and a fresh hash baseline before `adb install -r`.

Ignored evidence is under `.validation/photo-ratios/` (automatic and physical
runs, screenshots, JPEGs, clip samples, logs), `.validation/photo-ratio-recheck/`
and `.validation/photo-ratio-916-final/` (final production focus, `audit.json`,
prefs/settings, final Full measurements). The earlier
`.validation/photo-ratio-recheck-16x9/focus/` retains the correction history.

## Remaining limitations

- Full is **100% of the current window**, but the saved JPEG is a **sensor crop**
  to that runtime aspect. On this phone it is a 0.45 portrait crop; on another
  phone it will follow that phone’s screen. No fixed ratio is assumed.
- The Full/9:16 previews extend behind the gradient controls; those controls
  intentionally obscure parts of the image. The frame itself remains complete
  and unstretched.
- Non-Full presets still reserve the header/dock area; only 9:16 portrait and
  Full extend behind the dock. This matches the stock camera’s 9:16/Full
  presentation on this device.
- One physical Moto G54 and Android 15; split-screen, foldables, other OEM
  sensors, Android 16 large-screen policy and TalkBack usability are unverified.
  JPEG dimensions are device/sensor-dependent; the values above are this phone’s.
- The corrected source changes are currently **uncommitted** on
  `deploy/photo-landscape-moto-g54`; `main` remains `9f88384`.
