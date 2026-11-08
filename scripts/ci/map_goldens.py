#!/usr/bin/env python3
"""Capture and compare the T-0102 map goldens.

The map is driven through the debug control channel: `core.map.set_camera`
moves the camera to one fixed position and returns only after MapLibre reports
idle, then the frame is captured with `adb exec-out screencap -p`. The control
channel's own screenshot uses `PixelCopy` on the activity window; on this
emulator that path does not see the map's `SurfaceView`, so it returns a black
map area and cannot carry a map golden. `screencap` reads the composited
display, which includes the map surface.

Comparison metric: each image is reduced to its centre 16:9 area (the map
viewport with no status or navigation bar), then to the central half of that
area, which excludes every anchored overlay (scale bar, control bar,
attribution), and finally to a 108 x 192 grid of mean RGB blocks. At a fixed
camera the central 540 x 960 window covers the same ground on every 1080-wide
device, so a 16:9 and a 20:9 phone compare directly. A block mismatches when
any RGB channel differs by more than BLOCK_DELTA from the golden; the test fails
when more than MAX_MISMATCH_PERCENT of blocks mismatch. Block means absorb GPU
anti-aliasing and label rasterisation differences; a blank, wrong or shifted
map still changes most blocks.

The same command runs for the small fixture (CI) and for the whole-oblast region
package (local acceptance).

Usage:
    map_goldens.py capture --serial emulator-5580 --profile fixture \
        --apk app/build/outputs/apk/debug/app-debug.apk --output /hdd/t-0102-evidence/fixture
    map_goldens.py capture --serial emulator-5580 --profile oblast \
        --region-package /hdd/t-0102-evidence/region/ua-46/2026-11-05-bench \
        --output /hdd/t-0102-evidence/oblast
    map_goldens.py compare --profile fixture --captured /hdd/t-0102-evidence/fixture
"""

from __future__ import annotations

import argparse
import json
import re
import struct
import subprocess
import sys
import tempfile
import time
import zlib
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
CONTROL_DIR = ROOT / "tools" / "control"
sys.path.insert(0, str(CONTROL_DIR))

import control_client  # noqa: E402

SCHEMA = "grass-maps-map-goldens/1"
PACKAGE = "nl.r1a.grassmap"
ACTIVITY = "nl.r1a.grassmap.app.MainActivity"
SCREENSHOT_SETTLE_S = 0.8
# A partial or mid-flight frame can be composited after MapLibre reports idle.
# Two frames must agree before the capture is accepted, up to three frames.
STABILITY_GAP_S = 0.7
STABILITY_MAX_FRAMES = 3
STABILITY_MAX_MISMATCH_PERCENT = 0.5
# Software GL under swiftshader may not deliver MapLibre's idle event at all,
# even for a fully settled frame. The extra allowance below is added when the
# camera command reports `idle=false`: base settle plus one slow software frame
# (2.0 s), so the capture waits 2.8 s in total before reading the display.
SLOW_FRAME_ALLOWANCE_S = 2.0
# Software GL also trips SystemUI's ANR watchdog on the CI emulator. The error
# dialog dims the map and would enter every golden, so ANR/crash dialogs are
# hidden before launch and any existing dialog is closed.
LAUNCH_SETTLE_S = 5.0
FOCUS_RETRY_TIMEOUT_S = 10.0
FOCUS_RETRY_PAUSE_S = 0.5
# A null or foreign focus can mean the app is simply not foregrounded yet.
# Bring it forward once per focus check, then give the window time to settle.
APP_FOREGROUND_SETTLE_S = 2.0
# If focus still fails after the bounded check, redo the whole position once:
# the camera move and settle are idempotent, and the second attempt sees a
# foregrounded app. A persistent wrong focus still fails.
POSITION_ATTEMPTS = 2
# An ANR dialog that was already up before the suppression settings landed is
# dismissed by its own buttons, found through a uiautomator dump rather than
# fixed coordinates: `hide_error_dialogs` only suppresses new dialogs.
ANR_DISMISS_ATTEMPTS = 3
ANR_DISMISS_SETTLE_S = 1.0
UI_DUMP_PATH = "/data/local/tmp/grass-map-golden-window.xml"
CHANNEL_TIMEOUT_S = 60.0
CAMERA_READY_TIMEOUT_S = 45.0
# `core.map.set_camera` answers only after MapLibre reports idle (up to 15 s on
# the device). The RPC socket must stay open longer than that, or a slow
# renderer answers after the client has already timed out.
INVOKE_TIMEOUT_S = 30.0
RETRY_PAUSE_S = 0.5
# The first install after the dialog-suppression reboot can land while the
# package manager is still finishing its own startup and rejected the APK;
# the same command succeeds a few seconds later.
INSTALL_ATTEMPTS = 3
INSTALL_RETRY_PAUSE_S = 5.0
# The first camera command can run before the app has drawn anything under
# swiftshader, leaving a black capture. The debug harness logs its first full
# frame, so wait for that line before the first position.
# The authored Standard style compiles more layers than the T-0020 bench style,
# and swiftshader needs longer than a minute for the first full frame on the CI
# emulator (T-0108: style-loaded at 46 s, first frame just after 60 s).
FIRST_FRAME_TIMEOUT_S = 180.0
DEVICE_DEADLINE_S = 180.0
BLOCK_DELTA = 32
MAX_MISMATCH_PERCENT = 5.0
GRID_WIDTH = 108
GRID_HEIGHT = 192
DEVICE_REGION_STAGE = "/data/local/tmp/grass-map-golden-region"
# The app's Standard style ids. `light` is the default id the app loads.
STYLE_IDS = {
    "light": "standard",
    "dark": "standard-dark",
}

# Ten fixture places at three zooms. The fixture is the CI map (T-0015), so the
# style goldens stay small and need no region archive.
STANDARD_PLACES = (
    ("place-a", 49.9420, 23.9440),
    ("place-b", 49.9420, 23.9560),
    ("place-c", 49.9460, 23.9440),
    ("place-d", 49.9460, 23.9560),
    ("place-e", 49.9500, 23.9440),
    ("place-f", 49.9500, 23.9560),
    ("place-g", 49.9540, 23.9440),
    ("place-h", 49.9540, 23.9560),
    ("place-i", 49.9580, 23.9440),
    ("place-j", 49.9580, 23.9560),
)
STANDARD_ZOOMS = (12.0, 13.0, 14.0)

POSITIONS = {
    "standard": tuple(
        (f"{name}-z{int(zoom)}", lat, lon, zoom, 0.0, 0.0)
        for name, lat, lon in STANDARD_PLACES
        for zoom in STANDARD_ZOOMS
    ),
    "fixture": (
        ("fixture-center-z12", 49.9500, 23.9500, 12.0, 0.0, 0.0),
        ("fixture-center-z13", 49.9500, 23.9500, 13.0, 0.0, 0.0),
        ("fixture-west-z13", 49.9500, 23.9440, 13.0, 0.0, 0.0),
        ("fixture-east-z13", 49.9500, 23.9560, 13.0, 0.0, 0.0),
        ("fixture-north-z13", 49.9560, 23.9500, 13.0, 0.0, 0.0),
    ),
    "oblast": (
        ("lviv-rynok-z16", 49.8419, 24.0316, 16.0, 0.0, 0.0),
        ("lviv-rynok-z13", 49.8419, 24.0316, 13.0, 0.0, 0.0),
        ("lviv-sykhiv-z14", 49.7965, 24.0480, 14.0, 0.0, 0.0),
        ("zhovkva-z13", 50.0560, 23.9710, 13.0, 0.0, 0.0),
        ("skole-z12", 49.0380, 23.5130, 12.0, 0.0, 0.0),
    ),
}


class GoldenError(RuntimeError):
    """A capture or comparison step failed."""


class FocusError(GoldenError):
    """The app window does not own the focus at capture time."""


def run(
    command: list[str],
    timeout_s: float,
    check: bool = True,
) -> str:
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=timeout_s)
    except subprocess.TimeoutExpired as exc:
        raise GoldenError(f"timeout after {timeout_s:.0f}s: {' '.join(command)}") from exc
    if check and result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        raise GoldenError(
            f"exit {result.returncode}: {' '.join(command)}\n{detail[:600]}\n...\n{detail[-600:]}"
        )
    return result.stdout


class Adb:
    def __init__(self, serial: str) -> None:
        self.serial = serial
        self.prefix = ["adb", "-s", serial]

    def shell(self, *args: str, timeout_s: float = 120, check: bool = True) -> str:
        return run([*self.prefix, "shell", *args], timeout_s, check=check)

    def push(self, source: Path, target: str) -> None:
        run([*self.prefix, "push", str(source), target], 600)

    def install(self, apk: Path) -> None:
        last = ""
        for attempt in range(INSTALL_ATTEMPTS):
            try:
                result = subprocess.run(
                    [*self.prefix, "install", "-r", str(apk)],
                    capture_output=True,
                    text=True,
                    timeout=300,
                    check=False,
                )
            except subprocess.TimeoutExpired as exc:
                raise GoldenError(f"adb install timed out: {apk}") from exc
            if "Success" in result.stdout:
                return
            last = (result.stdout or "") + (result.stderr or "")
            if attempt + 1 < INSTALL_ATTEMPTS:
                time.sleep(INSTALL_RETRY_PAUSE_S)
        raise GoldenError(
            f"adb install did not report Success after {INSTALL_ATTEMPTS} attempts:\n"
            f"{last[:600]}\n...\n{last[-600:]}"
        )

    def screencap(self) -> bytes:
        """Capture the composited display, including the map's SurfaceView."""
        try:
            result = subprocess.run(
                [*self.prefix, "exec-out", "screencap", "-p"],
                capture_output=True,
                timeout=60,
                check=False,
            )
        except subprocess.TimeoutExpired as exc:
            raise GoldenError("adb exec-out screencap timed out") from exc
        if result.returncode != 0:
            raise GoldenError(
                f"adb exec-out screencap failed: {result.stderr.decode(errors='replace').strip()}"
            )
        if not result.stdout.startswith(PNG_SIGNATURE):
            raise GoldenError("adb exec-out screencap did not return a PNG")
        return result.stdout

    def logcat(self, *args: str, timeout_s: float = 60) -> str:
        return run([*self.prefix, "logcat", *args], timeout_s, check=False)

    def wait_for_device(self) -> None:
        deadline = time.monotonic() + DEVICE_DEADLINE_S
        while True:
            if self.shell("true", timeout_s=10, check=False) == "":
                return
            if time.monotonic() >= deadline:
                raise GoldenError(f"{self.serial} did not become available")
            time.sleep(2)


def airplane_mode(adb: Adb) -> list[str]:
    """Turn every radio off. The map reads local files only either way."""
    commands = [
        ("cmd", "connectivity", "airplane-mode", "enable"),
        ("settings", "put", "global", "airplane_mode_on", "1"),
        ("am", "broadcast", "-a", "android.intent.action.AIRPLANE_MODE", "--ez", "state", "true"),
        ("svc", "wifi", "disable"),
        ("svc", "data", "disable"),
    ]
    for command in commands:
        try:
            adb.shell(*command, timeout_s=20, check=False)
        except GoldenError as error:
            # A slow software emulator can miss the 20 s radio command budget.
            # The fixture reads local files only, so the capture continues and
            # the condition list names the command that did not answer.
            print(f"warning: radio setup did not finish: {error}", file=sys.stderr)
    return ["adb shell " + " ".join(command) for command in commands]


def resolve_package_path(source: Path) -> Path:
    """Accept a version directory or a region root that holds `current`."""
    if (source / "manifest.json").is_file():
        return source
    candidates = sorted(source.glob("*/*/manifest.json"))
    if len(candidates) == 1:
        return candidates[0].parent
    raise GoldenError(
        f"{source} is neither a package with manifest.json nor a region root with one version"
    )


def install_region_package(
    adb: Adb,
    package: Path,
) -> dict[str, str]:
    manifest = json.loads((package / "manifest.json").read_text(encoding="utf-8"))
    region = str(manifest["region"])
    version = str(manifest["version"])
    adb.shell("rm", "-rf", DEVICE_REGION_STAGE, timeout_s=60, check=False)
    adb.push(package, DEVICE_REGION_STAGE)
    region_root = f"files/regions/{region}"
    target = f"{region_root}/{version}"
    adb.shell("run-as", PACKAGE, "mkdir", "-p", region_root, timeout_s=60)
    adb.shell("run-as", PACKAGE, "mkdir", "-p", target, timeout_s=60)
    names = ["manifest.json"] + [str(component["file"]) for component in manifest["components"]]
    for name in names:
        adb.shell("run-as", PACKAGE, "cp", f"{DEVICE_REGION_STAGE}/{name}", f"{target}/{name}", timeout_s=120)
    with tempfile.NamedTemporaryFile("w", suffix="-current", delete=False) as handle:
        handle.write(version + "\n")
        current = Path(handle.name)
    try:
        adb.push(current, f"{DEVICE_REGION_STAGE}/current")
    finally:
        current.unlink()
    adb.shell(
        "run-as",
        PACKAGE,
        "cp",
        f"{DEVICE_REGION_STAGE}/current",
        f"{region_root}/current",
        timeout_s=60,
    )
    return {"region": region, "version": version, "layout": target}


def push_external_archives(
    adb: Adb,
    archives: list[Path],
) -> list[str]:
    target = f"/sdcard/Android/data/{PACKAGE}/files"
    adb.shell("mkdir", "-p", target, timeout_s=30)
    for archive in archives:
        adb.push(archive, f"{target}/{archive.name}")
    return [f"{target}/{archive.name}" for archive in archives]


def clear_app_data(adb: Adb) -> None:
    adb.shell("pm", "clear", PACKAGE, timeout_s=60, check=False)
    adb.shell("am", "force-stop", PACKAGE, timeout_s=30, check=False)


def launch(adb: Adb) -> str:
    adb.shell("am", "force-stop", PACKAGE, timeout_s=30, check=False)
    return adb.shell("am", "start", "-W", "-n", f"{PACKAGE}/{ACTIVITY}", timeout_s=120)


def wait_for_channel(adb: Adb) -> control_client.ControlClient:
    deadline = time.monotonic() + CHANNEL_TIMEOUT_S
    while True:
        try:
            control_client.ensure_forward(serial=adb.serial)
            token = control_client.read_token(serial=adb.serial)
            client = control_client.connect(token=token, timeout=5.0)
            # The short timeout only bounds dialling and authentication. The
            # camera command answers after the map goes idle, so the open
            # channel must wait longer than the command's own 15 s bound.
            client.set_timeout(INVOKE_TIMEOUT_S)
            return client
        except (control_client.ControlError, TimeoutError) as error:
            if time.monotonic() >= deadline:
                raise GoldenError(f"control channel did not open: {error}") from error
            time.sleep(1.0)


def invoke_camera(
    client: control_client.ControlClient,
    params: dict[str, object],
) -> str:
    """Wait for the map surface to bind, then move the camera.

    The control channel opens before the first map host exists, so
    `core.map.set_camera` answers `not_ready` until `MapScreen` has bound its
    surface. A software renderer can also exceed the socket timeout while the
    command is still running, so a socket timeout is retried inside the same
    budget: the outstanding reply is queued by the client and the retried
    request moves the camera to the same position again.
    """
    deadline = time.monotonic() + CAMERA_READY_TIMEOUT_S
    while True:
        try:
            return str(client.invoke("core.map.set_camera", params))
        except control_client.ControlError as error:
            if error.kind != "not_ready" or time.monotonic() >= deadline:
                raise
        except TimeoutError as error:
            if time.monotonic() >= deadline:
                raise GoldenError(
                    f"camera command did not answer within {CAMERA_READY_TIMEOUT_S:.0f}s"
                ) from error
        time.sleep(RETRY_PAUSE_S)


def record_failure(
    adb: Adb,
    output: Path,
    stage: str,
    reason: str,
    camera_result: str | None = None,
) -> None:
    """Write the failing device state into the artifact directory.

    CI uploads the whole output directory with `if: always()`, so a failed run
    names the position it stopped at, the reason, the camera command result,
    the logcat around it and the frame the device showed, instead of only a
    traceback.
    """
    output.mkdir(parents=True, exist_ok=True)
    (output / "failure.json").write_text(
        json.dumps(
            {
                "stage": stage,
                "reason": reason,
                "camera_result": camera_result,
                "time": datetime.now(timezone.utc).isoformat(),
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    try:
        (output / "failure-logcat.txt").write_text(
            adb.logcat("-d", "-v", "threadtime"), encoding="utf-8"
        )
    except (GoldenError, OSError) as error:
        print(f"could not capture logcat after failure: {error}", file=sys.stderr)
    try:
        (output / "failure-screen.png").write_bytes(adb.screencap())
    except (GoldenError, OSError) as error:
        print(f"could not capture the failing screen: {error}", file=sys.stderr)


def prepare_device(adb: Adb) -> list[str]:
    """Hide system error dialogs and wake the screen before a golden run.

    Swiftshader is slow enough on the CI emulator that SystemUI can trip its
    own ANR watchdog while the map renders. The dialog dims the map and covers
    the comparison window, so error dialogs are hidden and any dialog already
    showing is closed before the app is launched. The screen is also woken and
    the keyguard dismissed: a sleeping display captures as a black frame.
    """
    commands = [
        ("settings", "put", "global", "hide_error_dialogs", "1"),
        ("settings", "put", "global", "anr_show_background", "0"),
        ("am", "broadcast", "-a", "android.intent.action.CLOSE_SYSTEM_DIALOGS"),
        ("input", "keyevent", "224"),
        ("wm", "dismiss-keyguard"),
        ("svc", "power", "stayon", "true"),
    ]
    for command in commands:
        adb.shell(*command, timeout_s=20, check=False)
    entries = ["adb shell " + " ".join(command) for command in commands]
    if is_anr_dialog(focused_window(adb)):
        dismissed = dismiss_anr_dialog(adb)
        entries.append(f"dismissed existing ANR dialog via Wait: {dismissed}")
    return entries


def dismiss_system_dialogs(adb: Adb) -> None:
    """Close a dialog that appeared between two captures."""
    adb.shell(
        "am",
        "broadcast",
        "-a",
        "android.intent.action.CLOSE_SYSTEM_DIALOGS",
        timeout_s=20,
        check=False,
    )


def focused_window(adb: Adb) -> str | None:
    """The `mCurrentFocus` line from `dumpsys window`, or None when absent."""
    raw = adb.shell("dumpsys", "window", timeout_s=30, check=False)
    for line in raw.splitlines():
        if "mCurrentFocus" in line:
            return line.strip()
    return None


def is_anr_dialog(focus: str | None) -> bool:
    return focus is not None and "Application Not Responding" in focus


def tap_anr_wait(adb: Adb) -> bool:
    """Tap the ANR dialog's Wait button, found by text in a uiautomator dump."""
    adb.shell("uiautomator", "dump", UI_DUMP_PATH, timeout_s=60, check=False)
    xml = adb.shell("cat", UI_DUMP_PATH, timeout_s=30, check=False)
    node = re.search(r'<node [^>]*text="Wait"[^>]*>', xml)
    if node is None:
        return False
    bounds = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node.group(0))
    if bounds is None:
        return False
    x1, y1, x2, y2 = (int(value) for value in bounds.groups())
    adb.shell(
        "input",
        "tap",
        str((x1 + x2) // 2),
        str((y1 + y2) // 2),
        timeout_s=20,
        check=False,
    )
    return True


def dismiss_anr_dialog(adb: Adb) -> bool:
    """Dismiss an existing ANR dialog; True when none remains."""
    for _ in range(ANR_DISMISS_ATTEMPTS):
        if not is_anr_dialog(focused_window(adb)):
            return True
        if not tap_anr_wait(adb):
            return False
        time.sleep(ANR_DISMISS_SETTLE_S)
    return not is_anr_dialog(focused_window(adb))


def bring_app_to_front(adb: Adb) -> None:
    """Start the map activity again so its window can take the focus."""
    adb.shell(
        "am",
        "start",
        "-W",
        "-n",
        f"{PACKAGE}/{ACTIVITY}",
        timeout_s=120,
        check=False,
    )


def require_app_focused(
    adb: Adb,
    stage: str,
    *,
    foreground: bool = True,
) -> str:
    """Fail with a named window when the app does not own the focus.

    A dialog on top of the map dims every pixel beneath it, and a null focus
    means the app window was not foregrounded yet; both would make a golden
    meaningless. An ANR dialog is dismissed by its own Wait button. A null or
    foreign focus is retried after bringing the app to the front when
    `foreground` is set; the call before the camera move uses that, while the
    assertion after the settle does not, so a recreate can never silently
    reset the camera under a capture. After the bounded budget a persistent
    wrong focus is named instead of silently comparing the wrong window.
    """
    deadline = time.monotonic() + FOCUS_RETRY_TIMEOUT_S
    brought_to_front = False
    while True:
        focus = focused_window(adb)
        if focus is not None and PACKAGE in focus:
            return focus
        if is_anr_dialog(focus):
            dismiss_anr_dialog(adb)
        elif foreground and not brought_to_front:
            bring_app_to_front(adb)
            brought_to_front = True
            time.sleep(APP_FOREGROUND_SETTLE_S)
        if time.monotonic() >= deadline:
            raise FocusError(f"{stage}: the focused window is not {PACKAGE}: {focus}")
        time.sleep(FOCUS_RETRY_PAUSE_S)


def capture_position(
    adb: Adb,
    client: control_client.ControlClient,
    output: Path,
    name: str,
    params: dict[str, object],
) -> dict[str, object]:
    """Foreground the app, move the camera, settle, assert focus, and capture.

    `idle=false` is not an abort. Swiftshader can render a fully settled frame
    without ever delivering MapLibre's idle event, so the shot waits the base
    settle plus one slow-frame allowance and is marked `"idle": false` in
    `capture.json`; the pixel comparison is the acceptance, not the signal.
    Transport errors and the `not_ready` budget still abort in
    [invoke_camera]. A focus failure retries the whole position once before it
    aborts, which re-applies the camera after the app is foregrounded.
    """
    for attempt in range(POSITION_ATTEMPTS):
        require_app_focused(adb, name)
        result = invoke_camera(client, params)
        idle = "idle=true" in result
        settle = SCREENSHOT_SETTLE_S if idle else SCREENSHOT_SETTLE_S + SLOW_FRAME_ALLOWANCE_S
        dismiss_system_dialogs(adb)
        time.sleep(settle)
        try:
            require_app_focused(adb, name, foreground=False)
        except FocusError:
            if attempt + 1 >= POSITION_ATTEMPTS:
                raise
            bring_app_to_front(adb)
            time.sleep(APP_FOREGROUND_SETTLE_S)
            continue
        png, stability = stable_frame(adb)
        path = output / f"{name}.png"
        path.write_bytes(png)
        return {
            "name": name,
            "params": params,
            "file": str(path),
            "capture": "adb exec-out screencap -p",
            "idle": idle,
            "stability": stability,
            "camera_result": result,
        }
    raise FocusError(f"{name}: no capture after {POSITION_ATTEMPTS} attempts")


def stable_frame(adb: Adb) -> tuple[bytes, dict[str, object]]:
    """Capture until two consecutive composited frames agree.

    A partial frame can appear after idle on the software renderer; comparing
    two live frames catches it without touching the golden metric.
    """
    frames = [adb.screencap()]
    mismatch = 0.0
    for _ in range(1, STABILITY_MAX_FRAMES):
        time.sleep(STABILITY_GAP_S)
        frames.append(adb.screencap())
        mismatch = frame_mismatch(frames[-2], frames[-1])
        if mismatch <= STABILITY_MAX_MISMATCH_PERCENT:
            break
    return frames[-1], {
        "frames": len(frames),
        "last_mismatch_percent": mismatch,
        "stable": mismatch <= STABILITY_MAX_MISMATCH_PERCENT,
    }


def frame_mismatch(before: bytes, after: bytes) -> float:
    with tempfile.NamedTemporaryFile(suffix=".png") as first, tempfile.NamedTemporaryFile(
        suffix=".png"
    ) as second:
        first.write(before)
        first.flush()
        second.write(after)
        second.flush()
        return float(compare_images(Path(first.name), Path(second.name))["mismatch_percent"])


def wait_for_first_frame(adb: Adb, timeout_s: float = FIRST_FRAME_TIMEOUT_S) -> None:
    """Wait until the debug harness logs its first full map frame."""
    deadline = time.monotonic() + timeout_s
    while True:
        if "first-full-frame-ms=" in adb.logcat("-d", "-v", "brief", timeout_s=30):
            return
        if time.monotonic() >= deadline:
            raise GoldenError("the map did not report its first full frame")
        time.sleep(1.0)


def capture(
    adb: Adb,
    profile: str,
    output: Path,
    style: str | None,
) -> dict[str, object]:
    if profile == "oblast" and not (output / "region.json").exists():
        raise GoldenError("oblast capture needs the installed region package")
    conditions = {
        "airplane_mode": airplane_mode(adb),
        "system_dialogs": prepare_device(adb),
    }
    if output.exists():
        for stale in output.glob("*.png"):
            stale.unlink()
    output.mkdir(parents=True, exist_ok=True)
    stage = "launch"
    camera_result = None
    try:
        adb.logcat("-c", timeout_s=15)
        launch(adb)
        client = wait_for_channel(adb)
        # Let the first render spike finish before the first camera move; the
        # swiftshader renderer is still catching up when `am start -W` returns.
        time.sleep(LAUNCH_SETTLE_S)
        wait_for_first_frame(adb)
        if style is not None:
            selected = client.invoke("core.map.set_style", {"style": STYLE_IDS[style]})
            if f"style={STYLE_IDS[style]}" not in str(selected):
                raise GoldenError(f"style {style} was not selected: {selected}")
            print(f"selected style {style} -> {selected}", file=sys.stderr)
        started = datetime.now(timezone.utc).isoformat()
        shots = []
        for name, lat, lon, zoom, bearing, tilt in POSITIONS[profile]:
            stage = name
            params = {
                "lat": lat,
                "lon": lon,
                "zoom": zoom,
                "bearing": bearing,
                "tilt": tilt,
            }
            shot = capture_position(adb, client, output, name, params)
            camera_result = str(shot["camera_result"])
            shots.append(shot)
            print(f"captured {name} -> {shot['file']} idle={shot['idle']}", file=sys.stderr)
    except Exception as error:
        reason = f"{type(error).__name__}: {error}"
        try:
            record_failure(adb, output, stage, reason, camera_result)
        except (GoldenError, OSError) as record_error:
            print(f"could not record the failure: {record_error}", file=sys.stderr)
        raise
    (output / "capture.json").write_text(
        json.dumps(
            {
                "schema": SCHEMA,
                "profile": profile,
                "style": style,
                "device_serial": adb.serial,
                "started": started,
                "captured": datetime.now(timezone.utc).isoformat(),
                "conditions": conditions,
                "camera_command": "core.map.set_camera",
                "screenshot": "adb exec-out screencap -p",
                "positions": shots,
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    (output / "logcat.txt").write_text(adb.logcat("-d", "-v", "threadtime"), encoding="utf-8")
    return {"shots": shots, "conditions": conditions}


PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def _paeth(left: int, above: int, upper_left: int) -> int:
    estimate = left + above - upper_left
    distance_left = abs(estimate - left)
    distance_above = abs(estimate - above)
    distance_upper_left = abs(estimate - upper_left)
    if distance_left <= distance_above and distance_left <= distance_upper_left:
        return left
    if distance_above <= distance_upper_left:
        return above
    return upper_left


def read_png(path: Path) -> tuple[int, int, bytes]:
    """Decode an 8-bit, non-interlaced PNG to packed RGB bytes."""
    data = path.read_bytes()
    if data[:8] != PNG_SIGNATURE:
        raise GoldenError(f"{path}: not a PNG")
    position = 8
    width = height = bit_depth = color_type = interlace = None
    compressed = bytearray()
    while position + 8 <= len(data):
        length = int.from_bytes(data[position : position + 4], "big")
        kind = data[position + 4 : position + 8]
        chunk = data[position + 8 : position + 8 + length]
        position += 12 + length
        if kind == b"IHDR":
            width, height, bit_depth, color_type, _, _, interlace = struct.unpack(">IIBBBBB", chunk)
        elif kind == b"IDAT":
            compressed += chunk
        elif kind == b"IEND":
            break
    if width is None or height is None:
        raise GoldenError(f"{path}: no IHDR")
    if bit_depth != 8 or interlace != 0:
        raise GoldenError(f"{path}: unsupported PNG (depth {bit_depth}, interlace {interlace})")
    channels = {0: 1, 2: 3, 4: 2, 6: 4}.get(color_type)
    if channels is None:
        raise GoldenError(f"{path}: unsupported PNG colour type {color_type}")
    try:
        raw = zlib.decompress(bytes(compressed))
    except zlib.error as error:
        raise GoldenError(f"{path}: bad PNG data: {error}") from error
    stride = width * channels
    if len(raw) < height * (stride + 1):
        raise GoldenError(f"{path}: truncated PNG data")
    rows = bytearray(height * stride)
    previous = bytearray(stride)
    source = 0
    for y in range(height):
        filter_type = raw[source]
        source += 1
        line = bytearray(raw[source : source + stride])
        source += stride
        if filter_type == 0:
            pass
        elif filter_type == 1:
            for index in range(stride):
                left = line[index - channels] if index >= channels else 0
                line[index] = (line[index] + left) & 0xFF
        elif filter_type == 2:
            for index in range(stride):
                line[index] = (line[index] + previous[index]) & 0xFF
        elif filter_type == 3:
            for index in range(stride):
                left = line[index - channels] if index >= channels else 0
                line[index] = (line[index] + ((left + previous[index]) >> 1)) & 0xFF
        elif filter_type == 4:
            for index in range(stride):
                left = line[index - channels] if index >= channels else 0
                upper_left = previous[index - channels] if index >= channels else 0
                line[index] = (line[index] + _paeth(left, previous[index], upper_left)) & 0xFF
        else:
            raise GoldenError(f"{path}: bad PNG filter {filter_type}")
        rows[y * stride : (y + 1) * stride] = line
        previous = line
    if color_type == 2:
        return width, height, bytes(rows)
    rgb = bytearray(width * height * 3)
    for index in range(width * height):
        base = index * channels
        if color_type == 6:
            rgb[index * 3 : index * 3 + 3] = rows[base : base + 3]
        elif color_type == 0:
            value = rows[base]
            rgb[index * 3 : index * 3 + 3] = bytes((value, value, value))
        else:
            value = rows[base]
            rgb[index * 3 : index * 3 + 3] = bytes((value, value, value))
    return width, height, bytes(rgb)


def write_png(path: Path, width: int, height: int, rgb: bytes) -> None:
    def chunk(kind: bytes, body: bytes) -> bytes:
        return (
            len(body).to_bytes(4, "big")
            + kind
            + body
            + zlib.crc32(kind + body).to_bytes(4, "big")
        )

    rows = bytearray()
    stride = width * 3
    for y in range(height):
        rows.append(0)
        rows += rgb[y * stride : (y + 1) * stride]
    document = (
        PNG_SIGNATURE
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(bytes(rows), 9))
        + chunk(b"IEND", b"")
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(document)


def comparison_window(width: int, height: int) -> tuple[int, int, int, int]:
    """The centre 16:9 map area, then its central half.

    The outer crop drops the status and navigation bars. The inner half is
    540 x 960 on a 1080-wide device: it excludes the scale bar, control bar and
    attribution, which are anchored to the screen edges and would otherwise
    move between a 16:9 and a 20:9 device.
    """
    area_height = min(height, width * 16 // 9)
    area_top = (height - area_height) // 2
    window_width = width // 2
    window_height = area_height // 2
    left = (width - window_width) // 2
    top = area_top + (area_height - window_height) // 2
    return left, top, window_width, window_height


def block_grid(width: int, height: int, rgb: bytes) -> list[tuple[int, int, int]]:
    left, top, window_width, window_height = comparison_window(width, height)
    blocks = []
    for gy in range(GRID_HEIGHT):
        y0 = top + gy * window_height // GRID_HEIGHT
        y1 = top + (gy + 1) * window_height // GRID_HEIGHT
        for gx in range(GRID_WIDTH):
            x0 = left + gx * window_width // GRID_WIDTH
            x1 = left + (gx + 1) * window_width // GRID_WIDTH
            red = green = blue = count = 0
            for y in range(y0, y1):
                row = y * width * 3
                for x in range(x0, x1):
                    base = row + x * 3
                    red += rgb[base]
                    green += rgb[base + 1]
                    blue += rgb[base + 2]
                    count += 1
            blocks.append((red // count, green // count, blue // count))
    return blocks


def compare_images(golden: Path, candidate: Path) -> dict[str, object]:
    golden_width, golden_height, golden_rgb = read_png(golden)
    candidate_width, candidate_height, candidate_rgb = read_png(candidate)
    golden_blocks = block_grid(golden_width, golden_height, golden_rgb)
    candidate_blocks = block_grid(candidate_width, candidate_height, candidate_rgb)
    mismatched = 0
    total_delta = 0
    diff = bytearray()
    for before, after in zip(golden_blocks, candidate_blocks):
        delta = max(abs(before[index] - after[index]) for index in range(3))
        total_delta += delta
        if delta > BLOCK_DELTA:
            mismatched += 1
            diff += bytes((255, 0, 0))
        else:
            shade = (before[0] + before[1] + before[2]) // 3
            diff += bytes((shade, shade, shade))
    blocks = len(golden_blocks)
    percent = round(100.0 * mismatched / blocks, 3)
    return {
        "golden": str(golden),
        "captured": str(candidate),
        "golden_size": f"{golden_width}x{golden_height}",
        "captured_size": f"{candidate_width}x{candidate_height}",
        "blocks": blocks,
        "mismatched_blocks": mismatched,
        "mismatch_percent": percent,
        "mean_block_delta": round(total_delta / blocks, 2),
        "pass": percent <= MAX_MISMATCH_PERCENT,
        "_diff": bytes(diff),
    }


def compare(
    profile: str,
    captured: Path,
    reference: Path,
    output: Path,
    style: str | None,
) -> dict[str, object]:
    positions = []
    for name, *_ in POSITIONS[profile]:
        golden = reference / f"{name}.png"
        candidate = captured / f"{name}.png"
        if not golden.is_file():
            raise GoldenError(f"missing reference {golden}")
        if not candidate.is_file():
            raise GoldenError(f"missing capture {candidate}")
        result = compare_images(golden, candidate)
        diff_rgb = result.pop("_diff")
        write_png(output / f"{name}.diff.png", GRID_WIDTH, GRID_HEIGHT, diff_rgb)
        positions.append({"name": name, **result})
        print(
            f"{name}: {result['mismatch_percent']}% blocks, "
            f"mean delta {result['mean_block_delta']} -> "
            f"{'pass' if result['pass'] else 'FAIL'}",
            file=sys.stderr,
        )
    passed = all(position["pass"] for position in positions)
    document = {
        "schema": SCHEMA,
        "profile": profile,
        "style": style,
        "tolerance": {
            "block_delta": BLOCK_DELTA,
            "max_mismatch_percent": MAX_MISMATCH_PERCENT,
            "metric": "centre 16:9 area, central half, 108x192 mean-RGB blocks",
        },
        "positions": positions,
        "result": "pass" if passed else "fail",
    }
    output.mkdir(parents=True, exist_ok=True)
    (output / "comparison.json").write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    return document


def update_reference(profile: str, captured: Path, reference: Path) -> None:
    reference.mkdir(parents=True, exist_ok=True)
    for name, *_ in POSITIONS[profile]:
        source = captured / f"{name}.png"
        if not source.is_file():
            raise GoldenError(f"missing capture {source}")
        width, height, rgb = read_png(source)
        write_png(reference / f"{name}.png", width, height, rgb)
        print(f"reference {name} <- {source}", file=sys.stderr)


def default_reference(profile: str, style: str | None) -> Path:
    path = ROOT / "test-data" / "map-goldens" / profile
    return path / style if style else path


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Capture and compare map goldens.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    capture_parser = subparsers.add_parser("capture")
    capture_parser.add_argument("--serial", default=None)
    capture_parser.add_argument("--profile", choices=sorted(POSITIONS), required=True)
    capture_parser.add_argument("--apk", default=None)
    capture_parser.add_argument("--output", required=True)
    capture_parser.add_argument("--region-package", default=None)
    capture_parser.add_argument("--style", choices=sorted(STYLE_IDS), default=None)
    capture_parser.add_argument("--keep-data", action="store_true")

    compare_parser = subparsers.add_parser("compare")
    compare_parser.add_argument("--profile", choices=sorted(POSITIONS), required=True)
    compare_parser.add_argument("--captured", required=True)
    compare_parser.add_argument("--style", choices=sorted(STYLE_IDS), default=None)
    compare_parser.add_argument("--reference", default=None)
    compare_parser.add_argument("--output", default=None)
    compare_parser.add_argument("--update", action="store_true")

    return parser


def main() -> int:
    args = build_parser().parse_args()
    try:
        if args.command == "capture":
            serial = args.serial or control_client.adb(["get-serialno"]).stdout.strip()
            adb = Adb(serial)
            adb.wait_for_device()
            output = Path(args.output)
            if args.apk:
                try:
                    adb.install(Path(args.apk))
                except GoldenError as error:
                    record_failure(adb, output, "install", str(error))
                    raise
            if not args.keep_data:
                clear_app_data(adb)
            if args.profile == "oblast":
                if not args.region_package:
                    raise GoldenError("oblast capture requires --region-package")
                package = resolve_package_path(Path(args.region_package))
                metadata = install_region_package(adb, package)
                (output / "region.json").parent.mkdir(parents=True, exist_ok=True)
                (output / "region.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
            capture(adb, args.profile, output, args.style)
            return 0
        reference = (
            Path(args.reference)
            if args.reference
            else default_reference(args.profile, args.style)
        )
        output = Path(args.output) if args.output else Path(args.captured)
        if args.update:
            update_reference(args.profile, Path(args.captured), reference)
            return 0
        document = compare(args.profile, Path(args.captured), reference, output, args.style)
        return 0 if document["result"] == "pass" else 1
    except GoldenError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
