#!/usr/bin/env bash
#
# Counts executed JVM unit tests from the Gradle XML reports.
#
# An empty suite is not a pass. A whole-project total hides a module that used
# to have tests and now runs none, so every module with test sources must also
# report at least one executed test.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

count=0
suites=0
while IFS= read -r report; do
    [[ -z "$report" ]] && continue
    n="$(grep -o 'tests="[0-9]*"' "$report" | head -n 1 | grep -o '[0-9]*' || true)"
    count=$((count + ${n:-0}))
    suites=$((suites + 1))
done < <(
    find "$ROOT" -path "$ROOT/build" -prune -o \
        -path '*/build/test-results/*/*.xml' -print | sort
)

if [[ $count -eq 0 ]]; then
    echo "FAIL: no JVM unit tests ran." >&2
    echo "An empty suite would let a change that deletes every test pass the gate." >&2
    exit 1
fi

failed=0
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
    module_count=0
    while IFS= read -r report; do
        [[ -z "$report" ]] && continue
        n="$(grep -o 'tests="[0-9]*"' "$report" | head -n 1 | grep -o '[0-9]*' || true)"
        module_count=$((module_count + ${n:-0}))
    done < <(find "$module/build/test-results" -name '*.xml' 2>/dev/null || true)
    if [[ $module_count -eq 0 ]]; then
        echo "FAIL: ${module#"$ROOT"/} has test sources but ran no tests." >&2
        failed=1
    fi
done < <(
    find "$ROOT" -path "$ROOT/build" -prune -o \
        -type d -path '*/src/test' -print | sort
)

if [[ $failed -ne 0 ]]; then
    exit 1
fi

echo "UNIT TESTS: $count executed in $suites report files."
