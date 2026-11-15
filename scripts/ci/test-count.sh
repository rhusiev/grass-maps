#!/usr/bin/env bash
#
# Counts executed JVM unit tests from the Gradle XML reports.
#
# An empty suite is not a pass. A whole-project total hides a module that used
# to have tests and now runs none, so every module with test sources must also
# report at least one executed test. A total alone still hides a module that
# kept three tests out of a hundred and twenty, so every module is compared
# with the count recorded in `test-count-baseline.txt` and fails when it falls
# below that count.
#
# The baseline is a floor, not an exact count: adding tests leaves it alone,
# and removing tests on purpose means updating it in the same change. Its rows
# are `<module path relative to the repository root><TAB><executed tests>`.
#
# Usage: test-count.sh [--root DIR] [--baseline FILE] [--update]
#   --root DIR       read reports under DIR instead of the repository root
#   --baseline FILE  compare against FILE instead of test-count-baseline.txt
#   --update         rewrite the baseline from the reports on disk

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BASELINE="$SCRIPT_DIR/test-count-baseline.txt"
UPDATE=0

usage() {
    sed -n '/^# Usage:/,/^#   --update/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --root)
            [[ $# -ge 2 ]] || {
                echo "test-count.sh: --root needs a directory" >&2
                exit 2
            }
            ROOT="$(cd "$2" && pwd)"
            shift 2
            ;;
        --baseline)
            [[ $# -ge 2 ]] || {
                echo "test-count.sh: --baseline needs a file" >&2
                exit 2
            }
            BASELINE="$2"
            shift 2
            ;;
        --update)
            UPDATE=1
            shift
            ;;
        -h | --help)
            usage
            exit 0
            ;;
        *)
            echo "test-count.sh: unknown argument: $1" >&2
            exit 2
            ;;
    esac
done

# The number of tests a Gradle XML report recorded.
report_count() {
    local n
    n="$(grep -o 'tests="[0-9]*"' "$1" | head -n 1 | grep -o '[0-9]*' || true)"
    echo "${n:-0}"
}

# Every Gradle XML test report under the root, build directory excluded.
report_files() {
    find "$ROOT" -path "$ROOT/build" -prune -o \
        -path '*/build/test-results/*/*.xml' -print | sort
}

# Every directory holding JVM test sources, buildSrc excluded.
module_dirs() {
    find "$ROOT" -path "$ROOT/build" -prune -o \
        -type d -path '*/src/test' -print | sort
}

sum_reports() {
    local total=0 report n
    while IFS= read -r report; do
        [[ -z "$report" ]] && continue
        n="$(report_count "$report")"
        total=$((total + n))
    done
    echo "$total"
}

count=0
suites=0
while IFS= read -r report; do
    [[ -z "$report" ]] && continue
    count=$((count + $(report_count "$report")))
    suites=$((suites + 1))
done < <(report_files)

if [[ $count -eq 0 ]]; then
    echo "FAIL: no JVM unit tests ran." >&2
    echo "An empty suite would let a change that deletes every test pass the gate." >&2
    exit 1
fi

failed=0
declare -A module_counts=()
while IFS= read -r test_dir; do
    [[ -z "$test_dir" ]] && continue
    module="${test_dir%/src/test}"
    # buildSrc is a separate build with its own check.sh step.
    if [[ "$module" == "$ROOT/buildSrc" ]]; then
        continue
    fi
    if ! find "$test_dir" -name '*.kt' -print -quit | grep -q .; then
        continue
    fi
    module_count="$(sum_reports < <(find "$module/build/test-results" -name '*.xml' 2>/dev/null || true))"
    module_counts["${module#"$ROOT"/}"]="$module_count"
    if [[ $module_count -eq 0 ]]; then
        echo "FAIL: ${module#"$ROOT"/} has test sources but ran no tests." >&2
        failed=1
    fi
done < <(module_dirs)

if [[ $UPDATE -eq 1 ]]; then
    : >"$BASELINE"
    while IFS= read -r module; do
        printf '%s\t%s\n' "$module" "${module_counts[$module]}" >>"$BASELINE"
    done < <(printf '%s\n' "${!module_counts[@]}" | sort)
    echo "Wrote $BASELINE (${#module_counts[@]} modules)."
    exit "$failed"
fi

if [[ ! -f "$BASELINE" ]]; then
    echo "FAIL: no test count baseline at $BASELINE." >&2
    echo "Run scripts/ci/test-count.sh --update after a full test run." >&2
    exit 1
fi

declare -A baseline=()
while IFS=$'\t' read -r module expected; do
    [[ -z "$module" ]] && continue
    [[ "$module" == \#* ]] && continue
    if [[ ! "$expected" =~ ^[0-9]+$ ]]; then
        echo "FAIL: ${BASELINE##*/} row '$module' has no test count: '$expected'" >&2
        exit 1
    fi
    baseline["$module"]="$expected"
done <"$BASELINE"

while IFS= read -r module; do
    expected="${baseline[$module]}"
    if [[ -z "${module_counts[$module]+set}" ]]; then
        echo "FAIL: $module is in the test count baseline but has no test sources now." >&2
        failed=1
        continue
    fi
    actual="${module_counts[$module]}"
    if [[ "$actual" -lt "$expected" ]]; then
        echo "FAIL: $module ran $actual tests, below the recorded $expected." >&2
        echo "If the loss is intended, update the baseline in the same change." >&2
        failed=1
    elif [[ "$actual" -gt "$expected" ]]; then
        echo "note: $module ran $actual tests, above the recorded $expected; run test-count.sh --update."
    fi
done < <(printf '%s\n' "${!baseline[@]}" | sort)

while IFS= read -r module; do
    if [[ -z "${baseline[$module]+set}" ]]; then
        echo "FAIL: $module has test sources but no test count baseline row." >&2
        echo "Run scripts/ci/test-count.sh --update and commit the result." >&2
        failed=1
    fi
done < <(printf '%s\n' "${!module_counts[@]}" | sort)

if [[ $failed -ne 0 ]]; then
    exit 1
fi

echo "UNIT TESTS: $count executed in $suites report files."
