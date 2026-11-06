#!/usr/bin/env bash
#
# Counts executed JVM unit tests from the Gradle XML reports.
#
# An empty suite is not a pass. The skeleton has no unit tests yet, so this
# prints an explicit TODO instead of a silent green check. It fails only when
# no report directory exists where a test task should have written one.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

reports="$(
    find "$ROOT" -path "$ROOT/build" -prune -o \
        -path '*/build/test-results/*/*.xml' -print | sort
)"

count=0
suites=0
while IFS= read -r report; do
    [[ -z "$report" ]] && continue
    n="$(grep -o 'tests="[0-9]*"' "$report" | head -n 1 | grep -o '[0-9]*' || true)"
    count=$((count + ${n:-0}))
    suites=$((suites + 1))
done <<<"$reports"

if [[ $count -eq 0 ]]; then
    echo "UNIT TESTS: 0 executed."
    echo "TODO: the skeleton has no unit tests yet (T-0012 adds the first ones)."
    echo "This check ran and found an empty suite; it is not evidence that tests pass."
else
    echo "UNIT TESTS: $count executed in $suites report files."
fi
