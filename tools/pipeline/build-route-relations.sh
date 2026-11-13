#!/usr/bin/env bash
#
# Extracts the region's hiking and cycling route relations from an OSM PBF.
#
# The output is `relations.json`, the component `regions.RegionReader` reads:
# a compact document with one entry per member way, one entry per route
# relation, whose members name those ways in relation order, and the numbered
# junctions of the cycling node networks. The plugin assembles the geometry and
# draws it; the file is not GeoJSON.
#
# The selection is the T-0418/T-0419 rule, implemented in
# tools/pipeline/osm-extract:
#   keep    type=route and route=hiking or route=bicycle
#   skip    state=proposed|abandoned|disused|construction|planned, or
#           proposed=*
#   skip    a relation with no way members (node members are guideposts)
#   skip    a relation whose member ways have no usable geometry
#   skip    a relation with no point inside the bbox
#   keep    a node inside the bbox with lcn_ref, rcn_ref, ncn_ref or icn_ref
#   skip    every other route value, including route=bus and route=foot
#
# Usage: tools/pipeline/build-route-relations.sh --pbf PATH --out FILE [options]
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
        *) echo "build-route-relations.sh: unknown argument: $1" >&2; exit 2 ;;
    esac
done

[[ -n "$PBF" ]] || { echo "build-route-relations.sh: --pbf is required" >&2; exit 2; }
[[ -n "$OUT" ]] || { echo "build-route-relations.sh: --out is required" >&2; exit 2; }
[[ -f "$PBF" ]] || { echo "build-route-relations.sh: missing OSM extract: $PBF" >&2; exit 1; }
command -v sha256sum >/dev/null 2>&1 || {
    echo "build-route-relations.sh: sha256sum is required" >&2
    exit 1
}

if [[ -z "$EXTRACTOR" ]]; then
    [[ -n "$CARGO" ]] || { echo "build-route-relations.sh: cargo is required" >&2; exit 1; }
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
[[ -x "$EXTRACTOR" ]] || { echo "build-route-relations.sh: no extractor: $EXTRACTOR" >&2; exit 1; }

mkdir -p "$(dirname "$OUT")"
if [[ -n "$BBOX" ]]; then
    "$EXTRACTOR" --pbf "$PBF" --route-relations --bbox "$BBOX" --out "$OUT"
else
    "$EXTRACTOR" --pbf "$PBF" --route-relations --out "$OUT"
fi

python3 - "$OUT" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
bytes_ = path.read_bytes()
document = json.loads(bytes_)
if document.get("format") != "grass-maps-route-relations" or document.get("version") != 1:
    raise SystemExit(f"{path}: not a route-relation component")
relations = document.get("relations")
ways = document.get("ways")
if not isinstance(relations, list) or not isinstance(ways, list):
    raise SystemExit(f"{path}: missing relations or ways")
print(f"route-relation component: {len(relations)} relations, {len(ways)} ways, {len(document.get('nodes', []))} nodes, {len(bytes_)} bytes")
PY
sha256sum "$OUT"
