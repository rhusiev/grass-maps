"""Unit tests for the timeout-tolerant parts of the map golden capture."""

import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent.parent / "tools" / "control"))

import control_client
import map_goldens


class FakeClient:
    """A control client whose invoke answers from a scripted list."""

    def __init__(self, answers: list[object]) -> None:
        self.answers = answers
        self.calls = 0

    def invoke(self, command: str, params: object = None) -> object:
        self.calls += 1
        answer = self.answers.pop(0)
        if isinstance(answer, BaseException):
            raise answer
        return answer


class InvokeCameraTest(unittest.TestCase):
    def test_socket_timeout_is_retried(self) -> None:
        client = FakeClient([TimeoutError("timed out"), "lat=1.0 idle=true"])

        with mock.patch.object(map_goldens.time, "sleep"):
            result = map_goldens.invoke_camera(client, {})

        self.assertEqual(result, "lat=1.0 idle=true")
        self.assertEqual(client.calls, 2)

    def test_not_ready_is_retried(self) -> None:
        not_ready = control_client.ControlError(-32000, "not ready", {"kind": "not_ready"})
        client = FakeClient([not_ready, "lat=1.0 idle=true"])

        with mock.patch.object(map_goldens.time, "sleep"):
            result = map_goldens.invoke_camera(client, {})

        self.assertEqual(result, "lat=1.0 idle=true")
        self.assertEqual(client.calls, 2)

    def test_other_control_error_is_not_retried(self) -> None:
        failure = control_client.ControlError(-32603, "boom")
        client = FakeClient([failure])

        with self.assertRaises(control_client.ControlError):
            map_goldens.invoke_camera(client, {})

        self.assertEqual(client.calls, 1)

    def test_socket_timeout_exhausts_the_budget(self) -> None:
        client = FakeClient([TimeoutError("timed out")] * 3)

        with (
            mock.patch.object(map_goldens, "CAMERA_READY_TIMEOUT_S", 0.0),
            mock.patch.object(map_goldens.time, "sleep"),
            self.assertRaises(map_goldens.GoldenError),
        ):
            map_goldens.invoke_camera(client, {})

        self.assertEqual(client.calls, 1)


class CapturePositionTest(unittest.TestCase):
    """The frame, not MapLibre's idle event, is the acceptance."""

    def capture(self, answer: str) -> tuple[dict[str, object], mock.Mock]:
        adb = mock.Mock()
        adb.screencap.return_value = map_goldens.PNG_SIGNATURE + b"frame"
        client = FakeClient([answer])
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            with mock.patch.object(map_goldens.time, "sleep") as sleep:
                shot = map_goldens.capture_position(adb, client, output, "p", {"zoom": 12})
            self.assertTrue((output / "p.png").is_file())
        return shot, sleep

    def test_idle_false_still_captures_after_the_slow_frame_allowance(self) -> None:
        shot, sleep = self.capture("lat=1.0 idle=false")

        self.assertFalse(shot["idle"])
        self.assertEqual(shot["camera_result"], "lat=1.0 idle=false")
        sleep.assert_called_once_with(
            map_goldens.SCREENSHOT_SETTLE_S + map_goldens.SLOW_FRAME_ALLOWANCE_S
        )

    def test_idle_true_uses_only_the_base_settle(self) -> None:
        shot, sleep = self.capture("lat=1.0 idle=true")

        self.assertTrue(shot["idle"])
        sleep.assert_called_once_with(map_goldens.SCREENSHOT_SETTLE_S)


class RecordFailureTest(unittest.TestCase):
    def test_writes_stage_reason_logcat_and_screen(self) -> None:
        adb = mock.Mock()
        adb.logcat.return_value = "I/Map: idle"
        adb.screencap.return_value = map_goldens.PNG_SIGNATURE + b"screen"

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "artifacts"
            map_goldens.record_failure(
                adb,
                output,
                "fixture-center-z13",
                "GoldenError: camera command did not answer",
                "lat=1.0 idle=false",
            )

            failure = (output / "failure.json").read_text()
            self.assertIn("fixture-center-z13", failure)
            self.assertIn("GoldenError: camera command did not answer", failure)
            self.assertIn("lat=1.0 idle=false", failure)
            self.assertEqual((output / "failure-logcat.txt").read_text(), "I/Map: idle")
            self.assertEqual(
                (output / "failure-screen.png").read_bytes(),
                map_goldens.PNG_SIGNATURE + b"screen",
            )

    def test_missing_screen_does_not_mask_the_failure(self) -> None:
        adb = mock.Mock()
        adb.logcat.return_value = ""
        adb.screencap.side_effect = map_goldens.GoldenError("no screen")

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "artifacts"
            map_goldens.record_failure(adb, output, "launch", "GoldenError: launch failed")

            self.assertTrue((output / "failure.json").is_file())


if __name__ == "__main__":
    unittest.main()
