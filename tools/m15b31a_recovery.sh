#!/usr/bin/env bash
# Isolated deterministic verification-failure suite. Never touches production media.
set -euo pipefail
root=.validation/m15b31a
suffix=.m15b31a.m12bvalidation
pkg="com.calvinmazhindu.skitcam$suffix"
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
adb=(adb -s "${SERIAL:?Set SERIAL to the validation device}")
sdk="${ANDROID_SDK_ROOT:-$HOME/.android-sdk}"
command=${1:-all}

build() {
    mkdir -p "$root"
    ./gradlew --console=plain -PvalidationSuffix="$suffix" \
        :app:assembleDebug :app:assembleDebugAndroidTest > "$root/build.log" 2>&1
    cp app/build/outputs/apk/debug/app-debug.apk "$root/app.apk"
    cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk "$root/test.apk"
    sha256sum "$root/app.apk" "$root/test.apk"
}

install_packages() {
    "${adb[@]}" get-state
    for variant in app test; do
        expected=$pkg
        if [[ "$variant" = test ]]; then expected="$pkg.test"; fi
        "$sdk/build-tools/35.0.0/aapt" dump badging "$root/$variant.apk" | sed -n '1p' |
            grep -F "package: name='$expected'"
    done
    "${adb[@]}" install -r --user 0 "$root/app.apk"
    "${adb[@]}" install -r --user 0 "$root/test.apk"
}

stage() {
    local media="$root/inputs/segment_001.mp4"
    if [[ ! -f "$media" ]]; then
        mkdir -p "$root/inputs"
        ffmpeg -nostdin -v error -n \
            -f lavfi -i "testsrc2=size=1920x1080:rate=30:duration=6" \
            -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=6" \
            -c:v libx264 -preset veryfast -g 30 -bf 0 -pix_fmt yuv420p -b:v 6M \
            -c:a aac -b:a 128k -ac 2 "$media"
        ffprobe -v error -show_entries 'stream=codec_name,width,height:stream_side_data=rotation:format=duration' \
            -of compact "$media"
    fi
    if "${adb[@]}" shell run-as "$pkg" test -f files/validation-m15b31a-inputs/.ready 2>/dev/null; then
        return
    fi
    "${adb[@]}" shell run-as "$pkg" mkdir -p files/validation-m15b31a-inputs
    "${adb[@]}" shell -T "run-as $pkg sh -c 'set -C; cat > files/validation-m15b31a-inputs/segment_001.mp4'" < "$media"
    local expected actual
    expected=$(sha256sum "$media" | cut -d ' ' -f 1)
    actual=$("${adb[@]}" shell run-as "$pkg" sha256sum files/validation-m15b31a-inputs/segment_001.mp4 | cut -d ' ' -f 1)
    [[ "$actual" = "$expected" ]]
    "${adb[@]}" shell run-as "$pkg" touch files/validation-m15b31a-inputs/.ready
}

instrument() {
    local label=$1 class=$2
    local log="$root/$label.log"
    "${adb[@]}" shell am instrument --user 0 -w -r -e class "$class" "$runner" > "$log" 2>&1 || true
    if grep -Fq 'OK (' "$log"; then
        printf '%s: pass\n' "$label"
    else
        printf '%s: see %s\n' "$label" "$log"
        grep -E 'Tests run:|OK \(|FAILURES|stack=' "$log" | head -3 || true
    fi
}

faults() {
    # Each deterministic case runs in its own process so a previous injected codec
    # fault cannot influence the next case.
    local methods=(
        videoDecoderInitialisationFailureRecoversWithoutRendering
        videoDecoderFailureDuringVerificationRecoversWithoutRendering
        expectedVendorSelfReleaseRecoversWithoutRendering
        audioDecoderFailureDuringVerificationRecoversWithoutRendering
        persistentVerificationFailureIsBoundedAndNeverPublishes
        corruptCandidateIsRebuiltAndNeverPublished
        truncatedCandidateIsRebuiltAndNeverPublished
        missingCandidateIsRebuiltAndNeverPublished
        durationMismatchIsAnIntegrityFailureNotADecoderFailure
        cancellationLeavesNoPublicationAndRetryPublishesOnce
        completedJobDoesNotReuseCandidateWithoutRetryIntent
    )
    local index=0
    for method in "${methods[@]}"; do
        index=$((index+1))
        "${adb[@]}" shell am force-stop --user 0 "$pkg"
        instrument "fault-$index-$method" "com.calvinmazhindu.skitcam.export.ExportRecoveryDeviceTest#$method"
    done
}

death() {
    # Phase one is expected to be killed; its instrumentation failure is not a test failure.
    instrument death-after-phase1 \
        "com.calvinmazhindu.skitcam.export.ProcessDeathRecoveryDeviceTest#phase1KillAfterCandidateCheckpoint"
    instrument death-after-phase2 \
        "com.calvinmazhindu.skitcam.export.ProcessDeathRecoveryDeviceTest#phase2AfterCheckpointReusesTheCandidate"
    instrument death-before-phase1 \
        "com.calvinmazhindu.skitcam.export.ProcessDeathRecoveryDeviceTest#phase1KillBeforeCandidateCheckpoint"
    instrument death-before-phase2 \
        "com.calvinmazhindu.skitcam.export.ProcessDeathRecoveryDeviceTest#phase2BeforeCheckpointRebuildsSafely"
}

case "$command" in
    build) build ;;
    install) install_packages ;;
    stage) stage ;;
    faults) faults ;;
    death) death ;;
    all)
        build; install_packages; stage; faults; death ;;
    *) printf 'Usage: %s [build|install|stage|faults|death|all]\n' "$0" >&2; exit 2 ;;
esac
