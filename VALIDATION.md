# Validation — 2026-11-01

## Result

The debug APK built, installed and launched successfully on the connected
Motorola Moto G54 5G (Android API 35). Rear-camera streaming was confirmed by
CameraX's `PreviewView.StreamState.STREAMING`, Android camera-service ownership
of back-facing camera 0, and visual inspection of portrait and landscape
screenshots. The final app was left running with camera permission granted.
The device's original automatic rotation setting was restored (`wm user-rotation`: `free`).

## Build and artifact

- `./gradlew --version`: Gradle 8.13, JDK 17.0.20.1.
- Final `./gradlew :app:assembleDebug :app:lintDebug --no-daemon`: **BUILD SUCCESSFUL**.
- Android lint: **0 errors, 10 warnings**. Seven recommend newer dependency/Gradle
  versions; one recommends explicit Android 12 backup rules; two suggest Kotlin
  extension syntax for equivalent Android API calls. Versions remain deliberately
  pinned to the selected compatible stable releases. No lint baseline or error
  suppression was added.
- APK: `app/build/outputs/apk/debug/app-debug.apk` (approximately 13 MiB).
- APK SHA-256: `035e780074ba4846b2815a1f02b33dfbb0bd7c4841309276f0cdfdb27516da6f`.
- `apksigner verify --verbose`: verified with APK Signature Scheme v2.
- `aapt dump badging`: SkitCam; `com.calvinmazhindu.skitcam`; min SDK 26;
  compile/target SDK 36; version 0.1.0 (1).
- Camera is the only platform runtime permission. AndroidX also contributes its
  app-private signature permission `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.
  No Internet, microphone or storage/media permissions are requested.
- Gradle generated the real Wrapper JAR and launch scripts using its `wrapper`
  task. The JAR matches Gradle's official published SHA-256. The official binary
  distribution checksum is pinned in wrapper properties.

## Physical-device checks

| Scenario | Observed result |
| --- | --- |
| First launch without permission | Native camera permission prompt appeared |
| First denial | Explanation and Allow camera action; no crash |
| Retry then second denial | Open settings action replaced retry |
| Open settings | Opened SkitCam's application settings |
| Return after permission change | Preview started after ADB granted camera while app settings were open |
| Clean first-launch grant on final APK | Tapped While using the app; preview streamed |
| Portrait / landscape | Preview streamed after rotation; dark system bars; circular disabled control and readable label |
| Disabled record control | UI hierarchy assertion confirmed disabled ancestry; tapping left preview unchanged |
| Background | After Home transition completed, camera service reported no active camera clients |
| Foreground | Hot launch resumed rear-camera streaming |
| Runtime log | No SkitCam AndroidRuntime crash observed |

Denial and settings cases were exercised before the final visual adjustments;
permission handling did not change afterward. Final APK checks repeated clean
grant, portrait/landscape preview, disabled control, background release and
foreground recovery. The newly created app's permission preferences were reset
for the clean-launch check; it contains no user recordings or content.

## Resolved errors and remaining limits

- Corrected a Kotlin compile error by qualifying `Context.MODE_PRIVATE`.
- Corrected an API 26 lint error by removing the API 27-only theme attribute;
  AndroidX now configures dark system bars appropriately.
- Physical screenshot inspection caught and corrected excessive landscape inset
  padding that squeezed the record placeholder.
- Build tools could not strip three dependency native libraries and packaged them
  as-is (`libandroidx.graphics.path.so`, `libimage_processing_util_jni.so`,
  `libsurface_util_jni.so`). Packaging, signature verification and device execution
  succeeded. No NDK installation was needed.
- Sandbox restrictions initially blocked Gradle cache/native-library access,
  network resolution and ADB socket/USB access. Approved host execution completed
  those operations. No unrelated system changes or tool installations were made.
- Android SDK command-line tools are absent, but installed SDK platform/build tools
  and the cached Gradle distribution were sufficient.
- API 26 runtime, one-time permission expiry and forced camera-hardware failure
  were not tested. Camera startup failures have a visible retry path in code.
  Validation used build/lint and physical-device smoke checks; no emulator or
  automated instrumentation suite was introduced.
