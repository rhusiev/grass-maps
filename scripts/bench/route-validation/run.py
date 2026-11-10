"""Route the seeded Lviv-oblast pairs through Valhalla and GraphHopper.

Both engines get the same pair and the profile the app maps it to. Valhalla is
the local `valhalla_service` over the phase-A pack; GraphHopper 11.1 is the
local server over its stock foot/bike/hike models. The output is one CSV row
per pair and profile plus a summary JSON whose tolerance the app's test reads.

Usage:
  run.py --pairs pairs.json --profiles valhalla-profiles.json \
         --out routes.csv --summary summary.json \
         [--valhalla http://127.0.0.1:18002] [--graphhopper http://127.0.0.1:18989] \
         [--dem lviv-elev30-int16.tif]
"""

import argparse
import csv
import json
import math
import subprocess
import time
import urllib.error
import urllib.request

import numpy as np
from PIL import Image

ATTRIBUTE_KEYS = ("surface", "highway", "grade", "sac_scale", "access")
PROFILE_TO_GH = {"walk": "foot", "bike": "bike", "hike": "hike"}
TRACE_ATTRIBUTES = [
    "edge.length",
    "edge.surface",
    "edge.use",
    "edge.road_class",
    "edge.sac_scale",
    "edge.access_restriction",
]
TRACE_MAX_CHUNK_METRES = 50_000.0
SAC_SCALE_NAMES = [
    "none",
    "hiking",
    "mountain_hiking",
    "demanding_mountain_hiking",
    "alpine_hiking",
    "demanding_alpine_hiking",
    "difficult_alpine_hiking",
]
HIGHWAY_BY_USE = {
    "walkway": "footway",
    "footway": "footway",
    "path": "path",
    "steps": "steps",
    "pedestrian": "pedestrian",
    "service_road": "service",
    "alley": "service",
    "driveway": "driveway",
    "parking": "parking",
    "track": "track",
    "cycleway": "cycleway",
    "mountain_bike": "path",
    "bridleway": "bridleway",
    "ramp": "service",
    "turn_channel": "service",
    "emergency_access": "service",
}
METRES_PER_KILOMETRE = 1_000.0


def post(url, body, timeout=180, container=None):
    """POST one JSON body. With [container], the request runs inside it.

    A container name makes the call go through `docker exec`, so the engine can
    run with `--network none` and the run still proves it needs no network.
    """
    data = json.dumps(body).encode()
    started = time.monotonic()
    if container is not None:
        command = [
            "docker", "exec", "-i", container,
            "curl", "-sS", "-m", str(timeout),
            "-X", "POST", "-H", "Content-Type: application/json",
            "--data-binary", "@-", url,
        ]
        completed = subprocess.run(command, input=data, capture_output=True)
        elapsed = (time.monotonic() - started) * 1_000.0
        try:
            payload = json.loads(completed.stdout)
        except ValueError:
            return None, completed.stderr.decode(errors="replace")[:300] or "empty response", elapsed
        return payload, None, elapsed
    request = urllib.request.Request(url, data=data, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = json.loads(response.read())
        return payload, None, (time.monotonic() - started) * 1_000.0
    except urllib.error.HTTPError as error:
        detail = error.read().decode(errors="replace")
        try:
            message = json.loads(detail).get("error") or json.loads(detail).get("message") or detail
        except ValueError:
            message = detail
        return None, f"http {error.code}: {message}"[:300], (time.monotonic() - started) * 1_000.0
    except Exception as error:  # noqa: BLE001 - any transport failure is a row value
        return None, f"{type(error).__name__}: {error}"[:300], (time.monotonic() - started) * 1_000.0


def metres(a, b):
    latitude = math.radians((a[1] + b[1]) / 2.0)
    dx = math.radians(b[0] - a[0]) * math.cos(latitude)
    dy = math.radians(b[1] - a[1])
    return math.hypot(dx, dy) * 6_371_000.0


def decode_polyline6(encoded):
    points = []
    index = latitude = longitude = 0
    while index < len(encoded):
        for target in ("latitude", "longitude"):
            shift = result = 0
            while True:
                byte = ord(encoded[index]) - 63
                index += 1
                result |= (byte & 0x1F) << shift
                shift += 5
                if byte < 0x20:
                    break
            delta = ~(result >> 1) if result & 1 else result >> 1
            if target == "latitude":
                latitude += delta
            else:
                longitude += delta
        points.append((longitude / 1e6, latitude / 1e6))
    return points


def route_points(payload):
    points = []
    for leg in payload["trip"]["legs"]:
        shape = leg.get("shape")
        if not shape:
            continue
        for point in decode_polyline6(shape):
            if not points or points[-1] != point:
                points.append(point)
    return points


class Dem:
    """Nearest-pixel height from the committed Lviv 30 m GeoTIFF."""

    def __init__(self, path):
        image = Image.open(path)
        self.values = np.array(image)
        scale = image.tag_v2[33550]
        tiepoint = image.tag_v2[33922]
        self.lon0 = tiepoint[3]
        self.lat0 = tiepoint[4]
        self.lon_step = scale[0]
        self.lat_step = scale[1]
        self.rows, self.columns = self.values.shape

    def height(self, point):
        column = int(round((point[0] - self.lon0) / self.lon_step))
        row = int(round((self.lat0 - point[1]) / self.lat_step))
        if not (0 <= row < self.rows and 0 <= column < self.columns):
            return None
        value = int(self.values[row, column])
        return None if value == -9999 else float(value)


def chunked(points):
    if len(points) < 2:
        return []
    chunks = []
    start = 0
    distance = 0.0
    for index in range(1, len(points)):
        distance += metres(points[index - 1], points[index])
        too_long = distance > TRACE_MAX_CHUNK_METRES
        too_many = index - start >= 4_000
        last = index == len(points) - 1
        if last:
            chunks.append(points[start : index + 1])
        elif (too_long or too_many) and index > start + 1:
            chunks.append(points[start:index])
            start = index - 1
            distance = metres(points[start], points[index])
    return chunks


def trace_edges(valhalla, points, profile, container=None):
    edges = []
    for chunk in chunked(points):
        encoded = encode_polyline6(chunk)
        body = {
            "costing": profile["costing"],
            "costing_options": profile["costing_options"],
            "shape_match": "map_snap",
            "encoded_polyline": encoded,
            "units": "kilometers",
            "filters": {"action": "include", "attributes": TRACE_ATTRIBUTES},
        }
        payload, error, _ = post(f"{valhalla}/trace_attributes", body, container=container)
        if error is not None:
            return None, error
        for edge in payload.get("edges", []):
            length = edge.get("length")
            if length is None:
                return None, "trace edge without length"
            use = edge.get("use")
            if use is None or use == "road":
                highway = edge.get("road_class") or "unknown"
                if highway == "service_other":
                    highway = "service"
            else:
                highway = HIGHWAY_BY_USE.get(use, use)
            restriction = (edge.get("access_restriction") or [None])[0]
            edges.append(
                {
                    "lengthMeters": length * METRES_PER_KILOMETRE,
                    "surface": edge.get("surface") or "unknown",
                    "highway": highway,
                    "sac_scale": SAC_SCALE_NAMES[edge.get("sac_scale") or 0],
                    "access": restriction or "yes",
                }
            )
    return edges, None


def encode_polyline6(points):
    output = []
    previous_latitude = previous_longitude = 0
    for longitude, latitude in points:
        latitude_value = int(round(latitude * 1e6))
        longitude_value = int(round(longitude * 1e6))
        for delta in (latitude_value - previous_latitude, longitude_value - previous_longitude):
            value = ~(delta << 1) if delta < 0 else delta << 1
            while value >= 0x20:
                output.append(chr((0x20 | (value & 0x1F)) + 63))
                value >>= 5
            output.append(chr(value + 63))
        previous_latitude, previous_longitude = latitude_value, longitude_value
    return "".join(output)


def segments(points, edges, total_seconds):
    cumulative = [0.0]
    for index in range(1, len(points)):
        cumulative.append(cumulative[-1] + metres(points[index - 1], points[index]))

    def index_at(distance):
        for index, value in enumerate(cumulative):
            if value >= distance:
                return max(index, 1)
        return len(cumulative) - 1

    edge_total = max(sum(edge["lengthMeters"] for edge in edges), 1.0)
    seconds_per_metre = total_seconds / edge_total
    result = []
    travelled = 0.0
    start = 0
    for edge in edges:
        travelled += edge["lengthMeters"]
        end = index_at(travelled)
        if end <= start:
            start = end
            continue
        result.append(
            {
                "start": start,
                "end": end,
                "lengthMeters": edge["lengthMeters"],
                "seconds": edge["lengthMeters"] * seconds_per_metre,
                "surface": edge["surface"],
                "highway": edge["highway"],
                "sac_scale": edge["sac_scale"],
                "access": edge["access"],
            }
        )
        start = end
    return result


def annotate(dem, points, edges, total_seconds):
    result = segments(points, edges, total_seconds)
    heights = {}
    for segment in result:
        for index in (segment["start"], segment["end"]):
            if index not in heights:
                heights[index] = dem.height(points[index]) if dem else None
        start_height = heights[segment["start"]]
        end_height = heights[segment["end"]]
        if start_height is None or end_height is None or segment["lengthMeters"] <= 0:
            segment["grade"] = None
        else:
            segment["grade"] = (end_height - start_height) / segment["lengthMeters"] * 100.0
    return result


def wait_ready(url, seconds=120):
    for _ in range(seconds * 2):
        try:
            with urllib.request.urlopen(url, timeout=2) as response:
                if response.status == 200:
                    return
        except Exception:  # noqa: BLE001 - the server is still starting
            time.sleep(0.5)
    raise SystemExit(f"{url} did not become ready")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--pairs", required=True)
    parser.add_argument("--profiles", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument("--summary", required=True)
    parser.add_argument("--valhalla", default="http://127.0.0.1:8002")
    parser.add_argument("--graphhopper", default="http://127.0.0.1:18989")
    parser.add_argument("--valhalla-container", default=None)
    parser.add_argument("--dem", default=None)
    args = parser.parse_args()

    if args.valhalla_container is None:
        wait_ready(f"{args.valhalla}/status")
    wait_ready(f"{args.graphhopper}/health")
    document = json.load(open(args.pairs))
    profiles = json.load(open(args.profiles))["profiles"]
    dem = Dem(args.dem) if args.dem else None
    rows = []
    totals = {
        name: {
            "segments": 0,
            **{key: 0 for key in ATTRIBUTE_KEYS},
            "values": {key: {} for key in ("surface", "highway", "sac_scale", "access")},
        }
        for name in profiles
    }

    for profile_name, profile in profiles.items():
        for index, pair in enumerate(document["pairs"]):
            origin = pair["from"]
            destination = pair["to"]
            valhalla_body = {
                "costing": profile["costing"],
                "costing_options": profile["costing_options"],
                "units": "kilometers",
                "locations": [
                    {"lat": origin[1], "lon": origin[0], "type": "break"},
                    {"lat": destination[1], "lon": destination[0], "type": "break"},
                ],
            }
            valhalla_payload, valhalla_error, valhalla_ms = post(
                f"{args.valhalla}/route", valhalla_body, container=args.valhalla_container
            )
            valhalla_metres = None
            valhalla_seconds = None
            segment_count = 0
            coverage = {key: 0 for key in ATTRIBUTE_KEYS}
            if valhalla_error is None:
                trip = valhalla_payload["trip"]
                points = route_points(valhalla_payload)
                valhalla_metres = trip["summary"]["length"] * METRES_PER_KILOMETRE
                valhalla_seconds = trip["summary"]["time"]
                edges, trace_error = trace_edges(
                    args.valhalla, points, profile, container=args.valhalla_container
                )
                if trace_error is None:
                    annotated = annotate(dem, points, edges, valhalla_seconds)
                    segment_count = len(annotated)
                    for segment in annotated:
                        for key in ATTRIBUTE_KEYS:
                            if segment.get(key) is not None:
                                coverage[key] += 1
                        for key in ("surface", "highway", "sac_scale", "access"):
                            value = segment.get(key)
                            if value is not None:
                                counts = totals[profile_name]["values"][key]
                                counts[value] = counts.get(value, 0) + 1
                    totals[profile_name]["segments"] += segment_count
                    for key in ATTRIBUTE_KEYS:
                        totals[profile_name][key] += coverage[key]
                else:
                    valhalla_error = f"attributes: {trace_error}"

            gh_payload, gh_error, gh_ms = post(
                f"{args.graphhopper}/route",
                {
                    "profile": PROFILE_TO_GH[profile_name],
                    "points": [origin, destination],
                    "points_encoded": False,
                },
            )
            gh_metres = gh_payload["paths"][0]["distance"] if gh_error is None else None
            rows.append(
                {
                    "pair": index,
                    "profile": profile_name,
                    "valhalla": "ok" if valhalla_error is None else "error",
                    "valhalla_m": "" if valhalla_metres is None else round(valhalla_metres, 1),
                    "valhalla_s": "" if valhalla_seconds is None else round(valhalla_seconds, 1),
                    "valhalla_ms": round(valhalla_ms, 1),
                    "segments": segment_count,
                    "surface": coverage["surface"],
                    "highway": coverage["highway"],
                    "grade": coverage["grade"],
                    "sac_scale": coverage["sac_scale"],
                    "access": coverage["access"],
                    "valhalla_error": valhalla_error or "",
                    "graphhopper": "ok" if gh_error is None else "error",
                    "graphhopper_m": "" if gh_metres is None else round(gh_metres, 1),
                    "graphhopper_ms": round(gh_ms, 1),
                    "graphhopper_error": gh_error or "",
                },
            )
            print(f"{profile_name} {index:3d} v={valhalla_metres} g={gh_metres}", flush=True)

    with open(args.out, "w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)

    summary = summarise(document, profiles, rows, totals, args)
    with open(args.summary, "w") as handle:
        json.dump(summary, handle, indent=2)
        handle.write("\n")
    print(json.dumps(summary["tolerance"], indent=2))


def summarise(document, profiles, rows, totals, args):
    per_profile = {}
    deltas = []
    for profile_name in profiles:
        profile_rows = [row for row in rows if row["profile"] == profile_name]
        both = [row for row in profile_rows if row["valhalla"] == "ok" and row["graphhopper"] == "ok"]
        reference_routed = [row for row in profile_rows if row["graphhopper"] == "ok"]
        valhalla_only = [row for row in profile_rows if row["valhalla"] == "ok" and row["graphhopper"] != "ok"]
        missing = [
            row
            for row in reference_routed
            if row["valhalla"] != "ok"
        ]
        profile_deltas = [
            (row["valhalla_m"] - row["graphhopper_m"]) / row["graphhopper_m"]
            for row in both
            if row["graphhopper_m"]
        ]
        deltas += profile_deltas
        profile_abs = distribution([abs(delta) for delta in profile_deltas])
        per_profile[profile_name] = {
            "pairs": len(profile_rows),
            "valhalla_routed": sum(1 for row in profile_rows if row["valhalla"] == "ok"),
            "reference_routed": len(reference_routed),
            "both_routed": len(both),
            "valhalla_missing_where_reference_routes": len(missing),
            "missing_pairs": [row["pair"] for row in missing],
            "valhalla_only": len(valhalla_only),
            "reference_failures": [row["pair"] for row in profile_rows if row["graphhopper"] != "ok"],
            "distance_delta": distribution(profile_deltas),
            "distance_delta_abs": profile_abs,
            "segments": totals[profile_name]["segments"],
            "attribute_values": {
                key: dict(sorted(counts.items(), key=lambda item: -item[1]))
                for key, counts in totals[profile_name]["values"].items()
            },
            "attribute_coverage": {
                key: (
                    totals[profile_name][key] / totals[profile_name]["segments"]
                    if totals[profile_name]["segments"]
                    else 0.0
                )
                for key in ATTRIBUTE_KEYS
            },
        }
    overall = distribution(deltas)
    absolute = [abs(delta) for delta in deltas]
    overall_abs = distribution(absolute)
    # The bound the run must satisfy is the worst pair it produced, in either
    # direction, rounded up to 0.1 %; the p99 is kept beside it so the shape of
    # the tail is visible.
    tolerance = math.ceil(overall_abs["max"] * 1_000.0) / 1_000.0 if absolute else 0.0
    return {
        "generated": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "seed": document.get("seed"),
        "pair_count": document.get("count"),
        "profiles": per_profile,
        "distance_delta_all": overall,
        "distance_delta_abs_all": overall_abs,
        "tolerance": {
            "fraction": tolerance,
            "percent": tolerance * 100.0,
            "derived_from": "largest |valhalla - reference| / reference over every pair both engines routed, rounded up to 0.001",
            "p99": overall_abs["p99"],
            "samples": overall["count"],
        },
        "engines": {
            "valhalla": "ghcr.io/valhalla/valhalla:latest (3.9.1-52228c4e0), sha256 bf88b5c5c5131414c66a76ba77a8c5ce803a1c724c2a1f07c25961a874a419fb12, valhalla_service over the phase-A pack",
            "reference": "GraphHopper 11.1 graphhopper-web-11.1.jar, sha256 8462f758d9ea49edaded557cec5c687a0a24f004ec371cd4daaeb0534824ea33, stock foot/bike/hike custom models, imported from an osmium bbox extract of ukraine-latest.osm.pbf (2026-11-04), no elevation",
            "graphhopper": args.graphhopper,
            "valhalla_url": args.valhalla,
            "valhalla_network": (
                f"docker exec into {args.valhalla_container}, started with --network none"
                if args.valhalla_container
                else "host port"
            ),
        },
    }


def distribution(values):
    if not values:
        return {"count": 0}
    ordered = sorted(values)
    def at(fraction):
        index = min(len(ordered) - 1, int(fraction * (len(ordered) - 1)))
        return ordered[index]
    return {
        "count": len(ordered),
        "min": ordered[0],
        "p50": at(0.50),
        "p90": at(0.90),
        "p95": at(0.95),
        "p99": at(0.99),
        "max": ordered[-1],
        "mean": sum(ordered) / len(ordered),
    }


main()
