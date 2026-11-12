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
#   --dem-source DIR        GLO-30 tiles (default <bench>/data/dem)
#   --way-alerts-source PBF OSM input for the way-alert component (default
#                           test-data/regions/fixture-mini/way-alerts.osm.pbf)
#   --route-relations-source PBF
#                           OSM input for the route-relation component (default
#                           test-data/regions/fixture-mini/hiking-relations.osm.pbf)
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
readonly WAY_ALERTS_SOURCE="$ROOT/test-data/regions/fixture-mini/way-alerts.osm.pbf"
readonly ROUTE_RELATIONS_SOURCE="$ROOT/test-data/regions/fixture-mini/hiking-relations.osm.pbf"

BENCH="${GRASS_MAPS_BENCH:-/hdd/drive/Projects/Life/grass-maps-bench}"
PMTILES=""
MAP_SOURCE=""
CONTOURS_SOURCE=""
DEM_SOURCE=""
WAY_ALERTS_PBF="$WAY_ALERTS_SOURCE"
ROUTE_RELATIONS_PBF="$ROUTE_RELATIONS_SOURCE"
OUT="$ROOT/test-data/regions/$REGION_ID/$REGION_VERSION"

while [[ $# -gt 0 ]]; do
    case "$1" in
        --bench) BENCH="$2"; shift 2 ;;
        --pmtiles) PMTILES="$2"; shift 2 ;;
        --map-source) MAP_SOURCE="$2"; shift 2 ;;
        --contours-source) CONTOURS_SOURCE="$2"; shift 2 ;;
        --dem-source) DEM_SOURCE="$2"; shift 2 ;;
        --way-alerts-source) WAY_ALERTS_PBF="$2"; shift 2 ;;
        --route-relations-source) ROUTE_RELATIONS_PBF="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        -h | --help) sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "build-test-region.sh: unknown argument: $1" >&2; exit 2 ;;
    esac
done

PMTILES="${PMTILES:-$BENCH/tools/pmtiles}"
MAP_SOURCE="${MAP_SOURCE:-$BENCH/data/ukraine-final.pmtiles}"
CONTOURS_SOURCE="${CONTOURS_SOURCE:-$BENCH/data/lviv-90m-20m.pmtiles}"
DEM_SOURCE="${DEM_SOURCE:-$BENCH/data/dem}"

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
require_file "$ROUTE_RELATIONS_PBF" "fixture hiking-relation extract"
[[ -d "$DEM_SOURCE" ]] || {
    echo "build-test-region.sh: missing DEM source directory: $DEM_SOURCE" >&2
    exit 1
}

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

printf '%s\n' "Elevation grid"

python3 "$ROOT/tools/pipeline/build-elevation.py" grid \
    --source "$DEM_SOURCE" --bbox "$BBOX" --out "$work/elevation.bin"

# The way-alert component is the real extraction output. Its input is the
# fixture's own small OSM extract, not the Ukraine PBF: the fixture bbox has two
# real fords but no mapped crossing, tunnel or hazard, so the reader and plugin
# tests need a known feature set. The Lviv pack extracts from the real PBF; the
# same command and schema produce both.
echo "Extracting way alerts: $WAY_ALERTS_PBF"
"$ROOT/tools/pipeline/build-way-alerts.sh" \
    --pbf "$WAY_ALERTS_PBF" --bbox "$BBOX" --out "$work/way-alerts.geojson"

# The route-relation component follows the same rule: the fixture's own small
# OSM input carries the parallel routes and the skips the tests name, and the
# real extractor produces the committed bytes.
echo "Extracting hiking relations: $ROUTE_RELATIONS_PBF"
"$ROOT/tools/pipeline/build-route-relations.sh" \
    --pbf "$ROUTE_RELATIONS_PBF" --bbox "$BBOX" --out "$work/relations.json"

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
    "$CONTOURS_MIN_ZOOM" "$CONTOURS_MAX_ZOOM" "$DEM_SOURCE" \
    "$(basename "$WAY_ALERTS_PBF")" "$(sha256sum "$WAY_ALERTS_PBF" | cut -d' ' -f1)" \
    "$(basename "$ROUTE_RELATIONS_PBF")" "$(sha256sum "$ROUTE_RELATIONS_PBF" | cut -d' ' -f1)" <<'PY'
import hashlib
import json
import math
import pathlib
import sys
from collections import Counter

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
    dem_source,
    way_alerts_archive,
    way_alerts_archive_sha,
    route_relations_archive,
    route_relations_archive_sha,
) = sys.argv[1:]

work = pathlib.Path(work)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def way_alert_counts():
    document = json.loads((work / "way-alerts.geojson").read_text())
    kinds = Counter(feature["properties"]["kind"] for feature in document["features"])
    return {
        kind: kinds.get(kind, 0)
        for kind in ("railway_crossing", "tunnel", "hazard", "ford", "barrier")
    }


def route_relation_counts():
    document = json.loads((work / "relations.json").read_text())
    relations = document["relations"]
    networks = Counter(relation.get("network", "none") for relation in relations)
    return {
        "relations": len(relations),
        "ways": len(document["ways"]),
        "networks": dict(sorted(networks.items())),
        "withOsmcSymbol": sum(1 for relation in relations if "osmcSymbol" in relation),
    }


def dem_tiles():
    west, south, east, north = (float(value) for value in bbox.split(","))
    last_lat = math.ceil(north) - 1
    last_lon = math.ceil(east) - 1
    return [
        {
            "tile": f"Copernicus_DSM_COG_10_N{latitude:02d}_00_E{longitude:03d}_00_DEM.tif",
            "sha256": digest(
                pathlib.Path(dem_source)
                / f"Copernicus_DSM_COG_10_N{latitude:02d}_00_E{longitude:03d}_00_DEM.tif"
            ),
        }
        for latitude in range(int(south), last_lat + 1)
        for longitude in range(int(west), last_lon + 1)
    ]


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
        "elevationSource": {
            "dataset": "Copernicus DEM GLO-30",
            "resample": "3 arcsec cells, mean of the 1 arcsec source pixels covering each cell",
            "tiles": dem_tiles(),
        },
        "wayAlertSource": {
            "archive": way_alerts_archive,
            "sha256": way_alerts_archive_sha,
            "bbox": [float(value) for value in bbox.split(",")],
            "extraction": "osm-extract 0.1.0 way-alerts v1",
            "features": way_alert_counts(),
        },
        "routeRelationSource": {
            "archive": route_relations_archive,
            "sha256": route_relations_archive_sha,
            "bbox": [float(value) for value in bbox.split(",")],
            "extraction": "osm-extract 0.1.0 route-relations v1",
            "features": route_relation_counts(),
        },
    },
    "components": [
        component("map", "map.pmtiles", "pmtiles", 3, [int(map_min_zoom), int(map_max_zoom)], False),
        component("contours", "contours.pmtiles", "pmtiles", 3, [int(contours_min_zoom), int(contours_max_zoom)], False),
        component("elevation", "elevation.bin", "elevation-grid", 1, None, False),
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
        # Extracted from the fixture's own small OSM input; see the extraction
        # step above. The Lviv pack reads the Ukraine PBF instead.
        component("way-alerts", "way-alerts.geojson", "way-alerts-geojson", 1, None, False),
        component("relations", "relations.json", "route-relations-json", 1, None, False),
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
