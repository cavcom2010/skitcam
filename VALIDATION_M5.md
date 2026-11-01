# Milestone 5 validation — 2026-11-01

## Implementation and status

Work is on `feature/non-destructive-trimming`, based on committed Milestone 4
`33eccf2`. No automatic commit or push. App version is 0.5.0 (5); SDK levels,
recording state transitions, orientation policy and dependency pins are unchanged.

Trim is available from a completed segment's PAUSE review, including discarded
segments. It opens a same-activity overlay over the bound camera. Portrait uses
scrollable controls below video; landscape keeps video beside scrollable controls.
The timeline has eight asynchronously sampled frames, independently draggable
boundaries, excluded-area shading, a playhead, 1–64× zoom, position scrolling and
Show start/end controls. Integer timestamp entry and ±100 ms buttons supplement
dragging. Controls have accessibility labels and standard touch targets.

Save writes one atomic metadata transaction; Cancel/Back writes nothing. Reset
changes the draft only. Both closing actions return to PAUSE; reopen review to
see the saved interval. Drafts survive activity recreation/backgrounding in a
ViewModel, while player resources are released. Process death drops drafts.
Saved trims persist through Keep/Discard/Restore, arrangement and STOP. The
100-retained-segment cap and independent playback order remain unchanged.

No source MP4 is rewritten, rendered or deleted. No final MP4 is produced.

## Architecture and metadata contract

- `trim/TrimRange.kt`: pure validation, boundary helpers, overflow-safe totals,
  native source facts and verified presentation-duration evidence.
- `trim/TrimViewModel.kt`: transient draft, loading/save state, stale-callback
  protection and explicit repair of malformed trim metadata.
- `trim/TrimScreen.kt`: timeline, bounded frame extraction, player preparation,
  gestures, fine controls and responsive editor.
- The existing recording worker validates edits and rechecks source availability,
  tracks, duration and size/mtime identity before `SessionFileStore` atomic save.
  Failures leave the previous session and recording state intact.
- The existing review controller owns the sole player; normal review and Arrange
  preview also honor saved trims. Recording closes editor/player resources first.
- ThumbnailLoader accepts sample times; Trim uses its own 2 MiB cache and one
  extraction permit. Eight visible samples are requested; queued work cancels on
  viewport changes, backgrounding or closure. No full video is loaded into memory.

Metadata version 4 preserves version-3 IDs, chronology, paths, orientation,
Keep/Discard status and playback order. A segment gains `trim`, either null
(unresolved) or:

```json
{
  "sourceDurationMs": 4000,
  "trimStartMs": 300,
  "trimEndMs": 3500
}
```

All three values are integer Long milliseconds, using source presentation time
with an **exclusive end**. Effective duration is the difference; it is not stored
redundantly. Native post-finalisation inspection initializes a new segment to
its full source. CameraX's historical `durationMs` is preserved independently.
Trim prepares an unclipped Media3 timeline, requires a finite positive seekable
duration and captures native source identity. Saved ranges must match that
observed presentation duration. Save rechecks native identity and playable tracks.

Rules: nonnegative start, increasing endpoints, end within the verified source,
normally at least 500 ms retained. A source shorter than 500 ms may keep only
its full range. Values exceeding safe millisecond-to-microsecond conversion are
rejected. JSON fractions, strings, inverted and partial ranges are invalid.

Versions 1–3 load unresolved/full-source intent without probing or rewriting the
session. Legacy duration displays are labelled estimates until verified through
Trim. Invalid v4 trim values are retained verbatim as JSON values through unrelated
metadata saves, excluded from authoritative effective duration, and flagged in
review. Reset followed by Save is required to replace malformed values. No automatic
migration clamps or overwrites user decisions. Other valid segments remain usable.

A changed observed duration updates only the editor's source facts and shows a
notice. Full-source intent adopts the observed duration in the draft only. Existing user-cut boundaries remain unchanged; an out-of-bounds range cannot Save
until adjusted or Reset. Missing/corrupt/unseekable/unknown-duration media cannot
be trimmed. Size/mtime plus reinspection detects ordinary replacement; this is
not a cryptographic identity guarantee against a file deliberately replaced with
identical size, timestamp and media facts.

## Local validation

JVM tests: **62 passed**, no failures/errors/skips: 22 engine, 2 orientation,
8 Keep/Discard/codec, 11 arrangement and 19 trim tests.

New deterministic tests cover defaults, effective duration, minimum duration,
short sources, invalid/inverted/overflow values, boundary clamping, persistence,
restart/STOP, non-persisting drafts/reset, multiple trims, arrangement/decision
preservation, stale IDs, source replacement/unavailability, write failure,
recording-state restrictions, versions 1–3, malformed trim round trips, explicit
duration reconciliation and rejection of unverified duration changes.

Final complete application check (followed by a successful test-only rebuild/JVM/lint run after strengthening the landscape assertion):

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-daemon
```

**BUILD SUCCESSFUL in 1m 41s.** Debug and instrumentation APKs built. Lint:
**0 errors, 20 warnings**, matching the inherited milestone warning count.
APK Signature Scheme v2 verified using `apksigner verify --verbose`.
APK: `app/build/outputs/apk/debug/app-debug.apk`.
SHA-256: `a2f030e5884d061e6410af81d814b93357a5f611fddb45df8a1d4d312f0f5232`.
No lint suppression or baseline was added. Intermediate lint identified missing
Media3 UnstableApi opt-in and an indentation diagnostic; these were addressed.

## Physical-device validation

Motorola Moto G54 5G, serial `ZY22KJQLTV`, Android API 35. APK and test APK
installed using `adb install -r`; app data was never cleared. Initial USB transfers
failed intermittently (`device not found`, `unauthorized`, `connect failed: closed`).
The connection stabilized after the user reconnected it, and both installs succeeded.
Instrumentation runs locally on the phone with logs under `/data/local/tmp/`, so
host USB interruptions cannot terminate its UI-automation command.

Final corrected Trim suite: **OK (4 tests)** in 59.564 seconds, no skips/failures.
Final recording/PAUSE/arrangement regressions: **OK (14 tests)** in 145.98 seconds,
no skips/failures. Thus all 18 unique hardware test cases passed against the final
APK across the two runs. A subsequent stronger landscape test also passed:
**OK (1 test)** in 19.636 seconds. It waits for a rendered timeline after activity
recreation and exercises Zoom in/out, timeline position availability and Show
start/end before taking the final landscape screenshot. Portrait and landscape
screenshots were inspected. Some asynchronously extracted frames were still
loading at capture; the test sources show a dark scene.

| Scenario | Observed result |
| --- | --- |
| Portrait handles | Real injected touchscreen movements changed both start and end |
| Selected playback | Media3 played audio/video and reached clip end within the tested position tolerance |
| Save / reopen | Restored the selected boundaries and effective duration |
| Cancel / Reset | Cancel discarded changes; Reset selected full source only until Save |
| Multiple segments | Saved separate trims; arrangement and Discard/Restore preserved them |
| Recording continuity | RESUME/STOP after trimming produced valid native audio/video tracks |
| Landscape | Side-by-side video/controls, draft survival through background/recreation, saved Reset and subsequent recording |
| Missing/corrupt media | Error with Save disabled; recording snapshot unchanged |
| Interrupted atomic write | Partial uncommitted metadata did not replace the previous complete session |
| File integrity during edits | SHA-256 of test sources unchanged through trimming, decisions and arrangement |

The hardware tests inject actual MotionEvents and accessibility actions on the
physical phone. Recording poses are known inputs; this is not human finger testing
or physical orientation-sensor validation. Test recordings and diagnostic fixtures
are retained. The test cleanup completes only fixtures it created.

### Preservation

Before/after SHA-256 inventories confirm **all 157 pre-existing session files,
including original MP4s and metadata, are byte-identical**. No recordings were
removed or rewritten. Newly created test fixtures and failed candidates remain
on the device; cleanup only completed owned sessions through STOP.

Six selected new registered MP4s (two trimmed sources, post-trim resumed recordings
in portrait/landscape, and the restart fixture) were copied to `/tmp` only. All six
contained audio/video tracks according to `ffprobe` and decoded through
`ffmpeg -v error -i FILE -f null -` without errors. App registration also checks
native audio/video samples for every successful segment. Test display rotation
was confirmed restored to `free`. The final APK cold-launched with status `ok`
and was left on its Completed screen with camera available.

### Real process death

New test fixture `cba4318f-e1ec-4bb1-aa4b-ad3f30ed2fdb` saved `[300, 3141)` ms.
Force-stop/cold launch recovered Paused, a Trimmed indicator and that exact range.
Used the real +100 ms control to make a dirty `[400, 3141)` draft, then force-stopped
with the editor open. Metadata bytes remained identical. Cold launch/reopening
Trim showed `[300, 3141)` again. Cancel and STOP retained the saved range and MP4
in Completed metadata. The test does not claim a kill within a particular CPU
instruction immediately after filesystem commit; atomic rollback is tested separately.

### Corrections discovered through hardware validation

- Native MP4 duration and Media3 presentation duration differed by 1 ms on a real
  clip. Full-source intent now resolves to the observed full range in the draft;
  saved user cuts remain exact. Persistence still requires Save. A pure regression
  covers this policy and out-of-bounds user cuts.
- A right-boundary swipe opened Motorola's edge sidebar. Insets now keep timeline
  handles away from the screen edge; the corrected test verifies both handles move.
  No Motorola settings were changed.
- The landscape harness retained an old Activity's player reference after rotation.
  Rebinding the test models corrected that assertion. Temporary stack diagnostics
  were removed; actual playback errors retain a diagnostic log.
- Initial M5 device run had three Trim failures; an intermediate retry had two.
  Their newly created fixtures were retained and completed safely. The corrected
  four-test suite passed. Original recording/PAUSE/arrangement regressions passed
  throughout those initial runs.

## Remaining limitations and manual follow-ups

- Physical sensor directions, human finger use, TalkBack spoken navigation, extreme
  font sizes, API 26 fallback, very long videos, memory-pressure stress and unusual
  presentation timestamps remain manual follow-ups.
- Real Media3 playback and native audio/video sample validation passed. Subjective
  listening and external-app audio-focus conflicts remain unverified.
- Preview uses Media3 clipping and exact-seek requests, not a verified frame-cut
  engine. Decoder preroll, audio sample boundaries, rendering latency and sync-frame
  thumbnail selection limit visual precision. No frame-accuracy guarantee.
- Thumbnail work cancels between native extraction calls; an executing native frame
  extraction cannot be forcibly interrupted.
- Current-session PAUSE editing only; no completed-session library or final export.

## Milestone 6 handoff

1. Validate saved playback order as an exact permutation of retained segment IDs.
   Resolve in that order and exclude Discarded entries; chronology is independent.
2. Require readable sources and validated, verified trim metadata. Resolve legacy
   full-source intent before export; malformed/missing entries require user repair.
   Do not silently drop an invalid retained segment or change its chosen interval.
3. Apply `[trimStartMs, trimEndMs)` in the normalized source presentation timeline;
   convert milliseconds to microseconds with checked arithmetic. Re-probe sources
   and reject unexplained discrepancies before starting output.
4. Preserve relative audio/video timestamps with a common segment offset and an
   accumulated output timeline. Do not normalize each track independently. Handle
   gaps/edit lists explicitly; validate synchronization after rendering.
5. Inspect encoded orientation, resolution, frame rate/time base, video codec,
   audio codec/sample rate/channel layout and track properties. Choose an explicit
   normalization/re-encoding policy where sources differ. Arbitrary trim boundaries
   may require decoding/re-encoding; metadata is not a promise of lossless cutting.
6. Write a separate output artifact. Verify decodability, complete tracks, duration,
   order, selected intervals, audio/video sync and orientation before reporting
   success. Only a future explicit cleanup policy may make originals eligible for
   deletion; M5 grants no cleanup permission.

The metadata interface is ready for Milestone 6 design. Final assembly and its
output verification still require a separate implementation and validation milestone.
