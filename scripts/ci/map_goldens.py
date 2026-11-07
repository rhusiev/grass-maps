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
CHANNEL_TIMEOUT_S = 60.0
CAMERA_READY_TIMEOUT_S = 45.0
DEVICE_DEADLINE_S = 180.0
BLOCK_DELTA = 32
MAX_MISMATCH_PERCENT = 5.0
GRID_WIDTH = 108
GRID_HEIGHT = 192
DEVICE_REGION_STAGE = "/data/local/tmp/grass-map-golden-region"

POSITIONS = {
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
        raise GoldenError(f"exit {result.returncode}: {' '.join(command)}\n{detail[-600:]}")
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
        result = run([*self.prefix, "install", "-r", str(apk)], 300)
        if "Success" not in result:
            raise GoldenError(f"adb install did not report Success:\n{result.strip()}")

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
        adb.shell(*command, timeout_s=20, check=False)
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
            return control_client.connect(token=token, timeout=5.0)
        except control_client.ControlError as error:
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
    surface.
    """
    deadline = time.monotonic() + CAMERA_READY_TIMEOUT_S
    while True:
        try:
            return str(client.invoke("core.map.set_camera", params))
        except control_client.ControlError as error:
            if error.kind != "not_ready" or time.monotonic() >= deadline:
                raise
            time.sleep(0.5)


def capture(
    adb: Adb,
    profile: str,
    output: Path,
) -> dict[str, object]:
    if profile == "oblast" and not (output / "region.json").exists():
        raise GoldenError("oblast capture needs the installed region package")
    conditions = {"airplane_mode": airplane_mode(adb)}
    if output.exists():
        for stale in output.glob("*.png"):
            stale.unlink()
    output.mkdir(parents=True, exist_ok=True)
    launch(adb)
    client = wait_for_channel(adb)
    started = datetime.now(timezone.utc).isoformat()
    shots = []
    for name, lat, lon, zoom, bearing, tilt in POSITIONS[profile]:
        params = {
            "lat": lat,
            "lon": lon,
            "zoom": zoom,
            "bearing": bearing,
            "tilt": tilt,
        }
        result = invoke_camera(client, params)
        if "idle=true" not in result:
            raise GoldenError(f"{name}: camera command did not reach idle: {result}")
        time.sleep(SCREENSHOT_SETTLE_S)
        png = adb.screencap()
        path = output / f"{name}.png"
        path.write_bytes(png)
        shots.append({"name": name, "params": params, "file": str(path), "capture": "adb exec-out screencap -p"})
        print(f"captured {name} -> {path}", file=sys.stderr)
    (output / "capture.json").write_text(
        json.dumps(
            {
                "schema": SCHEMA,
                "profile": profile,
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


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Capture and compare map goldens.")
    subparsers = parser.add_subparsers(dest="command", required=True)

    capture_parser = subparsers.add_parser("capture")
    capture_parser.add_argument("--serial", default=None)
    capture_parser.add_argument("--profile", choices=sorted(POSITIONS), required=True)
    capture_parser.add_argument("--apk", default=None)
    capture_parser.add_argument("--output", required=True)
    capture_parser.add_argument("--region-package", default=None)
    capture_parser.add_argument("--keep-data", action="store_true")

    compare_parser = subparsers.add_parser("compare")
    compare_parser.add_argument("--profile", choices=sorted(POSITIONS), required=True)
    compare_parser.add_argument("--captured", required=True)
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
            if args.apk:
                adb.install(Path(args.apk))
            output = Path(args.output)
            if not args.keep_data:
                clear_app_data(adb)
            if args.profile == "oblast":
                if not args.region_package:
                    raise GoldenError("oblast capture requires --region-package")
                package = resolve_package_path(Path(args.region_package))
                metadata = install_region_package(adb, package)
                (output / "region.json").parent.mkdir(parents=True, exist_ok=True)
                (output / "region.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
            capture(adb, args.profile, output)
            return 0
        reference = (
            Path(args.reference)
            if args.reference
            else ROOT / "test-data" / "map-goldens" / args.profile
        )
        output = Path(args.output) if args.output else Path(args.captured)
        if args.update:
            update_reference(args.profile, Path(args.captured), reference)
            return 0
        document = compare(args.profile, Path(args.captured), reference, output)
        return 0 if document["result"] == "pass" else 1
    except GoldenError as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
