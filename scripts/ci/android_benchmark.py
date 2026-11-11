#!/usr/bin/env python3
"""Measure cold start and frame pacing of the debug app on an attached device.

The result is JSON on stdout and in a file under build/benchmarks/ by default.
Emulator numbers are trend numbers only: they are not the Moto g32 acceptance
gate. Every adb call is bounded, so a dead device fails instead of hanging.

The frame window covers a fresh process start and a fixed rotation interaction,
which is the first-frame path the current app has. When the map arrives, the
same window will contain map frames, but `dumpsys gfxinfo` still describes only
the app window; map SurfaceView frames need the SurfaceFlinger latch method the
bench RESULTS.md describes.

Usage:
    android_benchmark.py --apk app/build/outputs/apk/debug/app-debug.apk
    android_benchmark.py --serial emulator-5580 --runs 5 --output result.json
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import re
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = "grass-maps-benchmark/1"
KIND = "emulator-cold-start-frame-pacing"
PACKAGE = "nl.r1a.grassmap"
ACTIVITY = "nl.r1a.grassmap.app.MainActivity"
DEFAULT_RUNS = 5
INSTALL_TIMEOUT_S = 300
SETTLE_BETWEEN_RUNS_S = 1.0
INTERACTION_SETTLE_S = 1.0
# A gap longer than this is the app idling between the launch and the
# interaction, not a slow frame. Same rule as the bench frame-timeline method.
IDLE_GAP_MS = 250.0
# An interval over 1.5 periods means at least one vsync passed without a frame.
LATE_FACTOR = 1.5
ASSUMED_REFRESH_HZ = 60.0
# A completed frame must finish within this long of its intended vsync. A row
# with the unfinished-frame sentinel is not a sample.
MAX_FRAME_MS = 5000.0
# `am start -W` returns Status: timeout before a software-GPU first frame. The
# logcat Displayed line is the real start signal; poll for it for up to this
# long instead of reading logcat once at the ActivityManager timeout.
DISPLAY_DEADLINE_S = 120.0
DISPLAY_POLL_S = 2.0
# The fixed interaction rotates the app three times and back. Every rotation
# recreates the single-view window, and that window's frame enters the sample.
# The map will add many frames to each window instead.
INTERACTION_STEPS = ("lock 1", "lock 0") * 3


class BenchmarkError(RuntimeError):
    """A measurement step failed or timed out."""


def run(command: list[str], timeout_s: float, check: bool = True) -> str:
    """Run a command with a hard timeout and return its stdout."""
    try:
        result = subprocess.run(
            command,
            capture_output=True,
            text=True,
            timeout=timeout_s,
        )
    except subprocess.TimeoutExpired as exc:
        raise BenchmarkError(f"timeout after {timeout_s:.0f}s: {' '.join(command)}") from exc
    if check and result.returncode != 0:
        detail = (result.stderr or result.stdout).strip()
        raise BenchmarkError(
            f"exit {result.returncode}: {' '.join(command)}\n{detail[-600:]}"
        )
    return result.stdout


class Adb:
    """Small bounded wrapper around adb for one serial."""

    def __init__(self, serial: str) -> None:
        self.serial = serial
        self.prefix = ["adb", "-s", serial]

    def shell(self, *args: str, timeout_s: float = 60, check: bool = True) -> str:
        return run([*self.prefix, "shell", *args], timeout_s, check=check)

    def logcat(self, *args: str, timeout_s: float = 60) -> str:
        return run([*self.prefix, "logcat", *args], timeout_s)

    def logcat_clear(self) -> None:
        run([*self.prefix, "logcat", "-c"], 30)

    def install(self, apk: Path) -> None:
        result = run([*self.prefix, "install", "-r", str(apk)], INSTALL_TIMEOUT_S)
        if "Success" not in result:
            raise BenchmarkError(f"adb install did not report Success:\n{result.strip()}")

    def pid_of_package(self) -> str:
        return self.shell("pidof", PACKAGE, timeout_s=30, check=False).strip()


def percentile(values: list[float], fraction: float) -> float | None:
    """Nearest-rank percentile, the method the bench summary uses."""
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(1, math.ceil(fraction * len(ordered))) - 1]


def summary(values: list[float | None]) -> dict[str, float | int | None]:
    """Median, p90, p99, min and max of a metric."""
    clean = [value for value in values if value is not None]
    if not clean:
        return {
            "n": 0,
            "min": None,
            "median": None,
            "p90": None,
            "p99": None,
            "max": None,
        }
    return {
        "n": len(clean),
        "min": round(min(clean), 1),
        "median": round(percentile(clean, 0.5), 1),
        "p90": round(percentile(clean, 0.9), 1),
        "p99": round(percentile(clean, 0.99), 1),
        "max": round(max(clean), 1),
    }


def parse_properties(raw: str) -> dict[str, str]:
    return dict(re.findall(r"^\[([^\]]+)\]: \[([^\]]*)\]$", raw, re.MULTILINE))


def git_value(root: Path, *args: str) -> str | None:
    try:
        value = run(["git", "-C", str(root), *args], 10, check=False).strip()
    except BenchmarkError:
        return None
    return value or None


def refresh_rate(adb: Adb) -> tuple[float, str]:
    """Read the display refresh rate, or name the assumption."""
    for command in (["dumpsys", "display"], ["dumpsys", "SurfaceFlinger"]):
        raw = adb.shell(*command, timeout_s=60, check=False)
        match = re.search(r"(?:fps=|mRefreshRate=|renderFrameRate=)(\d+(?:\.\d+)?)", raw)
        if match:
            value = float(match.group(1))
            if value > 0:
                return value, f"adb shell {' '.join(command)}"
    return ASSUMED_REFRESH_HZ, "assumed, no refresh rate found in dumpsys"


def screen_size(adb: Adb) -> str:
    raw = adb.shell("wm", "size", timeout_s=30, check=False)
    match = re.search(r"(\d+)x(\d+)", raw)
    return f"{match.group(1)}x{match.group(2)}" if match else "unknown"


def wait_until_process_gone(adb: Adb, timeout_s: float = 20.0) -> None:
    deadline = time.monotonic() + timeout_s
    while adb.pid_of_package():
        if time.monotonic() >= deadline:
            raise BenchmarkError(f"{PACKAGE} was still running after force-stop")
        time.sleep(0.2)


START_FIELDS = ("Status", "LaunchState", "Activity", "ThisTime", "TotalTime", "WaitTime")


def parse_start_output(raw: str) -> dict[str, str | int]:
    """Parse `am start -W` output into a small dict."""
    parsed: dict[str, str | int] = {}
    for line in raw.splitlines():
        key, _, value = line.partition(":")
        key = key.strip()
        if key not in START_FIELDS or not value.strip():
            continue
        value = value.strip()
        if key in ("ThisTime", "TotalTime", "WaitTime"):
            parsed[key] = int(value)
        else:
            parsed[key] = value
    return parsed


DISPLAYED_MS = re.compile(r"\+(\d+)ms")
DISPLAYED_S_MS = re.compile(r"\+(\d+)s(\d+)ms")
EPOCH_LINE = re.compile(r"^\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+([^:]+):\s*(.*)$")


def parse_launch_log(raw: str) -> tuple[float | None, float | None]:
    """Return (displayed_ms, process_to_first_frame_ms) from an epoch logcat."""
    displayed_ms = None
    start_epoch = None
    displayed_epoch = None
    for line in raw.splitlines():
        match = EPOCH_LINE.match(line)
        if not match:
            continue
        epoch, message = float(match.group(1)), match.group(3)
        if "Start proc" in message and PACKAGE in message and start_epoch is None:
            start_epoch = epoch
            continue
        if "Displayed" not in message or PACKAGE not in message or displayed_epoch is not None:
            continue
        minute_match = DISPLAYED_S_MS.search(message)
        plain_match = DISPLAYED_MS.search(message)
        if minute_match:
            displayed_ms = float(int(minute_match.group(1)) * 1000 + int(minute_match.group(2)))
        elif plain_match:
            displayed_ms = float(plain_match.group(1))
        displayed_epoch = epoch
    process_ms = None
    if start_epoch is not None and displayed_epoch is not None and displayed_epoch >= start_epoch:
        process_ms = round((displayed_epoch - start_epoch) * 1000, 1)
    return displayed_ms, process_ms


def wait_for_display(adb: Adb) -> tuple[float | None, float | None]:
    """Poll logcat for the package's first Displayed line, up to the deadline."""
    deadline = time.monotonic() + DISPLAY_DEADLINE_S
    while True:
        displayed_ms, process_ms = parse_launch_log(
            adb.logcat("-d", "-v", "epoch", timeout_s=60)
        )
        if displayed_ms is not None or process_ms is not None:
            return displayed_ms, process_ms
        if time.monotonic() >= deadline:
            return None, None
        time.sleep(DISPLAY_POLL_S)


def cold_start_metrics(runs: list[dict[str, object]]) -> dict[str, object]:
    """Summaries for the measured fields, skipping launches with no value.

    A timed-out launch still carries a wait time and a launch state; it is
    data, not a failed benchmark run.
    """
    metrics = {
        key: summary([run.get(key) for run in runs])
        for key in ("total_time_ms", "wait_time_ms", "displayed_ms", "process_to_first_frame_ms")
    }
    metrics["timed_out"] = sum(1 for run in runs if run.get("status") == "timeout")
    return metrics


def sample_is_empty(metrics: dict[str, object], pacing: dict[str, object]) -> bool:
    """True when the run produced neither a first frame nor a pacing row."""
    cold = metrics["total_time_ms"]["n"] + metrics["process_to_first_frame_ms"]["n"]
    return cold == 0 and pacing["frame_time_ms"]["n"] == 0


def cold_start_run(adb: Adb) -> dict[str, float | int | str | None]:
    """One force-stopped launch measured with `am start -W` and logcat."""
    adb.shell("am", "force-stop", PACKAGE, timeout_s=30)
    wait_until_process_gone(adb)
    adb.logcat_clear()
    start = parse_start_output(
        adb.shell("am", "start", "-W", "-n", f"{PACKAGE}/{ACTIVITY}", timeout_s=90)
    )
    status = start.get("Status")
    if status not in ("ok", "timeout"):
        raise BenchmarkError(f"am start -W failed: {start}")
    displayed_ms, process_ms = parse_launch_log(adb.logcat("-d", "-v", "epoch", timeout_s=60))
    if displayed_ms is None and process_ms is None:
        displayed_ms, process_ms = wait_for_display(adb)
    return {
        "status": status,
        "total_time_ms": start.get("TotalTime"),
        "wait_time_ms": start.get("WaitTime"),
        "this_time_ms": start.get("ThisTime"),
        "launch_state": start.get("LaunchState"),
        "displayed_ms": displayed_ms,
        "process_to_first_frame_ms": process_ms,
    }


def set_rotation(adb: Adb, *args: str) -> str:
    """Set the display rotation through the window manager shell command."""
    return adb.shell("cmd", "window", "user-rotation", *args, timeout_s=30)


def swipe_interaction(adb: Adb, size: str, swipes: int) -> list[str]:
    """Fallback: a fixed swipe sequence, used when rotation is unavailable."""
    try:
        width, height = (int(part) for part in size.split("x"))
    except ValueError:
        width, height = 1080, 2400
    x = width // 2
    start_y, end_y = int(height * 0.65), int(height * 0.35)
    commands = []
    for _ in range(swipes):
        command = ["input", "swipe", str(x), str(start_y), str(x), str(end_y), "150"]
        adb.shell(*command, timeout_s=30)
        commands.append("adb shell " + " ".join(command))
        time.sleep(0.2)
    return commands


def parse_gfxinfo_header(raw: str) -> dict[str, float | int | None]:
    """Read the system's own frame summary from `dumpsys gfxinfo`."""
    def number(pattern: str) -> float | None:
        match = re.search(pattern, raw)
        return float(match.group(1)) if match else None

    janky_percent = None
    match = re.search(r"Janky frames:\s*\d+\s*\(([\d.]+)%\)", raw)
    if match:
        janky_percent = float(match.group(1))
    return {
        "total_frames": number(r"Total frames rendered:\s*(\d+)"),
        "janky_frames": number(r"Janky frames:\s*(\d+)"),
        "janky_percent": janky_percent,
        "p50_ms": number(r"50th percentile:\s*(\d+)ms"),
        "p90_ms": number(r"90th percentile:\s*(\d+)ms"),
        "p95_ms": number(r"95th percentile:\s*(\d+)ms"),
        "p99_ms": number(r"99th percentile:\s*(\d+)ms"),
        "missed_vsync": number(r"Number Missed Vsync:\s*(\d+)"),
        "slow_ui_thread": number(r"Number Slow UI thread:\s*(\d+)"),
        "slow_bitmap_uploads": number(r"Number Slow bitmap uploads:\s*(\d+)"),
        "slow_issue_draw_commands": number(r"Number Slow issue draw commands:\s*(\d+)"),
    }


def frame_rows(raw: str) -> list[tuple[int, int]]:
    """Return (IntendedVsync, FrameCompleted) for every completed frame row.

    An unfinished frame carries a sentinel in FrameCompleted, so a row counts
    only when the completed time is close to its intended vsync.
    """
    for block in raw.split("---PROFILEDATA---"):
        lines = [line.strip() for line in block.strip().splitlines() if line.strip()]
        if not lines or "FrameCompleted" not in lines[0]:
            continue
        header = lines[0].split(",")
        try:
            intended_index = header.index("IntendedVsync")
            completed_index = header.index("FrameCompleted")
        except ValueError:
            continue
        rows = []
        for line in lines[1:]:
            parts = line.split(",")
            if len(parts) <= max(intended_index, completed_index):
                continue
            try:
                intended = int(parts[intended_index])
                completed = int(parts[completed_index])
            except ValueError:
                continue
            if 0 < completed - intended <= MAX_FRAME_MS * 1_000_000:
                rows.append((intended, completed))
        return rows
    return []


def dump_gfxinfo(adb: Adb) -> tuple[dict[str, float | int | None], list[tuple[int, int]]]:
    raw = adb.shell("dumpsys", "gfxinfo", PACKAGE, "framestats", timeout_s=90)
    return parse_gfxinfo_header(raw), frame_rows(raw)


def frame_pacing(adb: Adb, refresh_hz: float) -> dict[str, object]:
    """Launch a fresh process, run the fixed interaction, then read gfxinfo."""
    adb.shell("am", "force-stop", PACKAGE, timeout_s=30)
    wait_until_process_gone(adb)
    adb.logcat_clear()
    start = parse_start_output(
        adb.shell("am", "start", "-W", "-n", f"{PACKAGE}/{ACTIVITY}", timeout_s=90)
    )
    status = start.get("Status")
    if status not in ("ok", "timeout"):
        raise BenchmarkError(f"the frame-pacing launch failed: {start}")
    # The ActivityManager wait can expire before the first frame; wait for the
    # Displayed line so gfxinfo sees the app's window rather than an empty one.
    wait_for_display(adb)

    windows: list[dict[str, object]] = []
    window_rows: list[list[tuple[int, int]]] = []
    commands: list[str] = []
    time.sleep(INTERACTION_SETTLE_S)
    header, rows = dump_gfxinfo(adb)
    windows.append({"stage": "launch", "frames": len(rows)})
    window_rows.append(rows)

    interaction_kind = "rotation"
    for step in INTERACTION_STEPS:
        commands.append(f"adb shell cmd window user-rotation {step}")
        try:
            set_rotation(adb, *step.split())
        except BenchmarkError:
            interaction_kind = "swipes"
            break
        time.sleep(INTERACTION_SETTLE_S)
        step_header, step_rows = dump_gfxinfo(adb)
        header = step_header
        windows.append({"stage": f"rotation-{len(windows)}", "frames": len(step_rows)})
        window_rows.append(step_rows)

    if interaction_kind == "rotation":
        try:
            set_rotation(adb, "free")
            commands.append("adb shell cmd window user-rotation free")
        except BenchmarkError:
            pass
    else:
        try:
            set_rotation(adb, "free")
        except BenchmarkError:
            pass
        swipe_commands = swipe_interaction(adb, screen_size(adb), swipes=6)
        commands += swipe_commands
        time.sleep(INTERACTION_SETTLE_S)
        step_header, step_rows = dump_gfxinfo(adb)
        header = step_header
        windows.append({"stage": "swipes", "frames": len(step_rows)})
        window_rows.append(step_rows)

    rows = [row for window in window_rows for row in window]
    durations = [(completed - intended) / 1e6 for intended, completed in rows]
    interval_pool: list[float] = []
    for window in window_rows:
        completed_ms = [completed / 1e6 for _, completed in window]
        for earlier, later in zip(completed_ms, completed_ms[1:]):
            if 0 < later - earlier <= IDLE_GAP_MS:
                interval_pool.append(later - earlier)
    duration_summary = summary(durations)
    interval_summary = summary(interval_pool)
    period_ms = 1000.0 / refresh_hz
    late = [interval for interval in interval_pool if interval > period_ms * LATE_FACTOR]
    total_frames = header["total_frames"]
    return {
        "available": bool(rows) or bool(total_frames),
        "reason": None
        if rows or total_frames
        else "dumpsys gfxinfo returned no frame rows",
        "window": (
            "fresh process start, short idle interval, fixed rotation interaction"
            if interaction_kind == "rotation"
            else "fresh process start, short idle interval, fixed swipe interaction"
        ),
        "source": "adb shell dumpsys gfxinfo <pkg> framestats and header",
        "scope_note": (
            "App-window FrameTimeline only. MapLibre SurfaceView frames carry no "
            "FrameTimeline vsync id, so a map needs the SurfaceFlinger latch method "
            "from the bench RESULTS.md."
        ),
        "interaction": {
            "kind": interaction_kind,
            "commands": commands,
        },
        "frames": len(durations),
        "sample_windows": windows,
        "frame_time_ms": {
            "p50": duration_summary["median"],
            "p90": duration_summary["p90"],
            "p99": duration_summary["p99"],
            "n": duration_summary["n"],
        },
        "frame_interval_ms": {
            "p50": interval_summary["median"],
            "p90": interval_summary["p90"],
            "p99": interval_summary["p99"],
            "n": interval_summary["n"],
        },
        "interval_note": (
            None
            if interval_pool
            else "each window drew one frame, so no frame-to-frame interval exists; "
            "the map will fill the same window with many frames"
        ),
        "late_intervals": {
            "count": len(late),
            "percent": round(100.0 * len(late) / len(interval_pool), 2)
            if interval_pool
            else None,
            "over_ms": round(period_ms * LATE_FACTOR, 2),
        },
        "refresh_hz": refresh_hz,
        "system_summary": header,
        "launch": {
            "status": status,
            "total_time_ms": start.get("TotalTime"),
            "wait_time_ms": start.get("WaitTime"),
        },
    }


def apk_identity(apk: Path) -> dict[str, object]:
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    return {"path": str(apk), "sha256": digest, "size_bytes": apk.stat().st_size}


def git_context(root: Path) -> dict[str, object]:
    sha = git_value(root, "rev-parse", "HEAD")
    branch = git_value(root, "rev-parse", "--abbrev-ref", "HEAD")
    status = git_value(root, "status", "--porcelain", "--untracked-files=no")
    return {
        "sha": sha,
        "branch": branch,
        "dirty": bool(status),
        "dirty_files": len(status.splitlines()) if status else 0,
    }


def ci_context() -> dict[str, object]:
    keys = {
        "commit": "GITHUB_SHA",
        "branch": "GITHUB_HEAD_REF",
        "ref_name": "GITHUB_REF_NAME",
        "run_id": "GITHUB_RUN_ID",
        "run_attempt": "GITHUB_RUN_ATTEMPT",
        "workflow": "GITHUB_WORKFLOW",
        "job": "GITHUB_JOB",
        "server_url": "GITHUB_SERVER_URL",
        "repository": "GITHUB_REPOSITORY",
    }
    values = {key: os.environ.get(env) for key, env in keys.items()}
    values["commit"] = os.environ.get("GITHUB_SHA")
    if not values["branch"]:
        values["branch"] = os.environ.get("GITHUB_REF_NAME")
    values["present"] = bool(os.environ.get("GITHUB_ACTIONS"))
    return values


def device_context(adb: Adb, refresh_hz: float, refresh_source: str) -> dict[str, object]:
    properties = parse_properties(adb.shell("getprop", timeout_s=60))
    return {
        "serial": adb.serial,
        "model": properties.get("ro.product.model"),
        "device": properties.get("ro.product.device"),
        "manufacturer": properties.get("ro.product.manufacturer"),
        "api_level": int(properties["ro.build.version.sdk"])
        if properties.get("ro.build.version.sdk", "").isdigit()
        else None,
        "release": properties.get("ro.build.version.release"),
        "abi": properties.get("ro.product.cpu.abi"),
        "hardware": properties.get("ro.hardware"),
        "hardware_egl": properties.get("ro.hardware.egl"),
        "hardware_vulkan": properties.get("ro.hardware.vulkan"),
        "is_emulator": properties.get("ro.kernel.qemu") == "1",
        "screen_size": screen_size(adb),
        "refresh_hz": refresh_hz,
        "refresh_source": refresh_source,
    }


def write_result(result: dict[str, object], output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2) + "\n")
    print(f"wrote {output}", file=sys.stderr)
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not summary_path:
        return
    cold = result["cold_start"]
    pacing = result["frame_pacing"]
    commit = result["commit"]
    device = result["device"]
    intervals = pacing["frame_interval_ms"]
    late = pacing["late_intervals"]
    with open(summary_path, "a") as summary_file:
        summary_file.write("## Emulator benchmark (trend only)\n\n")
        summary_file.write(f"- commit: `{commit['sha']}` on `{commit['branch']}`\n")
        summary_file.write(
            f"- device: {device['model']} API {device['api_level']}, "
            f"{device['refresh_hz']} Hz, gpu {result['conditions']['gpu_mode']}\n"
        )
        total = cold["total_time_ms"]
        process = cold["process_to_first_frame_ms"]
        if total["median"] is not None:
            samples = total["n"]
            start_text = f"median {total['median']} ms, p90 {total['p90']} ms"
        elif process["median"] is not None:
            samples = process["n"]
            start_text = (
                f"process-to-first-frame median {process['median']} ms, "
                f"p90 {process['p90']} ms"
            )
        else:
            samples = 0
            start_text = "no cold-start sample"
        summary_file.write(
            f"- cold start: {start_text} over {samples} runs; "
            f"{cold['timed_out']} timed out\n"
        )
        frame_time = pacing["frame_time_ms"]
        if intervals["n"]:
            summary_file.write(
                f"- frame interval: p50 {intervals['p50']} ms, p90 {intervals['p90']} ms, "
                f"{late['percent']} % late over {late['over_ms']} ms\n"
            )
        elif frame_time["n"]:
            summary_file.write(
                f"- first frame time: p50 {frame_time['p50']} ms, "
                f"p90 {frame_time['p90']} ms over {frame_time['n']} frames; "
                "no frame-to-frame interval (one frame per window)\n"
            )
        else:
            summary_file.write("- frame pacing: no frames in the sample window\n")
        summary_file.write("Trend numbers only; not the Moto g32 acceptance gate.\n")


def default_output(
    root: Path, commit: str | None, api_level: int | None, branch: str | None
) -> Path:
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    sha = (commit or "unknown")[:12]
    safe_branch = re.sub(r"[^A-Za-z0-9._-]+", "-", branch or "detached")[:32]
    api = f"api{api_level}" if api_level else "api-unknown"
    return root / "build" / "benchmarks" / f"{sha}-{safe_branch}-{api}-{timestamp}.json"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--serial", default="")
    parser.add_argument("--runs", type=int, default=DEFAULT_RUNS)
    parser.add_argument("--output", type=Path, default=None)
    parser.add_argument("--gpu-mode", default="unknown")
    parser.add_argument("--device-source", default="unknown")
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.apk.is_file():
        raise BenchmarkError(f"APK not found: {args.apk}")
    if args.runs < 1:
        raise BenchmarkError("--runs must be at least 1")
    adb = Adb(args.serial)
    if adb.shell("getprop", "sys.boot_completed", timeout_s=30).strip() != "1":
        raise BenchmarkError(f"{args.serial or 'the device'} is not fully booted")

    adb.install(args.apk)
    refresh_hz, refresh_source = refresh_rate(adb)
    git = git_context(args.repo_root)
    ci = ci_context()
    device = device_context(adb, refresh_hz, refresh_source)

    runs = []
    print("warm-up launch (not recorded)", file=sys.stderr)
    cold_start_run(adb)
    time.sleep(SETTLE_BETWEEN_RUNS_S)
    for index in range(args.runs):
        print(f"cold start run {index + 1}/{args.runs}", file=sys.stderr)
        runs.append(cold_start_run(adb))
        time.sleep(SETTLE_BETWEEN_RUNS_S)

    print("frame pacing window", file=sys.stderr)
    pacing = frame_pacing(adb, refresh_hz)

    metrics = cold_start_metrics(runs)
    result = {
        "schema": SCHEMA,
        "kind": KIND,
        "note": (
            "Emulator trend numbers only. These are not the Moto g32 acceptance "
            "gate and must not be compared across GPU modes or hosts."
        ),
        "timestamp": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "commit": git,
        "ci": ci,
        "device": device,
        "conditions": {
            "device_source": args.device_source,
            "gpu_mode": args.gpu_mode,
            "runs": args.runs,
            "warmup_launches": 1,
            "settle_between_runs_s": SETTLE_BETWEEN_RUNS_S,
            "interaction_settle_s": INTERACTION_SETTLE_S,
            "idle_gap_ms": IDLE_GAP_MS,
            "late_factor": LATE_FACTOR,
        },
        "build": apk_identity(args.apk),
        "method": {
            "cold_start": f"adb shell am start -W -n {PACKAGE}/{ACTIVITY}",
            "frame_pacing": f"adb shell dumpsys gfxinfo {PACKAGE} framestats",
            "interaction": "fixed rotation via adb shell cmd window user-rotation, then free",
            "frame_scope": "app-window UI frames; map SurfaceView frames are out of scope",
        },
        "cold_start": {
            "runs": runs,
            "timed_out": metrics["timed_out"],
            "total_time_ms": metrics["total_time_ms"],
            "wait_time_ms": metrics["wait_time_ms"],
            "displayed_ms": metrics["displayed_ms"],
            "process_to_first_frame_ms": metrics["process_to_first_frame_ms"],
        },
        "frame_pacing": pacing,
    }
    output = args.output or default_output(
        args.repo_root,
        git["sha"],
        device["api_level"],
        ci["branch"] or git["branch"],
    )
    write_result(result, output)
    if sample_is_empty(metrics, pacing):
        raise BenchmarkError(
            "the sample is empty: no cold-start first frame and no frame-pacing row"
        )
    json.dump(result, sys.stdout, indent=2)
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (BenchmarkError, OSError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
