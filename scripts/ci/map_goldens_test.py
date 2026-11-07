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


class RecordFailureTest(unittest.TestCase):
    def test_writes_stage_logcat_and_screen(self) -> None:
        adb = mock.Mock()
        adb.logcat.return_value = "I/Map: idle"
        adb.screencap.return_value = map_goldens.PNG_SIGNATURE + b"screen"

        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "artifacts"
            map_goldens.record_failure(adb, output, "fixture-center-z13")

            self.assertIn("fixture-center-z13", (output / "failure.json").read_text())
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
            map_goldens.record_failure(adb, output, "launch")

            self.assertTrue((output / "failure.json").is_file())


if __name__ == "__main__":
    unittest.main()
