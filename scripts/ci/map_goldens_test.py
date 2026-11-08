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


class StableFrameTest(unittest.TestCase):
    def _png(self, left_black: bool) -> bytes:
        width, height = 216, 384
        rgb = bytearray(width * height * 3)
        for y in range(height):
            for x in range(width):
                value = 0 if left_black and x < width // 2 else 255
                base = (y * width + x) * 3
                rgb[base : base + 3] = bytes((value, value, value))
        with tempfile.NamedTemporaryFile(suffix=".png") as handle:
            path = Path(handle.name)
            map_goldens.write_png(path, width, height, bytes(rgb))
            return path.read_bytes()

    def test_two_matching_frames_are_accepted(self) -> None:
        adb = mock.Mock()
        adb.screencap.return_value = self._png(False)

        with mock.patch.object(map_goldens.time, "sleep"):
            frame, stability = map_goldens.stable_frame(adb)

        self.assertEqual(2, stability["frames"])
        self.assertTrue(stability["stable"])
        self.assertEqual(2, adb.screencap.call_count)

    def test_a_partial_frame_is_retried(self) -> None:
        adb = mock.Mock()
        adb.screencap.side_effect = [self._png(True), self._png(False), self._png(False)]

        with mock.patch.object(map_goldens.time, "sleep"):
            frame, stability = map_goldens.stable_frame(adb)

        self.assertEqual(3, stability["frames"])
        self.assertTrue(stability["stable"])
        self.assertEqual(3, adb.screencap.call_count)


class AirplaneModeTest(unittest.TestCase):
    def test_a_slow_radio_command_does_not_abort_the_capture(self) -> None:
        adb = mock.Mock()
        adb.shell.side_effect = map_goldens.GoldenError("timeout after 20s")

        commands = map_goldens.airplane_mode(adb)

        self.assertEqual(5, len(commands))
        self.assertTrue(any("svc wifi disable" in command for command in commands))


class StandardPositionsTest(unittest.TestCase):
    def test_ten_places_at_three_zooms(self) -> None:
        positions = map_goldens.POSITIONS["standard"]

        self.assertEqual(30, len(positions))
        self.assertEqual(10, len({name.rsplit("-z", 1)[0] for name, *_ in positions}))
        self.assertEqual({12.0, 13.0, 14.0}, {position[3] for position in positions})
        self.assertEqual(30, len({position[0] for position in positions}))

    def test_style_reference_paths_are_separate(self) -> None:
        self.assertEqual(
            map_goldens.default_reference("standard", "dark"),
            map_goldens.ROOT / "test-data" / "map-goldens" / "standard" / "dark",
        )
        self.assertEqual(
            map_goldens.default_reference("fixture", None),
            map_goldens.ROOT / "test-data" / "map-goldens" / "fixture",
        )


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
        adb.shell.return_value = (
            "mCurrentFocus=Window{1 u0 nl.r1a.grassmap/nl.r1a.grassmap.app.MainActivity}"
        )
        client = FakeClient([answer])
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            with (
                mock.patch.object(map_goldens.time, "sleep") as sleep,
                mock.patch.object(
                    map_goldens,
                    "stable_frame",
                    return_value=(b"frame", {"frames": 2, "last_mismatch_percent": 0.0, "stable": True}),
                ),
            ):
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


class PrepareDeviceTest(unittest.TestCase):
    def test_hides_error_dialogs_and_closes_existing_ones(self) -> None:
        adb = mock.Mock()
        adb.shell.return_value = ""

        commands = map_goldens.prepare_device(adb)

        self.assertTrue(any("hide_error_dialogs" in command for command in commands))
        self.assertTrue(any("CLOSE_SYSTEM_DIALOGS" in command for command in commands))
        self.assertTrue(any("keyevent 224" in command for command in commands))
        self.assertEqual(adb.shell.call_count, 7)


class AppFocusTest(unittest.TestCase):
    APP_FOCUS = (
        "mCurrentFocus=Window{2 u0 nl.r1a.grassmap/nl.r1a.grassmap.app.MainActivity}"
    )

    def test_app_focus_passes_and_returns_the_window(self) -> None:
        adb = mock.Mock()
        adb.shell.return_value = (
            "mCurrentFocus=Window{1 u0 nl.r1a.grassmap/nl.r1a.grassmap.app.MainActivity}"
        )

        focus = map_goldens.require_app_focused(adb, "fixture-center-z12")

        self.assertIn("nl.r1a.grassmap", focus)

    def test_null_focus_brings_the_app_front_then_passes(self) -> None:
        adb = FakeAdb(["", self.APP_FOCUS])

        with mock.patch.object(map_goldens.time, "sleep"):
            focus = map_goldens.require_app_focused(adb, "fixture-center-z12")

        self.assertIn("nl.r1a.grassmap", focus)
        self.assertEqual(adb.fronted, 1)

    def test_persistent_null_focus_fails_with_focus_error(self) -> None:
        adb = FakeAdb([""])

        with (
            mock.patch.object(map_goldens, "FOCUS_RETRY_TIMEOUT_S", 0.0),
            mock.patch.object(map_goldens.time, "sleep"),
            self.assertRaises(map_goldens.FocusError) as raised,
        ):
            map_goldens.require_app_focused(adb, "fixture-center-z12")

        self.assertIn("None", str(raised.exception))

    def test_system_dialog_focus_fails_naming_the_window(self) -> None:
        adb = mock.Mock()
        adb.shell.return_value = (
            "mCurrentFocus=Window{2 u0 com.android.systemui/com.android.systemui.recents.RecentsActivity}"
        )

        with (
            mock.patch.object(map_goldens, "FOCUS_RETRY_TIMEOUT_S", 0.0),
            mock.patch.object(map_goldens.time, "sleep"),
            self.assertRaises(map_goldens.GoldenError) as raised,
        ):
            map_goldens.require_app_focused(adb, "fixture-center-z12")

        self.assertIn("nl.r1a.grassmap", str(raised.exception))
        self.assertIn("com.android.systemui", str(raised.exception))


class FakeAdb:
    """An adb whose focus moves through a scripted list and which records taps."""

    def __init__(self, focus: list[str], xml: str = "") -> None:
        self.focus = focus
        self.xml = xml
        self.taps: list[tuple[str, str]] = []
        self.fronted = 0

    def shell(self, *args: str, timeout_s: float = 0, check: bool = True) -> str:
        if args[:2] == ("dumpsys", "window"):
            return self.focus.pop(0) if len(self.focus) > 1 else self.focus[0]
        if args[:2] == ("am", "start"):
            self.fronted += 1
            return ""
        if args[0] == "cat":
            return self.xml
        if args[:2] == ("input", "tap"):
            self.taps.append((args[2], args[3]))
            return ""
        return ""

    def screencap(self) -> bytes:
        return map_goldens.PNG_SIGNATURE + b"frame"


class AnrDialogTest(unittest.TestCase):
    ANR_FOCUS = "mCurrentFocus=Window{1 u0 Application Not Responding: com.android.systemui}"
    APP_FOCUS = (
        "mCurrentFocus=Window{2 u0 nl.r1a.grassmap/nl.r1a.grassmap.app.MainActivity}"
    )
    WAIT_XML = '<hierarchy><node text="Wait" bounds="[10,20][110,220]" /></hierarchy>'

    def test_tap_anr_wait_uses_the_wait_button_bounds(self) -> None:
        adb = FakeAdb([self.ANR_FOCUS], xml=self.WAIT_XML)

        self.assertTrue(map_goldens.tap_anr_wait(adb))
        self.assertEqual(adb.taps, [("60", "120")])

    def test_dismiss_anr_dialog_rechecks_focus_after_the_tap(self) -> None:
        adb = FakeAdb([self.ANR_FOCUS, self.APP_FOCUS], xml=self.WAIT_XML)

        with mock.patch.object(map_goldens.time, "sleep"):
            dismissed = map_goldens.dismiss_anr_dialog(adb)

        self.assertTrue(dismissed)
        self.assertEqual(adb.taps, [("60", "120")])


class InstallTest(unittest.TestCase):
    def test_install_retries_a_rejected_first_attempt(self) -> None:
        adb = map_goldens.Adb("emulator-5554")
        rejected = map_goldens.subprocess.CompletedProcess(
            [], 1, stdout="Failure [INSTALL_FAILED_BUSY]", stderr=""
        )
        accepted = map_goldens.subprocess.CompletedProcess(
            [], 0, stdout="Success", stderr=""
        )

        with (
            mock.patch.object(
                map_goldens.subprocess, "run", side_effect=[rejected, accepted]
            ) as install,
            mock.patch.object(map_goldens.time, "sleep") as sleep,
        ):
            adb.install(Path("app-debug.apk"))

        self.assertEqual(install.call_count, 2)
        sleep.assert_called_once_with(map_goldens.INSTALL_RETRY_PAUSE_S)


class FirstFrameTest(unittest.TestCase):
    def test_waits_for_the_harness_first_frame_line(self) -> None:
        adb = mock.Mock()
        adb.logcat.side_effect = ["I/Map: starting", "I/T0020: first-full-frame-ms=2000"]

        with mock.patch.object(map_goldens.time, "sleep") as sleep:
            map_goldens.wait_for_first_frame(adb)

        self.assertEqual(adb.logcat.call_count, 2)
        sleep.assert_called_once()


class CapturePositionRetryTest(unittest.TestCase):
    def test_focus_failure_after_the_settle_retries_the_position_once(self) -> None:
        # attempt 1: app, then focus lost; attempt 2: app before and after.
        adb = FakeAdb([
            AppFocusTest.APP_FOCUS,
            "",
            AppFocusTest.APP_FOCUS,
            AppFocusTest.APP_FOCUS,
        ])
        client = FakeClient(["lat=1.0 idle=true", "lat=1.0 idle=true"])

        with tempfile.TemporaryDirectory() as directory:
            with (
                mock.patch.object(map_goldens, "FOCUS_RETRY_TIMEOUT_S", 0.0),
                mock.patch.object(map_goldens.time, "sleep"),
                mock.patch.object(
                    map_goldens,
                    "stable_frame",
                    return_value=(b"frame", {"frames": 2, "last_mismatch_percent": 0.0, "stable": True}),
                ),
            ):
                shot = map_goldens.capture_position(adb, client, Path(directory), "p", {})

        self.assertEqual(client.calls, 2)
        self.assertTrue(shot["idle"])

    def test_null_focus_before_the_camera_is_forced_foreground(self) -> None:
        adb = FakeAdb(["", AppFocusTest.APP_FOCUS])
        client = FakeClient(["lat=1.0 idle=true"])

        with tempfile.TemporaryDirectory() as directory:
            with (
                mock.patch.object(map_goldens.time, "sleep"),
                mock.patch.object(
                    map_goldens,
                    "stable_frame",
                    return_value=(b"frame", {"frames": 2, "last_mismatch_percent": 0.0, "stable": True}),
                ),
            ):
                shot = map_goldens.capture_position(adb, client, Path(directory), "p", {})

        self.assertEqual(client.calls, 1)
        self.assertEqual(adb.fronted, 1)
        self.assertTrue(shot["idle"])

    def test_persistent_focus_failure_fails_after_the_retry(self) -> None:
        adb = FakeAdb([AppFocusTest.APP_FOCUS, ""])
        client = FakeClient(["lat=1.0 idle=true", "lat=1.0 idle=true"])

        with tempfile.TemporaryDirectory() as directory:
            with (
                mock.patch.object(map_goldens, "FOCUS_RETRY_TIMEOUT_S", 0.0),
                mock.patch.object(map_goldens.time, "sleep"),
                self.assertRaises(map_goldens.FocusError),
            ):
                map_goldens.capture_position(adb, client, Path(directory), "p", {})

        self.assertEqual(client.calls, 1)
        self.assertGreaterEqual(adb.fronted, 1)


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
