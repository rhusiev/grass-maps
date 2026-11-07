#!/usr/bin/env python3
"""Map-frame pacing from Perfetto `BufferTX` latches, stored per commit.

MapLibre's buffers carry no FrameTimeline vsync id, so SurfaceFlinger's
FrameTimeline has no rows for the map's SurfaceView layer. This script times
map frames by SurfaceFlinger latching them: the layer's `BufferTX` counter
(buffers sent to SurfaceFlinger and not yet latched) drops on every latch.

The result is written as one JSON document per commit under the output
directory, so a commit's number can be compared with its neighbours. Emulator
numbers are trends only; the Moto g32 acceptance gate is measured separately.

Usage:
    map-frame-timeline.py --refresh-hz 60 TRACE...
    map-frame-timeline.py --refresh-hz 60 --output build/benchmarks/map-pacing TRACE...

Environment:
    TRACE_PROCESSOR  path to the Perfetto trace_processor binary (default:
                     `trace_processor` on PATH)
"""

from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import math
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = "grass-maps-map-pacing/1"
PACKAGE = "nl.r1a.grassmap"
# A gap longer than this is the map idling between gestures, not a slow frame.
DEFAULT_MAX_GAP_MS = 250.0
# An interval over 1.5 periods means at least one vsync passed without a frame.
DEFAULT_LATE_FACTOR = 1.5
NS_PER_MS = 1e6

LATCH_QUERY = """
select c.ts, c.value from counter c join counter_track t on c.track_id = t.id
where t.name like 'BufferTX - SurfaceView[{package}%' order by c.ts
"""


class PacingError(RuntimeError):
    """A trace could not be read or queried."""


def trace_processor() -> str:
    return os.environ.get("TRACE_PROCESSOR", "trace_processor")


def query(trace: Path, sql: str) -> list[dict[str, str]]:
    """Run one PerfettoSQL query and return its rows."""
    try:
        result = subprocess.run(
            [trace_processor(), "-q", "/dev/stdin", str(trace)],
            input=sql,
            capture_output=True,
            text=True,
            check=True,
        )
    except FileNotFoundError as exc:
        raise PacingError(
            "trace_processor not found; set TRACE_PROCESSOR to the Perfetto binary"
        ) from exc
    except subprocess.CalledProcessError as exc:
        detail = (exc.stderr or exc.stdout).strip()
        raise PacingError(f"trace_processor failed on {trace}:\n{detail[-600:]}") from exc
    body = result.stdout[result.stdout.index('"') :] if '"' in result.stdout else ""
    return list(csv.DictReader(io.StringIO(body)))


def counter_rows(trace: Path) -> list[tuple[int, float]]:
    sql = LATCH_QUERY.format(package=PACKAGE)
    rows = []
    for row in query(trace, sql):
        try:
            rows.append((int(row["ts"]), float(row["value"])))
        except (KeyError, TypeError, ValueError):
            continue
    return rows


def latch_times_ms(rows: list[tuple[int, float]]) -> list[float]:
    """Timestamps where the queued-buffer counter falls: one latch per fall."""
    latches: list[float] = []
    previous = 0.0
    for timestamp, value in rows:
        if value < previous:
            latches.append(timestamp / NS_PER_MS)
        previous = value
    return latches


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(1, math.ceil(fraction * len(ordered))) - 1]


def summarize(
    latches: list[float],
    refresh_hz: float,
    max_gap_ms: float = DEFAULT_MAX_GAP_MS,
    late_factor: float = DEFAULT_LATE_FACTOR,
) -> dict[str, object]:
    """Interval summary and the late-interval share for one or more traces."""
    period_ms = 1000.0 / refresh_hz
    intervals = [
        later - earlier
        for earlier, later in zip(latches, latches[1:])
        if 0 < later - earlier <= max_gap_ms
    ]
    late = [interval for interval in intervals if interval > period_ms * late_factor]
    missed_vsyncs = sum(round(interval / period_ms) - 1 for interval in late)
    return {
        "map_frames": len(latches),
        "interval_ms": {
            "n": len(intervals),
            "p50": round(percentile(intervals, 0.5), 1) if intervals else None,
            "p90": round(percentile(intervals, 0.9), 1) if intervals else None,
            "p99": round(percentile(intervals, 0.99), 1) if intervals else None,
        },
        "late_intervals": {
            "count": len(late),
            "percent": round(100.0 * len(late) / len(intervals), 2) if intervals else None,
            "over_ms": round(period_ms * late_factor, 2),
        },
        "missed_vsyncs": missed_vsyncs,
    }


def git_value(*args: str) -> str | None:
    try:
        value = subprocess.run(
            ["git", *args], capture_output=True, text=True, check=True, timeout=10
        ).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return None
    return value or None


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def document(
    traces: list[Path],
    latches: list[float],
    refresh_hz: float,
    max_gap_ms: float,
    late_factor: float,
) -> dict[str, object]:
    status = git_value("status", "--porcelain", "--untracked-files=no")
    result = {
        "schema": SCHEMA,
        "commit": git_value("rev-parse", "HEAD"),
        "branch": git_value("rev-parse", "--abbrev-ref", "HEAD"),
        "dirty": bool(status),
        "timestamp": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "source": f"Perfetto BufferTX SurfaceView counter for {PACKAGE}",
        "refresh_hz": refresh_hz,
        "late_factor": late_factor,
        "max_gap_ms": max_gap_ms,
        "traces": [
            {"path": str(trace), "sha256": file_sha256(trace), "bytes": trace.stat().st_size}
            for trace in traces
        ],
        "note": (
            "Emulator trend only. Do not compare across GPU modes or hosts; "
            "the Moto g32 acceptance gate is measured on the phone."
        ),
    }
    result.update(summarize(latches, refresh_hz, max_gap_ms, late_factor))
    return result


def commit_path(output: Path, commit: str | None) -> Path:
    return output / f"{commit or 'unknown'}.json"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--refresh-hz", type=float, required=True)
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("build/benchmarks/map-pacing"),
        help="directory for one JSON document per commit",
    )
    parser.add_argument("--max-gap-ms", type=float, default=DEFAULT_MAX_GAP_MS)
    parser.add_argument("--late-factor", type=float, default=DEFAULT_LATE_FACTOR)
    parser.add_argument("--stdout-only", action="store_true", help="do not write the per-commit file")
    parser.add_argument("traces", nargs="+", type=Path)
    args = parser.parse_args(argv)

    latches: list[float] = []
    per_trace = []
    for trace in args.traces:
        if not trace.is_file():
            print(f"map-frame-timeline: no such trace: {trace}", file=sys.stderr)
            return 2
        trace_latches = latch_times_ms(counter_rows(trace))
        per_trace.append({"path": str(trace), "map_frames": len(trace_latches)})
        latches += trace_latches

    result = document(
        args.traces, latches, args.refresh_hz, args.max_gap_ms, args.late_factor
    )
    result["per_trace"] = per_trace
    text = json.dumps(result, indent=2)
    print(text)
    if not args.stdout_only:
        args.output.mkdir(parents=True, exist_ok=True)
        path = commit_path(args.output, result["commit"])
        path.write_text(text + "\n")
        print(f"wrote {path}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
