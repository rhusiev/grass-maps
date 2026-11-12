#!/usr/bin/env bash
#
# Builds a complete Lviv oblast region package with the real way-alert
# component, beside the existing pack.
#
# The map archive is copied from an existing pack, so its bytes and provenance
# stay the ones that pack recorded. The contour component is extracted from the
# 10 m contour archive built from the 90 m GLO-30 surface. The search section,
# the elevation grid and the way-alert component are built from the Ukraine OSM
# PBF and the GLO-30 tiles in the bench repository. The result is a package the
# current reader can mount: every component the manifest requires is present.
#
# Usage: tools/pipeline/build-lviv-region.sh [options]
#   --bench DIR        bench repository (default $GRASS_MAPS_BENCH or
#                      /hdd/drive/Projects/Life/grass-maps-bench)
#   --pbf PATH         OSM extract (default <bench>/data/ukraine-latest.osm.pbf)
#   --base-pack DIR    pack to copy the map from (default
#                      /hdd/t-0102-evidence/region/ua-46/2026-11-05-bench)
#   --contours-source PATH
#                      10 m contour archive (default
#                      <bench>/data/lviv-90m-10m.pmtiles)
#   --pmtiles PATH     pmtiles CLI (default <bench>/tools/pmtiles)
#   --dem-source DIR   GLO-30 COG tiles (default <bench>/data/dem)
#   --bbox BOX         west,south,east,north (default 22.5,48.9,25.2,50.6)
#   --version VALUE    package version (default 2026-11-12-1)
#   --out DIR          version directory (default /hdd/t-0418-hiking-relations/
#                      lviv-region/ua-46/<version>)
#   --work DIR         scratch directory (default <out>.work)
#   --keep-work        keep the OSM extract and search TSV after the build
#
# The build is not incremental: --out is replaced only after the package is
# complete.

set -euo pipefail

readonly ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly REGION_ID="ua-46"
readonly OSMIUM_IMAGE="${OSMIUM_IMAGE:-docker.io/stefda/osmium-tool:latest}"
readonly EXTRACTOR_VERSION="osm-extract 0.1.0 way-alerts v1"

BENCH="${GRASS_MAPS_BENCH:-/hdd/drive/Projects/Life/grass-maps-bench}"
PBF="$BENCH/data/ukraine-latest.osm.pbf"
BASE_PACK="/hdd/t-0102-evidence/region/ua-46/2026-11-05-bench"
CONTOURS_SOURCE="$BENCH/data/lviv-90m-10m.pmtiles"
PMTILES="$BENCH/tools/pmtiles"
CONTOURS_FILE="lviv-contours-10m.pmtiles"
DEM_SOURCE="$BENCH/data/dem"
BBOX="22.5,48.9,25.2,50.6"
VERSION="2026-11-12-1"
OUT=""
WORK=""
KEEP_WORK=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --bench) BENCH="$2"; shift 2 ;;
        --pbf) PBF="$2"; shift 2 ;;
        --base-pack) BASE_PACK="$2"; shift 2 ;;
        --contours-source) CONTOURS_SOURCE="$2"; shift 2 ;;
        --pmtiles) PMTILES="$2"; shift 2 ;;
        --dem-source) DEM_SOURCE="$2"; shift 2 ;;
        --bbox) BBOX="$2"; shift 2 ;;
        --version) VERSION="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --work) WORK="$2"; shift 2 ;;
        --keep-work) KEEP_WORK=1; shift ;;
        -h | --help) sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "build-lviv-region.sh: unknown argument: $1" >&2; exit 2 ;;
    esac
done

OUT="${OUT:-/hdd/t-0418-hiking-relations/lviv-region/$REGION_ID/$VERSION}"
WORK="${WORK:-$OUT.work}"

for tool in python3 podman sha256sum; do
    command -v "$tool" >/dev/null 2>&1 || {
        echo "build-lviv-region.sh: $tool is required" >&2
        exit 1
    }
done
CARGO="${CARGO:-$(command -v cargo || true)}"
[[ -n "$CARGO" ]] || { echo "build-lviv-region.sh: cargo is required" >&2; exit 1; }

require_file() {
    [[ -f "$1" ]] || {
        echo "build-lviv-region.sh: missing $2: $1" >&2
        exit 1
    }
}

require_file "$PBF" "OSM extract"
require_file "$BASE_PACK/manifest.json" "base manifest"
require_file "$CONTOURS_SOURCE" "contour archive"
require_file "$PMTILES" "pmtiles CLI"
[[ -d "$DEM_SOURCE" ]] || {
    echo "build-lviv-region.sh: missing DEM source directory: $DEM_SOURCE" >&2
    exit 1
}
map_file="$(
    python3 - "$BASE_PACK/manifest.json" <<'PY'
import json
import sys
components = json.load(open(sys.argv[1]))["components"]
print(next(component["file"] for component in components if component["kind"] == "map"))
PY
)"
require_file "$BASE_PACK/$map_file" "base map"

if [[ -n "${CARGO_HOME:-}" ]]; then
    CARGO_HOME="$(cd "$CARGO_HOME" && pwd -P)"
    export CARGO_HOME
fi
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$ROOT/build/rust-region-target}"
EXTRACTOR="$CARGO_TARGET_DIR/release/osm-extract"
"$CARGO" build --quiet --release --locked \
    --manifest-path "$ROOT/tools/pipeline/osm-extract/Cargo.toml"

rm -rf "$WORK"
mkdir -p "$WORK/pack"
cp --reflink=auto "$BASE_PACK/$map_file" "$WORK/pack/$map_file"
"$PMTILES" extract "$CONTOURS_SOURCE" "$WORK/pack/$CONTOURS_FILE" \
    --bbox="$BBOX" --minzoom=9 --maxzoom=14 -q
cp --reflink=auto "$(realpath "$PBF")" "$WORK/source.osm.pbf"

started=$(date +%s)

echo "Extracting the region from $PBF"
podman run --rm -v "$WORK":/data:Z \
    "$OSMIUM_IMAGE" \
    osmium extract --overwrite --bbox "$BBOX" \
    --output /data/region.osm.pbf /data/source.osm.pbf

osm_timestamp="$(
    podman run --rm -v "$WORK":/data:Z "$OSMIUM_IMAGE" osmium fileinfo -e /data/source.osm.pbf 2>/dev/null |
        awk -F= '/osmosis_replication_timestamp=/ {print $2; exit}'
)"
if [[ -z "$osm_timestamp" ]]; then
    osm_timestamp="$(python3 - "$BASE_PACK/manifest.json" <<'PY'
import json
import sys
print(json.load(open(sys.argv[1]))["osmTimestamp"])
PY
)"
fi
echo "OSM timestamp: $osm_timestamp"

echo "Extracting way alerts"
"$ROOT/tools/pipeline/build-way-alerts.sh" \
    --pbf "$WORK/region.osm.pbf" --bbox "$BBOX" \
    --out "$WORK/pack/way-alerts.geojson" --extractor "$EXTRACTOR"

echo "Extracting hiking route relations"
"$ROOT/tools/pipeline/build-route-relations.sh" \
    --pbf "$WORK/region.osm.pbf" --bbox "$BBOX" \
    --out "$WORK/pack/relations.json" --extractor "$EXTRACTOR"

echo "Extracting the search places"
"$EXTRACTOR" --pbf "$WORK/region.osm.pbf" --out "$WORK/places.tsv"
echo "Building the search section and feature component"
"$CARGO" run --quiet --release --locked \
    --manifest-path "$ROOT/rust/grass-core/Cargo.toml" \
    --bin build-section -- "$WORK/places.tsv" "$WORK/pack"

echo "Building the elevation grid"
python3 "$ROOT/tools/pipeline/build-elevation.py" grid \
    --source "$DEM_SOURCE" --bbox "$BBOX" --out "$WORK/pack/elevation.bin"

python3 - "$WORK/pack" "$BASE_PACK" "$REGION_ID" "$VERSION" "$osm_timestamp" "$BBOX" \
    "$(basename "$PBF")" "$(sha256sum "$PBF" | cut -d' ' -f1)" "$EXTRACTOR_VERSION" \
    "$DEM_SOURCE" "$(basename "$CONTOURS_SOURCE")" \
    "$(sha256sum "$CONTOURS_SOURCE" | cut -d' ' -f1)" "$CONTOURS_FILE" <<'PY'
import hashlib
import json
import math
import pathlib
import sys
from collections import Counter

(
    pack,
    base_pack,
    region,
    version,
    osm_timestamp,
    bbox,
    osm_archive,
    osm_archive_sha,
    extractor_version,
    dem_source,
    contours_source_archive,
    contours_source_sha,
    contours_file,
) = sys.argv[1:]

pack = pathlib.Path(pack)
base_pack = pathlib.Path(base_pack)
dem_source = pathlib.Path(dem_source)
base = json.loads((base_pack / "manifest.json").read_text())
base_components = {component["kind"]: component for component in base["components"]}
base_pipeline = base["pipeline"]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def component(kind, file, fmt, fmt_version, zooms=None):
    path = pack / file
    entry = {
        "kind": kind,
        "file": file,
        "format": fmt,
        "formatVersion": fmt_version,
        "version": version,
        "bytes": path.stat().st_size,
        "sha256": digest(path),
        "standIn": False,
    }
    if zooms:
        entry["zoomLevels"] = zooms
    return entry


def way_alert_counts():
    document = json.loads((pack / "way-alerts.geojson").read_text())
    kinds = Counter(feature["properties"]["kind"] for feature in document["features"])
    return {
        kind: kinds.get(kind, 0)
        for kind in ("railway_crossing", "tunnel", "hazard", "ford", "barrier")
    }


def route_relation_counts():
    document = json.loads((pack / "relations.json").read_text())
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
    return [
        {
            "tile": f"Copernicus_DSM_COG_10_N{latitude:02d}_00_E{longitude:03d}_00_DEM.tif",
            "sha256": digest(
                dem_source / f"Copernicus_DSM_COG_10_N{latitude:02d}_00_E{longitude:03d}_00_DEM.tif"
            ),
        }
        for latitude in range(int(south), math.ceil(north))
        for longitude in range(int(west), math.ceil(east))
    ]


map_component = base_components["map"]
manifest = {
    "region": region,
    "version": version,
    "packageFormat": 1,
    "osmTimestamp": osm_timestamp,
    "pipeline": {
        "tool": "pmtiles extract",
        "basePack": str(base_pack),
        "bbox": [float(value) for value in bbox.split(",")],
        "mapSource": base_pipeline["mapSource"],
        "contoursSource": {
            "archive": contours_source_archive,
            "sha256": contours_source_sha,
            "intervalMeters": 10,
            "dem": "Copernicus DEM GLO-30",
            "resample": "3 arcsec cells, mean of the 1 arcsec source pixels covering each cell",
            "detail": "10 m lines are resampled detail from the 90 m DEM, not 90 m ground truth",
        },
        "elevationSource": {
            "dataset": "Copernicus DEM GLO-30",
            "resample": "3 arcsec cells, mean of the 1 arcsec source pixels covering each cell",
            "tiles": dem_tiles(),
        },
        "searchSource": {
            "archive": osm_archive,
            "sha256": osm_archive_sha,
            "bbox": [float(value) for value in bbox.split(",")],
        },
        "wayAlertSource": {
            "archive": osm_archive,
            "sha256": osm_archive_sha,
            "bbox": [float(value) for value in bbox.split(",")],
            "extraction": extractor_version,
            "features": way_alert_counts(),
        },
        "routeRelationSource": {
            "archive": osm_archive,
            "sha256": osm_archive_sha,
            "bbox": [float(value) for value in bbox.split(",")],
            "extraction": "osm-extract 0.1.0 route-relations v1",
            "features": route_relation_counts(),
        },
    },
    "components": [
        component(
            "map",
            map_component["file"],
            map_component["format"],
            map_component["formatVersion"],
            map_component.get("zoomLevels"),
        ),
        component("contours", contours_file, "pmtiles", 3, [9, 14]),
        component("elevation", "elevation.bin", "elevation-grid", 1),
        component("search-format", "format.bin", "search-section", 2),
        component("search-terms", "terms.fst", "search-section", 2),
        component("search-postings", "postings.bin", "search-section", 2),
        component("search-places", "places.bin", "search-section", 2),
        component("search-context", "context.bin", "search-section", 2),
        component("search-entities", "entities.bin", "search-section", 2),
        component("search-kinds", "kinds.bin", "search-section", 2),
        component("search-strings", "strings.bin", "search-section", 2),
        component("features", "features.bin", "place-features", 1),
        component("way-alerts", "way-alerts.geojson", "way-alerts-geojson", 1),
        component("relations", "relations.json", "route-relations-json", 1),
    ],
}

(pack / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
PY

finished=$(date +%s)

rm -rf "$OUT"
mkdir -p "$(dirname "$OUT")"
mv "$WORK/pack" "$OUT"
printf '%s\n' "$VERSION" >"$(dirname "$OUT")/current"

echo
echo "Built $OUT in $((finished - started)) s"
ls -l "$OUT"
echo
du -sh "$OUT"
find "$OUT" -maxdepth 1 -type f -printf '%f %s\n' | sort
if [[ "$KEEP_WORK" -eq 0 ]]; then
    rm -rf "$WORK"
fi
