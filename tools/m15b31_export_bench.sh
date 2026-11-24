#!/usr/bin/env bash
# Fresh validation packages and explicitly named disposable inputs only.
set -euo pipefail
build=${1:?Usage: bash tools/m15b31_export_bench.sh base|candidate [case:run ...]}
shift
case "$build" in base|candidate) ;; *) exit 2 ;; esac
: "${SERIAL:?Set SERIAL to the selected validation device}"
: "${ANDROID_SDK_ROOT:=$HOME/.android-sdk}"
root=.validation/m15b31
out="$root/$build"
pkg="com.calvinmazhindu.skitcam.m15b31$build.m12bvalidation"
base=com.calvinmazhindu.skitcam
runner="$pkg.test/androidx.test.runner.AndroidJUnitRunner"
adb=(adb -s "$SERIAL")
"${adb[@]}" get-state
if ! "${adb[@]}" shell run-as "$pkg" test -f files/m15b31-inputs/.ready 2>/dev/null; then
    test -z "$("${adb[@]}" shell pm list packages --user 0 "$pkg")"
    for variant in app test; do
        expected=$pkg
        if [[ "$variant" = test ]]; then expected="$pkg.test"; fi
        "$ANDROID_SDK_ROOT/build-tools/35.0.0/aapt" dump badging "$out/$variant.apk" | \
            sed -n '1p' | grep -F "package: name='$expected'"
        "${adb[@]}" install --user 0 "$out/$variant.apk"
    done
    "${adb[@]}" shell run-as "$pkg" mkdir -p files/m15b31-inputs
    for i in $(seq 1 12); do
        name=$(printf 'segment_%03d.mp4' "$i")
        source=".validation/m15b3/portrait-01/$name"
        if ((i == 12)); then source="$root/inputs/$name"; fi
        "${adb[@]}" shell -T "run-as $pkg sh -c 'set -C; cat > files/m15b31-inputs/$name'" < "$source"
        expected=$(sha256sum "$source" | cut -d ' ' -f 1)
        actual=$("${adb[@]}" shell run-as "$pkg" sha256sum "files/m15b31-inputs/$name" | cut -d ' ' -f 1)
        [[ "$actual" = "$expected" ]]
    done
    "${adb[@]}" shell run-as "$pkg" touch files/m15b31-inputs/.ready
fi
for entry in "$@"; do
    [[ "$entry" =~ ^(single|multi|order|discard|trim|long|real|realwhole):([1-9]|10)$ ]]
    kind=${BASH_REMATCH[1]}
    run=${BASH_REMATCH[2]}
    report="$kind-$run"
    test ! -e "$out/$report.json"
    "${adb[@]}" shell am instrument --user 0 -w -r -e class "$base.export.FieldExportBenchmarkDeviceTest" \
        -e case "$kind" -e run "$run" "$runner" > "$out/$report.log" 2>&1
    if grep -Fxq 'OK (1 test)' "$out/$report.log"; then
        "${adb[@]}" exec-out run-as "$pkg" cat "files/m15b31-results/$report.json" > "$out/$report.json"
        python3 - "$out/$report.json" <<'PY'
import json
import sys
with open(sys.argv[1], encoding="utf-8") as f:
    row = json.load(f)
print(f'{row["case"]}:{row["run"]} {row["path"]} {row["elapsedMs"]/1000:.2f} s')
PY
    else
        printf '%s: instrumentation did not pass; see local log\n' "$report"
    fi
done
