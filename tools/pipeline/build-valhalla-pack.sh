#!/usr/bin/env bash
#
# Builds a Valhalla routing pack for one oblast with GLO-30 elevation.
#
# The graph is built from an osmium polygon extract, then
# `valhalla_add_elevation` reads the 1 arcsec `.hgt` tiles produced by
# `build-elevation.py hgt` and writes edge grades into the graph. The result is
# `tiles.tar` plus the admin and timezone databases and `valhalla.json`, the
# same shape as the phase-A country pack.
#
# Usage: tools/pipeline/build-valhalla-pack.sh [options]
#   --pbf PATH          OSM extract (default $GRASS_MAPS_BENCH/data/ukraine-latest.osm.pbf)
#   --polygon PATH      oblast polygon, GeoJSON (default $GRASS_MAPS_BENCH/data/lviv-oblast.geojson)
#   --admin-pbf PATH    PBF for boundary relations (default: the same as --pbf)
#   --dem-source DIR    GLO-30 COG tiles (default $GRASS_MAPS_BENCH/data/dem)
#   --timezones PATH    reuse this timezones.sqlite instead of building one
#   --out DIR           output pack directory (default $GRASS_MAPS_BENCH/data/valhalla-lviv-elev)
#   --work DIR          scratch directory (default <out>.work)
#   --keep-work         keep the OSM extract and HGT tiles after the build
#
# The build is not incremental: --out is replaced only after the pack is complete.
set -euo pipefail

readonly ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly VALHALLA_IMAGE="${VALHALLA_IMAGE:-ghcr.io/valhalla/valhalla:latest}"
readonly OSMIUM_IMAGE="${OSMIUM_IMAGE:-docker.io/stefda/osmium-tool:latest}"
readonly BENCH="${GRASS_MAPS_BENCH:-/hdd/drive/Projects/Life/grass-maps-bench}"

PBF="$BENCH/data/ukraine-latest.osm.pbf"
POLYGON="$BENCH/data/lviv-oblast.geojson"
ADMIN_PBF=""
DEM_SOURCE="$BENCH/data/dem"
TIMEZONES=""
OUT="$BENCH/data/valhalla-lviv-elev"
WORK=""
KEEP_WORK=0

while [[ $# -gt 0 ]]; do
    case "$1" in
        --pbf) PBF="$2"; shift 2 ;;
        --polygon) POLYGON="$2"; shift 2 ;;
        --admin-pbf) ADMIN_PBF="$2"; shift 2 ;;
        --dem-source) DEM_SOURCE="$2"; shift 2 ;;
        --timezones) TIMEZONES="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --work) WORK="$2"; shift 2 ;;
        --keep-work) KEEP_WORK=1; shift ;;
        -h | --help) sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "build-valhalla-pack.sh: unknown argument: $1" >&2; exit 2 ;;
    esac
done

ADMIN_PBF="${ADMIN_PBF:-$PBF}"
WORK="${WORK:-$OUT.work}"

for tool in python3 podman sha256sum; do
    command -v "$tool" >/dev/null 2>&1 || {
        echo "build-valhalla-pack.sh: $tool is required" >&2
        exit 1
    }
done

require_file() {
    [[ -f "$1" ]] || {
        echo "build-valhalla-pack.sh: missing $2: $1" >&2
        exit 1
    }
}

require_file "$PBF" "OSM extract"
require_file "$POLYGON" "oblast polygon"
require_file "$ADMIN_PBF" "admin PBF"
[[ -z "$TIMEZONES" || -f "$TIMEZONES" ]] || {
    echo "build-valhalla-pack.sh: missing timezone database: $TIMEZONES" >&2
    exit 1
}

rm -rf "$WORK"
mkdir -p "$WORK/pack"
cp --reflink=auto "$(realpath "$PBF")" "$WORK/source.osm.pbf"
if [[ "$(realpath "$ADMIN_PBF")" == "$(realpath "$PBF")" ]]; then
    ADMIN_PBF="$WORK/source.osm.pbf"
else
    cp --reflink=auto "$(realpath "$ADMIN_PBF")" "$WORK/admins.osm.pbf"
    ADMIN_PBF="$WORK/admins.osm.pbf"
fi
python3 - "$POLYGON" "$WORK/oblast.geojson" <<'PY'
import json
import sys

document = json.load(open(sys.argv[1]))
feature = document["features"][0] if document.get("type") == "FeatureCollection" else document
if feature.get("type") != "Feature":
    raise SystemExit(f"{sys.argv[1]}: expected a GeoJSON Feature or FeatureCollection")
json.dump(feature, open(sys.argv[2], "w"))
PY

bbox="$(python3 - "$POLYGON" <<'PY'
import json
import sys

document = json.load(open(sys.argv[1]))


def points(value):
    if isinstance(value, list) and value and isinstance(value[0], (int, float)):
        yield value
    elif isinstance(value, list):
        for item in value:
            yield from points(item)


coordinates = []
for feature in document["features"]:
    coordinates.extend(points(feature["geometry"]["coordinates"]))
longitudes = [point[0] for point in coordinates]
latitudes = [point[1] for point in coordinates]
print(f"{min(longitudes)},{min(latitudes)},{max(longitudes)},{max(latitudes)}")
PY
)"
echo "oblast bbox: $bbox"

started=$(date +%s)
echo "Building GLO-30 HGT tiles"
python3 "$ROOT/tools/pipeline/build-elevation.py" hgt \
    --source "$DEM_SOURCE" --bbox "$bbox" --out "$WORK/pack/elevation"

echo "Extracting the oblast from $PBF"
podman run --rm \
    -v "$WORK":/data:Z \
    "$OSMIUM_IMAGE" \
    osmium extract --overwrite --polygon /data/oblast.geojson \
    --output /data/lviv.osm.pbf /data/source.osm.pbf

echo "Writing the Valhalla config and admin databases"
podman run --rm -v "$WORK":/data:Z "$VALHALLA_IMAGE" valhalla_build_config \
    --mjolnir-tile-dir /data/pack/tiles \
    --mjolnir-tile-extract /data/pack/tiles.tar \
    --mjolnir-timezone /data/pack/timezones.sqlite \
    --mjolnir-admin /data/pack/admins.sqlite \
    --additional-data-elevation /data/pack/elevation \
    >"$WORK/pack/valhalla.json"
if [[ -n "$TIMEZONES" ]]; then
    cp "$TIMEZONES" "$WORK/pack/timezones.sqlite"
else
    podman run --rm -v "$WORK":/data:Z "$VALHALLA_IMAGE" valhalla_build_timezones \
        >"$WORK/pack/timezones.sqlite"
fi
podman run --rm \
    -v "$WORK":/data:Z \
    "$VALHALLA_IMAGE" valhalla_build_admins \
    -c /data/pack/valhalla.json "/data/$(basename "$ADMIN_PBF")"

echo "Building graph tiles (the config's elevation directory adds GLO-30 grades in this pass)"
built=$(date +%s)
podman run --rm -v "$WORK":/data:Z "$VALHALLA_IMAGE" valhalla_build_tiles \
    -c /data/pack/valhalla.json /data/lviv.osm.pbf
tiles_done=$(date +%s)
echo "Graph build with elevation: $((tiles_done - built)) s"

echo "Packing the tile extract"
podman run --rm -v "$WORK":/data:Z "$VALHALLA_IMAGE" valhalla_build_extract \
    -c /data/pack/valhalla.json -v
finished=$(date +%s)

# The HGT tiles are build input: the graph now carries the grades, and the
# region package serves runtime heights from its own elevation grid, so the
# pack does not ship a second elevation source.
elevation_size="$(du -sh "$WORK/pack/elevation" | cut -f1)"
rm -rf "$WORK/pack/elevation"
python3 - "$WORK/pack/valhalla.json" <<'PY'
import json
import sys

path = sys.argv[1]
document = json.load(open(path))
document["additional_data"]["elevation"] = ""
json.dump(document, open(path, "w"), indent=2)
PY

rm -rf "$OUT"
mkdir -p "$(dirname "$OUT")"
mv "$WORK/pack" "$OUT"

echo
echo "Built $OUT in $((finished - started)) s"
echo "GLO-30 HGT build input (removed from the pack): $elevation_size"
du -sh "$OUT" "$OUT/tiles.tar"
ls -l "$OUT"
sha256sum "$OUT/tiles.tar" >"$OUT/tiles.tar.sha256"
cat "$OUT/tiles.tar.sha256"

if [[ "$KEEP_WORK" -eq 0 ]]; then
    rm -rf "$WORK"
fi
