#!/usr/bin/env python3
"""Unit tests for the timeout-tolerant parts of the emulator benchmark."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import android_benchmark


TIMEOUT_OUTPUT = """Starting: Intent { cmp=nl.r1a.grassmap/.app.MainActivity }
Status: timeout
LaunchState: UNKNOWN (-1)
Activity: nl.r1a.grassmap/.app.MainActivity
WaitTime: 24927
"""


class ParseStartOutputTest(unittest.TestCase):
    def test_timeout_keeps_wait_time_without_total_time(self) -> None:
        parsed = android_benchmark.parse_start_output(TIMEOUT_OUTPUT)

        self.assertEqual(parsed["Status"], "timeout")
        self.assertEqual(parsed["LaunchState"], "UNKNOWN (-1)")
        self.assertEqual(parsed["WaitTime"], 24927)
        self.assertNotIn("TotalTime", parsed)


class TransientInstallErrorTest(unittest.TestCase):
    def test_dropped_service_is_transient(self) -> None:
        detail = (
            "exit 1: adb -s emulator-5554 install -r app-debug.apk\n"
            "adb: failed to install app-debug.apk: "
            "cmd: Failure calling service package: Broken pipe (32)"
        )

        self.assertTrue(android_benchmark.transient_install_error(detail))

    def test_bad_apk_is_not_transient(self) -> None:
        detail = "exit 1: adb -s emulator-5554 install -r app-debug.apk\nadb: failed to install: INSTALL_FAILED_INVALID_APK"

        self.assertFalse(android_benchmark.transient_install_error(detail))


class ColdStartMetricsTest(unittest.TestCase):
    def test_timeout_is_counted_and_excluded_from_missing_medians(self) -> None:
        runs = [
            {
                "status": "ok",
                "total_time_ms": 400,
                "wait_time_ms": 410,
                "displayed_ms": 400.0,
                "process_to_first_frame_ms": 380.0,
            },
            {
                "status": "timeout",
                "total_time_ms": None,
                "wait_time_ms": 24927,
                "displayed_ms": None,
                "process_to_first_frame_ms": None,
            },
            {
                "status": "ok",
                "total_time_ms": 600,
                "wait_time_ms": 610,
                "displayed_ms": 600.0,
                "process_to_first_frame_ms": 580.0,
            },
        ]

        metrics = android_benchmark.cold_start_metrics(runs)

        self.assertEqual(metrics["timed_out"], 1)
        self.assertEqual(metrics["total_time_ms"]["n"], 2)
        self.assertEqual(metrics["total_time_ms"]["median"], 400)
        self.assertEqual(metrics["displayed_ms"]["n"], 2)
        self.assertEqual(metrics["wait_time_ms"]["n"], 3)
        self.assertEqual(metrics["wait_time_ms"]["max"], 24927)


if __name__ == "__main__":
    unittest.main()
