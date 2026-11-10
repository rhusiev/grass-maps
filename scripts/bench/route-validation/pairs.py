"""Generate the seeded Lviv-oblast waypoint pairs for the routing validation.

The pairs are sampled uniformly inside the oblast polygon, rejecting a draw
outside the polygon or closer than MIN_SEPARATION_M to its partner. The seed,
the polygon digest and the rule are recorded with the list so a rerun
reproduces it exactly.
"""

import hashlib
import json
import math
import random
import sys

from shapely.geometry import Point, shape

SEED = 20261110
PAIRS = 100
MIN_SEPARATION_M = 2000.0
MAX_SEPARATION_M = 100000.0
POLYGON = "/hdd/drive/Projects/Life/grass-maps-bench/data/lviv-oblast.geojson"


def metres(a, b):
    lat = math.radians((a[1] + b[1]) / 2.0)
    dx = math.radians(b[0] - a[0]) * math.cos(lat)
    dy = math.radians(b[1] - a[1])
    return math.hypot(dx, dy) * 6371000.0


def main(out):
    raw = open(POLYGON, "rb").read()
    digest = hashlib.sha256(raw).hexdigest()
    area = shape(json.loads(raw)["features"][0]["geometry"])
    minx, miny, maxx, maxy = area.bounds
    rng = random.Random(SEED)
    pairs = []
    while len(pairs) < PAIRS:
        a = (rng.uniform(minx, maxx), rng.uniform(miny, maxy))
        b = (rng.uniform(minx, maxx), rng.uniform(miny, maxy))
        if not area.contains(Point(a)) or not area.contains(Point(b)):
            continue
        if not (MIN_SEPARATION_M <= metres(a, b) <= MAX_SEPARATION_M):
            continue
        pairs.append({"from": list(a), "to": list(b)})
    document = {
        "seed": SEED,
        "count": PAIRS,
        "min_separation_m": MIN_SEPARATION_M,
        "max_separation_m": MAX_SEPARATION_M,
        "polygon": {
            "path": POLYGON,
            "sha256": digest,
            "bounds": [minx, miny, maxx, maxy],
        },
        "pairs": pairs,
    }
    with open(out, "w") as handle:
        json.dump(document, handle, indent=2)
        handle.write("\n")
    print(f"{len(pairs)} pairs, {digest[:16]}", file=sys.stderr)


main(sys.argv[1])
