#!/usr/bin/env bash
#
# Reports a check that belongs to the pipeline but is not implemented yet.
# The job that calls it is named "(pending)" and prints this in the run, so the
# gap is visible. This script never claims that anything was verified.

set -euo pipefail

name="${1:?usage: pending-check.sh <check name> <reason>}"
reason="${2:?usage: pending-check.sh <check name> <reason>}"

echo "PENDING CHECK: $name"
echo "Reason: $reason"
echo "Nothing was verified by this job."

if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    {
        echo "## Pending check: $name"
        echo
        echo "Not implemented yet: $reason"
        echo
        echo "This job did not verify anything."
    } >>"$GITHUB_STEP_SUMMARY"
fi
