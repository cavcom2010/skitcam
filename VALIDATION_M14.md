# M14 Recording Safety, Recovery & Session Management

## Build and scope

- Branch `feature/m14-recording-safety`, based on the clean M13 checkout at `fa71f35`.
- Owner device Motorola Moto G54 5G, serial `ZY22KJQLTV`, Android 15 / API 35.
- Validation app `com.calvinmazhindu.skitcam.m14validation`. Instrumentation targets this package.
- [Validation APK](.validation/m14/SkitCam-M14-validation.apk) and [SHA-256](.validation/m14/SkitCam-M14-validation.apk.sha256).
- [Build and lint log](.validation/m14/build-final.log) and [APK signature verification](.validation/m14/apk-signature.txt).

The M13 baseline kept one segment per CameraX recording. Pause closed and validated the MP4 before committing the next tray count. Review, trimming and order were metadata edits. Finish used the existing export journal and publication service. Interrupted sessions entered an error state that could only be closed. Cancelling an export retained the originals, but New Recording could hide their session on a later launch. The M13 follow-up reported 266 JVM tests, zero lint errors and 35 warnings. [M13 report](VALIDATION_M13_REVIEW.md)

## Behaviour and state transitions

| Action | Result |
|---|---|
| Confirm Finish | The existing export job freezes the final session and publishes the verified result. An incomplete final take leads to recovery. |
| Open or cancel either discard prompt | Capture and editing continue in their prior state. |
| Confirm discard while paused | Validate the explicitly selected private session, commit cleanup intent, remove its files and then return to the viewfinder. |
| Confirm discard while recording | Request the existing safe stop. Wait for the matching CameraX finalisation and durable registration before cleanup. A failed or timed-out finalisation leaves recovery available and retains files. |
| Cancel export | Preserve the original clips and the export checkpoint. Retry export uses that checkpoint. |
| New Recording after an unexported skit | Choose Keep as draft and start new, Discard and start new, or Cancel. Drafts remain available from Settings → Recover skit. The discard path needs the second confirmation. An ambiguous publication can be retained and retried to reconcile the saved-video status. |
| Interrupted recording | Inspect the latest committed metadata and verify confirmed MP4s. Present the recovered count and status of any unfinished or unavailable clips. Later retains the files. Review and Export use the earlier valid clips. Continue opens the restored session paused, ready for manual Resume with the original profile and orientation. |
| Automatic battery pause | `Starting/Recording → Finalising → Paused` after CameraX success and durable registration. The tray and all paused editing and export actions remain available. Failure enters recovery and retains the candidate. |

M14 uses existing Finish placement and the scrollable M13 confirmation component for destructive actions. There is one contextual recovery entry in Settings. Confirmed clips retain their keep decisions, non-destructive trims, shelf order and playback order when recovery opens. A resumed take gets a fresh ID and an unused filename above the last recorded attempt.

### File cleanup and recovery

Metadata reads prefer a valid committed base and fall back to a valid legacy backup. An isolated `.new` file is uncommitted. Writes sync the staged file, atomically replace the base and sync the containing directory before retiring a legacy backup. The new path avoids framework `AtomicFile.startWrite` restoring a stale backup over a valid base before a replacement can be written. The original MP4 is synced before registration. M14 reads metadata versions 1–7 and writes version 8. [Android AtomicFile source](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/util/AtomicFile.java)

Abandonment validates the selected private directory, enumerates the expected filenames and rejects symbolic links and unexpected entries before removing media. An atomic directory rename records the cleanup intent. If deletion is interrupted, the staged directory stays discoverable for **Retry cleanup**. Job checkpoints protect completed and uncertain publications and the originals used by Export Project. Cleanup operations are scoped to the app’s private session directory. Gallery, photos and external documents lie outside that path.

Recovery validates readable video and audio tracks, samples and duration in the committed clips. It preserves unfinished candidates and keeps them out of the confirmed tray. Recovery reports the number of missing or unreadable confirmed clips and retains the original metadata when opening the playable subset. A directory with unreadable metadata is identified in recovery for inspection. SkitCam cannot reconstruct the original edit history from that directory. The validation package’s retained candidate files can be inspected with `adb run-as`.

### Battery thresholds

The activity reads Android’s sticky battery state and listens for updates while visible. The same policy gates the capture controls and the recording worker. Tests inject battery readings inside the validation app.

| Battery | Start or Resume | While recording |
|---|---|---|
| Above 10% | Available | Normal |
| 10% | Available | Shows a warning and continues |
| Above 7% and below 10% | Blocked | Shows a warning and continues |
| 7% or below | Blocked | Requests safe pause once |
| Unknown or invalid | Waits for a usable reading | Continues pending a usable reading |

At 5% or below the idle camera explains the critical restriction. Charging uses the same thresholds. At 10% the user can Resume manually. Photo capture follows its existing controls. [Android battery monitoring guide](https://developer.android.com/training/monitoring-device-state/battery-monitoring)

## Automated verification

```sh
./gradlew -PvalidationSuffix=.m14validation \
  :app:testDebugUnitTest :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:lintDebug --offline
git diff --check
```

- JVM **279 passed**, zero failures, errors or skips.
- Debug lint **0 errors, 35 warnings**.
- Both validation APKs built and installed. APK identity, signing and the instrumentation target were verified. The installed app has the same SHA-256 as the handoff APK.

| Device checks | Result |
|---|---|
| Focused safety, storage and export-journal tests on the final APK | **19 passed**. Includes live and paused discard, keeping a skit at confirmation, cleanup failure and retry, unrelated sources, both New Recording choices, cancelled export retry, low-battery editing and playback, missing files and atomic storage writes. [Device log](.validation/m14/device-final-safety-exact-apk.log) |
| Continuation on the final APK | **1 passed** after a separate force-stop. The two committed clips and the unfinished candidate retained their hashes. The next saved clip was segment 4. An earlier pair of instrumented runs also checked Later followed by Resume on the same interrupted fixture. [Final checkpoint](.validation/m14/interruption-continuation-checkpoint.json), [final continuation](.validation/m14/device-recovery-continue-exact-apk.log), [earlier Later](.validation/m14/device-recovery-later.log), [earlier continuation](.validation/m14/device-recovery-continue.log) |
| Recovered export on the final APK | **2 passed** across separate launches of the same interrupted fixture. One checked Later against the original committed metadata. The next exported only the two confirmed clips and checked the unfinished file’s hash. [Committed checkpoint](.validation/m14/interruption-before-stop-exact-apk.json), [Later log](.validation/m14/device-recoveryCanBeDeferredWithoutLosingConfirmedClips.log), [export log](.validation/m14/device-recoveryExportsOnlyConfirmedSegments.log) |
| Existing functional cases on the final APK | **3 passed**. Built-in microphone discovery, metering and resumed audio, real two-segment export with Gallery playback, and Export Project through the Android document picker. [Device log](.validation/m14/device-m13-functional.log) |
| M13 presentation matrix on the final APK | **20 passed** at 1.15× and 2.0× text in portrait and landscape. Includes camera capture and Finish, stable Review and trim geometry, inline reorder and playback, and Gallery/export presentation. The host terminated the first run after 17 passes. The remaining three passed in a separate batch after resetting the display settings. [First batch](.validation/m14/device-m13-matrix.log), [final three](.validation/m14/device-m13-matrix-tail.log) |
| Photo capture on the final APK | One live validation photo reached the completed publication state with valid dimensions and a Gallery URI. Video mode was selected again afterward. [Photo smoke log](.validation/m14/device-photo.log), [publication receipt](.validation/m14/photo-publication.json) |

The final APK passed 45 instrumented device cases and the live photo smoke check. The interruption checkpoint deliberately ends with an instrumentation process crash. The host verified that the app had two confirmed clips and a third pending take in an active recording before issuing `am force-stop` against the validation package. Recovery cases ran in fresh processes afterward. The device ended with its original 1.15× font scale, free rotation and disabled fixed-to-user-rotation setting.

Visual inspection covered the three-action Finish dialog during capture, the same dialog while paused at 2.0×, and landscape Review. [Active Finish](.validation/m14/screens/portrait-1.15-finish-active.png), [large-text paused Finish](.validation/m14/screens/portrait-2.0-finish-paused.png), [landscape Review](.validation/m14/screens/landscape-1.15-review-trim.png)

## Owner inspection on the Moto G54

1. Install the validation APK if needed. Launch the isolated package explicitly because the validation build uses the existing non-launcher configuration.

   ```sh
   adb -s ZY22KJQLTV install -r .validation/m14/SkitCam-M14-validation.apk
   adb -s ZY22KJQLTV shell am start -n com.calvinmazhindu.skitcam.m14validation/com.calvinmazhindu.skitcam.MainActivity
   ```

2. Record two disposable clips, pause and open Finish. Choose **Discard entire skit**, then **Keep skit**. Check both clips in the tray. Open the dialogs again and confirm **Discard 2 clips**. Repeat during an active disposable take if desired.
3. Record another skit and cancel export while **Cancel export** is available. Verify **Retry export** and reopen **New Recording** to check the retain, discard and cancel choices. Keep one draft and reopen it from **Settings → Recover skit**.
4. Run the simulated battery case with the matching instrumentation APK. Use disposable clips if following the interruption case.

   ```sh
   adb -s ZY22KJQLTV install -r .validation/m14/SkitCam-M14-validation-test.apk
   adb -s ZY22KJQLTV shell am instrument -w -r \
     -e class 'com.calvinmazhindu.skitcam.recording.M14SafetyDeviceTest#criticalBatteryKeepsReviewAndTrimAvailableAndCanResume' \
     com.calvinmazhindu.skitcam.m14validation.test/androidx.test.runner.AndroidJUnitRunner
   ```

5. Inspect Review and Finish in portrait and landscape with enlarged text. Play and trim a disposable clip, save or reset the trim, reorder, then finish. Open the finished video from Gallery and confirm **Export Project** is available.

## Files

- Capture, recovery and storage in `RecordingEngine.kt`, `RecordingViewModel.kt`, `CameraRecordingPort.kt`, `RecordingBattery.kt`, `RecordingControls.kt`, `SessionCodec.kt`, `SessionFileStore.kt`, `RecordingFileCommit.kt` and `SessionCleanup.kt`.
- Camera and confirmation UI in `MainActivity.kt`, `CameraPreview.kt`, `CameraModel.kt`, `CameraTools.kt`, `SkitCamComponents.kt` and `strings.xml`.
- Export lifecycle in `ExportJournal.kt`, `ExportModel.kt`, `ExportScreen.kt` and `ExportService.kt`.
- JVM coverage in `RecordingEngineTest.kt`, `RecordingBatteryPolicyTest.kt`, `SessionCleanupTest.kt`, `CameraPolicyTest.kt` and `SegmentDecisionsTest.kt`.
- Device and regression coverage in `M14SafetyDeviceTest.kt`, `SessionFileStoreRecoveryDeviceTest.kt`, `CameraUxDeviceTest.kt`, `ProjectDeviceTest.kt`, `M13UiDeviceTest.kt` and `tools/validate_m13.py`.

## Limitations and release boundary

An abrupt loss of power can still destroy the open MP4 or interrupt storage writes. Older media with both committed metadata copies unusable needs separate inspection. M14 does not attempt MP4 repair or infer trims or decisions from orphaned files. If a legacy export might already have published, retry the old job before discarding or editing its sources.

Live CameraX takes in this run used the handset’s portrait position and built-in microphone. The landscape matrix exercised display, preview, Review and dialogs. Physical landscape recording, an attached external microphone, and spoken TalkBack output remain owner inspection checks.

The installed M14 build is for owner inspection. Validation used generated or newly captured disposable clips. Production and previous owner-used validation installations were left in place. The branch remains local and unmerged.
