#!/usr/bin/env python3
"""Capture and compare the T-0320 navigation screen goldens.

The screen is deterministic because a debug command fills it with a fixed
navigation state, the committed `test-data/navigation/screen-route.json`. The
screen is still the production composable: the command supplies the same values
a published state does, not a picture. The map behind it is set by
`core.map.set_camera`, and the theme is the system night mode plus the matching
Standard style.

Comparison uses the same 108x192 mean-block grid as the map goldens, applied to
the whole frame: the turn card and the End/ETA/expand bar are the subject, so the
map goldens' centre crop would remove them.

Usage:
    navigation_goldens.py capture --serial emulator-5580 \
        --apk app/build/outputs/apk/debug/app-debug.apk \
        --output build/map-goldens/navigation [--update]
    navigation_goldens.py compare --captured build/map-goldens/navigation
"""

from __future__ import annotations

import argparse
import json
import shutil
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
ROOT = SCRIPT_DIR.parent.parent
sys.path.insert(0, str(SCRIPT_DIR))

import map_goldens as g  # noqa: E402

SCHEMA = "grass-maps-navigation-goldens/1"
REFERENCE = ROOT / "test-data" / "navigation-goldens"
ROUTE_PATH = ROOT / "test-data" / "navigation" / "screen-route.json"
ROUTE = json.loads(ROUTE_PATH.read_text(encoding="utf-8"))
STYLE_IDS = {"light": "standard", "dark": "standard-dark"}
THEMES = ("light", "dark")
CASES = (
    ("following", "collapsed", ()),
    ("expanded", "trip", ()),
    ("alert", "collapsed", ("crossing",)),
)
CAMERA = {"lat": 49.9480, "lon": 23.9460, "zoom": 15.0, "bearing": 0.0, "tilt": 45.0}
FIXTURE_SETTLE_S = 1.2


def fixture_params(
    sheet: str,
    dark: bool,
    alerts: tuple[str, ...],
) -> dict[str, object]:
    return {
        "route": ROUTE,
        "phase": "following",
        "sheet": sheet,
        "speed_kmh": 4.8,
        "along_m": 0.0,
        "destination": "Lviv Opera",
        "next_stop": "Svit Kavy",
        "alerts": list(alerts),
        "dark": dark,
    }


def invoke_fixture(client, params: dict[str, object]) -> str:
    """Invoke the screen fixture, retrying the pre-surface `not_ready`."""
    deadline = time.monotonic() + g.CAMERA_READY_TIMEOUT_S
    while True:
        try:
            return str(client.invoke("core.nav.show_screen", params))
        except g.control_client.ControlError as error:
            if error.kind != "not_ready" or time.monotonic() >= deadline:
                raise
        except TimeoutError as error:
            if time.monotonic() >= deadline:
                raise g.GoldenError("screen fixture did not answer") from error
        time.sleep(g.RETRY_PAUSE_S)


def wait_for_fixture(adb: g.Adb, marker: str) -> None:
    deadline = time.monotonic() + g.CAMERA_READY_TIMEOUT_S
    while True:
        if marker in adb.logcat("-d", "-v", "brief", timeout_s=30):
            return
        if time.monotonic() >= deadline:
            raise g.GoldenError(f"the screen fixture did not log {marker}")
        time.sleep(1.0)


def set_night_mode(adb: g.Adb, theme: str) -> None:
    adb.shell("cmd", "uimode", "night", "yes" if theme == "dark" else "no", timeout_s=30, check=False)


def capture_theme(
    adb: g.Adb,
    theme: str,
    output: Path,
) -> list[dict[str, object]]:
    output.mkdir(parents=True, exist_ok=True)
    set_night_mode(adb, theme)
    adb.logcat("-c", timeout_s=15)
    g.launch(adb, contours=False)
    client = g.wait_for_channel(adb)
    time.sleep(g.LAUNCH_SETTLE_S)
    g.wait_for_first_frame(adb)
    style = g.invoke_style(client, theme)
    print(f"{theme}: selected style -> {style}", file=sys.stderr)
    g.invoke_camera(client, CAMERA)
    shots = []
    for name, sheet, alerts in CASES:
        marker = f"navigation-screen-fixture phase=following sheet={sheet}"
        result = invoke_fixture(client, fixture_params(sheet, theme == "dark", alerts))
        wait_for_fixture(adb, marker)
        time.sleep(FIXTURE_SETTLE_S)
        shot = g.capture_position(
            adb,
            client,
            output,
            name,
            dict(CAMERA),
        )
        shots.append(shot | {"fixture": result, "sheet": sheet})
        print(f"{theme}/{name}: {shot['file']} idle={shot['idle']}", file=sys.stderr)
    return shots


def capture(
    serial: str,
    apk: Path,
    output: Path,
) -> None:
    adb = g.Adb(serial)
    adb.wait_for_device()
    conditions = {
        "airplane_mode": g.airplane_mode(adb),
        "system_dialogs": g.prepare_device(adb),
    }
    adb.install(apk)
    if output.exists():
        shutil.rmtree(output)
    output.mkdir(parents=True, exist_ok=True)
    started = datetime.now(timezone.utc).isoformat()
    all_shots = []
    for theme in THEMES:
        all_shots += capture_theme(adb, theme, output / theme)
    (output / "capture.json").write_text(
        json.dumps(
            {
                "schema": SCHEMA,
                "device_serial": serial,
                "started": started,
                "captured": datetime.now(timezone.utc).isoformat(),
                "conditions": conditions,
                "camera": CAMERA,
                "route": str(ROUTE_PATH.relative_to(ROOT)),
                "fixture_command": "core.nav.show_screen",
                "screenshot": "adb exec-out screencap -p",
                "shots": all_shots,
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )
    (output / "logcat.txt").write_text(adb.logcat("-d", "-v", "threadtime"), encoding="utf-8")


def compare(
    captured: Path,
    reference: Path,
    output: Path | None,
    update: bool,
) -> int:
    failures = 0
    results = []
    for theme in THEMES:
        for name, _, _ in CASES:
            reference_path = reference / theme / f"{name}.png"
            captured_path = captured / theme / f"{name}.png"
            if not captured_path.exists():
                print(f"missing capture: {captured_path}", file=sys.stderr)
                failures += 1
                continue
            if update:
                reference_path.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(captured_path, reference_path)
                results.append(
                    {
                        "golden": str(reference_path),
                        "captured": str(captured_path),
                        "updated": True,
                    }
                )
                continue
            if not reference_path.exists():
                print(f"missing reference: {reference_path}", file=sys.stderr)
                failures += 1
                continue
            reference_width, reference_height, _ = g.read_png(reference_path)
            captured_width, captured_height, _ = g.read_png(captured_path)
            if (reference_width, reference_height) != (captured_width, captured_height):
                print(
                    f"size mismatch for {theme}/{name}: {reference_width}x{reference_height} "
                    f"vs {captured_width}x{captured_height}",
                    file=sys.stderr,
                )
                failures += 1
                continue
            result = g.compare_images(
                reference_path,
                captured_path,
                window=(0, 0, captured_width, captured_height),
            )
            passed = bool(result["pass"])
            if not passed:
                failures += 1
            diff = result.pop("_diff")
            if output is not None:
                diff_path = output / theme / f"{name}-diff.png"
                diff_path.parent.mkdir(parents=True, exist_ok=True)
                g.write_png(diff_path, g.GRID_WIDTH, g.GRID_HEIGHT, diff)
                result["diff"] = str(diff_path)
            results.append(result)
            print(
                f"{theme}/{name}: mismatch={result['mismatch_percent']}% "
                f"mean_delta={result['mean_block_delta']} pass={passed}",
                file=sys.stderr,
            )
    if output is not None:
        (output / "comparison.json").write_text(
            json.dumps({"results": results, "pass": failures == 0}, indent=2) + "\n",
            encoding="utf-8",
        )
    return 0 if failures == 0 else 1


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    capture_parser = sub.add_parser("capture")
    capture_parser.add_argument("--serial", required=True)
    capture_parser.add_argument("--apk", type=Path, required=True)
    capture_parser.add_argument("--output", type=Path, required=True)
    compare_parser = sub.add_parser("compare")
    compare_parser.add_argument("--captured", type=Path, required=True)
    compare_parser.add_argument("--reference", type=Path, default=REFERENCE)
    compare_parser.add_argument("--output", type=Path)
    compare_parser.add_argument("--update", action="store_true")
    return parser


def main() -> int:
    args = build_parser().parse_args()
    if args.command == "capture":
        capture(args.serial, args.apk, args.output)
        return 0
    return compare(args.captured, args.reference, args.output, args.update)


if __name__ == "__main__":
    raise SystemExit(main())
