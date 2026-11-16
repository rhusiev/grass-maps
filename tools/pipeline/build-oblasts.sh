#!/usr/bin/env bash
#
# Builds every Ukrainian oblast region package from one monthly country build.
#
# One command, one country snapshot: the country vector tile archive, the
# country contour archive, the country Valhalla tile build and the GLO-30
# source tiles are the inputs; every oblast package is a slice of them plus a
# search section extracted from the same PBF. The manifest of every package
# records the same build digests, so an installed routing part can always be
# traced to the one country build it came from.
#
# Oblast set: the admin_level=4 polygons tagged ISO3166-2=UA-*, 27 units. Kyiv
# city (UA-30) folds into Kyiv Oblast (UA-32) and Sevastopol (UA-40) into
# Crimea (UA-43), leaving 25 packages. A Russian-mirror boundary without an
# ISO tag is skipped.
#
# Usage: tools/pipeline/build-oblasts.sh [options]
#   --pbf PATH           monthly country OSM extract (default $GRASS_MAPS_BENCH/
#                        data/ukraine-latest.osm.pbf)
#   --map PATH           country vector tile archive (default <bench>/data/
#                        ukraine-final.pmtiles)
#   --dem DIR            Copernicus GLO-30 tiles (default <bench>/data/dem)
#   --contours PATH      country contour archive (required unless --build-contours)
#   --routing-tar PATH   country Valhalla tile extract (required unless
#                        --build-routing)
#   --timezones PATH     Valhalla timezone database (default <bench>/data/
#                        valhalla/timezones.sqlite)
#   --planetiler PATH    planetiler jar (default <bench>/tools/planetiler.jar)
#   --pmtiles PATH       pmtiles CLI (default <bench>/tools/pmtiles)
#   --oblast ID[,ID...]  build only these ISO ids (default all 25)
#   --out DIR            output root (default /hdd/t-0501-oblasts)
#   --version VALUE      package version (default the PBF snapshot date)
#   --work DIR           scratch directory (default <out>.work)
#   --build-contours     build the country contour archive from the DEM first
#   --build-routing      build the country Valhalla tiles from the PBF first
#   --routing-elevation  with --build-routing, sample GLO-30 grades into the graph
#   --routing-grades    the reused --routing-tar already carries GLO-30 grades
#   --land-polygons KIND what the --map archive was built with: `stub` (the
#                        empty sea/land archives, the current state) or `real`
#   --keep-work          keep the per-oblast OSM extract and search TSV
#   --elevation-30m      also build each oblast's 30 m elevation add-on, a
#                        package of its own: <out>/<region>-elevation-30m/
#                        <version>/ with one 1 arcsec elevation.bin, listed in
#                        the catalog as an add-on of the region (T-0509)
#   --elevation-30m-only build only the add-on of the --oblast ids. It needs
#                        --version, --boundaries and --dem, and no PBF, map,
#                        contours or routing input. It writes no catalog: the
#                        catalog is written by a build that has the oblast
#                        packages beside the add-ons
#   --boundaries PATH    the boundary table (boundaries.json) for
#                        --elevation-30m-only (default <out>/boundaries/
#                        boundaries.json)
#   --osm-timestamp VALUE  with --elevation-30m-only, the snapshot recorded in
#                        the add-on manifest (default <version>T00:00:00Z)
#
# The build is not incremental: a package directory is replaced only after its
# manifest is written. Existing packages are rebuilt when the command runs.
#
# Output: <out>/<region>/<version>/ with the package and its manifest,
# <out>/boundaries/ (the exact polygons and the package table),
# <out>/outlines/ (simplified polygons for the catalog), <out>/catalog.json and
# <out>/metrics.json (time, peak RSS, disk bytes and routing bytes per oblast).

set -euo pipefail

readonly ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly PY="$ROOT/tools/pipeline/oblast_pipeline.py"
readonly OSMIUM_IMAGE="${OSMIUM_IMAGE:-docker.io/stefda/osmium-tool:latest}"
readonly VALHALLA_IMAGE="${VALHALLA_IMAGE:-ghcr.io/valhalla/valhalla:latest}"
readonly GDAL_IMAGE="${GDAL_IMAGE:-ghcr.io/osgeo/gdal:ubuntu-small-latest}"
readonly EXTRACTOR_VERSION="osm-extract 0.1.0 way-alerts v1"
readonly MAP_MIN_ZOOM=8
readonly MAP_MAX_ZOOM=14
readonly CONTOUR_INTERVAL=10
readonly PLANETILER_HEAP="${PLANETILER_HEAP:-8g}"
readonly SLOT_TIMEOUT="${SLOT_TIMEOUT:-6h}"
readonly ROUTING_SLICE_TIMEOUT=30m

BENCH="${GRASS_MAPS_BENCH:-/hdd/drive/Projects/Life/grass-maps-bench}"
PBF="$BENCH/data/ukraine-latest.osm.pbf"
MAP="$BENCH/data/ukraine-final.pmtiles"
DEM="$BENCH/data/dem"
CONTOURS=""
ROUTING_TAR=""
TIMEZONES="$BENCH/data/valhalla/timezones.sqlite"
PLANETILER="$BENCH/tools/planetiler.jar"
PMTILES="$BENCH/tools/pmtiles"
OBLASTS="all"
OUT="/hdd/t-0501-oblasts"
VERSION=""
WORK=""
BUILD_CONTOURS=0
BUILD_ROUTING=0
ROUTING_ELEVATION=0
ROUTING_GRADES=0
KEEP_WORK=0
LAND_POLYGONS="stub"
ELEVATION_30M=0
ELEVATION_30M_ONLY=0
BOUNDARIES=""
OSM_TIMESTAMP=""

if [[ -z "${GRASS_MAPS_IN_DATA_SLOT:-}" && -z "${CI:-}" ]] && command -v agent-lock >/dev/null 2>&1; then
    export GRASS_MAPS_IN_DATA_SLOT=1
    exec agent-lock run gm-data --timeout "$SLOT_TIMEOUT" -- "$0" "$@"
fi

while [[ $# -gt 0 ]]; do
    case "$1" in
        --pbf) PBF="$2"; shift 2 ;;
        --map) MAP="$2"; shift 2 ;;
        --dem) DEM="$2"; shift 2 ;;
        --contours) CONTOURS="$2"; shift 2 ;;
        --routing-tar) ROUTING_TAR="$2"; shift 2 ;;
        --timezones) TIMEZONES="$2"; shift 2 ;;
        --planetiler) PLANETILER="$2"; shift 2 ;;
        --pmtiles) PMTILES="$2"; shift 2 ;;
        --oblast) OBLASTS="$2"; shift 2 ;;
        --out) OUT="$2"; shift 2 ;;
        --version) VERSION="$2"; shift 2 ;;
        --work) WORK="$2"; shift 2 ;;
        --build-contours) BUILD_CONTOURS=1; shift ;;
        --build-routing) BUILD_ROUTING=1; shift ;;
        --routing-elevation) ROUTING_ELEVATION=1; shift ;;
        --routing-grades) ROUTING_GRADES=1; shift ;;
        --land-polygons) LAND_POLYGONS="$2"; shift 2 ;;
        --keep-work) KEEP_WORK=1; shift ;;
        --elevation-30m) ELEVATION_30M=1; shift ;;
        --elevation-30m-only) ELEVATION_30M=1; ELEVATION_30M_ONLY=1; shift ;;
        --boundaries) BOUNDARIES="$2"; shift 2 ;;
        --osm-timestamp) OSM_TIMESTAMP="$2"; shift 2 ;;
        -h | --help) sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "build-oblasts.sh: unknown argument: $1" >&2; exit 2 ;;
    esac
done

WORK="${WORK:-$OUT.work}"

run_host() {
    local stage="$1" report="$2"
    shift 2
    local options=()
    if [[ "$1" == --cwd ]]; then
        options+=(--cwd "$2")
        shift 2
    fi
    python3 "$PY" run --stage "$stage" --report "$report" "${options[@]}" -- "$@"
}

# One oblast's 30 m elevation add-on: GLO-30 at its own 1 arcsec resolution in
# the elevation container, with a manifest of its own. The cells are not
# averaged, so the grid is as fine as the source (T-0509).
build_elevation_30m() {
    local region="${1,,}"
    local NAME BBOX INCLUDES
    eval "$(python3 "$PY" info --boundaries "$TABLE" --region "$region")"
    local pack="$WORK/packs/$region-elevation-30m"
    local stages="$WORK/stages/$region-elevation-30m"
    local started=$SECONDS
    rm -rf "$pack" "$stages"
    mkdir -p "$pack" "$stages"

    echo
    echo "== $region $NAME, 30 m elevation add-on"
    run_host elevation-30m "$stages/elevation-30m.json" \
        python3 "$ROOT/tools/pipeline/build-elevation.py" grid \
        --source "$DEM" --bbox "$BBOX" --out "$pack/elevation.bin" --skip-missing --snap-bbox --arcsec 1

    cat >"$WORK/spec-$region-elevation-30m.json" <<JSON
{
  "region": "$region",
  "version": "$VERSION",
  "pack": "$pack",
  "osmTimestamp": "$osm_timestamp",
  "tool": "build-oblasts.sh",
  "demSource": "$DEM"
}
JSON
    python3 "$PY" addon-manifest --spec "$WORK/spec-$region-elevation-30m.json" --boundaries "$TABLE"

    rm -rf "$OUT/$region-elevation-30m"
    mkdir -p "$OUT/$region-elevation-30m"
    mv "$pack" "$OUT/$region-elevation-30m/$VERSION"
    echo "== $region 30 m add-on done in $((SECONDS - started)) s," \
        "$(stat -c %s "$OUT/$region-elevation-30m/$VERSION/elevation.bin") bytes"
}

if [[ "$ELEVATION_30M_ONLY" -eq 1 ]]; then
    [[ -n "$VERSION" ]] || { echo "build-oblasts.sh: --elevation-30m-only needs --version" >&2; exit 2; }
    [[ "$OBLASTS" != "all" ]] || { echo "build-oblasts.sh: --elevation-30m-only needs --oblast" >&2; exit 2; }
    [[ -d "$DEM" ]] || { echo "build-oblasts.sh: missing DEM directory: $DEM" >&2; exit 1; }
    TABLE="${BOUNDARIES:-$OUT/boundaries/boundaries.json}"
    [[ -f "$TABLE" ]] || { echo "build-oblasts.sh: missing boundary table: $TABLE" >&2; exit 1; }
    osm_timestamp="${OSM_TIMESTAMP:-${VERSION}T00:00:00Z}"
    mkdir -p "$WORK" "$OUT"
    IFS=',' read -r -a selected <<<"$OBLASTS"
    for region in "${selected[@]}"; do
        build_elevation_30m "$region"
    done
    exit 0
fi

for tool in python3 podman sha256sum tar; do
    command -v "$tool" >/dev/null 2>&1 || {
        echo "build-oblasts.sh: $tool is required" >&2
        exit 1
    }
done
CARGO="${CARGO:-$(command -v cargo || true)}"
[[ -n "$CARGO" ]] || { echo "build-oblasts.sh: cargo is required" >&2; exit 1; }
if [[ -n "${CARGO_HOME:-}" ]]; then
    CARGO_HOME="$(cd "$CARGO_HOME" && pwd -P)"
    export CARGO_HOME
fi
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$ROOT/build/rust-region-target}"
JAVA="${JAVA_HOME:-$HOME/dotfiles/local/share/jdk}/bin/java"

require_file() {
    [[ -f "$1" ]] || {
        echo "build-oblasts.sh: missing $2: $1" >&2
        exit 1
    }
}

require_file "$PBF" "OSM extract"
require_file "$MAP" "country map archive"
require_file "$PMTILES" "pmtiles CLI"
[[ -d "$DEM" ]] || {
    echo "build-oblasts.sh: missing DEM directory: $DEM" >&2
    exit 1
}
if [[ "$BUILD_ROUTING" -eq 0 ]]; then
    require_file "$ROUTING_TAR" "country Valhalla tile extract (or pass --build-routing)"
fi
if [[ "$BUILD_CONTOURS" -eq 0 ]]; then
    require_file "$CONTOURS" "country contour archive (or pass --build-contours)"
fi

mkdir -p "$WORK" "$OUT"

DATA_DIR="$(cd "$(dirname "$(realpath "$PBF")")" && pwd -P)"
TABLE="$WORK/boundaries/boundaries/boundaries.json"

pbf_sha="$(sha256sum "$PBF" | cut -d' ' -f1)"
map_sha="$(sha256sum "$MAP" | cut -d' ' -f1)"

osm_timestamp="$(
    podman run --rm -v "$DATA_DIR":/in:ro,Z "$OSMIUM_IMAGE" \
        osmium fileinfo -e "/in/$(basename "$PBF")" 2>/dev/null |
        awk -F= '/osmosis_replication_timestamp=/ {print $2; exit}'
)"
[[ -n "$osm_timestamp" ]] || { echo "build-oblasts.sh: the PBF carries no replication timestamp" >&2; exit 1; }
VERSION="${VERSION:-${osm_timestamp%%T*}}"

run_container() {
    local stage="$1" report="$2" image="$3"
    shift 3
    local volumes=()
    while [[ "$1" == --volume ]]; do
        volumes+=(--volume "$2")
        shift 2
    done
    python3 "$PY" run --stage "$stage" --report "$report" --container "$image" "${volumes[@]}" -- "$@"
}

echo "Monthly country build"
echo "  PBF        $PBF"
echo "  sha256     $pbf_sha"
echo "  snapshot   $osm_timestamp"
echo "  version    $VERSION"
echo "  map        $MAP ($map_sha)"
echo "  contours   ${CONTOURS:-<built from $DEM>}"
echo "  routing    ${ROUTING_TAR:-<built from the PBF>}"
echo

if [[ "$BUILD_CONTOURS" -eq 1 ]]; then
    require_file "$JAVA" "Java for Planetiler"
fi

if [[ ! -f "$TABLE" ]]; then
    echo "Exporting the admin_level=4 boundaries"
    admin="$WORK/boundaries/admin-level-4.geojson"
    mkdir -p "$WORK/boundaries"
    podman run --rm -v "$DATA_DIR":/in:ro,Z -v "$WORK/boundaries":/out:Z "$GDAL_IMAGE" \
        ogr2ogr -f GeoJSON /out/admin-level-4.geojson "/in/$(basename "$PBF")" multipolygons \
        -where "boundary = 'administrative' AND admin_level = '4'"
    python3 "$PY" boundaries --admin-geojson "$admin" --out "$WORK/boundaries"
fi

if [[ "$BUILD_CONTOURS" -eq 1 ]]; then
    echo "Building the country contour archive from $DEM"
    contours_dir="$WORK/country-contours"
    rm -rf "$contours_dir"
    mkdir -p "$contours_dir"
    cp "$ROOT/tools/pipeline/contours-100-50-10.yml" "$contours_dir/"
    run_container gdal-buildvrt "$WORK/stages/country/gdal-buildvrt.json" "$GDAL_IMAGE" \
        --volume "$DEM":/dem:ro,Z --volume "$contours_dir":/data:Z \
        bash -c 'gdalbuildvrt /data/ua.vrt /dem/*.tif'
    python3 - "$WORK/boundaries/boundaries" "$contours_dir/ukraine.geojson" <<'GEOM'
import json
import pathlib
import sys

features = [
    json.loads(path.read_text())
    for path in sorted(pathlib.Path(sys.argv[1]).glob("ua-*.geojson"))
]
json.dump({"type": "FeatureCollection", "features": features}, open(sys.argv[2], "w"))
GEOM
    run_container gdal-warp-90m "$WORK/stages/country/gdal-warp-90m.json" "$GDAL_IMAGE" \
        --volume "$DEM":/dem:ro,Z --volume "$contours_dir":/data:Z \
        gdalwarp -overwrite -multi -t_srs EPSG:4326 -tr 0.000833333 0.000833333 -r average \
        -cutline /data/ukraine.geojson -crop_to_cutline \
        -dstnodata -9999 -co COMPRESS=DEFLATE -co PREDICTOR=2 -co TILED=YES \
        /data/ua.vrt /data/ua-90m.tif
    run_container gdal-contour "$WORK/stages/country/gdal-contour.json" "$GDAL_IMAGE" \
        --volume "$DEM":/dem:ro,Z --volume "$contours_dir":/data:Z \
        gdal_contour -i "$CONTOUR_INTERVAL" -a ele -f GPKG -nln contours /data/ua-90m.tif /data/ua-contours.gpkg
    run_host planetiler-contours "$WORK/stages/country/planetiler-contours.json" --cwd "$contours_dir" \
        "$JAVA" "-Xmx$PLANETILER_HEAP" -jar "$PLANETILER" generate-custom --schema=contours-100-50-10.yml \
        --output=ua-contours.pmtiles --force --minzoom=11 --tmpdir="$contours_dir/tmp"
    CONTOURS="$contours_dir/ua-contours.pmtiles"
fi
require_file "$CONTOURS" "country contour archive"
contours_sha="$(sha256sum "$CONTOURS" | cut -d' ' -f1)"

if [[ "$BUILD_ROUTING" -eq 1 ]]; then
    echo "Building the country Valhalla tiles from $PBF"
    routing_dir="$WORK/country-routing"
    rm -rf "$routing_dir"
    mkdir -p "$routing_dir/tiles"
    require_file "$TIMEZONES" "Valhalla timezone database"
    cp "$TIMEZONES" "$routing_dir/timezones.sqlite"
    elevation_args=()
    if [[ "$ROUTING_ELEVATION" -eq 1 ]]; then
        echo "Resampling GLO-30 to Valhalla HGT tiles"
        run_host hgt "$WORK/stages/country/hgt.json" \
            python3 "$ROOT/tools/pipeline/build-elevation.py" hgt \
            --source "$DEM" --bbox "$(python3 - "$TABLE" <<'PY'
import json
import sys

packages = json.load(open(sys.argv[1]))["packages"]
print(
    f"{min(package['bbox'][0] for package in packages)},"
    f"{min(package['bbox'][1] for package in packages)},"
    f"{max(package['bbox'][2] for package in packages)},"
    f"{max(package['bbox'][3] for package in packages)}"
)
PY
)" --out "$routing_dir/elevation" --skip-missing
        elevation_args=(--additional-data-elevation /data/elevation)
    fi
    podman run --rm -v "$routing_dir":/data:Z "$VALHALLA_IMAGE" valhalla_build_config \
        --mjolnir-tile-dir /data/tiles \
        --mjolnir-tile-extract /data/tiles.tar \
        --mjolnir-timezone /data/timezones.sqlite \
        --mjolnir-admin /data/admins.sqlite \
        "${elevation_args[@]}" \
        >"$routing_dir/valhalla.json"
    run_container valhalla-admins "$WORK/stages/country/valhalla-admins.json" "$VALHALLA_IMAGE" \
        --volume "$routing_dir":/data:Z --volume "$DATA_DIR":/in:ro,Z \
        valhalla_build_admins -c /data/valhalla.json "/in/$(basename "$PBF")"
    run_container valhalla-tiles "$WORK/stages/country/valhalla-tiles.json" "$VALHALLA_IMAGE" \
        --volume "$routing_dir":/data:Z --volume "$DATA_DIR":/in:ro,Z \
        valhalla_build_tiles -c /data/valhalla.json "/in/$(basename "$PBF")"
    run_container valhalla-extract "$WORK/stages/country/valhalla-extract.json" "$VALHALLA_IMAGE" \
        --volume "$routing_dir":/data:Z \
        valhalla_build_extract -c /data/valhalla.json -v
    rm -rf "$routing_dir/elevation"
    ROUTING_TAR="$routing_dir/tiles.tar"
fi
require_file "$ROUTING_TAR" "country Valhalla tile extract"
routing_build_sha="$(sha256sum "$ROUTING_TAR" | cut -d' ' -f1)"
if [[ "$BUILD_ROUTING" -eq 1 ]]; then
    cp "$WORK/country-routing/valhalla.json" "$WORK/routing-config.json"
else
    podman run --rm "$VALHALLA_IMAGE" valhalla_build_config \
        --mjolnir-tile-dir /data/tiles \
        --mjolnir-tile-extract /data/routing/tiles.tar \
        --mjolnir-timezone /data/timezones.sqlite \
        --mjolnir-admin /data/admins.sqlite >"$WORK/routing-config.json"
fi
routing_grades="none"
if [[ "$ROUTING_ELEVATION" -eq 1 || "$ROUTING_GRADES" -eq 1 ]]; then
    routing_grades="GLO-30 grades sampled from the DEM HGT tiles"
fi
routing_build="valhalla_build_tiles over the country PBF, sliced per oblast by valhalla_build_extract --geojson-dir"

if [[ "$LAND_POLYGONS" == "stub" ]]; then
    land_polygons="stub archives: the land and water polygon archives are empty, so a coastline has no sea polygon (osmdata.openstreetmap.de was unreachable at archive build time)"
else
    land_polygons="real sea and land polygons"
fi

mkdir -p "$WORK/stages/country"
cat >"$WORK/build.json" <<JSON
{
  "version": "$VERSION",
  "osmTimestamp": "$osm_timestamp",
  "pbfArchive": "$(basename "$PBF")",
  "pbfSha256": "$pbf_sha",
  "mapArchive": "$(basename "$MAP")",
  "mapSha256": "$map_sha",
  "contoursArchive": "$(basename "$CONTOURS")",
  "contoursSha256": "$contours_sha",
  "contoursLevels": "100/50/10 m from the 90 m GLO-30 surface",
  "routingBuildArchive": "$(basename "$ROUTING_TAR")",
  "routingBuildSha256": "$routing_build_sha",
  "routingGrades": "$routing_grades"
}
JSON

echo "Building the Rust extractor"
"$CARGO" build --quiet --release --locked \
    --manifest-path "$ROOT/tools/pipeline/osm-extract/Cargo.toml"
EXTRACTOR="$CARGO_TARGET_DIR/release/osm-extract"
[[ -x "$EXTRACTOR" ]] || { echo "build-oblasts.sh: no extractor: $EXTRACTOR" >&2; exit 1; }

build_oblast() {
    local region="${1,,}"
    local NAME BBOX INCLUDES
    eval "$(python3 "$PY" info --boundaries "$TABLE" --region "$region")"
    local pack="$WORK/packs/$region"
    local stages="$WORK/stages/$region"
    local boundary="$WORK/boundaries/boundaries/${region,,}.geojson"
    local started=$SECONDS
    rm -rf "$pack" "$stages"
    mkdir -p "$pack" "$stages"

    echo
    echo "== $region $NAME"

    run_host map "$stages/map.json" \
        "$PMTILES" extract "$MAP" "$pack/map.pmtiles" \
        --region="$boundary" --minzoom="$MAP_MIN_ZOOM" --maxzoom="$MAP_MAX_ZOOM" -q

    run_host contours "$stages/contours.json" \
        "$PMTILES" extract "$CONTOURS" "$pack/contours.pmtiles" --region="$boundary" -q

    run_container osm-extract "$stages/osm-extract.json" "$OSMIUM_IMAGE" \
        --volume "$DATA_DIR":/in:ro,Z --volume "$WORK":/data:Z \
        osmium extract --overwrite --strategy=smart \
        --polygon "/data/boundaries/boundaries/${region,,}.geojson" \
        --output "/data/packs/$region/region.osm.pbf" "/in/$(basename "$PBF")"

    run_host elevation "$stages/elevation.json" \
        python3 "$ROOT/tools/pipeline/build-elevation.py" grid \
        --source "$DEM" --bbox "$BBOX" --out "$pack/elevation.bin" --skip-missing --snap-bbox

    mkdir -p "$WORK/search"
    run_host search-places "$stages/search-places.json" \
        "$EXTRACTOR" --pbf "$pack/region.osm.pbf" --out "$WORK/search/$region.tsv"
    run_host search-index "$stages/search-index.json" \
        "$CARGO" run --quiet --release --locked \
        --manifest-path "$ROOT/rust/grass-core/Cargo.toml" \
        --bin build-section -- "$WORK/search/$region.tsv" "$pack"

    run_host way-alerts "$stages/way-alerts.json" \
        "$ROOT/tools/pipeline/build-way-alerts.sh" \
        --pbf "$pack/region.osm.pbf" --out "$pack/way-alerts.geojson" --extractor "$EXTRACTOR"
    run_host relations "$stages/relations.json" \
        "$ROOT/tools/pipeline/build-route-relations.sh" \
        --pbf "$pack/region.osm.pbf" --out "$pack/relations.json" --extractor "$EXTRACTOR"

    mkdir -p "$WORK/slice/$region"
    python3 - "$boundary" "$WORK/slice/$region/$region.geojson" <<'PY'
import json
import sys

feature = json.load(open(sys.argv[1]))
json.dump({"type": "FeatureCollection", "features": [feature]}, open(sys.argv[2], "w"))
PY
    python3 - "$WORK/routing-config.json" "$WORK/slice/$region.json" <<'PY'
import json
import sys

country = json.load(open(sys.argv[1]))
country["mjolnir"]["tile_extract"] = "/data/routing/tiles.tar"
country["additional_data"]["elevation"] = ""
json.dump(country, open(sys.argv[2], "w"), indent=2)
PY
    run_container routing "$stages/routing.json" "$VALHALLA_IMAGE" \
        --volume "$WORK":/data:Z --volume "$(dirname "$ROUTING_TAR")":/data/routing:ro,Z \
        valhalla_build_extract -c "/data/slice/$region.json" \
        -e "/data/packs/$region/routing.tar" -g "/data/slice/$region" -O -v

    # The package ships the slice as a directory, so the app can merge the tiles
    # of several packs. index.bin only describes the tar and is not copied.
    mkdir -p "$pack/routing/tiles"
    tar -xf "$pack/routing.tar" -C "$pack/routing/tiles" --exclude=index.bin
    rm -f "$pack/routing.tar"
    python3 - "$WORK/routing-config.json" "$pack/routing/valhalla.json" <<'PY'
import json
import sys

config = json.load(open(sys.argv[1]))
config["mjolnir"]["tile_extract"] = ""
config["mjolnir"]["tile_dir"] = ""
config["additional_data"]["elevation"] = ""
json.dump(config, open(sys.argv[2], "w"), indent=2)
PY

    local routing_tiles
    routing_tiles="$(find "$pack/routing/tiles" -name '*.gph' | wc -l)"
    if [[ "$routing_tiles" -eq 0 ]]; then
        echo "build-oblasts.sh: $region sliced no routing tiles" >&2
        exit 1
    fi

    cat >"$WORK/spec-$region.json" <<JSON
{
  "region": "$region",
  "version": "$VERSION",
  "pack": "$pack",
  "osmTimestamp": "$osm_timestamp",
  "tool": "build-oblasts.sh",
  "footprint": "exact oblast polygon, no buffer",
  "pbfArchive": "$(basename "$PBF")",
  "pbfSha256": "$pbf_sha",
  "mapArchive": "$(basename "$MAP")",
  "mapSha256": "$map_sha",
  "contoursArchive": "$(basename "$CONTOURS")",
  "contoursSha256": "$contours_sha",
  "contoursInterval": $CONTOUR_INTERVAL,
  "demSource": "$DEM",
  "extractor": "$EXTRACTOR_VERSION",
  "routingBuildArchive": "$(basename "$ROUTING_TAR")",
  "routingBuildSha256": "$routing_build_sha",
  "routingBuild": "$routing_build",
  "routingTiles": $routing_tiles,
  "routingGrades": "$routing_grades",
  "landPolygons": "$land_polygons"
}
JSON
    python3 "$PY" manifest --spec "$WORK/spec-$region.json" --boundaries "$TABLE"

    local seconds=$((SECONDS - started))
    python3 "$PY" metrics --file "$OUT/metrics.json" --manifest "$pack/manifest.json" \
        --stages "$stages" --seconds "$seconds" --routing-tiles "$routing_tiles" \
        --build "$WORK/build.json"

    rm -rf "$OUT/$region"
    mkdir -p "$OUT/$region"
    if [[ "$KEEP_WORK" -eq 0 ]]; then
        rm -rf "$pack/region.osm.pbf" "$WORK/search/$region.tsv"
    fi
    mv "$pack" "$OUT/$region/$VERSION"
    echo "== $region done in $seconds s, $(du -sh "$OUT/$region/$VERSION" | cut -f1)"
}

if [[ "$OBLASTS" == "all" ]]; then
    mapfile -t selected < <(python3 - "$TABLE" <<'PY'
import json
import sys

for package in json.load(open(sys.argv[1]))["packages"]:
    print(package["id"])
PY
)
else
    IFS=',' read -r -a selected <<<"$OBLASTS"
fi

for region in "${selected[@]}"; do
    build_oblast "$region"
    if [[ "$ELEVATION_30M" -eq 1 ]]; then
        build_elevation_30m "$region"
    fi
done

echo
echo "Writing the catalog"
rm -rf "$OUT/outlines" "$OUT/boundaries"
cp -a "$WORK/boundaries/outlines" "$OUT/outlines"
cp -a "$WORK/boundaries/boundaries" "$OUT/boundaries"
cat >"$WORK/catalog-spec.json" <<JSON
{
  "generated": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
  "version": "$VERSION",
  "osmTimestamp": "$osm_timestamp",
  "pbfArchive": "$(basename "$PBF")",
  "pbfSha256": "$pbf_sha",
  "mapArchive": "$(basename "$MAP")",
  "mapSha256": "$map_sha",
  "contoursArchive": "$(basename "$CONTOURS")",
  "contoursSha256": "$contours_sha",
  "routingBuildSha256": "$routing_build_sha",
  "landPolygons": "$land_polygons",
  "boundaries": "$TABLE"
}
JSON
python3 "$PY" catalog --root "$OUT" --spec "$WORK/catalog-spec.json"

python3 - "$OUT/metrics.json" <<'PY'
import json
import sys

document = json.load(open(sys.argv[1]))
print()
print(f"{'region':8} {'version':22} {'s':>6} {'peak RSS':>9} {'disk':>9} {'routing':>9} {'tiles':>6}")
for region in document["regions"]:
    print(
        f"{region['region']:8} {region['version']:22} {region['seconds']:6.0f} "
        f"{region['peakRssBytes'] / 1024 / 1024:8.0f}M {region['diskBytes'] / 1024 / 1024:8.0f}M "
        f"{region['routing']['bytes'] / 1024 / 1024:8.0f}M {region['routing']['tiles']:6}"
    )
PY

echo
echo "Built ${#selected[@]} oblast package(s) in $OUT for version $VERSION"
