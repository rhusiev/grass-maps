"""Tests for the device readiness gate in lib/device.sh.

A fake `adb` reads its answers from environment variables and counts calls in
a state directory, so the gate runs without a device. The tests prove that the
gate waits while a probe fails, that it needs consecutive passes, and that the
timeout message names the failed probe.
"""

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

DEVICE_SH = Path(__file__).resolve().parent / "lib" / "device.sh"
EXPECTED_API = "29"
POLL_INTERVAL_S = "0.05"
GATE_SECONDS = 20
SHORT_GATE_SECONDS = 1

FAKE_ADB = r"""#!/usr/bin/env bash
state="$FAKE_ADB_STATE"
[[ "$1" == "-s" ]] && shift 2
bump() { local n=$(($(cat "$state/$1" 2>/dev/null || echo 0) + 1)); echo "$n" > "$state/$1"; echo "$n"; }
case "$*" in
    "shell getprop sys.boot_completed")
        n=$(bump boot)
        if ((n <= ${FAKE_BOOT_BAD_CALLS:-0})); then echo 0; else echo 1; fi ;;
    "shell getprop ro.build.version.sdk")
        n=$(bump sdk)
        if ((n <= ${FAKE_SDK_BAD_CALLS:-0} || n == ${FAKE_SDK_FAIL_AT:-0})); then echo "${FAKE_SDK_BAD_VALUE-}"; else echo "${FAKE_SDK_VALUE:-29}"; fi ;;
    "shell pm path android")
        n=$(bump pm)
        ((n > ${FAKE_PM_BAD_CALLS:-0})) && echo "package:/system/framework/framework-res.apk" ;;
    "shell am get-current-user")
        n=$(bump am)
        ((n > ${FAKE_AM_BAD_CALLS:-0})) && echo 0 ;;
    "shell settings get global window_animation_scale")
        n=$(bump settings)
        ((n > ${FAKE_SETTINGS_BAD_CALLS:-0})) && echo 1.0 ;;
    *) exit 1 ;;
esac
"""


class DeviceReadyGateTest(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self._tmp.cleanup)
        self.dir = Path(self._tmp.name)
        self.state = self.dir / "state"
        self.state.mkdir()
        adb = self.dir / "adb"
        adb.write_text(FAKE_ADB, encoding="utf-8")
        adb.chmod(0o755)

    def run_gate(
        self, seconds: int, expected: str = EXPECTED_API, **fake: str
    ) -> subprocess.CompletedProcess[str]:
        env = {
            **os.environ,
            "PATH": f"{self.dir}:{os.environ['PATH']}",
            "FAKE_ADB_STATE": str(self.state),
            "DEVICE_POLL_INTERVAL_SECONDS": POLL_INTERVAL_S,
            **fake,
        }
        script = f'source "{DEVICE_SH}"; wait_for_device_ready {seconds} "{expected}"'
        return subprocess.run(
            ["bash", "-c", script],
            env=env,
            capture_output=True,
            text=True,
            check=False,
        )

    def calls(self, name: str) -> int:
        return int((self.state / name).read_text())

    def test_passes_at_once_on_a_ready_device(self) -> None:
        result = self.run_gate(GATE_SECONDS)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.calls("sdk"), 3)

    def test_waits_while_sdk_is_empty_then_passes(self) -> None:
        result = self.run_gate(GATE_SECONDS, FAKE_SDK_BAD_CALLS="4")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.calls("sdk"), 4 + 3)

    def test_waits_while_sdk_is_one_then_passes(self) -> None:
        result = self.run_gate(
            GATE_SECONDS, FAKE_SDK_BAD_CALLS="2", FAKE_SDK_BAD_VALUE="1"
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.calls("sdk"), 2 + 3)

    def test_waits_for_boot_completed(self) -> None:
        result = self.run_gate(GATE_SECONDS, FAKE_BOOT_BAD_CALLS="3")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_waits_for_package_and_activity_services(self) -> None:
        result = self.run_gate(GATE_SECONDS, FAKE_PM_BAD_CALLS="2", FAKE_AM_BAD_CALLS="2")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_one_failure_resets_the_consecutive_count(self) -> None:
        # Two passes, one failure on call 3, then three fresh passes.
        result = self.run_gate(GATE_SECONDS, FAKE_SDK_FAIL_AT="3")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.calls("sdk"), 6)

    def test_timeout_names_the_sdk_probe(self) -> None:
        result = self.run_gate(
            SHORT_GATE_SECONDS, FAKE_SDK_BAD_CALLS="999", FAKE_SDK_BAD_VALUE="1"
        )
        self.assertEqual(result.returncode, 1)
        self.assertIn("ro.build.version.sdk is '1', expected '29'", result.stderr)
        self.assertIn("FAIL: device not ready after 1s", result.stderr)

    def test_timeout_names_the_boot_probe(self) -> None:
        result = self.run_gate(SHORT_GATE_SECONDS, FAKE_BOOT_BAD_CALLS="999")
        self.assertEqual(result.returncode, 1)
        self.assertIn("sys.boot_completed is '0'", result.stderr)

    def test_timeout_names_the_package_manager(self) -> None:
        result = self.run_gate(SHORT_GATE_SECONDS, FAKE_PM_BAD_CALLS="999")
        self.assertEqual(result.returncode, 1)
        self.assertIn("package manager did not answer", result.stderr)

    def test_timeout_names_the_activity_manager(self) -> None:
        result = self.run_gate(SHORT_GATE_SECONDS, FAKE_AM_BAD_CALLS="999")
        self.assertEqual(result.returncode, 1)
        self.assertIn("activity manager did not answer", result.stderr)

    def test_timeout_names_the_settings_service(self) -> None:
        result = self.run_gate(SHORT_GATE_SECONDS, FAKE_SETTINGS_BAD_CALLS="999")
        self.assertEqual(result.returncode, 1)
        self.assertIn("settings service did not answer", result.stderr)

    def test_wrong_api_level_never_passes(self) -> None:
        result = self.run_gate(SHORT_GATE_SECONDS, FAKE_SDK_VALUE="35")
        self.assertEqual(result.returncode, 1)
        self.assertIn("expected '29'", result.stderr)

    def test_empty_expected_accepts_any_real_level(self) -> None:
        result = self.run_gate(GATE_SECONDS, expected="", FAKE_SDK_VALUE="35")
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_empty_expected_still_rejects_level_one(self) -> None:
        result = self.run_gate(SHORT_GATE_SECONDS, expected="", FAKE_SDK_VALUE="1")
        self.assertEqual(result.returncode, 1)
        self.assertIn("expected 'a number'", result.stderr)


if __name__ == "__main__":
    unittest.main()
