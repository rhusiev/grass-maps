#!/usr/bin/env bash
#
# Builds the committed test region package from the bench archives.
#
# The test region is made with the same stage real oblast packs use for their
# split: `pmtiles extract` over the Planetiler build, clipped to a bounding box.
# It is tiny on purpose (a rural box, z12-14 for the map and z9-14 for the
# contours), so JVM tests can read real bytes without a large download.
#
# Usage: tools/pipeline/build-test-region.sh [options]
#   --bench DIR             bench repository (default $GRASS_MAPS_BENCH or
#                           /hdd/drive/Projects/Life/grass-maps-bench)
#   --pmtiles PATH          pmtiles CLI (default <bench>/tools/pmtiles)
#   --map-source PATH       vector tile archive (default <bench>/data/ukraine-final.pmtiles)
#   --contours-source PATH  contour archive (default <bench>/data/lviv-90m-20m.pmtiles)
#   --out DIR               package directory (default test-data/regions/<region>/<version>)
#
# The output is deterministic: re-running against the same archives produces
# the same bytes and the same manifest.

set -euo pipefail

readonly ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly REGION_ID="fixture-mini"
readonly REGION_VERSION="2026-11-03-1"
readonly BBOX="23.940,49.940,23.960,49.960"
readonly MAP_MIN_ZOOM=12
readonly MAP_MAX_ZOOM=14
readonly CONTOURS_MIN_ZOOM=9
readonly CONTOURS_MAX_ZOOM=14
readonly SEARCH_SOURCE="$ROOT/tools/pipeline/fixture-places.tsv"

BENCH="${GRASS_MAPS_BENCH:-/hdd/drive/Projects/Life/grass-maps-bench}"
PMTILES=""
MAP_SOURCE=""
CONTOURS_SOURCE=""
OUT="$ROOT/test-data/regions/$REGION_ID/$REGION_VERSION"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --bench) BENCH="$2"; shift 2 ;;
        --pmtiles) PMTILES="$2"; shift 2 ;;
        --map-source) MAP_SOURCE="$2"; shift 2 ;;
        --contours-source) CONTOURS_SOURCE="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        -h | --help) sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "build-test-region.sh: unknown argument: $1" >&2; exit 2 ;;
    esac
done

PMTILES="${PMTILES:-$BENCH/tools/pmtiles}"
MAP_SOURCE="${MAP_SOURCE:-$BENCH/data/ukraine-final.pmtiles}"
CONTOURS_SOURCE="${CONTOURS_SOURCE:-$BENCH/data/lviv-90m-20m.pmtiles}"

for tool in python3 sha256sum; do
    command -v "$tool" >/dev/null 2>&1 || {
        echo "build-test-region.sh: $tool is required" >&2
        exit 1
    }
done

# The crate sources resolve uniffi templates relative to the cargo home, so a
# home reached through a symlink fails to build. Canonicalise it, as Gradle does.
if [[ -n "${CARGO_HOME:-}" ]]; then
    CARGO_HOME="$(cd "$CARGO_HOME" && pwd -P)"
    export CARGO_HOME
fi
CARGO="${CARGO:-$(command -v cargo || true)}"
[[ -n "$CARGO" ]] || {
    echo "build-test-region.sh: cargo is required" >&2
    exit 1
}

require_file() {
    [[ -f "$1" ]] || {
        echo "build-test-region.sh: missing $2: $1" >&2
        exit 1
    }
}

require_file "$PMTILES" "pmtiles CLI"
require_file "$MAP_SOURCE" "map source archive"
require_file "$CONTOURS_SOURCE" "contours source archive"
require_file "$SEARCH_SOURCE" "fixture place list"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "Extracting map tiles: bbox=$BBOX z$MAP_MIN_ZOOM-$MAP_MAX_ZOOM"
"$PMTILES" extract "$MAP_SOURCE" "$work/map.pmtiles" \
    --bbox="$BBOX" --minzoom="$MAP_MIN_ZOOM" --maxzoom="$MAP_MAX_ZOOM" -q

echo "Extracting contours: bbox=$BBOX z$CONTOURS_MIN_ZOOM-$CONTOURS_MAX_ZOOM"
"$PMTILES" extract "$CONTOURS_SOURCE" "$work/contours.pmtiles" \
    --bbox="$BBOX" --minzoom="$CONTOURS_MIN_ZOOM" --maxzoom="$CONTOURS_MAX_ZOOM" -q

for tile_file in map.pmtiles contours.pmtiles; do
    magic="$(head -c 7 "$work/$tile_file")"
    [[ "$magic" == "PMTiles" ]] || {
        echo "build-test-region.sh: $tile_file is not a PMTiles archive" >&2
        exit 1
    }
done

printf '%s\n' \
    "Grass Maps test region stand-in: elevation.bin" \
    "Not a GLO-30 int16 grid. See tools/pipeline/README.md." \
    >"$work/elevation.bin"

echo "Building the search section and feature component: $SEARCH_SOURCE"
"$CARGO" run --quiet --release --locked \
    --manifest-path "$ROOT/rust/grass-core/Cargo.toml" \
    --bin build-section -- "$SEARCH_SOURCE" "$work"

osm_timestamp="$("$PMTILES" show "$MAP_SOURCE" |
    awk '/^planetiler:osm:osmosisreplicationtime/ {print $2}')"
[[ -n "$osm_timestamp" ]] || {
    echo "build-test-region.sh: $MAP_SOURCE has no Planetiler OSM timestamp" >&2
    exit 1
}

map_source_sha="$(sha256sum "$MAP_SOURCE" | cut -d' ' -f1)"
contours_source_sha="$(sha256sum "$CONTOURS_SOURCE" | cut -d' ' -f1)"

python3 - "$work" "$REGION_ID" "$REGION_VERSION" "$osm_timestamp" "$BBOX" \
    "$(basename "$MAP_SOURCE")" "$map_source_sha" "$MAP_MIN_ZOOM" "$MAP_MAX_ZOOM" \
    "$(basename "$CONTOURS_SOURCE")" "$contours_source_sha" \
    "$CONTOURS_MIN_ZOOM" "$CONTOURS_MAX_ZOOM" <<'PY'
import hashlib
import json
import pathlib
import sys

(
    work,
    region,
    version,
    osm_timestamp,
    bbox,
    map_archive,
    map_archive_sha,
    map_min_zoom,
    map_max_zoom,
    contours_archive,
    contours_archive_sha,
    contours_min_zoom,
    contours_max_zoom,
) = sys.argv[1:]

work = pathlib.Path(work)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def component(kind, file, fmt, fmt_version, zooms, stand_in):
    path = work / file
    entry = {
        "kind": kind,
        "file": file,
        "format": fmt,
        "formatVersion": fmt_version,
        "version": version,
        "bytes": path.stat().st_size,
        "sha256": digest(path),
        "standIn": stand_in,
    }
    if zooms:
        entry["zoomLevels"] = zooms
    return entry


manifest = {
    "region": region,
    "version": version,
    "packageFormat": 1,
    "osmTimestamp": osm_timestamp,
    "pipeline": {
        "tool": "pmtiles extract",
        "bbox": [float(value) for value in bbox.split(",")],
        "mapSource": {"archive": map_archive, "sha256": map_archive_sha},
        "contoursSource": {"archive": contours_archive, "sha256": contours_archive_sha},
    },
    "components": [
        component("map", "map.pmtiles", "pmtiles", 3, [int(map_min_zoom), int(map_max_zoom)], False),
        component("contours", "contours.pmtiles", "pmtiles", 3, [int(contours_min_zoom), int(contours_max_zoom)], False),
        component("elevation", "elevation.bin", "stand-in", 1, None, True),
        # The search segment is the eight files a version-2 reader maps, stored
        # unpacked until the single-file search.idx container lands. `format.bin`
        # declares the section version; the committed package predates it and is
        # still version 1, which the reader opens without the header.
        component("search-format", "format.bin", "search-section", 2, None, False),
        component("search-terms", "terms.fst", "search-section", 2, None, False),
        component("search-postings", "postings.bin", "search-section", 2, None, False),
        component("search-places", "places.bin", "search-section", 2, None, False),
        component("search-context", "context.bin", "search-section", 2, None, False),
        component("search-entities", "entities.bin", "search-section", 2, None, False),
        component("search-kinds", "kinds.bin", "search-section", 2, None, False),
        component("search-strings", "strings.bin", "search-section", 2, None, False),
        component("features", "features.bin", "place-features", 1, None, False),
    ],
}

(work / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
PY

rm -rf "$OUT"
mkdir -p "$(dirname "$OUT")"
cp -R "$work" "$OUT"

echo
echo "Built $OUT"
ls -l "$OUT"
echo
echo "Total: $(du -sh "$OUT" | cut -f1)"
