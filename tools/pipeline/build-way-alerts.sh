#!/usr/bin/env bash
#
# Extracts the region's way-alert features from an OSM PBF extract.
#
# The output is `way-alerts.geojson`, the component `regions.RegionReader`
# reads: a GeoJSON FeatureCollection whose features carry `kind`
# (`railway_crossing`, `tunnel`, `hazard`, `ford` or `barrier`), `osmType`
# (`node` or `way`) and `osmId`. A node is a Point, a way a LineString.
#
# The selection is the T-0319A rule, implemented in tools/pipeline/osm-extract:
#   railway_crossing  node with railway=level_crossing or
#                     railway=tram_level_crossing, or a node shared by a
#                     railway=rail/narrow_gauge way and a travel highway=* way
#   tunnel            way with tunnel=* on a travel highway=* (building
#                     passages, culverts, floodways, canals and pipes are not
#                     passages a rider goes through)
#   hazard            node or travel-highway way with hazard=*
#   ford              node or travel-highway way with ford=*
#   barrier           node with barrier=*, except bollard, kerb, entrance and
#                     a height/width restrictor, which a bike passes
#
# Usage: tools/pipeline/build-way-alerts.sh --pbf PATH --out FILE [options]
#   --pbf PATH        OSM extract to read (required)
#   --out FILE        component to write (required)
#   --bbox BOX        west,south,east,north clip; omit when the PBF is already
#                     clipped to the region
#   --extractor PATH  prebuilt osm-extract binary (default: build the crate)
#   --cargo PATH      cargo executable when the extractor is built
#
# The output is deterministic for one PBF and one bbox.

set -euo pipefail

readonly ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

PBF=""
OUT=""
BBOX=""
EXTRACTOR=""
CARGO="${CARGO:-$(command -v cargo || true)}"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --pbf) PBF="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --bbox) BBOX="$2"; shift 2 ;;
        --extractor) EXTRACTOR="$2"; shift 2 ;;
        --cargo) CARGO="$2"; shift 2 ;;
        -h | --help) sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "build-way-alerts.sh: unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ -n "$PBF" ]] || { echo "build-way-alerts.sh: --pbf is required" >&2; exit 2; }
[[ -n "$OUT" ]] || { echo "build-way-alerts.sh: --out is required" >&2; exit 2; }
[[ -f "$PBF" ]] || { echo "build-way-alerts.sh: missing OSM extract: $PBF" >&2; exit 1; }
command -v sha256sum >/dev/null 2>&1 || {
    echo "build-way-alerts.sh: sha256sum is required" >&2
    exit 1
}

if [[ -z "$EXTRACTOR" ]]; then
    [[ -n "$CARGO" ]] || { echo "build-way-alerts.sh: cargo is required" >&2; exit 1; }
    # A cargo home reached through a symlink fails the crate's uniffi
    # templates, so resolve it as Gradle and check.sh do.
    if [[ -n "${CARGO_HOME:-}" ]]; then
        CARGO_HOME="$(cd "$CARGO_HOME" && pwd -P)"
        export CARGO_HOME
    fi
    export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$ROOT/build/rust-region-target}"
    "$CARGO" build --quiet --release --locked \
        --manifest-path "$ROOT/tools/pipeline/osm-extract/Cargo.toml"
    EXTRACTOR="$CARGO_TARGET_DIR/release/osm-extract"
fi
[[ -x "$EXTRACTOR" ]] || { echo "build-way-alerts.sh: no extractor: $EXTRACTOR" >&2; exit 1; }

mkdir -p "$(dirname "$OUT")"
if [[ -n "$BBOX" ]]; then
    "$EXTRACTOR" --pbf "$PBF" --way-alerts --bbox "$BBOX" --out "$OUT"
else
    "$EXTRACTOR" --pbf "$PBF" --way-alerts --out "$OUT"
fi

python3 - "$OUT" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
bytes_ = path.read_bytes()
document = json.loads(bytes_)
if document.get("type") != "FeatureCollection" or not isinstance(document.get("features"), list):
    raise SystemExit(f"{path}: not a way-alert FeatureCollection")
print(f"way-alert component: {len(document['features'])} features, {len(bytes_)} bytes")
PY
sha256sum "$OUT"
