# SkitCam Milestone 7 — external microphone reliability

Status: implemented and tested with built-in hardware. External hardware,
spoken-dialogue quality and an isolated export-timing failure remain follow-ups.
Branch `feature/external-microphone-reliability`, based on committed M6 `b119ae0`.
No automatic commit or push. Minimum SDK 26, compile/target 36, CameraX 1.5.3 and
Media3 1.11.1 retained; no dependency expansion. Application version 0.7.0 (7).

## Architecture and capability boundary

CameraX Recorder remains responsible for audio/video capture, timestamps and
muxing. Media3 export is unchanged. Public CameraX AudioStats supplies capture
health and sampled relative peak amplitude. AudioManager input discovery and
recording callbacks observe system capabilities without opening another capture
stream. There are no AudioRecord instances owned by the monitor, global routing
changes, communication-mode changes, SCO requests or privileged permissions.

Inspection of the exact cached CameraX 1.5.3 AAR confirmed no supported device
selector or public audio-session identifier. `setAudioSource`/`getAudioSource`
are LIBRARY-restricted use-case APIs, not supported device selection. They are
not used. AudioRecord's preferred-device API requires access to that capture
instance and does not guarantee actual routing. Replacing CameraX with custom
AudioRecord/muxing was rejected because it would introduce synchronization and
lifecycle risks for the existing recording engine.

The new audio package separates pure Kotlin policy/state from the Android
adapter and Compose interface. State separates connected endpoints, Android
routing policy, system-observed camcorder routes, permission/mute health, peak
samples and warnings. Even unique system observations are **not** claimed to be
SkitCam's verified route; CameraX does not expose the identifier needed to bind
them authoritatively. No microphone preference is persisted, and session schema 4
is unchanged. Connection IDs are transient and never reused as saved preferences.

Discovery classifies built-in, USB device/accessory/headset, wired/line interfaces,
Bluetooth SCO and BLE headset input endpoints, plus other input types. Outputs
are excluded. An audio receiver being connected does not prove capture through it.
Bluetooth inputs not exposed by Android are unavailable to this observer; Bluetooth
playback connectivity is not treated as microphone capability.

## Safety, lifecycle and UI

The selected policy is pause-and-warn on external capture-endpoint removal. This
is intentionally conservative: an unverified connected device may not have been
used. A changed device for the same observed camcorder capture session also
triggers a warning and pause; observations still carry an ownership caveat.
New endpoints alone do not interrupt recording. Expected configuration removal
at finalisation is not mistaken for a changed device.

`pauseForAudioChange` accepts Starting/Recording and uses existing Finalising →
Paused behavior. It is idempotent during finalisation, never auto-resumes and never
registers a segment until CameraX success plus native sample validation. An early
capture with no valid samples can still end in Error under existing rules; its
candidate remains retained. Explicit RESUME rechecks existing guards and starts a
new attempt. Genuine mute/silence/source/encoder failures keep the existing safe
Error path; zero amplitude alone never stops recording.

Device callbacks observe while the camera UI is foreground or capture is settling;
recording callbacks are registered per capture attempt, with attempt identity
checks rejecting delayed callbacks. Callbacks unregister after background capture
settles. Meter and observations clear between attempts. Old peak samples expire
after two seconds; no preview/paused microphone stream is opened.

The compact header control opens a scrollable, read-only details dialog. Header
space is measured so the indicator does not cover paused thumbnails. Text and
accessibility descriptions distinguish permission denial, global mute, source
silencing, capture failure, unavailable level and unverified identity. The level
bar uses finite values clamped to [0,1]; it is sampled peak, not calibrated dB/SPL
or a guarantee against clipping. Existing playback release and recording handoff
remain intact.

Built-in fallback means disconnecting external inputs, explicitly resuming and
checking a test recording. CameraX cannot enforce built-in choice here; no forced
fallback or guaranteed external input is advertised.

## Validation

The first full build passed 87 JVM tests, assembleDebug, instrumentation assembly
and full lint. This includes 77 existing tests and ten new cases: eight audio
policy/state tests and two engine transition tests. Builds 2–6 added per-attempt callback isolation, system mute observation, the
landscape review correction and finalisation audio-health validation. Build 6
passed all 87 JVM tests, both APK assemblies and full lint.

A device integrity inventory was captured before install/testing at
`/tmp/skitcam-m7-before.sha256`. No source cleanup is performed. Dedicated test
recordings are retained; final comparison follows below.

Commands:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lint --no-daemon
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

New `AudioDeviceTest#builtinDiscoveryLevelPauseResumeAndExport` checks native
built-in discovery, real CameraX audio stats, peak bounds, explicit unverified
labels, pause-level release, recreation, resume and final two-segment export.
Existing recording, review, arrangement, trim and M6 Finished workflow tests
provide regression coverage. External device removals and routing reactions are
simulated in deterministic policy/engine tests, not physical external-mic tests.

## Lint classification

M6 baseline: 22 warnings, zero errors:

| Category | Count | Interpretation |
| --- | ---: | --- |
| Version notices | 10 | AGP/dependency newer-version suggestions; no unsolicited upgrades |
| Kotlin convenience | 7 | UseKtx suggestions |
| Storage space | 3 | Existing UsableSpace checks |
| Backup rules | 1 | Existing DataExtractionRules warning |
| Layout | 1 | Existing UselessParent suggestion |

Final M7 lint retains exactly these 22 warnings and zero errors: no newly
introduced warnings remain. An intermediate InlinedApi warning for the API 28
microphone-mute broadcast was corrected with an API guard and lightweight
API 26–27 polling while observation is active. No warning baseline was added.

## Hardware coverage and limitations

User confirmed built-in microphone only. USB-C receivers, analog adapters, wired
headsets and Bluetooth microphone routing/disconnection remain **physically
untested**, regardless of simulated test results. No source or UI will claim
those paths were validated. Built-in spoken-dialogue/listening quality, clipping,
external noise, physical sensor behavior, API 26 fallbacks and OEM callback quirks
require separate explicit observations. An audio track or nonzero peak alone does
not prove intelligibility, route identity or lip-sync.

## Official references

[CameraX AudioStats](https://developer.android.com/reference/androidx/camera/video/AudioStats),
[Recorder builder](https://developer.android.com/reference/androidx/camera/video/Recorder.Builder),
[AudioManager](https://developer.android.com/reference/android/media/AudioManager),
[AudioRecordingConfiguration](https://developer.android.com/reference/android/media/AudioRecordingConfiguration),
[AudioRecord routing](https://developer.android.com/reference/android/media/AudioRecord#getPreferredDevice()).

## Actual Motorola results

Moto G54 5G (`ZY22KJQLTV`), Android API 35. The first 20-case hardware run had
nine passing cases, one landscape review failure and ten skipped cases after the
failed fixture remained safely Paused. The extra microphone header compressed the
landscape review player. Hiding the audio indicator while review is open restored
the existing player space; the microphone control remains on the camera screen.
The failed fixture was retained and completed through STOP, including export.

The corrected 13-case retry passed in **250.077 seconds**: built-in microphone,
all four PAUSE cases, all four arrangement cases and all four trimming cases.
Together with the earlier six recording cases and full M6 Finished workflow,
**all 20 distinct device cases passed across the runs**. The first setup was also
obstructed by the notification panel; dismissing it and using New Recording
allowed capture. No external device was simulated and counted as physical routing.

Built-in fixture `968d44f8-ca66-4047-afda-c82c1d47e13e` reported two BuiltIn input
endpoints and four Other Android input endpoints. No USB/wired/Bluetooth capture
endpoint was reported. Android's camcorder configuration named BuiltIn device 17,
capture session 49257. The app correctly retained the ownership caveat rather than
calling that configuration verified for CameraX. CameraX reported Active audio
and sampled peak `0.00003051850947599719`.

Read-only copies of both source segments and the finished MP4 were inspected with
ffprobe and fully decoded by FFmpeg with source-time-base/passthrough timing.
All three decoded successfully. They contain AVC 1280×720 and AAC 48 kHz stereo:

| File | Video duration | Audio duration | Mean audio | Peak audio |
| --- | ---: | ---: | ---: | ---: |
| segment_001.mp4 | 1.671300 s | 1.622062 s | -77.8 dBFS | -63.9 dBFS |
| segment_002.mp4 | 1.737544 s | 1.667979 s | -85.5 dBFS | -69.5 dBFS |
| Finished MP4 | 3.408544 s | 3.465292 s | -80.3 dBFS | -64.3 dBFS |

These are very quiet, nonzero recordings. This establishes capture/encoding and
preservation through export; it does **not** validate dialogue intelligibility,
subjective listening, absence of audible artifacts or lip-sync against speech.
A spoken check was offered to the user; it remains pending unless separately
recorded here. No signal was played into the microphone and described as speech.

Diagnostic fixture copies remain under `/tmp/skitcam-m7-audio/`. Actual live-level
state was also saved in the app's new `files/validation-m7/` test-only directory.
The adapter never loads or processes the audio samples itself.

Finalisation now checks CameraX audio health as well as periodic Status events:
a short clip with reported unavailable/muted/silenced/error audio is retained as
a candidate and not silently registered as a successful segment. Genuine capture
errors and successful finalisation still use the existing engine transitions.

## Changed surfaces

- New audio model/policy, Android monitor and compact Compose indicator/details UI.
- CameraRecordingPort forwards attempt-correlated health/peaks and checks final audio.
- RecordingRuntime owns the monitor; the Activity supplies foreground lifecycle.
- RecordingEngine adds guarded pause-for-input-change without adding states.
- Eight audio JVM cases, two engine cases and one built-in device workflow case.
- App version, README and this validation report. Session format and export pipeline
  are unchanged; there are no new runtime permissions or dependencies.

## Final APK checks and observed export limitation

Final application build 6 passed all **87 JVM tests**, debug APK assembly,
instrumentation assembly and full lint: **zero errors, 22 existing warnings**.
No new lint warnings remain. Per-attempt callbacks and system mute observation
are included. The final built-in microphone workflow passed again.

A subsequent final-APK export test was rejected by the unchanged M6 verifier:
new test session `769f2f6a-ae12-4159-bfe8-1029bf834bac` produced a candidate whose
video track was 3.442244 s and audio track 3.550708 s (about 108 ms difference).
Last decoded presentation timestamps differed by about 120 ms. Original segment
track end times were much closer; the first source also had an audio start offset
of 66.104 ms. The source timing and composition behavior need further diagnosis;
this report does not establish that M7 caused it or that it predates M7.

Verification correctly blocked publication. All source MP4s, session metadata
and the failed candidate were preserved. Copies are under
`/tmp/skitcam-m7-timing-failure/`. No tolerance or export algorithm was changed to
make this test pass. This isolated composition-timing failure remains unresolved.

A fresh final-APK recording then passed the complete STOP → verified MP4 → Play →
Share chooser → New Recording workflow in **14.715 seconds**. No share was sent.
Thus 20 distinct device cases passed across the runs, with the failed candidate
above explicitly retained as an additional failure observation. This is not a
claim that every recording/export attempt passed.

Final test commands:

```sh
adb shell am instrument -w -r -e class com.calvinmazhindu.skitcam.audio.AudioDeviceTest,com.calvinmazhindu.skitcam.export.ExportDeviceTest#stopAutomaticallyExportsAndPublishesTwoSegments com.calvinmazhindu.skitcam.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -r -e class com.calvinmazhindu.skitcam.export.ExportDeviceTest#stopAutomaticallyExportsAndPublishesTwoSegments com.calvinmazhindu.skitcam.test/androidx.test.runner.AndroidJUnitRunner
```

No commit or push has been made. External route selection remains unsupported
with the existing CameraX public API. Physical USB/wired/Bluetooth tests are
pending because the available hardware was built-in only. Permission/mute/error
presentation and external-change reactions have deterministic policy coverage;
physical permission revocation, global mute toggles, external disconnection,
TalkBack, extreme font scaling and older Android behavior remain manual checks.

## Final preservation and UI evidence

The before/after SHA-256 inventories covered **492 pre-existing session files**,
including original MP4s, session metadata and existing export artifacts. All 492
were present and byte-identical after testing: **zero changed or missing entries**.
New diagnostic fixtures and exports remain retained.

On the final APK, the real audio-details control opened the scrollable read-only
panel, showed Android-managed routing and connected-but-not-confirmed input
labels, and closed through its visible Close control. New Recording returned to
the preview. The microphone indicator is hidden during segment review so the
existing landscape player controls retain their space.


## Milestone 7.1 follow-up

The isolated export failure above was subsequently reproduced in an independent
committed-M6 control and investigated at sample and clip-join level. A bounded
source-gap correction now passes the preserved-input copies and the complete
25-case device regression suite without relaxing verification. The original
failed job/candidate remain unchanged. See [VALIDATION_M7_1.md](VALIDATION_M7_1.md)
for the evidence, correction, exact test results and remaining limitations. This
follow-up supersedes the earlier unresolved status for that reproduced case;
external microphone and dialogue-quality coverage remain as described above.
