# Milestone 3 validation — 2026-11-01

## Result and artifact

Implemented on `feature/pause-environment` from committed Milestone 2
`8a0e403`. Version 0.3.0 (3) builds, installs and runs on Motorola Moto G54 5G,
serial `ZY22KJQLTV`, Android API 35. Minimum SDK remains 26, compile/target 36.

PAUSE provides lazy segment thumbnails, optional local playback and persistent
Keep/Discard decisions. The live camera preview and recording engine remain
available. **STOP retains individual MP4 files and all metadata; combining and
export are not implemented.**

- APK: `app/build/outputs/apk/debug/app-debug.apk`.
- SHA-256: `094ee25222eb4639193d465972b068ffc0ff3ca9e59caccf0baf83e01b27cf5a`.
- `apksigner verify --verbose`: APK Signature Scheme v2 verified.
- Added Media3 ExoPlayer/UI 1.9.4. Existing Kotlin/Compose/CameraX/toolchain pins
  were preserved. JSON-java is a JVM-test-only dependency.
- Media3 contributes normal ACCESS_NETWORK_STATE/WAKE_LOCK permissions through
  its manifest; no Internet permission or playback service was added.

## Build and tests

`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-daemon`
completed successfully on the final source.

- **32 JVM tests passed**, no failures or skips: 22 engine regressions,
  2 orientation tests and 8 decision/codec tests.
- New tests cover default Keep, version 1 compatibility, version 2 round trips,
  restart restoration, retained count/duration, all-discarded completion,
  write failures, invalid transitions, stale identifiers and idempotency.
- The 100-kept boundary is tested: Discard frees a slot, the next filename is
  `segment_101.mp4`, and restoring a discarded segment at capacity is rejected.
- Lint: **0 errors, 20 warnings**. Warnings cover pinned-version updates,
  existing backup guidance, usable-space guidance, Kotlin extension style and
  the intentional wrapper that anchors native playback controls at the bottom.
  No error baseline or suppression was added.

Debug and test APKs were installed using `adb install -r`, without clearing data.
The initial combined device run reported **OK (10 tests)** in 56.628 seconds:
six Milestone 2 hardware regressions and four PAUSE environment tests.
After visual and accessibility corrections, the final, stronger PAUSE suite
reported **OK (4 tests)** in 54.8 seconds, with no skips or failures.

## Final PAUSE hardware checks

| Scenario | Observed result |
| --- | --- |
| Portrait layout | Left vertical strip, decoded thumbnails, live preview and accessible recording controls |
| Landscape layout | Bottom horizontal strip, right recording controls, readable review panel |
| Strip overflow | Recorded six segments in each orientation and scrolled to Segment 6 |
| Review open | Prepared local MP4 without autoplay; native Play started decoded playback |
| Keep / Discard / Keep | Updated metadata and retained duration/count without changing the MP4 |
| Review action layout | Keep, Discard and Close had complete touch bounds of at least 48 dp in both layouts |
| Close | Released the player and kept the current metadata decision |
| RESUME during playback | Released player before recording; subsequent files contained valid video and audio samples |
| Preview continuity | CameraX remained bound and preview availability remained true during review |
| Missing/corrupt media | Thumbnail fallback or local playback error; recording state unaffected |
| Activity recreation | Closed review and preserved Paused session and discarded decision |
| Atomic repository | Read-back matched durable session decisions |

Native playback tests used actual accessibility actions and Media3 decoder state.
Recording uses known orientation inputs in instrumentation, as in Milestone 2;
this does not bypass orientation enforcement in the production UI. Temporary
display-rotation locks were restored to the original `free` setting.

## Restart and preservation checks

Force-stopped and cold-launched the final APK. The last newly created fixture
restored Paused with its segment visibly marked Discarded, zero retained duration
and `0/100 kept`. Review selection/player were not restored. Tapping Keep through
the real review interface restored `1/100 kept` and persisted `kept: true` in
version 2 JSON. Closed review and tapped STOP: the session became Completed,
retained its MP4 and metadata, and displayed the separate-files limitation.
The app was left on that Completed screen with preview available.

Checksums taken before installation and after all tests confirmed **all 31
pre-existing session files, including recordings and metadata, were unchanged
byte-for-byte**. Test MP4 checksums also remained unchanged after Keep/Discard
and STOP. No recorded files or existing sessions were deleted or overwritten.
Only newly created test fixtures were completed during retries and cleanup.

Retained 12 newly created test sessions containing 27 MP4 files, including the
expected unregistered camera-source-loss candidate from the regression suite.
All 27 files contained video and audio according to `ffprobe` and decoded through
`ffmpeg -v error -i FILE -f null -` without errors. Copies in `/tmp` were used
only for inspection. Deliberately corrupt fixtures live separately under
`files/validation-m3/` and are not recorded segments.

## Corrections and remaining manual follow-ups

- Screenshot inspection exposed low-contrast inherited text and clipped review
  actions in landscape. Explicit content colors, flexible video height and compact
  native playback controls corrected both; final screenshots were inspected.
- An intermediate thumbnail-readiness assertion failed because label/action
  semantics were split across accessibility nodes. Remaining tests in that run
  skipped to preserve its open fixture. Unified thumbnail button semantics and
  fresh accessibility queries corrected this; the final enhanced suite passed.
- The API 26 thumbnail fallback, other devices/tablets, extreme text scaling and
  full TalkBack spoken navigation remain manual follow-ups. Current-device
  accessibility labels, decoding readiness, scrolling and touch bounds were tested.
- Physical rotation sensing retains Milestone 2 behavior. Tests verify both
  display layouts and recording rotations but do not substitute for holding and
  rotating the phone through all physical sensor directions.
- Long-duration/large-history memory testing, external-app audio-focus conflicts
  and subjective speaker/headphone listening remain manual follow-ups. Playback
  and post-review recording pipelines were exercised with real media.
- Missing media is never silently discarded. Its retained status follows the
  user's decision; unreadable items can still be marked Discarded without deletion.
- Review is limited to the current Paused session. No completed-session library,
  trimming, arranging, combining, export, cloud or backend features were added.
