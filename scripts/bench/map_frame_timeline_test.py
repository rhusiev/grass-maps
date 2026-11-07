#!/usr/bin/env python3
"""Tests for map-frame-timeline.py; run with `python3 scripts/bench/map_frame_timeline_test.py`."""

from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("map-frame-timeline.py")
SPEC = importlib.util.spec_from_file_location("map_frame_timeline", SCRIPT)
assert SPEC and SPEC.loader
PACING = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PACING)

HZ_60 = 60.0
PERIOD_MS = 1000.0 / HZ_60


def rows(spacings_ms: list[float]) -> list[tuple[int, float]]:
    """A counter that alternates 1/0 with the requested latch spacings."""
    timestamp = 1_000_000_000
    result = [(timestamp, 1.0)]
    for spacing in spacings_ms:
        timestamp += int(spacing * 1e6)
        result.append((timestamp, 0.0))
        result.append((timestamp, 1.0))
    return result


class LatchTest(unittest.TestCase):
    def test_each_counter_fall_is_one_latch(self) -> None:
        latches = PACING.latch_times_ms(rows([PERIOD_MS, PERIOD_MS]))
        self.assertEqual(len(latches), 2)
        self.assertAlmostEqual(latches[1] - latches[0], PERIOD_MS, delta=0.001)

    def test_an_increase_is_not_a_latch(self) -> None:
        latches = PACING.latch_times_ms([(0, 0.0), (1_000_000, 1.0), (2_000_000, 2.0)])
        self.assertEqual(latches, [])

    def test_a_multi_step_fall_is_still_one_latch(self) -> None:
        latches = PACING.latch_times_ms([(0, 3.0), (1_000_000, 0.0)])
        self.assertEqual(len(latches), 1)


class SummaryTest(unittest.TestCase):
    def test_frames_at_the_period_are_not_late(self) -> None:
        latches = [i * PERIOD_MS for i in range(6)]
        result = PACING.summarize(latches, HZ_60)
        self.assertEqual(result["late_intervals"]["count"], 0)
        self.assertEqual(result["late_intervals"]["percent"], 0.0)
        self.assertEqual(result["map_frames"], 6)

    def test_one_missed_vsync_is_late(self) -> None:
        latches = [0.0, PERIOD_MS, PERIOD_MS * 2, PERIOD_MS * 4]
        result = PACING.summarize(latches, HZ_60)
        self.assertEqual(result["late_intervals"]["count"], 1)
        self.assertEqual(result["late_intervals"]["percent"], 33.33)
        self.assertEqual(result["missed_vsyncs"], 1)
        self.assertAlmostEqual(result["late_intervals"]["over_ms"], round(PERIOD_MS * 1.5, 2))

    def test_the_idle_gap_between_gestures_is_not_a_frame(self) -> None:
        latches = [0.0, PERIOD_MS, PERIOD_MS * 2, PERIOD_MS * 2 + 500.0]
        result = PACING.summarize(latches, HZ_60)
        self.assertEqual(result["late_intervals"]["count"], 0)

    def test_interval_statistics_use_the_clean_intervals(self) -> None:
        latches = [0.0, 10.0, 20.0, 30.0]
        result = PACING.summarize(latches, HZ_60)
        self.assertEqual(result["interval_ms"]["n"], 3)
        self.assertEqual(result["interval_ms"]["p50"], 10.0)

    def test_no_intervals_reports_none_rather_than_zero(self) -> None:
        result = PACING.summarize([5.0], HZ_60)
        self.assertEqual(result["late_intervals"]["percent"], None)
        self.assertEqual(result["interval_ms"]["p50"], None)


class DocumentTest(unittest.TestCase):
    def test_the_output_is_keyed_by_commit(self) -> None:
        path = PACING.commit_path(Path("/tmp/out"), "abc1234")
        self.assertEqual(path.name, "abc1234.json")

    def test_the_document_carries_the_trace_identity(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            trace = Path(directory) / "trace.pftrace"
            trace.write_bytes(b"perfetto")
            doc = PACING.document([trace], [0.0, PERIOD_MS], HZ_60, 250.0, 1.5)
        self.assertEqual(doc["schema"], PACING.SCHEMA)
        self.assertEqual(doc["traces"][0]["bytes"], 8)
        self.assertEqual(len(doc["traces"][0]["sha256"]), 64)
        self.assertEqual(doc["late_intervals"]["count"], 0)
        json.dumps(doc)


if __name__ == "__main__":
    unittest.main(verbosity=2)
