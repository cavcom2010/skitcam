# M15A — Production quality foundation

- Branch: `feature/m15a-quality-foundation`.
- Base: `cc8981fd96101b9de8112e62d7212440074ea751`; local and remote `main` matched after fetch.
- Release settings remain `versionName = "0.13.0"` and `versionCode = 17`. No production signing, merge, or release.
- M14.2 UI and recording, trimming, playback and export policy are unchanged. [M15B field test matrix](M15B_FIELD_TEST_MATRIX.md) is a plan, not implementation.

## Required CI

[Android quality workflow](.github/workflows/android-quality.yml) runs on PRs to `main`, pushes to `main` and `feature/**`, and manual dispatch. It uses Ubuntu 24.04, Temurin JDK 17, SDK 36 and Build Tools 35.0.0. `./gradlew` uses the checked-in Gradle 8.13 wrapper, AGP 8.13.2 and Kotlin 2.3.21. Feature/PR jobs can read but not write the shared Gradle cache. The job has read-only repository permissions, does not retain checkout credentials, and uploads only JVM and lint reports, including on failures when reports exist. `--continue` lets independent tasks finish but still returns failure if any required task fails.

The same Gradle task set ran locally without using an emulator or phone:

```sh
./gradlew --no-daemon --console=plain --continue \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Host preflight: JDK 17.0.21.1, installed SDK platform 36 and Build Tools 35.0.0, and approximately 5.5 GiB free on the project/SDK filesystem at the start. This work did not remove files to free space or change disk configuration. The workflow also passed [`actionlint` 1.7.12](.validation/m15a/actionlint.log). **A hosted GitHub Actions run is not verified:** nothing has been pushed. The first approved feature-branch push should confirm cold runner setup and report upload before the check becomes a branch-protection requirement.

## Build and device results

- Local CI-equivalent build: **294 JVM tests passed**, zero failures, errors or skips; both debug APKs built, including the instrumentation APK ([build log](.validation/m15a/ci-equivalent-final.log), [counts](.validation/m15a/build-summary.json)).
- Lint: **0 errors, 46 warnings**. The additional 10 relative to the offline M14.2 report are online dependency/tool update advisories; dependency versions were not changed ([report](.validation/m15a/lint-results-debug.xml)).
- Moto G54, Android 15: under the fresh `.m15avalidation` package, the first diagnostics build passed both Review cases ([frame retention](.validation/m15a/trim-repeatedTapsKeepPresentedFrameAndDoNotReprepare.log), [trim/switch/export](.validation/m15a/trim-dragPlaybackSaveResetSwitchAndExport.log)) and all [three thumbnail cases](.validation/m15a/thumbnails.log). Seven [AtomicFile cases](.validation/m15a/recovery-storage.log), five [export journal cases](.validation/m15a/export-journal.log), and both stages of [process-death recovery](.validation/m15a/recovery-restore.log) passed.
- The fresh [ten-clip M14.2 navigation regression](.validation/m15a/navigation-portrait.log) passed, including keep/discard/restore, centred and manually scrolled pills, forward/backward navigation, loop/replay and an eight-clip Gallery export with unchanged source hashes. No playback failure event occurred in that navigation run. No existing installation or user media was replaced or deleted; live validation added disposable exports.
- The final diagnostics build passed both [playback failure fixtures](.validation/m15a/corrupt-playback-final.log), [normal saved-video playback and release](.validation/m15a/saved-playback.log), [landscape playback at 2.0× text](.validation/m15a/navigation-landscape.log), and [the narrow summary states](.validation/m15a/summary-narrow.log). The two failure fixtures generated three [structured records](.validation/m15a/playback-diagnostics-final.jsonl): unsupported container for Review and Play Skit, unavailable URI for saved playback. They exposed no fixture path, URI, ID or raw exception.
- The [background recording regression](.validation/m15a/background-recording.log) **failed**: the current clip finalised and the camera reopened, but the test timed out waiting for a stable portrait pose before it could resume. The same test [failed at the same step](.validation/m15a/background-recording-baseline.log) on an isolated APK built from the unchanged `cc8981f` source. Both recorded clips and sessions were retained.
- The [built-in microphone regression](.validation/m15a/builtin-audio.log) passed separately using a fresh `.m15aaudio` build, without touching either paused failure fixture. It checks capture health, metering, resume after activity recreation, and export; it is not a listening test. **Total: 26 distinct passing M15A device cases and one reproduced pre-existing failure on the Moto G54.**

## Playback failure diagnostics

The same app-owned `SkitCamPlayback` warning now emits one JSON event per reported failure in Play Skit, Review, or saved-video playback. It replaces raw throwable logging, including from the saved-video player, which previously shared the tag. Device model/API/Android release and the Media3 error code are included. For MediaCodec failures, codec implementation name, codec MIME, hardware acceleration and numeric platform error are included **when known**. The event also reports the last observed player state, one-based clip position/count, relative position, transition reason, and whether a surface or rendered frame was observed. Preparation, seeking, and playback are inferred from callbacks; an ambiguous phase is `unknown`. It is not a crash reporter or a proof of root cause.

No media URI, path, segment/session ID, exception message, media data, or stack trace is emitted by the app logger. Fields use typed values or allowlisted codec data. Output is local Logcat only; no telemetry is sent or saved by the app. To collect just the structured events, filter both the tag and record prefix (especially on a device also running older validation builds):

```sh
adb -s "$SERIAL" logcat -b main -d -v raw -s SkitCamPlayback:W '*:S' |
  grep '^{"event":"playback_failure"'
```

Do not collect full Logcat: Media3, the OS and camera logs are not covered by this filter. Existing playback messages and original-media preservation behavior are unchanged. Missing files rejected before opening a player still use the existing missing-media message.

## Risk-ranked assessment

| Priority | Finding | Evidence and next action |
|---|---|---|
| High | Intermittent Play Skit decoder failure | **Confirmed on the pre-M14.2 baseline**: two reported MediaCodec failures, followed by passing runs. Cause and frequency are unknown. Play Skit prepares immediately; immersive Review waits for a real surface and uses a different surface path. Neither observation shows which component caused the failure. Compare cold/repeated starts and early seeks on the G54 and a second chipset before changing Media3 or surface sequencing. |
| High | Capture interruption and storage exhaustion | JVM coverage simulates refusal, low-space finalisation, timeouts, partial journal writes, late callbacks and battery transitions. Process-death and file-store tests use disposable sessions. Real filesystem exhaustion during a sustained high-bitrate recording is not established; use test hooks or a disposable emulator, not the owner’s storage. |
| High | Stable pose after foreground return | Reproduced on both M15A and its unchanged baseline on the G54. Safe finalisation succeeded, but the test could not resume without a stable pose. `OrientationTracker` clears its pose on stop, while [Android 15 AOSP](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r1/core/java/android/view/OrientationEventListener.java) retains the prior angle and only notifies on changes. This supports, but does not confirm on the Moto, the hypothesis that a stationary phone receives no fresh callback. Re-test while holding the phone still and while moving it before proposing a targeted fix. |
| High | Wrong, silent or drifting audio | The recording port requests audio and checks CameraX status; the observer unregisters its callbacks when the foreground/recording attempt no longer needs them. Those checks don’t identify the real microphone signal or measure lip sync. Verify USB/wireless route changes, backgrounding and long clips on physical devices. |
| Medium | Camera/decoder resources after repeated lifecycle transitions | `MainActivity` closes both edit/playback players on pause/destroy, the preview unbinds its own use cases, and the saved-video activity releases its player on stop. The CameraX recording handle remains until finalisation. No persistent leak established by the audit. Stress repeated close/reopen/record and foreground/background cycles on hardware and check for old camera or microphone owners. |
| Medium | Export interruption and provider behaviour | The journal and provider publisher verify output, check pending-row ownership and reuse a known publication checkpoint. Journal tests cover failure/retry state; M14 covered cancellation/retry. Provider crashes, full volumes and OEM media indexing failures remain field-test gaps; also exercise the pre-29 Gallery publication path. |
| Medium | Privacy of other diagnostic channels | Raw playback exception logging was found and replaced for all current uses of the app’s playback log tag. Existing CameraX finalisation logs under the separate `SkitCam` tag still include a source path; that is outside this playback change and should not be included in support captures. |
| Product limitation | No delete action in SkitCam Gallery | Confirmed by inspection: Gallery lists and opens saved media, and the saved-video screen exposes playback/share/project export, not delete. Deleting an exported MP4 via the system Gallery or Files app (`Movies/SkitCam`) does not remove SkitCam’s original segments. Refresh the list after an external deletion. In-app deletion is deferred; it needs an explicit ownership/consent design, separate from deleting originals. |

## Emulator feasibility

The [Ubuntu runner image](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md) includes the SDK tools, and [hardware-accelerated emulator runs](https://github.com/ReactiveCircus/android-emulator-runner#running-hardware-accelerated-emulators-on-linux-runners) are feasible when the selected runner exposes usable KVM. After the owner freed disk space (about 37 GiB available), API 35 AOSP x86_64 and the Android Emulator were installed in the existing SDK. [`emulator -accel-check`](.validation/m15a/emulator-accel.log) confirmed usable KVM. No hosted job was run.

The local headless pilot booted twice without snapshots (35 and 36 seconds). Each boot ran the same 14 fixture tests twice: [1.1](.validation/m15a/emulator-1-1.log), [1.2](.validation/m15a/emulator-1-2.log), [2.1](.validation/m15a/emulator-2-1.log), [2.2](.validation/m15a/emulator-2-2.log). **56/56 test executions passed.** This establishes local feasibility for the selected tests, not hosted reliability or camera/codec fidelity. The [local pilot driver](.validation/m15a/run-emulator.sh) and logs are Git-ignored evidence.

For a hosted pilot, use a separate, opt-in API 35 x86_64 run with `/dev/kvm` and `emulator -accel-check` preflight, a bounded boot timeout, and `.m15avalidation`. Boot headless without snapshots (`-no-window -no-snapshot -accel on -gpu swiftshader`); the smoke group does not use the camera or microphone. Start with `SessionFileStoreRecoveryDeviceTest`, `ExportJournalDeviceTest` and `PlaybackFailureDeviceTest`. Run several times without automatic retries, and retain boot and test failure evidence. Do not run all `connectedDebugAndroidTest` cases indiscriminately: fixtures, validation package guards and physical camera/sensor assumptions are documented test prerequisites, not failures to suppress. Keep the emulator out of the required gate until the selected suite shows consistent hosted results. This does not substitute for Moto decoder, external microphones, physical rotation, and audible sync checks.

## Reproducing the targeted tests

Build and install the two debug APKs with `-PvalidationSuffix=.m15avalidation` on an isolated device or test emulator. Do not install over a production package or clear an already used validation package. For example, to run the fixture-free smoke group after installation:

```sh
pkg=com.calvinmazhindu.skitcam.m15avalidation
base=com.calvinmazhindu.skitcam
adb -s "$SERIAL" shell am instrument -w -r \
  -e class "$base.recording.SessionFileStoreRecoveryDeviceTest,$base.export.ExportJournalDeviceTest,$base.playback.PlaybackFailureDeviceTest" \
  "$pkg.test/androidx.test.runner.AndroidJUnitRunner"
```

Check the instrumentation result for `OK` and the expected count, not just the `adb` exit code. Run the remaining groups separately on a fresh, disposable device installation:

- **Review:** generate input clips with `tools/trim_flash_fixtures.sh` and `tools/thumbnail_fixtures.sh`. Stage under `files/trim-flash-inputs/` and `files/thumbnail-inputs/` of the validation app. Run each `TrimPreviewFlashDeviceTest` method in its own instrumentation process, then `ThumbnailReadabilityDeviceTest`.
- **Recovery:** run `M12ARecoveryDeviceTest#a_checkpointPausedSession` on a fresh recording store, force-stop the isolated package only, then run `M12ARecoveryDeviceTest#b_restoreAfterProcessDeath`.
- **M14.2 UI:** `SegmentNavigationDeviceTest#verifyFreshSession` records its own disposable ten-take session instead of requiring the pre-M14.2 baseline APK. For `#landscapeLargeText`, stage a four-second H.264/AAC 1920×1080 landscape test clip under `files/m142/landscape-input.mp4`. Run `SegmentSummaryBarDeviceTest` in its own invocation after the landscape case. `captureBaseline` and `verifyRecoveredSession` remain the separate, two-APK M14.2 upgrade harness.

## Release candidates

When approved, use `0.14.0-rc.1`, `0.14.0-rc.2`, then `0.14.0`. Assign a strictly increasing integer code to each build distributed through the same application ID, starting at the next unused code above `17`. Do not reuse a candidate’s code for the final build. Local isolated validation builds can retain today’s version configuration.

**Review recommendation:** Accept the quality foundation for review, not as a production release sign-off. Confirm the first hosted workflow run, then investigate the reproduced foreground/resume failure and the intermittent decoder failure before hardware release testing is signed off.
