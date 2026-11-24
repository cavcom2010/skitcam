#!/usr/bin/env bash
# Twelve realistic long exports with one explicit Retry each, over the M15B.3 sources.
set -euo pipefail
root=.validation/m15b31a/bench
suffix=.m15b31candidate.m12bvalidation
pkg="com.calvinmazhindu.skitcam$suffix"
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
adb=(adb -s "${SERIAL:?Set SERIAL to the validation device}")
sdk="${ANDROID_SDK_ROOT:-$HOME/.android-sdk}"
first=${1:-1}
last=${2:-12}

mkdir -p "$root"
if [[ "${SKIP_BUILD:-0}" != 1 ]]; then
    ./gradlew --console=plain -PvalidationSuffix="$suffix" \
        :app:assembleDebug :app:assembleDebugAndroidTest > "$root/build.log" 2>&1
    cp app/build/outputs/apk/debug/app-debug.apk "$root/app.apk"
    cp app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk "$root/test.apk"
    sha256sum "$root/app.apk" "$root/test.apk"
fi
"${adb[@]}" get-state
"${adb[@]}" install -r --user 0 "$root/app.apk"
"${adb[@]}" install -r --user 0 "$root/test.apk"
"${adb[@]}" shell run-as "$pkg" test -f files/m15b31-inputs/.ready

for run in $(seq "$first" "$last"); do
    report="$root/recovery-$run.json"
    test ! -e "$report"
    "${adb[@]}" shell am force-stop --user 0 "$pkg"
    log="$root/recovery-$run.log"
    "${adb[@]}" shell am instrument --user 0 -w -r \
        -e class "com.calvinmazhindu.skitcam.export.RecoveryBenchmarkDeviceTest#realisticAttemptWithExplicitRetry" \
        -e run "$run" "$runner" > "$log" 2>&1 || true
    if grep -Fq 'OK (' "$log"; then
        "${adb[@]}" exec-out run-as "$pkg" cat "files/m15b31-results/recovery-$run.json" > "$report"
        python3 - "$report" <<'PY'
import json, sys
with open(sys.argv[1], encoding="utf-8") as f:
    row = json.load(f)
retry = "n/a" if row["retryElapsedMs"] is None else f'{row["retryElapsedMs"]/1000:.2f} s'
print(f'{row["run"]:>2} {row["firstState"]:<8} {row["firstElapsedMs"]/1000:7.2f} s  '
      f'retry={retry:<9} final={row["finalState"]:<8} '
      f'production={row["finalProductionAttempts"]} verification={row["finalVerificationAttempts"]} '
      f'reused={row["reusedCandidate"]} failure={row["firstLastVerifyFailure"] or "-"}')
PY
    else
        printf '%s: instrumentation did not pass; see %s\n' "$run" "$log"
    fi
    sleep 20
done
