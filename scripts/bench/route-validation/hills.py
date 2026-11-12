#!/usr/bin/env python3
"""Capture the T-0308 bike hill-avoidance measurement.

Routes a candidate pair twice with the committed bicycle profile: once with
the tuned `use_hills` value and once with full avoidance (`use_hills` 0.0).
Ascent is sampled from the same GLO-30 DEM the pack was built from, every
``--step`` metres along the returned line, and summed as the positive height
changes.

Outputs:
  * a report of every candidate pair and its two ascents (``--report``)
  * the selected fixture (``--out``) for names in ``--only``

Usage:
  hills.py --pairs candidates.json --profiles valhalla-profiles.json \
           --dem lviv-elev30-int16.tif --report all.json \
           --out test-data/routing/hills/bike-hills.json --only name1,name2
"""

import argparse
import json
import math
import pathlib
import sys

import run as validation

FULL_AVOIDANCE = 0.0


def route_body(profile, pair, avoid_hills):
    options = profile["costing_options"]
    if avoid_hills:
        costing = profile["costing"]
        options = {costing: dict(options[costing], use_hills=FULL_AVOIDANCE)}
    return {
        "costing": profile["costing"],
        "costing_options": options,
        "units": "kilometers",
        "language": "uk-UA",
        "locations": [
            {"lat": pair["from"][1], "lon": pair["from"][0], "type": "break"},
            {"lat": pair["to"][1], "lon": pair["to"][0], "type": "break"},
        ],
    }


def sample_line(points, step):
    """Points every ``step`` metres along a lon/lat polyline."""
    if len(points) < 2:
        return []
    sampled = [points[0]]
    next_distance = step
    travelled = 0.0
    for start, end in zip(points, points[1:]):
        length = validation.metres(start, end)
        while length > 0.0 and next_distance <= travelled + length:
            fraction = (next_distance - travelled) / length
            sampled.append(
                (
                    start[0] + (end[0] - start[0]) * fraction,
                    start[1] + (end[1] - start[1]) * fraction,
                ),
            )
            next_distance += step
        travelled += length
    if sampled[-1] != points[-1]:
        sampled.append(points[-1])
    return sampled


def height_bilinear(dem, point):
    """Bilinear height at a lon/lat point, or None outside the raster."""
    x = (point[0] - dem.lon0) / dem.lon_step - 0.5
    y = (dem.lat0 - point[1]) / dem.lat_step - 0.5
    x = min(max(x, 0.0), dem.columns - 1.0)
    y = min(max(y, 0.0), dem.rows - 1.0)
    x0 = int(math.floor(x))
    y0 = int(math.floor(y))
    x1 = min(x0 + 1, dem.columns - 1)
    y1 = min(y0 + 1, dem.rows - 1)
    fx = x - x0
    fy = y - y0
    corners = [
        (dem.values[y0, x0], (1.0 - fx) * (1.0 - fy)),
        (dem.values[y0, x1], fx * (1.0 - fy)),
        (dem.values[y1, x0], (1.0 - fx) * fy),
        (dem.values[y1, x1], fx * fy),
    ]
    if any(int(value) == -9999 for value, _ in corners):
        return None
    return float(sum(value * weight for value, weight in corners))


def ascent_of(points, dem, step):
    heights = []
    for point in sample_line(points, step):
        height = height_bilinear(dem, point)
        if height is not None:
            heights.append(round(height))
    ascent = sum(max(0, later - earlier) for earlier, later in zip(heights, heights[1:]))
    return ascent, heights


def measure(args, profile, pair, avoid_hills):
    body = route_body(profile, pair, avoid_hills)
    payload, error, _ = validation.post(
        f"{args.valhalla}/route",
        body,
        container=args.valhalla_container,
    )
    if error is not None:
        return {"error": error}
    points = validation.route_points(payload)
    if not points:
        return {"error": "route has no shape"}
    trip = payload["trip"]
    ascent, heights = ascent_of(points, args.dem, args.step)
    return {
        "distanceMeters": round(trip["summary"]["length"] * validation.METRES_PER_KILOMETRE, 1),
        "durationSeconds": round(trip["summary"]["time"], 1),
        "ascentMeters": round(ascent, 1),
        "shape": validation.encode_polyline6(points),
        "shapePoints": len(points),
        "samples": ",".join(str(height) for height in heights),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--pairs", required=True)
    parser.add_argument("--profiles", required=True)
    parser.add_argument("--dem", required=True)
    parser.add_argument("--report", required=True)
    parser.add_argument("--out", default=None)
    parser.add_argument("--only", default="")
    parser.add_argument("--valhalla", default="http://127.0.0.1:18002")
    parser.add_argument("--valhalla-container", default=None)
    parser.add_argument("--step", type=float, default=25.0)
    args = parser.parse_args()

    args.dem = validation.Dem(args.dem)
    profile = json.load(open(args.profiles))["profiles"]["bike"]
    pairs = json.load(open(args.pairs))["pairs"]
    wanted = [name for name in args.only.split(",") if name]
    if wanted:
        pairs = [pair for pair in pairs if pair["name"] in wanted]
    if args.valhalla_container is None:
        validation.wait_ready(f"{args.valhalla}/status")

    measured = []
    for index, pair in enumerate(pairs):
        default = measure(args, profile, pair, avoid_hills=False)
        avoid = measure(args, profile, pair, avoid_hills=True)
        record = {
            "name": pair["name"],
            "from": pair["from"],
            "to": pair["to"],
            "default": default,
            "avoidHills": avoid,
        }
        if "error" not in default and "error" not in avoid:
            record["ascentDropMeters"] = round(default["ascentMeters"] - avoid["ascentMeters"], 1)
        else:
            record["ascentDropMeters"] = None
        measured.append(record)
        drop = record["ascentDropMeters"]
        print(
            f"[{index + 1}/{len(pairs)}] {pair['name']}: "
            f"{'error' if drop is None else f'{drop:+.1f} m'}",
            file=sys.stderr,
        )

    pathlib.Path(args.report).write_text(json.dumps({"routes": measured}, indent=2) + "\n")

    if args.out is None:
        return
    selected = measured
    fixture = {
        "profile": "bike",
        "costing": profile["costing"],
        "dem": "Copernicus GLO-30, lviv-elev30-int16.tif",
        "samplingMetres": args.step,
        "rule": "sum of positive height changes along the route line",
        "selection": "largest ascent drop among the 100 candidate pairs in candidates.json",
        "defaultUseHills": profile["costing_options"][profile["costing"]]["use_hills"],
        "avoidHillsUseHills": FULL_AVOIDANCE,
        "routes": selected,
    }
    pathlib.Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    pathlib.Path(args.out).write_text(json.dumps(fixture, indent=2) + "\n")


if __name__ == "__main__":
    sys.exit(main())
