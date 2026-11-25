#!/usr/bin/env bash
# Controlled decoder trials and path regressions on an isolated app (host fixture tools only).
set -euo pipefail
root=.validation/m15b31b
suffix=.m15b31b.m12bvalidation
pkg="com.calvinmazhindu.skitcam$suffix"
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
adb=(adb -s "${SERIAL:?Set SERIAL to the validation device}")
sdk="${ANDROID_SDK_ROOT:-$HOME/.android-sdk}"
test_class=com.calvinmazhindu.skitcam.export

build() {
    mkdir -p "$root"
    ./gradlew --console=plain -PvalidationSuffix="$suffix" \
        :app:assembleDebug :app:assembleDebugAndroidTest > "$root/build.log" 2>&1
    cp app/build/outputs/apk/debug/app-debug.apk "$root/app.apk"
    cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk "$root/test.apk"
    sha256sum "$root/app.apk" "$root/test.apk"
}

install_packages() {
    for variant in app test; do
        local expected=$pkg
        if [[ "$variant" = test ]]; then expected=$pkg.test; fi
        "$sdk/build-tools/35.0.0/aapt" dump badging "$root/$variant.apk" | grep -F "package: name='$expected'"
        "${adb[@]}" install -r --user 0 "$root/$variant.apk"
    done
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
    printf '%s  %s\n' "$actual" "$to"
}

stage_short() {
    local short=${SHORT_SOURCE:-.validation/m15b31a/inputs/segment_001.mp4}
    stage_file "$short" decoder-compatibility/short.mp4
    stage_file "$short" validation-m15b31a-inputs/segment_001.mp4
}

stage_paths() {
    local probes=${MARKER_INPUTS:-.validation/m15b3/timing-inputs}
    local golden=${GOLDEN_EXPORT:-.validation/m15b3/final-readbacks/ordinary.mp4}
    mkdir -p "$root/paths"
    local source="$probes/zero/segment_001.mp4"
    [[ -f "$root/paths/bframes.mp4" ]] || ffmpeg -nostdin -v error -n -i "$source" -c:v libx264 \
        -preset fast -bf 2 -x264-params b-adapt=0 -pix_fmt yuv420p -c:a copy "$root/paths/bframes.mp4"
    [[ -f "$root/paths/different.mp4" ]] || ffmpeg -nostdin -v error -n -i "$source" -c:v libx264 \
        -preset fast -profile:v high -bf 0 -pix_fmt yuv420p -c:a copy "$root/paths/different.mp4"
    [[ -f "$root/paths/vfr.mp4" ]] || ffmpeg -nostdin -v error -n -i "$source" -c:v libx264 \
        -vf "select='not(eq(mod(n,5),2))'" -fps_mode vfr -bf 0 -pix_fmt yuv420p -c:a copy "$root/paths/vfr.mp4"
    for variant in zero offset; do
        for clip in segment_001.mp4 segment_002.mp4; do
            stage_file "$probes/$variant/$clip" "validation-m12b-inputs/$variant/$clip"
        done
    done
    for fixture in bframes different vfr; do
        stage_file "$root/paths/$fixture.mp4" "validation-m12b-inputs/$fixture.mp4"
    done
    stage_file "$golden" validation-m12b-inputs/golden.mp4
}

instrument() {
    local label=$1 method=$2
    shift 2
    mkdir -p "$root"
    "${adb[@]}" shell am force-stop --user 0 "$pkg"
    "${adb[@]}" shell am instrument --user 0 -w -r -e class "$test_class.$method" \
        "$@" "$runner" > "$root/$label.log" 2>&1 || true
    grep -E '^OK \(|^Tests run:|^INSTRUMENTATION_RESULT: shortMsg=' "$root/$label.log"
    grep -Eq '^OK \([1-9][0-9]* tests?\)' "$root/$label.log"
}

trial() {
    local label=$1 method=$2
    shift 2
    local status=0
    local before
    before=$("${adb[@]}" shell date '+%m-%d\ %H:%M:%S.000' | tr -d '\r')
    instrument "$label" "DecoderCompatibilityDeviceTest#$method" -e trialId "$label" "$@" || status=$?
    "${adb[@]}" logcat -d -T "$before" -v brief -s SkitCamDecoderTrial:I |
        tee "$root/$label.logcat" |
        grep -F "\"trialId\":\"$label\"" > "$root/$label.codec.log"
    return "$status"
}

death() {
    local method="ProcessDeathRecoveryDeviceTest"
    local phase from to
    for phase in before after recheck; do
        case "$phase" in
            before) from=KillBeforeCandidateCheckpoint; to=BeforeCheckpointRebuildsSafely ;;
            after) from=KillAfterCandidateCheckpoint; to=AfterCheckpointReusesTheCandidate ;;
            recheck) from=KillDuringCandidateRecheck; to=ConsumedCandidateRebuildsSafely ;;
        esac
        if instrument "death-$phase-1" "$method#phase1$from"; then
            printf '%s did not terminate its process\n' "$phase" >&2
            return 1
        fi
        grep -q 'Process crashed' "$root/death-$phase-1.log"
        instrument "death-$phase-2" "$method#phase2$to"
    done
}

command=${1:?Use build, install, stage-short, stage-long, stage-field, stage-paths, trial, instrument or death}
shift
case "$command" in
    build) build ;;
    install) install_packages ;;
    stage-short) stage_short ;;
    stage-long) stage_file "${1:?Provide the retained candidate}" decoder-compatibility/long.mp4 ;;
    stage-field)
        source=${FIELD_INPUTS:-.validation/m15b3/portrait-01}
        for clip in 1 2 3; do
            stage_file "$source/segment_00$clip.mp4" "decoder-compatibility/field-$clip.mp4"
        done ;;
    stage-paths) stage_paths ;;
    trial) trial "$@" ;;
    instrument) instrument "$@" ;;
    death) death ;;
    *) exit 2 ;;
esac
