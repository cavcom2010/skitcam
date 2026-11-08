# SkitCam 0.13.0 — release validation

Validation date: **7 November 2026**  
Device: **Moto G54 5G**, Android 15 / API 35, 1080×2400.  
Production package: **`com.calvinmazhindu.skitcam`**.  
Isolated release packages: `.m12bvalidation`, `.m11validation`.

This report supersedes the earlier 0.13.0 draft. It records the remediation
requested after the GPT-6.1 Sol review, including the project-export camera-return
diagnosis, exact five-segment UI loop, idle→START header measurement, and a final
preservation audit captured after every device test.

No commit, push, merge, tag or GitHub release was created.

## 1. Baseline and branch

| Item | Result |
|---|---|
| Working branch | **`release/0.13.0`** |
| Branch state at start | Already existed and was clean at `fb77b52` |
| `main` / cached `origin/main` | `fb77b52c6ce7b17e84e201f4b17374d4c15e76a4` |
| M12B present in baseline | **Yes** |
| Baseline ancestor of work | **Yes** |
| User changes reset, cleaned or discarded | **None** |

## 2. Files changed

| File | Change |
|---|---|
| `app/build.gradle.kts` | Version **0.13.0 / 17**; generated `BuildConfig` enabled |
| `app/src/main/java/com/calvinmazhindu/skitcam/camera/CameraTools.kt` | Settings reads `BuildConfig.VERSION_NAME` |
| `README.md` | 0.13.0 notes; corrected arrangement-vs-timestamp wording; fast-path/size tradeoff |
| `app/src/androidTest/.../project/ProjectDeviceTest.kt` | Camera-return diagnostics; explicit Skit Video selection; unambiguous DocumentsUI root selection |
| `app/src/androidTest/.../m12a/M12ADeviceTest.kt` | Exact five-segment UI loop test; idle→START header test; restore-safe Skit Video selection |
| `VALIDATION_RELEASE_0_13_0.md` | This report |

No production camera, export, persistence or UI behaviour was changed during
remediation. The project-return failure was a test assumption, not a CameraX or
project-resource defect.

The application ID, minimum SDK, signing setup, manifest, CameraX/Media3
architecture and session storage were not changed. Milestone 12C was not started.

## 3. Version, APK and signing identity

| Item | Value |
|---|---|
| `versionName` | **0.13.0** |
| `versionCode` | **17** |
| APK | `skitcam-0.13.0-17-debug.apk` |
| APK SHA-256 | `42d2c54d60218a56cb1ffa1d4fbe7c8d53550e8973aa73f61f2e53ae599d892e` |
| Installed APK SHA-256 | **Matches candidate** |
| Package | **`com.calvinmazhindu.skitcam`** |
| Signing scheme | APK Signature Scheme v2 |
| Signer DN | `C=US, O=Android, CN=Android Debug` |
| Certificate SHA-256 | `bae70b79b460e601507a63349ae6f501ef7ca09c0106a6e2f5b65f8d0eec18a7` |

The production candidate was rebuilt from the final tree and reproduced the same
SHA-256. The installed package still reports **0.13.0 / 17** and the same signer.
Settings displays **`SkitCam · 0.13.0`**.

The delivery remains debug-built and debug-signed, matching previous local
releases. It is not a Play Store release-signing setup.

## 4. Project export → camera return

### Root cause

The initial failure was diagnosed with bounded values. At the timeout the test
reported:

```text
cameraReady=false, pose=0, streaming=true, cameraBound=false,
engineState=Completed, commandPending=false, exportBusy=false,
lifecycle=RESUMED, cameraPermission=true, switching=false,
videoPending=false, videoReady=false, zoomReady=true,
choice=CameraChoice(mode=Photo, lens=Rear)
```

The failure was **A: an incorrect test mode assumption**, not a CameraX rebind or
project-resource leak. SkitCam correctly remembered Photo as the last mode, while
the test assumed the video camera was ready. There was no open session, export was
not busy, the activity was resumed, camera permission was granted, and the preview
was streaming.

### Test corrections

- Wait for recovery and preview streaming after the project export.
- Explicitly select **Skit Video / Rear** before waiting for `cameraReady`.
- Keep bounded diagnostics (`cameraReady`, pose, streaming, binding, engine state,
  command pending, export busy, lifecycle, permission, switching, video readiness,
  video message/diagnostic, port profile, choice).
- DocumentsUI: target the actual `android:id/title` Downloads root row and tap its
  bounds. The previous search could select the non-clickable breadcrumb.

### Result

`ProjectDeviceTest#systemPickerCreatesVerifiedPortableProjectAndCameraStillRecords`
now **passes** on both the `.m11validation` release package and the **installed
production app** (explicit opt-in `allowProduction=true`; isolated remains the
default):

- Project ZIP completes and is verified.
- Camera preview becomes ready again.
- A new segment records.
- The new segment finalises and exports successfully.
- Existing source metadata and hashes remain unchanged.

The production run added a three-segment fixture session plus the post-project
recording session, two public videos, one project-export receipt and the expected
private test artifacts. No existing source or Gallery item was replaced.

The earlier “automation failure” description is withdrawn: the project ZIP itself
was valid, and the camera assertion failed because the test selected the wrong
assumed mode.

## 5. Exact five-segment UI loop

`M12ADeviceTest#g_fiveSegmentUiLoopStopsAtSuffixAndReleasesForResume` passed on both
the isolated release package and the installed production app.

Observed sequence:

1. Five kept segments recorded; session paused.
2. Actual UI: **More actions for segment 3 → Play from here**.
3. Loop ON plan: exactly **3 → 4 → 5**.
4. Observing rendered transitions produced indices **0 → 1 → 2 → 0**, returning to
   Segment 3. Segments 1 and 2 never entered the plan or playback.
5. Actual UI: Loop chip changed to **Loop off**.
6. Playback finished after Segment 5 and did not wrap.
7. **Close playback** released the player and cleared the plan.
8. **RESUME** started a new recording; playback remained released.
9. The original five source MP4 hashes were unchanged.
10. The session completed and exported; RESUME created the expected sixth segment.

## 6. Idle → STARTING → RECORDING → PAUSE header stability

`M12ADeviceTest#h_idleToStartHeaderBoundsRemainStable` passed on the isolated
release package and the installed production app.

Header bounds were identical in all four states:

| Control | Completed (idle) | Starting | Recording | Paused |
|---|---|---|---|---|
| Settings | `[20,121][140,241]` | same | same | same |
| Microphone indicator | `[820,117][940,237]` | same | same | same |
| Quick settings | `[940,121][1060,241]` | same | same | same |

Recording sample report:
`.validation/release-0.13.0/header-idle-to-start.json`.

START was issued through the same `RecordingViewModel.start()` command that the
START control invokes. A UI-automation tap can return after the transient STARTING
state has already passed, so the test polls the real state transitions and captures
the Starting sample directly. PAUSE was performed through the actual UI control.
This specifically covers the original idle→recording header jump, including the
reserved microphone meter row.

## 7. Build and test results

Commands run:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:lintDebug --no-daemon
git diff --check
```

| Check | Result |
|---|---|
| JVM | **235 tests, 0 failures, 0 errors, 0 skipped** |
| `assembleDebug` | **Success** |
| `assembleDebugAndroidTest` | **Success** |
| Lint | **0 errors, 17 warnings** |
| `git diff --check` | **Passed** |

Lint warning categories: 1 AGP version, 8 dependency updates, 1 newer dependency,
1 Android 16 fixed-orientation notice, 4 `UsableSpace`, 2 `UseKtx`. No new
code-quality warning was introduced by the remediation.

Device evidence:

| Test | Package | Result |
|---|---|---|
| Project ZIP + camera return + new recording | `.m11validation` | **OK** |
| Project ZIP + camera return + new recording | production | **OK** |
| Exact five-segment UI loop + Loop OFF + Close/RESUME | `.m12bvalidation` | **OK** |
| Exact five-segment UI loop + Loop OFF + Close/RESUME | production | **OK** |
| Idle→STARTING→RECORDING→PAUSE header bounds | `.m12bvalidation` | **OK** |
| Idle→STARTING→RECORDING→PAUSE header bounds | production | **OK** |
| Production export/Gallery/camera return | production | **OK** |
| Gallery older-video playback | `.m12bvalidation` | **OK** |
| Fast-path safety suite (direct copy, fallback, retry, publication) | `.m12bvalidation` | **OK (12 tests)** |
| Recovery/journal suites | `.m12bvalidation` | **OK (10 tests)** |
| Photo ratio/clipping suites | `.m11validation` | **OK (2 tests)** |
| Arrangement + Trim | `.m12bvalidation` | **OK (2 tests)** |

During test development the first header attempt did not observe STARTING and left
an Error session in the **isolated** package. That session was closed normally
(`Completed`, retained candidate); production data was not affected. The final
header test captures STARTING and passes with cleanup in place.

## 8. Export and loop observations

Fresh release-build timings, single runs:

| Case | Path | STOP → live camera | Output |
|---|---|---:|---:|
| Rear single, renderer baseline | `RENDER` | 12.73 s | 11,892,724 B |
| Rear single, automatic | `VIDEO_PASSTHROUGH_AUDIO_RENDER` | 11.27 s | 26,741,542 B |
| Rear five clips, automatic | `VIDEO_PASSTHROUGH_AUDIO_RENDER` | 29.86 s | 79,486,137 B |
| Front single, automatic | `VIDEO_PASSTHROUGH_AUDIO_RENDER` | 11.60 s | 26,620,268 B |
| Production two clips, automatic | `VIDEO_PASSTHROUGH_AUDIO_RENDER` | 5.31 s | not separately recorded |

Temperature was **28.0 °C** for the rear single/rear five runs and **30.0 °C** for
the renderer baseline and front single runs; the report does not claim a single
temperature for all benchmarks.

The renderer remains the fallback for trims, unsafe video timestamp/keyframe
order, incompatible codecs/metadata and legacy jobs. Saved segment arrangement
changes do not by themselves require rendering. Full verification, source hashing,
Gallery readback and the existing 100 ms audio/video timing gate remain active
(`ExportMedia.kt`).

## 9. Final production preservation audit

The authoritative final inventories (`.validation/release-0.13.0/final2/`)
were captured **after all device tests**, including the production project-export
run:

| Scope | Files |
|---|---:|
| Production `files/**` | **1,422** |
| Production `shared_prefs/**` | **4** |
| `Movies/SkitCam` | **614** |
| `Pictures/SkitCam` | **295** |
| Public SkitCam Gallery total | **909** |

Versus the original pre-release baseline:

| Scope | Missing | Changed pre-existing | Added |
|---|---:|---:|---:|
| Production `files/**` | **0** | **1** | **69** |
| Public SkitCam Gallery | **0** | **0** | **51** |

The only changed pre-existing private file is `files/profileInstalled`:

- It was **unchanged immediately after installation**.
- It changed later during normal app runtime/validation.
- It is an Android profile-install marker, not user media, a session, a trim/order
  decision, or an export journal.

`shared_prefs/**` was backed up before release and is now separately hash-audited:

- `camera_preferences.xml` changed (last mode/lens/ratio and test-driven UI state).
- `camera_permission.xml`, `microphone_permission.xml` and
  `video_preferences.xml` are unchanged.
- Preferences are not included in the `files/**` manifests.

Additions were additive and attributable:

- Before install, isolated release validation added 18 public files; production
  private data was unchanged.
- The earlier production smoke added 5 public files (3 videos, 2 photos) and 25
  private files: **20 session files, 2 photo capture journals, 3 diagnostic
  screenshots**.
- Remediation and late export suites added 26 public files (25 videos, 1 photo) and
  25 private files: **21 session files, 1 photo capture journal, 2 screenshots and
  1 JSON report**.
- The final production project-export run added 2 public videos and 19 private files:
  **12 session files, 1 project-export job receipt, 1 project ZIP, 1 fixture marker,
  1 job JSON and 3 screenshots**.
- The 27 public videos are attributable to: 19 isolated export/benchmark runs,
  3 isolated UI acceptance runs, 2 production UI acceptance runs, 1 production
  export regression, and 2 production project-export runs (fixture export and
  post-project recording).
- One additional production Full-aspect photo appeared at 23:09 with a complete
  capture journal and public JPEG. It is additive and was not tied to a named
  remediation test.

All pre-existing original `segment_*.mp4` files, `session.json` files, export
journals, trim/order/Keep decisions and public Gallery media remain byte-for-byte
unchanged. No file was deleted.

### Audit scope and limits

| Category | Coverage |
|---|---|
| Backed up | `files/**` (1,353 files) and `shared_prefs/**` (4 files) in a verified 4.5 GiB tar, SHA-256 `1fb02d4be14a4568893389b9bd5a0050ef2bfb3fe7a32b6160cb1178964989ea` |
| Individually hash-audited | Final `files/**` (1,422) and `shared_prefs/**` (4); public Gallery (909) |
| Not available | Other Android user's private data; MediaStore row/SAF-grant validity; hardware failure during a write |

SHA-256 proves byte preservation for captured files. It does not prove that every
valuable file was captured, that the backup restores successfully, that unchanged
videos play, or that another user's data was inspected.

## 10. Remaining known limitations

1. The APK is debug-built/debug-signed; it is not a store release-signing setup.
2. Another Android user has the package installed; that user's private data was not
   auditable.
3. Guided physical-rotation tests were not run. The landscape device test passed
   after forcing display rotation, then settings were restored.
4. The project-export acceptance test defaults to the isolated package; the final
   production run required the explicit `allowProduction=true` instrumentation
   argument. Production still should not run fault-injection suites.
5. One additional production photo in the final audit window is not tied to a
   named remediation test; it is additive and has a complete capture journal.
6. Fast-path outputs preserve camera video and are larger. Direct copy is narrow;
   real recordings normally use passthrough with audio rendering.
7. Trims and unsafe timestamp/codec/legacy inputs still render. Verification is the
   dominant fixed cost; the camera returns only after verified publication.
8. A physical mid-transform kill was not re-proven; journal/force-stop recovery is
   covered by deterministic and device tests.
9. Device coverage remains one Moto G54 5G on Android 15.
10. Lint reports 17 pre-existing warnings.

## 11. Final verdict

**READY TO COMMIT.**

The review requirements are met:

- Project ZIP export completes, and the camera returns.
- A new segment records, finalises and exports after project export.
- The exact five-segment UI loop was observed on device: `3 → 4 → 5 → 3`, with
  Loop OFF stopping after Segment 5 and Segments 1–2 excluded.
- Idle → STARTING → RECORDING → PAUSE header bounds are identical.
- The final preservation audit was captured after all tests.
- No original files are missing; the only changed historical private file is the
  Android `profileInstalled` marker, explained above.
- README and validation inconsistencies are corrected.
- The installed APK is the verified 0.13.0/17 candidate with the expected signer.

No commit or push has been performed. Independent review should inspect the
test-only diff in `ProjectDeviceTest.kt` and `M12ADeviceTest.kt` together with the
final audit under `.validation/release-0.13.0/final2/`.
