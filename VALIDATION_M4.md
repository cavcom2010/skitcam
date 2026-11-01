# Milestone 4 validation — 2026-11-01

## Result and design

Implemented on `feature/segment-arrangement`, branched from committed Milestone 3
`980638e`. Version 0.4.0 (4) retains minimum SDK 26 and compile/target SDK 36.
No dependencies were added. Existing CameraX state transitions, recording
orientation policy, permissions, preview and Media3 ownership were preserved.

Arrange is available only while Paused with at least two retained segments.
The same-activity overlay keeps CameraX bound. It provides numbered retained
items, lazy thumbnails, duration, long-press drag handles, edge auto-scroll,
Move up/down buttons and accessibility actions. Preview reuses the existing
player without autoplay or decision controls. Done atomically saves; Cancel or
Back leaves saved metadata untouched. Pending edits remain in a ViewModel through
backgrounding/recreation, but are intentionally lost on process death.

Metadata version 3 separates immutable segment IDs, recording chronology and
playback order. Version 1/2 metadata loads deterministic IDs and chronological
retained order, upgrading only on a subsequent save. Discard removes an ID from
playback order; restoring or recording a new segment appends it. Existing items
retain their relative order. Invalid, duplicate, foreign, stale or discarded
IDs cannot be saved as an arrangement. Repository failure preserves the previous
order and Paused state. The 100-retained-segment cap is unchanged.

**STOP retains all metadata, saved playback order and individual MP4 files.
Assembly, combining and export are not implemented.** No original MP4 is deleted
or rewritten by arrangement or Keep/Discard actions.

## Automated validation

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-daemon
```

- 43 JVM tests pass: 22 recording-engine, 2 orientation, 8 decision/codec and
  11 arrangement tests. No failures or skips.
- Arrangement cases cover permutations, persistence/recovery, cancellation,
  deterministic legacy migration, invalid transitions/identities, stale edits,
  write failure, Keep/Discard/restore, append behavior, no-op saves and the cap.
- APK and instrumentation APK build successfully using the existing Wrapper,
  SDK and JDK 17. Final build completed successfully in 2m 1s.
- Final lint: **0 errors, 20 warnings**, matching the existing milestone warning
  count. No suppressions or error baseline were added. Warnings concern pinned
  dependency updates, existing backup/storage guidance and style recommendations.
- Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
- SHA-256: `a147203c9f6ab3c5ec539c9d7dfb4ba63734fe688c4df09c9eed201c6878c402`.
- `apksigner verify --verbose`: APK Signature Scheme v2 verified.
- The final change after hardware validation only reorders a private Composable's
  named parameters to satisfy lint; behavior is unchanged. JVM tests/build/lint
  were rerun, and the resulting APK was installed with data preserved.

## Motorola Moto G54 5G

Device `ZY22KJQLTV`, Android API 35. App/test APKs installed with `adb install -r`;
app data was never cleared. Complete passing runs:

- Recording regressions: **OK (6 tests)**, 26.701 seconds.
- PAUSE and arrangement: **OK (8 tests)**, 130.201 seconds, including all four
  new arrangement tests. No failures or skips in these final runs.

| Check | Observed result |
| --- | --- |
| Portrait drag | Real injected touchscreen long press and movement changed draft order |
| Cancel | Reopened editor with original saved order; metadata bytes unchanged |
| Done | Saved ID permutation; chronology, identity, paths and counts unchanged |
| Landscape drag / edge auto-scroll | Moved Segment 1 beyond the initial viewport to position 8 |
| Read-only preview | Real Media3 decoding and Play worked; Back to Arrange preserved draft |
| Recording after arranging | RESUME/STOP produced valid audio/video in both orientations |
| Camera continuity | CameraX video use case remained bound during editor/review |
| Background / recreation | Preserved draft, closed playback, returned with camera ready |
| Keep / Discard / restore | Removed discarded ID; restored it at the end without disturbing peers |
| External metadata edit | Stale editor disabled saving and requested Cancel/reopen |
| Missing/corrupt media regression | Thumbnail fallback/local playback errors left recording state intact |

Tests inject known recording poses, as in earlier milestones, while using real
CameraX, microphone and storage. Temporary display rotation locks are restored;
they do not alter production orientation enforcement. Native accessibility clicks
exercise Preview, Done, Cancel and up/down controls. Dragging uses actual
MotionEvents, including a hold at the viewport edge. Portrait preview, portrait
drag and landscape auto-scroll screenshots were inspected for layout/readability.
The final APK installed successfully and cold-launched with status `ok`. The
phone's original `free` display-rotation setting was restored explicitly after
the interrupted attempts left a temporary lock.

## Real process restart and preservation

The newly created fixture `b08e1e06-1fdc-4e46-a689-f52f13a82c36` had committed
playback order `[3, 1, 2]`. Opened Arrange and changed the tentative order to
`[1, 3, 2]`, then force-stopped while the dirty editor was open. Cold launch
returned to Paused; reopening Arrange showed `[3, 1, 2]`. Persisted metadata was
byte-identical before/after process death. Cancel and STOP completed that fixture
with order `[3, 1, 2]` and all three source files retained.

Before/after SHA-256 inventories confirm **all 70 pre-existing session files,
including metadata and MP4s, remain byte-identical**. Only newly created test
fixtures were completed during retries/cleanup. No existing session was removed
or rewritten. New test recordings and failed candidates remain on the device.

Copied only the 23 newly created test-session directories to `/tmp` for inspection.
All 62 registered MP4s contain audio and video tracks according to `ffprobe` and
decode with `ffmpeg -v error -i FILE -f null -` without errors. Two unregistered
source-loss candidates remain separate from the registered segment inventory.

## Intermediate errors and manual follow-ups

- An initial legacy recording test hit CameraX finalisation error 8 (no valid
  data). Its metadata was retained and the candidate was not registered. The harness now
  saves/temporarily locks/restores display rotation, matching the newer suites;
  final recording regressions passed. Production orientation policy is unchanged.
- USB reconnected during two host-attached instrumentation attempts. Android's
  UiAutomation connection died, causing UI assertions to fail and subsequent
  tests to skip under the existing-session preservation guard. Retained and
  completed only those new fixtures, then ran instrumentation locally on the
  phone with its output saved under `/data/local/tmp/`. Both final suites passed.
- API 26, other phones/tablets, extreme font sizes, full TalkBack spoken
  navigation, upward edge-scroll stress and long histories remain manual
  follow-ups. The 100-item boundary is covered in JVM tests; the largest native
  arrangement gesture fixture used eight retained segments.
- Physical orientation sensing is inherited from Milestone 2. Display-layout
  tests do not replace rotating the phone through every physical direction.
- Playback and recorded audio tracks were exercised, but subjective speaker/
  headphone listening and external-app audio-focus conflicts remain follow-ups.
- Missing/corrupt media retains its metadata status and can still be reordered;
  it shows the inherited fallback/error. No media is silently discarded.
- Arrangement applies only to the current Paused session. There is no completed
  session library, trimming, advanced editing, final assembly or export.
