#!/usr/bin/env python3
"""Measure one bicycle costing key against its tuned value on seeded pairs.

Every pair is routed three times with the committed bicycle profile: once with
the tuned value, once with the step's Avoid value and once with its Don't mind
value. The two routes are the same only when the decoded line is equal point
for point, so a key that leaves every line identical is a no-op in the engine.

Outputs one CSV row per pair and key plus a summary JSON whose comparisons
`test-data/routing/README.md` quotes.

Usage:
  preferences.py --pairs lviv-oblast-pairs.json \
      --profiles valhalla-profiles.json --key use_roads \
      --out test-data/routing/lviv-use-roads.csv \
      --summary test-data/routing/lviv-use-roads-summary.json \
      --valhalla-container valhalla-keys
"""

import argparse
import csv
import hashlib
import json
import sys
import time

import run as validation

# The two steps of ValhallaProfiles.kt that are measured here, with the values
# the app sends for Avoid and Don't mind.
ARMS = {
    "use_roads": {"avoid": 0.0, "dont_mind": 1.0},
    "avoid_bad_surfaces": {"avoid": 1.0, "dont_mind": 0.0},
}
COMPARISONS = (
    {
        "name": "avoid_vs_dont_mind",
        "left": "avoid",
        "right": "dont_mind",
        "delta": "avoid_dont_mind_delta_m",
        "percent": "avoid_dont_mind_delta_pct",
    },
    {
        "name": "avoid_vs_tuned",
        "left": "avoid",
        "right": "tuned",
        "delta": "avoid_delta_m",
        "percent": "avoid_delta_pct",
    },
    {
        "name": "dont_mind_vs_tuned",
        "left": "dont_mind",
        "right": "tuned",
        "delta": "dont_mind_delta_m",
        "percent": "dont_mind_delta_pct",
    },
)


def route(profile, pair, options, valhalla, container):
    """One `/route` answer as its distance, duration and decoded line."""
    body = {
        "costing": profile["costing"],
        "costing_options": {profile["costing"]: options},
        "units": "kilometers",
        "language": "uk-UA",
        "locations": [
            {"lat": pair["from"][1], "lon": pair["from"][0], "type": "break"},
            {"lat": pair["to"][1], "lon": pair["to"][0], "type": "break"},
        ],
    }
    payload, error, _ = validation.post(
        f"{valhalla}/route", body, container=container
    )
    if error is not None:
        return None, error
    points = validation.route_points(payload)
    if not points:
        return None, "route has no shape"
    summary = payload["trip"]["summary"]
    return (
        {
            "metres": summary["length"] * validation.METRES_PER_KILOMETRE,
            "seconds": summary["time"],
            "points": points,
        },
        None,
    )


def same_geometry(left, right):
    """True when both answers exist and their lines are equal point for point."""
    if left is None or right is None:
        return False
    return left["points"] == right["points"]


def delta(value, base):
    return None if value is None or base is None else round(value - base, 1)


def percent(value, base):
    if value is None or base is None or base == 0.0:
        return None
    return round((value - base) / base * 100.0, 2)


def digest(path):
    with open(path, "rb") as handle:
        return hashlib.sha256(handle.read()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--pairs", required=True)
    parser.add_argument("--profiles", required=True)
    parser.add_argument("--key", required=True, choices=sorted(ARMS))
    parser.add_argument("--out", required=True)
    parser.add_argument("--summary", required=True)
    parser.add_argument("--valhalla", default="http://127.0.0.1:8002")
    parser.add_argument("--valhalla-container", default=None)
    args = parser.parse_args()

    document = json.load(open(args.pairs))
    profile = json.load(open(args.profiles))["profiles"]["bike"]
    arms = ARMS[args.key]
    tuned = profile["costing_options"][profile["costing"]][args.key]

    if args.valhalla_container is None:
        validation.wait_ready(f"{args.valhalla}/status")

    rows = []
    errors = []
    base_options = profile["costing_options"][profile["costing"]]

    def measure(pair, options):
        return route(profile, pair, options, args.valhalla, args.valhalla_container)

    for index, pair in enumerate(document["pairs"]):
        routes = {"tuned": measure(pair, dict(base_options))}
        for arm, value in arms.items():
            options = dict(base_options)
            options[args.key] = value
            routes[arm] = measure(pair, options)

        failures = [f"{arm}: {error}" for arm, (_, error) in routes.items() if error]
        if failures:
            reason = "; ".join(failures)
            errors.append({"pair": index, "error": reason})
            rows.append({"pair": index, "key": args.key, "error": reason})
            print(
                f"[{index + 1}/{len(document['pairs'])}] error: {failures[0]}",
                file=sys.stderr,
            )
            continue

        measured = {arm: result for arm, (result, _) in routes.items()}
        tuned_metres = measured["tuned"]["metres"]
        avoid_metres = measured["avoid"]["metres"]
        dont_mind_metres = measured["dont_mind"]["metres"]
        row = {
            "pair": index,
            "key": args.key,
            "tuned_m": round(tuned_metres, 1),
            "avoid_m": round(avoid_metres, 1),
            "dont_mind_m": round(dont_mind_metres, 1),
            "tuned_s": round(measured["tuned"]["seconds"], 1),
            "avoid_s": round(measured["avoid"]["seconds"], 1),
            "dont_mind_s": round(measured["dont_mind"]["seconds"], 1),
            "tuned_points": len(measured["tuned"]["points"]),
            "avoid_points": len(measured["avoid"]["points"]),
            "dont_mind_points": len(measured["dont_mind"]["points"]),
            "avoid_same_as_tuned": same_geometry(measured["avoid"], measured["tuned"]),
            "dont_mind_same_as_tuned": same_geometry(measured["dont_mind"], measured["tuned"]),
            "avoid_same_as_dont_mind": same_geometry(measured["avoid"], measured["dont_mind"]),
            "avoid_delta_m": delta(avoid_metres, tuned_metres),
            "dont_mind_delta_m": delta(dont_mind_metres, tuned_metres),
            "avoid_dont_mind_delta_m": delta(avoid_metres, dont_mind_metres),
            "avoid_delta_pct": percent(avoid_metres, tuned_metres),
            "dont_mind_delta_pct": percent(dont_mind_metres, tuned_metres),
            "avoid_dont_mind_delta_pct": percent(avoid_metres, dont_mind_metres),
        }
        rows.append(row)
        print(
            f"[{index + 1}/{len(document['pairs'])}] {row['avoid_dont_mind_delta_m']:+.1f} m, "
            f"{'identical' if row['avoid_same_as_dont_mind'] else 'changed'}",
            file=sys.stderr,
        )

    fields = [
        "pair",
        "key",
        "tuned_m",
        "avoid_m",
        "dont_mind_m",
        "tuned_s",
        "avoid_s",
        "dont_mind_s",
        "tuned_points",
        "avoid_points",
        "dont_mind_points",
        "avoid_same_as_tuned",
        "dont_mind_same_as_tuned",
        "avoid_same_as_dont_mind",
        "avoid_delta_m",
        "dont_mind_delta_m",
        "avoid_dont_mind_delta_m",
        "avoid_delta_pct",
        "dont_mind_delta_pct",
        "avoid_dont_mind_delta_pct",
        "error",
    ]
    with open(args.out, "w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, restval="")
        writer.writeheader()
        writer.writerows(rows)

    summary = summarise(args, document, profile, arms, tuned, rows, errors)
    with open(args.summary, "w") as handle:
        json.dump(summary, handle, indent=2)
        handle.write("\n")
    print(json.dumps(summary["comparisons"], indent=2))


def summarise(args, document, profile, arms, tuned, rows, errors):
    routed = [row for row in rows if row.get("tuned_m") is not None]
    comparisons = {}
    for comparison in COMPARISONS:
        left = comparison["left"]
        right = comparison["right"]
        changed = [row for row in routed if not row[f"{left}_same_as_{right}"]]
        distance_changed = [
            row for row in routed if row[f"{left}_m"] != row[f"{right}_m"]
        ]
        deltas = [
            row[comparison["delta"]]
            for row in routed
            if row.get(comparison["delta"]) is not None
        ]
        percents = [
            row[comparison["percent"]]
            for row in routed
            if row.get(comparison["percent"]) is not None
        ]
        comparisons[comparison["name"]] = {
            "geometry_changed": len(changed),
            "geometry_identical": len(routed) - len(changed),
            "distance_changed": len(distance_changed),
            "delta_meters": validation.distribution(deltas),
            "delta_percent": validation.distribution(percents),
            "absolute_delta_meters": validation.distribution([abs(value) for value in deltas]),
        }
    return {
        "generated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "key": args.key,
        "profile": "bike",
        "costing": profile["costing"],
        "tuned": tuned,
        "avoid": arms["avoid"],
        "dont_mind": arms["dont_mind"],
        "pairs": len(rows),
        "routed": len(routed),
        "errors": errors,
        "geometry_rule": "exact equality of the decoded polyline6 point list",
        "comparisons": comparisons,
        "inputs": {
            "pairs": args.pairs,
            "pairs_sha256": digest(args.pairs),
            "pairs_seed": document.get("seed"),
            "profiles": args.profiles,
            "profiles_sha256": digest(args.profiles),
        },
        "engines": {
            "valhalla": "ghcr.io/valhalla/valhalla:latest (3.9.1-52228c4e0), sha256 bf88b5c5c5131414c66a76ba77a8c5ce803a1c724c2a1f07c25961a874a419fb12",
            "pack": "/hdd/t-0518-merged, mounted read-only at /data, valhalla_service with --network none",
            "url": args.valhalla,
            "network": (
                f"docker exec into {args.valhalla_container}"
                if args.valhalla_container
                else "host port"
            ),
        },
    }


if __name__ == "__main__":
    sys.exit(main())
