# M15B.3.2 — Export publication and Retry hardening

Branch: `feature/m15b3-field-validation`. Reviewed starting point: `8cb88d1`;
main baseline: `c242500`. This is engineering hardening, not completion of
the [M15B field matrix](M15B_FIELD_TEST_MATRIX.md).

## Root causes and changes

| Area | Root cause | Correction |
|---|---|---|
| Publication identity and provider uncertainty | Null/empty lookups could lead to insertion; missing visibility information defaulted to pending, permitting a destructive `rwt` open. The insert and private URI commit are separate operations. | Explicit absent, pending, published and unknown results. A single pending/trashed-inclusive lookup checks URI ID, deterministic job name, path and owner; without a URI, the job marker is also required. Sync intent before insertion, recover the same pending row by identity if the process dies before saving the URI, and never insert again on an inconclusive result. |
| Pending/visible recovery | A resume always copied the private file again, even when the visible row was already the verified result. A verified digest could be discarded/replaced after publication began. | Inspect a visible row only through read-only descriptors and reconcile `Completed` only on the existing digest. A pending row is also checked read-only first. If incomplete, compare its existing prefix against verified output and append only the missing bytes, using a non-truncating descriptor and append mode. If the pending prefix is inconsistent, stop rather than replace it. Sync pending bytes, read back SHA-256 before visibility, and confirm final visibility. Keep the original verified digest authoritative once publication starts. |
| Passthrough production | The integrity-only fallback applied after production. `selectedPath` stayed fast when Media3 rejected that path before producing output. | Check the actual encoded AVC format at Media3's asset-loader boundary, including compatible initialization data across clips. Treat only a specific encoded AVC track-argument rejection at the unchanged muxer as a second production incompatibility signal. Persist `RENDER` and clear the old candidate identity before attempting fallback. Fast-candidate integrity failures retain the bounded fallback; decoder, generic I/O, cancellation and storage failures do not trigger it. |
| Failure classification | The fallback render ran inside the original `catch` and escaped verification failure journaling. MediaCodec factory I/O escaped the codec boundary. | Initial, fallback and Retry verification share one failure-recording path. Only `IOException` from codec creation gets decoder classification; unrelated extractor/file I/O does not. A watchdog timeout when the decoder produces no output is a decoder failure, not proof of a vendor cause. Continue to release the codec and extractor independently. |
| Reuse storage | An already-present candidate was charged for two new outputs. | Candidate re-check charges one remaining Gallery copy using the actual candidate size plus the 100 MiB reserve. Check again at publication; rebuilding uses the original full production estimate. A matching existing row needs no second copy or additional disk budget. |

The service still runs one export worker at a time. Publication is serialized
within the app; cancellation is committed before the publishing transition,
not accepted from a stale cancellation button after publishing begins. The full
sequential AVC/AAC decode, format/duration/timestamp checks, 100 ms A/V bounds,
original-path checks, source hashes, synced candidate SHA-256 identity and
durable one-recheck allowance remain in force. No original deletion, retention
policy, quality setting, dependency, or production decoder-selection change.

## Files

- Publication and storage: `GalleryPublisher.kt`, `PublicationRecovery.kt`,
  `ExportStorage.kt`, `ExportJournal.kt`.
- Processing and Retry: `ExportService.kt`, `ExportScreen.kt`,
  `ExportProcessor.kt`, `ExportMedia.kt`, `PassthroughProduction.kt`,
  `LeadingAudioGapFactory.kt`; isolated injections in `ExportFaults.kt`.
- Validation: `PublicationRecoveryTest`, `PublicationHardeningDeviceTest`,
  `RetryHardeningDeviceTest`, updated `ExportPathsDeviceTest` and
  `ExportRecoveryFixtures`, `tools/m15b32_export_hardening.sh`.

## Reproduction

The host gate is the checked-in Android quality workflow's task set:

```sh
./gradlew --no-daemon --console=plain --continue \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Use a verified device serial and only the isolated
`com.calvinmazhindu.skitcam.m15b32.m12bvalidation` app and its test APK.
The runner verifies the package IDs and the staged fixture hashes, scopes
force-stop to that validation app, and checks JUnit verdicts rather than just
ADB exit status. It does not clear the app or sweep MediaStore. Use a new
`RUN` label to leave existing logs and driver checkpoints alone.

```sh
export SERIAL='your-validation-device'
export RUN=first
bash tools/m15b32_export_hardening.sh build
bash tools/m15b32_export_hardening.sh install
bash tools/m15b32_export_hardening.sh stage
bash tools/m15b32_export_hardening.sh publication
bash tools/m15b32_export_hardening.sh retry
bash tools/m15b32_export_hardening.sh regressions
```

Defaults stage the disposable M15B.3.1A short input and M15B.3/M15B.3.1B
regression fixtures. Override `SHORT_SOURCE`, `MARKER_INPUTS`,
`GOLDEN_EXPORT` and `DERIVED_INPUTS` with equivalent fixture paths when
needed; the golden must meet the direct-copy contract. Keep APKs, local
logs and disposable media under the ignored `.validation/` directory.

## Validation scope

- `PublicationRecoveryTest`: successful empty versus unknown lookup; ownership,
  row identity and visibility proof; matching and mismatching published rows;
  exact permitted provider actions; codec-creation I/O and capacity arithmetic.
- `PublicationHardeningDeviceTest`: kill before insertion, after insertion
  before URI commit, during write, before visibility and after visibility;
  kill twice in the same job. Also exercise empty after insert intent, null,
  empty, throwing and ambiguous lookup with an existing publication, a changed
  pending prefix and a bad published digest. Final phases check row count and
  visibility, journal state and attempts, SHA-256 and untouched originals.
  Matching full pending and visible rows are recovered with the private
  candidate removed, the capacity stub exhausted, and any writable open forbidden.
- `RetryHardeningDeviceTest`: actual Media3 callback with incompatible encoded
  format, persistent renderer failure, cancellation, and death following
  the durable path transition. Inject raw `IOException` at codec creation
  and extractor input, and exercise the verifier's duration gate after render
  fallback. Four capacity tests cover exact one-copy budget, too little for
  publication, space exhaustion after verification, and invalidated candidate
  with budget below the renderer requirement.
- Regression groups: `ExportPathsDeviceTest`, `ExportJournalDeviceTest`,
  `SessionFileStoreRecoveryDeviceTest`, `ExportRecoveryDeviceTest`,
  `ProcessDeathRecoveryDeviceTest`, and Gallery/camera navigation.

The delivery validation APK pair was built for the Moto G54 5G on
Android 15/API 35 (`V1TDS35H.83-20-5-8-9`):

| Artifact | SHA-256 |
|---|---|
| Isolated app APK | `c67f4ee903a429476e57e1996ce17dc585582e09c093f69d1bae5117fca49d7c` |
| Instrumentation APK | `7e883e3164825fac62feaecd66f4194b978a9e3def3ce4d46ee24c49858f3968` |

### Host quality gates

The full CI-equivalent command passed on the delivered source
(`.validation/m15b32/quality-delivery.log`): **307 JVM tests**, no failures,
errors or skips; **0 lint errors, 46 warnings** (the baseline warning count);
and successful debug and instrumentation APK builds. The six existing Python
host tests passed. `bash -n` and `git diff --check` passed.

### Moto publication recovery

**10/10 final JUnit cases passed**, with 11 expected `Process crashed` verdicts
at deliberate kill points. Logs are under `.validation/m15b32/delivery/`
(`pub-*.log`; `publication.summary.log`).

| Case | Observed result |
|---|---|
| Before insertion | No row or insert intent in the interrupted journal; Retry saved one matching visible row. |
| After insertion, before URI commit | One pending row and no journal URI; Retry found the row by identity and published that same URI. |
| During copy | The row held a nonempty, incomplete prefix; Retry appended the verified remainder and published exactly that row. |
| Full pending copy before visibility | With the private candidate removed and writes forbidden, Retry synced and read back the existing bytes, then made the row visible. |
| After visibility, before `Completed` | With the private candidate removed and writes forbidden, Retry reconciled the same row read-only. Concurrent/repeated Retry requests left one row. |
| Repeated process death | Killed after insertion and again while resuming the write. The third process recovered the original row and digest without re-rendering. |
| After durable insert intent, before the provider call | Empty lookup was inconclusive. Two retries stopped without insertion or loss of the verified output. |
| Null, empty, throwing and ambiguous lookups | Every faulty lookup stopped safely with the same visible URI and digest; the next valid lookup completed read-only. |
| Mismatching visible digest | Two retries failed read-only. The existing row and its deliberately modified fixture bytes were unchanged. |
| Mismatching pending prefix | Retry neither replaced the bytes nor made the row visible. |

An independent host SHA-256 of the private candidate from the
post-insert recovery case matched its `Completed` journal digest. FFmpeg
decoded both tracks end to end: 75 AVC frames at 1920×1080 and 119 AAC
frames at 48 kHz stereo. The on-device assertion independently read the
actual Gallery URI and matched it to the same verified digest.

The ordinary, trimmed/reordered and retried fixture **Gallery readbacks**
were also pulled from the isolated app, rehashed on the host against their
journals, and fully decoded on both tracks with FFmpeg (`-xerror`, passthrough
timestamps). All three passed.

### Moto Retry, classification and storage

**12/12 final JUnit cases passed**, including recovery from one deliberate
kill immediately after persisting the fallback selection
(`retry.summary.log` and per-method logs).

| Cases | Observed result |
|---|---|
| Passthrough runtime AVC rejection | The same job completed on `RENDER`: two production attempts, one full verification, and one exact publication. At the path-transition kill there was no candidate or row. |
| Persistent renderer I/O, path-transition death and cancellation | Retry never returned to the known-incompatible fast path. Cancellation did not trigger fallback. Recovery after the kill performed just the renderer attempt. |
| Codec creation, extractor I/O and wrong duration | Raw codec-open I/O was recorded as `decoder` on both paths; the candidate was rechecked without a new production attempt. Fallback verification recorded extractor I/O as `other` and the wrong duration as `integrity`; each caused a new production attempt on Retry. |
| Four capacity cases | Exact one-copy budget allowed reuse, one byte under it refused without consuming the recheck, a budget drop after verification refused before any insertion, and invalidating a candidate restored the full rebuild requirement. Failure cases left originals intact and no row. |

### Moto regressions and navigation

| Suite | Result |
|---|---:|
| `ExportPathsDeviceTest` | 14/14 |
| `ExportJournalDeviceTest` | 5/5 |
| `SessionFileStoreRecoveryDeviceTest` | 7/7 |
| `ExportRecoveryDeviceTest` | 11/11 |
| `ProcessDeathRecoveryDeviceTest` | 3/3 recovery phases; all three first phases killed as intended |
| `ExportDeviceTest#stopAutomaticallyExportsAndPublishesTwoSegments` | Passed; exact Gallery item, playback advancement, share chooser and return to streaming camera |
| `GalleryDeviceTest#olderVideoCanBeSelectedPlayedAndReturnedToBrowser` | Passed; correct older URI, playback, return and activity recreation |

The delivery run totals **64 passing device tests** plus **15 deliberately
killed phases**; no delivery cases failed or skipped. Logs include
`regressions.summary.log`, `camera-gallery.log` and `gallery-older.log`.

## Boundaries and limitations

- A death after syncing insert intent but before the provider commits (or returns
  proof of absence) is intrinsically ambiguous. An empty result after intent is
  not permission to insert again. The job can remain blocked, with the verified
  output and originals intact. Older journals that might already have inserted
  a row are treated the same way when no owned row is discoverable.
- MediaStore and the private journal do not share a transaction. The app
  serializes its own writer and rechecks identity and pending state immediately
  before any writable open and again before append. Android does not offer an
  atomic “open only while pending” call against another privileged actor.
  This app never requests truncation or replacement on the MediaStore path.
- The deterministic cases use app-process death, not sudden power loss or an
  OEM provider crash. The storage tests use isolated capacity injection, not
  physical shared-volume exhaustion. The API 26–28 legacy scanner path and the
  API 29 pending-query branch require separate device validation.
- The intermittent MediaTek AVC failure remains **unresolved**. M15B.3.1B did
  not reproduce it in targeted compatibility trials; this milestone does not
  change codec selection or establish its frequency.
- Repeated real captures in both orientations, long takes, reliable physical
  sync markers, microphone source and reconnect testing, second-player hearing
  checks, supported 4K, another chipset, and the export-wait budget remain open.
  Do not treat this report as release or unattended-export sign-off.

## Device discovery during validation

The Moto creates the pending MediaStore row before necessarily creating its
backing file. The first on-device direct-copy smoke test caught an `ENOENT`
from the read-only probe of that new pending row. A missing backing file is
now treated as “this confirmed pending row has no complete copy”; the app
rechecks the exact row before opening it for append. It is **not** treated as
an absent row, and does not authorize another insert. The delivered publisher
uses MediaStore's `wa` mode after a read-only prefix check; it does not use
`rwt` or the API-30-only `Os.fcntlInt` call that lint caught in an intermediate
build. The corrected direct-copy smoke test passed. The initial failed runs
and their disposable evidence were kept separate; no package data or Gallery
rows were cleared between validation APK updates.
