"""Unit tests for the route layer wait of the navigation goldens."""

import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent / "tools" / "control"))

import map_goldens
import navigation_goldens


class ScriptedClient:
    """Answers the route layer probe with the next scripted reply."""

    def __init__(self, replies: list[str]) -> None:
        self.replies = list(replies)
        self.reads = 0

    def invoke(self, command: str, params: dict[str, object]) -> str:
        self.reads += 1
        return self.replies.pop(0) if len(self.replies) > 1 else self.replies[0]


class WaitForRouteLayerTest(unittest.TestCase):
    def wait(self, replies: list[str]) -> ScriptedClient:
        client = ScriptedClient(replies)
        with mock.patch.object(navigation_goldens.time, "sleep"):
            navigation_goldens.wait_for_route_layer(client, 7)
        return client

    def test_a_steady_full_route_passes_after_two_reads(self) -> None:
        client = self.wait(["points=7 draws=3"])

        self.assertEqual(client.reads, 2)

    def test_a_clear_between_reads_is_waited_out(self) -> None:
        client = self.wait(["points=7 draws=3", "points=0 draws=4", "points=7 draws=5", "points=7 draws=5"])

        self.assertEqual(client.reads, 4)

    def test_a_redraw_between_reads_is_waited_out(self) -> None:
        client = self.wait(["points=7 draws=3", "points=7 draws=4", "points=7 draws=4"])

        self.assertEqual(client.reads, 3)

    def test_a_route_that_never_returns_times_out(self) -> None:
        client = ScriptedClient(["points=0 draws=4"])
        ticks = iter([0.0, 1.0, 10_000.0, 10_001.0])
        with mock.patch.object(navigation_goldens.time, "sleep"), mock.patch.object(
            navigation_goldens.time, "monotonic", side_effect=lambda: next(ticks)
        ):
            with self.assertRaises(map_goldens.GoldenError):
                navigation_goldens.wait_for_route_layer(client, 7)

    def test_an_unknown_reply_is_an_error(self) -> None:
        with self.assertRaises(map_goldens.GoldenError):
            navigation_goldens.read_route_layer(ScriptedClient(["points=7"]))


if __name__ == "__main__":
    unittest.main()
