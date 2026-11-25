#!/usr/bin/env bash
# Runs only against the fresh, isolated M15B.3.2 validation package on Android 11+.
set -euo pipefail
root=.validation/m15b32
run=${RUN:-first}
[[ "$run" =~ ^[A-Za-z0-9-]+$ ]]
suffix=.m15b32.m12bvalidation
pkg=com.calvinmazhindu.skitcam$suffix
runner=$pkg.test/androidx.test.runner.AndroidJUnitRunner
base=com.calvinmazhindu.skitcam
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/.android-sdk}}
adb=("${ADB:-adb}" -s "${SERIAL:?Set SERIAL to the validation device}")
logs=$root/$run
mkdir -p "$logs"

build() {
    ./gradlew --console=plain -PvalidationSuffix="$suffix" \
        :app:assembleDebug :app:assembleDebugAndroidTest > "$logs/build.log" 2>&1
    cp app/build/outputs/apk/debug/app-debug.apk "$root/app.apk"
    cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk "$root/test.apk"
    sha256sum "$root/"{app,test}.apk
}

install_packages() {
    local replace=${1:-false}
    for variant in app test; do
        local expected=$pkg
        [[ "$variant" != test ]] || expected=$pkg.test
        "$sdk/build-tools/35.0.0/aapt" dump badging "$root/$variant.apk" > "$logs/$variant.badging"
        grep -Fq "package: name='$expected'" "$logs/$variant.badging"
        local packages
        packages=$("${adb[@]}" shell pm list packages --user 0 "$expected" | tr -d '\r')
        if [[ "$replace" == false ]] && grep -Fxq "package:$expected" <<< "$packages"; then
            printf 'Refusing to replace already installed %s\n' "$expected" >&2
            return 1
        fi
    done
    if [[ "$replace" == true ]]; then
        # Explicitly update only these validation APKs, without clearing their evidence.
        "${adb[@]}" shell am force-stop --user 0 "$pkg"
        "${adb[@]}" install -r --user 0 "$root/app.apk"
        "${adb[@]}" install -r --user 0 "$root/test.apk"
    else
        "${adb[@]}" install --user 0 "$root/app.apk"
        "${adb[@]}" install --user 0 "$root/test.apk"
    fi
}

stage_file() {
    local from=$1 to="files/$2" expected actual
    expected=$(sha256sum "$from" | cut -d ' ' -f 1)
    if ! "${adb[@]}" shell run-as "$pkg" test -f "$to"; then
        "${adb[@]}" shell run-as "$pkg" mkdir -p "${to%/*}"
        "${adb[@]}" shell -T "run-as $pkg sh -c 'set -C; cat > $to'" < "$from"
    fi
    actual=$("${adb[@]}" shell run-as "$pkg" sha256sum "$to" | cut -d ' ' -f 1)
    [[ "$expected" == "$actual" ]]
}

stage() {
    local short=${SHORT_SOURCE:-.validation/m15b31a/inputs/segment_001.mp4}
    local probes=${MARKER_INPUTS:-.validation/m15b3/timing-inputs}
    local golden=${GOLDEN_EXPORT:-.validation/m15b3/final-readbacks/ordinary.mp4}
    local derived=${DERIVED_INPUTS:-.validation/m15b31b/paths}
    stage_file "$short" validation-m15b31a-inputs/segment_001.mp4
    stage_file "$golden" validation-m12b-inputs/golden.mp4
    local variant clip fixture
    for variant in zero offset; do
        for clip in segment_001.mp4 segment_002.mp4; do
            stage_file "$probes/$variant/$clip" "validation-m12b-inputs/$variant/$clip"
        done
    done
    for fixture in bframes different vfr; do
        stage_file "$derived/$fixture.mp4" "validation-m12b-inputs/$fixture.mp4"
    done
}

instrument() {
    local label=$1 expected=$2 class=$3
    shift 3
    local log="$logs/$label.log"
    [[ ! -e "$log" ]] || { printf 'Log already exists: %s\n' "$log" >&2; return 1; }
    "${adb[@]}" shell am force-stop --user 0 "$pkg" || return
    "${adb[@]}" shell am instrument --user 0 -w -r -e class "$base.$class" \
        "$@" "$runner" > "$log" 2>&1 || true
    if [[ "$expected" == death ]]; then
        grep -q '^INSTRUMENTATION_RESULT: shortMsg=Process crashed' "$log" || { tail -30 "$log"; return 1; }
    else
        grep -Eq "^OK \\($expected tests?\\)" "$log" || { tail -30 "$log"; return 1; }
        if grep -Eq '^INSTRUMENTATION_STATUS_CODE: -[234]|^FAILURES!!!' "$log"; then
            tail -30 "$log"
            return 1
        fi
    fi
    printf '%s: %s\n' "$label" "$expected"
}

publication() {
    local class=export.PublicationHardeningDeviceTest label phase next status=0
    for entry in before,BEFORE_INSERT insert,AFTER_INSERT write,DURING_GALLERY_WRITE \
        pending,BEFORE_VISIBLE visible,AFTER_VISIBLE repeated,AFTER_INSERT intent,AFTER_INSERT_INTENT \
        uncertainty,AFTER_VISIBLE mismatch,AFTER_VISIBLE prefix,DURING_GALLERY_WRITE; do
        label=${entry%,*}; phase=KILL_${entry#*,}
        instrument "pub-$label-1" death "$class#interruptPublication" \
            -e ticket "$run-$label" -e phase "$phase" || { status=1; continue; }
        next=recoverAndRetryIdempotently
        case "$label" in
            repeated)
                instrument "pub-$label-2" death "$class#interruptRecoveryWhileWriting" \
                    -e ticket "$run-$label" || { status=1; continue; }
                phase=KILL_DURING_GALLERY_WRITE ;;
            intent) next=possibleInsertWithEmptyQueryDoesNotInsertAgain ;;
            uncertainty) next=unknownProviderResponsesDoNotTouchAnExistingVideo ;;
            mismatch) next=publishedDigestMismatchIsReadOnlyAndRepeatable ;;
            prefix) next=mismatchingPendingPrefixIsNotReplacedOrPublished ;;
        esac
        instrument "pub-$label-final" 1 "$class#$next" \
            -e ticket "$run-$label" -e phase "$phase" || status=1
    done
    return "$status"
}

retry() {
    local class=export.RetryHardeningDeviceTest method status=0
    for method in incompatibleRuntimeAvcFallsBackBeforeThereIsACandidate \
        persistentRendererIoDoesNotLoopOrReturnToPassthrough cancelAtRuntimePassthroughBoundaryDoesNotFallBack \
        codecCreationIoAfterPassthroughDoesNotRenderOnRetry fallbackVerificationRecordsCodecIoAndThenUsesTheSameCandidate \
        fallbackVerificationRecordsExtractorIoAsOtherAndRebuilds fallbackVerificationRecordsIntegrityFailureAndRebuilds \
        oneCopyBudgetAllowsReuseButNotFreshProduction notEnoughForPublicationDoesNotConsumeTheRecheck \
        spaceCanRunOutAfterVerificationButBeforePublication invalidatedCandidateNeedsAFullRebuildBudget; do
        instrument "$method" 1 "$class#$method" || status=1
    done
    if instrument fallback-death-1 death "$class#interruptAfterDurableFallbackSelection" -e ticket "$run"; then
        instrument fallback-death-2 1 "$class#recoveryUsesOnlyRendererAfterDurableTransition" -e ticket "$run" || status=1
    else status=1
    fi
    return "$status"
}

regressions() {
    local status=0 method
    instrument paths 14 export.ExportPathsDeviceTest || status=1
    instrument journals 5 export.ExportJournalDeviceTest || status=1
    instrument store 7 recording.SessionFileStoreRecoveryDeviceTest || status=1
    for method in videoDecoderInitialisationFailureRecoversWithoutRendering \
        videoDecoderFailureDuringVerificationRecoversWithoutRendering expectedVendorSelfReleaseRecoversWithoutRendering \
        audioDecoderFailureDuringVerificationRecoversWithoutRendering persistentVerificationFailureIsBoundedAndNeverPublishes \
        corruptCandidateIsRebuiltAndNeverPublished truncatedCandidateIsRebuiltAndNeverPublished \
        missingCandidateIsRebuiltAndNeverPublished durationMismatchIsAnIntegrityFailureNotADecoderFailure \
        cancellationLeavesNoPublicationAndRetryPublishesOnce completedJobDoesNotReuseCandidateWithoutRetryIntent; do
        instrument "$method" 1 "export.ExportRecoveryDeviceTest#$method" || status=1
    done
    local class=export.ProcessDeathRecoveryDeviceTest pair from to
    for pair in KillBeforeCandidateCheckpoint,BeforeCheckpointRebuildsSafely \
        KillAfterCandidateCheckpoint,AfterCheckpointReusesTheCandidate \
        KillDuringCandidateRecheck,ConsumedCandidateRebuildsSafely; do
        from=${pair%,*}; to=${pair#*,}
        if instrument "$from" death "$class#phase1$from"; then
            instrument "$to" 1 "$class#phase2$to" || status=1
        else status=1
        fi
    done
    return "$status"
}

case "${1:?Use build, install, update-validation, stage, publication, retry, regressions or instrument}" in
    build) build ;;
    install) install_packages ;;
    update-validation) install_packages true ;;
    stage) stage ;;
    publication) publication ;;
    retry) retry ;;
    regressions) regressions ;;
    instrument) shift; instrument "$@" ;;
    *) exit 2 ;;
esac
