#!/usr/bin/env python3
"""Helpers for the all-oblast region build: boundaries, manifests, catalog, metrics.

The shell pipeline (`build-oblasts.sh`) runs the heavy stages; this module owns
the parts that are easier to test than to express in shell: the oblast
boundary set and its two folds, the package manifest, the download catalog and
the per-oblast measurement record.

Subcommands:

    boundaries  admin_level=4 polygons -> one GeoJSON per oblast package
    manifest    one finished pack directory -> manifest.json
    catalog     every region manifest under a root -> catalog.json
    metrics     one record per built oblast -> metrics.json
    run         run one build stage, measuring wall time and peak RSS

Every subcommand prints what it wrote. The module has no third-party imports.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import pathlib
import re
import shlex
import subprocess
import sys
import time

# Kyiv city and Sevastopol are separate admin_level=4 units in OSM; the product
# folds each into its surrounding oblast so a user never installs them alone.
FOLDS = {
    "UA-30": "UA-32",
    "UA-40": "UA-43",
}

PACKAGE_FORMAT = 1
CATALOG_FORMAT = 1
METRICS_FORMAT = 1

# The package components every oblast pack carries, in manifest order. The
# search section is stored unpacked because the reader maps its files directly.
SEARCH_FILES = (
    "format.bin",
    "terms.fst",
    "postings.bin",
    "places.bin",
    "context.bin",
    "entities.bin",
    "kinds.bin",
    "strings.bin",
)

ROUTING_KIND = "routing"
ROUTING_DIRECTORY = "routing"
ROUTING_FORMAT = "valhalla-tile-directory"
DIRECTORY_TYPE = "directory"
LISTING_FIELD_SEPARATOR = "\t"

STAGE_PARTS = {
    "search-places": "search",
    "search-index": "search",
}

PEAK_METHOD = "max over stages: host stages sampled through the process tree at 4 Hz, container stages from the container cgroup memory.peak"

ATTRIBUTION = [
    "© OpenStreetMap contributors",
    "Copernicus DEM GLO-30 © European Union, ESA",
]

OTHER_TAGS_PAIR = re.compile(r'"((?:[^"\\]|\\.)*)"=>"((?:[^"\\]|\\.)*)"')
OSM_ID_KEY = "osm_id"


def _unescape(text: str) -> str:
    return text.replace('\\"', '"').replace("\\\\", "\\")


def parse_other_tags(text: str | None) -> dict[str, str]:
    """The flattened GDAL `other_tags` string as a tag map."""
    if not text:
        return {}
    return {_unescape(key): _unescape(value) for key, value in OTHER_TAGS_PAIR.findall(text)}


def _points(node, out):
    if isinstance(node, list) and node and isinstance(node[0], (int, float)):
        out.append((float(node[0]), float(node[1])))
    elif isinstance(node, list):
        for item in node:
            _points(item, out)
    return out


def geometry_points(geometry) -> list[list[tuple[float, float]]]:
    """Every ring of a Polygon or MultiPolygon, as closed point lists."""
    if geometry is None:
        return []
    if geometry.get("type") == "Polygon":
        rings = [geometry.get("coordinates") or []]
    elif geometry.get("type") == "MultiPolygon":
        rings = geometry.get("coordinates") or []
    else:
        return []
    result = []
    for polygon in rings:
        for ring in polygon:
            points = _points(ring, [])
            if len(points) >= 3:
                result.append(close_ring(points))
    return result


def close_ring(points: list[tuple[float, float]]) -> list[tuple[float, float]]:
    """Repeat the first point when the source ring is not closed."""
    if points[0] != points[-1]:
        return points + [points[0]]
    return points


def _to_plain(value):
    return value.tolist() if hasattr(value, "tolist") else value


def multipolygon(rings: list[list[tuple[float, float]]]) -> dict:
    """One MultiPolygon geometry from the outer rings of the folded features."""
    return {"type": "MultiPolygon", "coordinates": [[[[_to_plain(x), _to_plain(y)] for x, y in ring]] for ring in rings]}


def _segment_distance(point, start, end):
    """Distance from point to the segment start-end."""
    px, py = point
    ax, ay = start
    bx, by = end
    dx = bx - ax
    dy = by - ay
    if dx == 0.0 and dy == 0.0:
        return math.hypot(px - ax, py - ay)
    t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
    t = max(0.0, min(1.0, t))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def simplify_ring(points: list[tuple[float, float]], tolerance: float) -> list[tuple[float, float]]:
    """Douglas-Peucker on a closed ring, keeping it closed and at least a triangle."""
    if len(points) <= 4 or tolerance <= 0.0:
        return points
    closed = close_ring(points)
    ring = closed[:-1]
    start = ring[0]
    farthest = max(range(1, len(ring)), key=lambda index: math.hypot(ring[index][0] - start[0], ring[index][1] - start[1]))
    first = _simplify_line(ring[: farthest + 1], tolerance)
    second = _simplify_line(ring[farthest:] + [start], tolerance)
    result = first[:-1] + second[:-1]
    if len(result) < 3:
        return closed
    return result + [result[0]]


def _simplify_line(points: list[tuple[float, float]], tolerance: float) -> list[tuple[float, float]]:
    if len(points) <= 2:
        return points
    keep = [False] * len(points)
    keep[0] = True
    keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        first, last = stack.pop()
        if last <= first + 1:
            continue
        furthest = -1.0
        index = first
        for candidate in range(first + 1, last):
            distance = _segment_distance(points[candidate], points[first], points[last])
            if distance > furthest:
                furthest = distance
                index = candidate
        if furthest > tolerance:
            keep[index] = True
            stack.append((first, index))
            stack.append((index, last))
    return [point for point, kept in zip(points, keep) if kept]


def boundary_packages(features: list[dict]) -> dict[str, dict]:
    """The 25 packages from the ISO-tagged admin_level=4 features.

    A feature without an `ISO3166-2` tag is a Russian-mirror boundary and is
    skipped. Kyiv city and Sevastopol are folded into their parent and listed
    under `includes`.
    """
    units = {}
    for feature in features:
        properties = feature.get("properties") or {}
        tags = parse_other_tags(properties.get("other_tags"))
        iso = tags.get("ISO3166-2")
        if not iso or not iso.startswith("UA-"):
            continue
        rings = geometry_points(feature.get("geometry"))
        if not rings:
            continue
        # GDAL promotes `name` to a column of its own and leaves `name:*` in
        # `other_tags`; either can carry the field for a given feature.
        name = properties.get("name") or tags.get("name") or iso
        name_en = properties.get("name:en") or tags.get("name:en") or name
        units[iso] = {
            "id": iso,
            "name": name,
            "nameEn": name_en,
            "osmId": properties.get(OSM_ID_KEY),
            "rings": rings,
            "includes": [],
        }
    packages = {}
    for iso, unit in units.items():
        if iso in FOLDS:
            continue
        package = dict(unit)
        package["iso"] = iso
        package["includes"] = []
        packages[iso] = package
    for iso, target in FOLDS.items():
        if iso not in units:
            continue
        if target not in packages:
            raise SystemExit(f"boundaries: {iso} folds into {target}, which the extract does not carry")
        packages[target]["rings"] = packages[target]["rings"] + units[iso]["rings"]
        packages[target]["includes"].append(iso)
    ordered = {}
    for iso in sorted(packages):
        package = packages[iso]
        package["bbox"] = [
            min(point[0] for ring in package["rings"] for point in ring),
            min(point[1] for ring in package["rings"] for point in ring),
            max(point[0] for ring in package["rings"] for point in ring),
            max(point[1] for ring in package["rings"] for point in ring),
        ]
        ordered[iso] = package
    return ordered


def _sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def directory_files(directory: pathlib.Path) -> list[tuple[str, pathlib.Path]]:
    """Every regular file under a directory component, as (relative path, file).

    The list is sorted by the UTF-8 bytes of the relative path, which uses `/`
    on every host. A symbolic link, a name with a tab or a line break, or a
    directory with no file is refused, because the listing could not describe it.
    """
    found = []
    for path in directory.rglob("*"):
        if path.is_symlink():
            raise SystemExit(f"manifest: {path} is a symbolic link")
        if not path.is_file():
            continue
        relative = path.relative_to(directory).as_posix()
        if any(ord(char) < 0x20 for char in relative):
            raise SystemExit(f"manifest: {relative!r} holds a control character")
        found.append((relative, path))
    if not found:
        raise SystemExit(f"manifest: directory component {directory.name} holds no file")
    return sorted(found, key=lambda item: item[0].encode("utf-8"))


def directory_listing(directory: pathlib.Path) -> str:
    """The canonical listing of a directory component.

    One line per regular file, sorted by the UTF-8 bytes of its path relative to
    the directory: `<path> TAB <size in decimal bytes> TAB <sha256 in lower-case
    hex> LF`. Every line ends with LF, so the last one does too. The component's
    `sha256` is the SHA-256 of this text encoded as UTF-8 and its `bytes` is the
    sum of the file sizes. `PackageVerifier` in the app computes the same text.
    """
    return "".join(
        LISTING_FIELD_SEPARATOR.join((relative, str(path.stat().st_size), _sha256(path))) + "\n"
        for relative, path in directory_files(directory)
    )


def directory_component(kind, directory, fmt, format_version, version, pack):
    path = pathlib.Path(pack) / directory
    if not path.is_dir():
        raise SystemExit(f"manifest: missing component directory {directory}")
    files = directory_files(path)
    listing = directory_listing(path)
    return {
        "kind": kind,
        "file": directory,
        "type": DIRECTORY_TYPE,
        "format": fmt,
        "formatVersion": format_version,
        "version": version,
        "bytes": sum(file.stat().st_size for _, file in files),
        "sha256": hashlib.sha256(listing.encode("utf-8")).hexdigest(),
        "standIn": False,
    }


def component(kind, file, fmt, format_version, version, pack, zooms=None):
    path = pathlib.Path(pack) / file
    if not path.is_file():
        raise SystemExit(f"manifest: missing component {file}")
    entry = {
        "kind": kind,
        "file": file,
        "format": fmt,
        "formatVersion": format_version,
        "version": version,
        "bytes": path.stat().st_size,
        "sha256": _sha256(path),
        "standIn": False,
    }
    if zooms:
        entry["zoomLevels"] = list(zooms)
    return entry


def manifest_components(pack, version) -> list[dict]:
    entries = [
        component("map", "map.pmtiles", "pmtiles", 3, version, pack, [8, 14]),
        component("contours", "contours.pmtiles", "pmtiles", 3, version, pack, [11, 14]),
        component("elevation", "elevation.bin", "elevation-grid", 1, version, pack),
    ]
    for file in SEARCH_FILES:
        entries.append(component(f"search-{file.split('.')[0]}", file, "search-section", 2, version, pack))
    entries.append(component("features", "features.bin", "place-features", 1, version, pack))
    entries.append(component("way-alerts", "way-alerts.geojson", "way-alerts-geojson", 1, version, pack))
    entries.append(component("relations", "relations.json", "route-relations-json", 1, version, pack))
    entries.append(directory_component(ROUTING_KIND, ROUTING_DIRECTORY, ROUTING_FORMAT, 1, version, pack))
    kinds = [entry["kind"] for entry in entries]
    files = [entry["file"] for entry in entries]
    if len(set(kinds)) != len(kinds) or len(set(files)) != len(files):
        raise SystemExit("manifest: repeated kind or file")
    return entries


def dem_tiles(source: str, bbox: list[float]) -> tuple[list[dict], list[str]]:
    """The GLO-30 tiles a bbox needs: present entries and the sea-only omissions."""
    directory = pathlib.Path(source)
    entries = []
    missing = []
    for latitude in range(math.floor(bbox[1]), math.ceil(bbox[3])):
        for longitude in range(math.floor(bbox[0]), math.ceil(bbox[2])):
            name = (
                f"Copernicus_DSM_COG_10_{'N' if latitude >= 0 else 'S'}{abs(latitude):02d}_00_"
                f"{'E' if longitude >= 0 else 'W'}{abs(longitude):03d}_00_DEM.tif"
            )
            path = directory / name
            if path.is_file():
                entries.append({"tile": name, "sha256": _sha256(path)})
            else:
                missing.append(name)
    return entries, missing


def _bbox(value) -> list[float]:
    """A bbox given as a `W,S,E,N` string or a list, as a list."""
    if isinstance(value, str):
        return [float(part) for part in value.split(",")]
    return [float(part) for part in value]


def _feature_counts(path: pathlib.Path, keys) -> dict:
    document = json.loads(path.read_text())
    counts = {key: 0 for key in keys}
    for feature in document.get("features", []):
        kind = (feature.get("properties") or {}).get("kind")
        if kind in counts:
            counts[kind] += 1
    return counts


def _relation_counts(path: pathlib.Path) -> dict:
    document = json.loads(path.read_text())
    relations = document.get("relations", [])
    return {
        "relations": len(relations),
        "ways": len(document.get("ways", [])),
        "withOsmcSymbol": sum(1 for relation in relations if "osmcSymbol" in relation),
    }


def write_manifest(spec: dict, boundaries: dict | None = None) -> dict:
    pack = pathlib.Path(spec["pack"])
    package = None
    if boundaries is not None:
        package = next(
            (entry for entry in boundaries["packages"] if entry["id"].upper() == spec["region"].upper()),
            None,
        )
        if package is None:
            raise SystemExit(f"manifest: {spec['region']} is not in the boundary table")
    if package is not None:
        bbox = [float(value) for value in package["bbox"]]
        includes = package.get("includes", [])
    else:
        bbox = _bbox(spec["bbox"])
        includes = spec.get("includes", [])
    components = manifest_components(pack, spec["version"])
    tiles, missing = dem_tiles(spec["demSource"], bbox)
    pipeline = {
        "tool": spec["tool"],
        "footprint": spec["footprint"],
        "bbox": bbox,
        "mapSource": {
            "archive": spec["mapArchive"],
            "sha256": spec["mapSha256"],
            "extraction": "pmtiles extract --region=<oblast> --minzoom=8 --maxzoom=14",
            "landPolygons": spec["landPolygons"],
        },
        "contoursSource": {
            "archive": spec["contoursArchive"],
            "sha256": spec["contoursSha256"],
            "intervalMeters": spec.get("contoursInterval", 10),
            "dem": "Copernicus DEM GLO-30",
            "resample": "3 arcsec cells, mean of the 1 arcsec source pixels covering each cell",
        },
        "elevationSource": {
            "dataset": "Copernicus DEM GLO-30",
            "resample": "3 arcsec cells, mean of the 1 arcsec source pixels covering each cell",
            "tiles": tiles,
            "missingTiles": missing,
        },
        "searchSource": {
            "archive": spec["pbfArchive"],
            "sha256": spec["pbfSha256"],
            "bbox": bbox,
        },
        "wayAlertSource": {
            "archive": spec["pbfArchive"],
            "sha256": spec["pbfSha256"],
            "bbox": bbox,
            "extraction": spec["extractor"],
            "features": _feature_counts(
                pack / "way-alerts.geojson",
                ("railway_crossing", "tunnel", "hazard", "ford", "barrier"),
            ),
        },
        "routeRelationSource": {
            "archive": spec["pbfArchive"],
            "sha256": spec["pbfSha256"],
            "bbox": bbox,
            "extraction": "osm-extract 0.1.0 route-relations v1",
            "features": _relation_counts(pack / "relations.json"),
        },
        "routingSource": {
            "archive": spec["routingBuildArchive"],
            "sha256": spec["routingBuildSha256"],
            "build": spec["routingBuild"],
            "tiles": spec["routingTiles"],
            "selection": "valhalla_build_extract --geojson-dir=<oblast>, every tile intersecting the polygon",
            "elevationGrades": spec["routingGrades"],
            "slicedDirectory": ROUTING_DIRECTORY,
        },
    }
    if includes:
        pipeline["includes"] = includes
    manifest = {
        "region": spec["region"],
        "version": spec["version"],
        "packageFormat": PACKAGE_FORMAT,
        "osmTimestamp": spec["osmTimestamp"],
        "pipeline": pipeline,
        "components": components,
    }
    text = json.dumps(manifest, indent=2) + "\n"
    (pack / "manifest.json").write_text(text)
    return manifest


def write_boundaries(admin_geojson: pathlib.Path, out: pathlib.Path, tolerance: float) -> dict:
    document = json.loads(admin_geojson.read_text())
    packages = boundary_packages(document.get("features", []))
    out.mkdir(parents=True, exist_ok=True)
    boundaries = out / "boundaries"
    boundaries.mkdir(exist_ok=True)
    outlines = out / "outlines"
    outlines.mkdir(exist_ok=True)
    table = {"format": 1, "folds": FOLDS, "packages": []}
    for iso, package in packages.items():
        exact = multipolygon(package["rings"])
        (boundaries / f"{iso.lower()}.geojson").write_text(
            json.dumps({"type": "Feature", "properties": {"id": iso}, "geometry": exact}) + "\n"
        )
        (boundaries / f"{iso.lower()}.geometry.json").write_text(json.dumps(exact) + "\n")
        simplified = [simplify_ring(ring, tolerance) for ring in package["rings"]]
        (outlines / f"{iso.lower()}.geojson").write_text(json.dumps(multipolygon(simplified)) + "\n")
        table["packages"].append(
            {
                "id": iso,
                "name": {"uk": package["name"], "en": package["nameEn"]},
                "iso": package["iso"],
                "osmId": package["osmId"],
                "includes": package["includes"],
                "bbox": package["bbox"],
            }
        )
    (boundaries / "boundaries.json").write_text(json.dumps(table, indent=2, ensure_ascii=False) + "\n")
    return table


# The catalog's parts are the download rows a user reads. The feature
# component belongs to the search section; way alerts and route relations are
# their own small parts so their size is not hidden inside the map.
PART_KINDS = {
    "map": "map",
    "contours": "contours",
    "elevation": "elevation",
    "features": "search",
    ROUTING_KIND: "routing",
}


def _part_kind(kind: str) -> str:
    if kind.startswith("search-"):
        return "search"
    return PART_KINDS.get(kind, kind)


# The catalog's download rows are the five parts server-protocol.md names. The
# way-alert and route-relation components are map content, so their size sits
# in the map row; the manifest still declares them separately.
CATALOG_PARTS = {
    "features": "search",
    "way-alerts": "map",
    "relations": "map",
}


def write_catalog(root: pathlib.Path, spec: dict) -> dict:
    boundaries = json.loads((pathlib.Path(spec["boundaries"])).read_text())
    names = {package["id"].upper(): package for package in boundaries["packages"]}
    regions = []
    for manifest_path in sorted(root.glob("ua-*/*/manifest.json")):
        manifest = json.loads(manifest_path.read_text())
        region = manifest["region"]
        if region.upper() not in names:
            raise SystemExit(f"catalog: {region} is not in the boundary table")
        parts = {}
        for entry in manifest["components"]:
            kind = _part_kind(entry["kind"])
            kind = CATALOG_PARTS.get(kind, kind)
            part = parts.setdefault(kind, {"kind": kind, "version": entry["version"], "size_bytes": 0})
            part["size_bytes"] += entry["bytes"]
        region_entry = {
            "id": region,
            "name": names[region.upper()]["name"],
            "kind": "region",
            "parent": "ua",
            "bbox": manifest["pipeline"]["bbox"],
            "outline": f"outlines/{region.lower()}.geojson",
            "version": manifest["version"],
            "size_bytes": sum(entry["bytes"] for entry in manifest["components"]),
            "parts": [parts[kind] for kind in sorted(parts)],
        }
        if names[region.upper()].get("includes"):
            region_entry["includes"] = names[region.upper()]["includes"]
        regions.append(region_entry)
    catalog = {
        "format": CATALOG_FORMAT,
        "generated": spec["generated"],
        "attribution": ATTRIBUTION,
        "build": {
            "version": spec["version"],
            "osmTimestamp": spec["osmTimestamp"],
            "pbfArchive": spec["pbfArchive"],
            "pbfSha256": spec["pbfSha256"],
            "mapArchive": spec["mapArchive"],
            "mapSha256": spec["mapSha256"],
            "contoursArchive": spec["contoursArchive"],
            "contoursSha256": spec["contoursSha256"],
            "routingBuildSha256": spec["routingBuildSha256"],
            "landPolygons": spec["landPolygons"],
        },
        "regions": regions,
    }
    (root / "catalog.json").write_text(json.dumps(catalog, indent=2, ensure_ascii=False) + "\n")
    return catalog


def read_stage_reports(directory: pathlib.Path) -> list[dict]:
    reports = []
    for path in sorted(directory.glob("*.json")):
        reports.append(json.loads(path.read_text()))
    return reports


def write_metrics(path: pathlib.Path, record: dict, build: dict | None = None) -> dict:
    if path.is_file():
        document = json.loads(path.read_text())
    else:
        document = {"format": METRICS_FORMAT, "regions": []}
    document["regions"] = [entry for entry in document["regions"] if entry["region"] != record["region"]]
    document["regions"].append(record)
    document["regions"].sort(key=lambda entry: entry["region"])
    if build is not None:
        document["build"] = build
    path.write_text(json.dumps(document, indent=2) + "\n")
    return document


def _rss_bytes(pid: int) -> int:
    try:
        for line in pathlib.Path(f"/proc/{pid}/status").read_text().splitlines():
            if line.startswith("VmRSS:"):
                return int(line.split()[1]) * 1024
    except (FileNotFoundError, ProcessLookupError, PermissionError):
        return 0
    return 0


def _process_tree_rss(root: int) -> int:
    children: dict[int, list[int]] = {}
    for entry in pathlib.Path("/proc").iterdir():
        if not entry.name.isdigit():
            continue
        try:
            stat = (entry / "stat").read_text()
        except (FileNotFoundError, ProcessLookupError, PermissionError):
            continue
        fields = stat.rsplit(")", 1)[-1].split()
        if len(fields) > 1:
            children.setdefault(int(fields[1]), []).append(int(entry.name))
    total = 0
    stack = [root]
    while stack:
        pid = stack.pop()
        total += _rss_bytes(pid)
        stack.extend(children.get(pid, []))
    return total


def _cgroup_path(container: str) -> str | None:
    result = subprocess.run(
        ["podman", "inspect", "--format", "{{.State.CgroupPath}}", container],
        capture_output=True,
        text=True,
    )
    path = result.stdout.strip()
    if not path:
        return None
    return path.lstrip("/")


def _cgroup_value(container: str, name: str) -> int | None:
    path = _cgroup_path(container)
    if not path:
        return None
    try:
        return int((pathlib.Path("/sys/fs/cgroup") / path / name).read_text().strip())
    except (FileNotFoundError, ValueError):
        return None


def run_stage(
    stage: str,
    command: list[str],
    report: pathlib.Path,
    container: str | None,
    volumes: list[str],
    cwd: str | None = None,
) -> int:
    """Run one stage and write its wall time and peak RSS.

    A host command is sampled through its process tree at 4 Hz. A container
    stage reads the container's own cgroup `memory.peak`, which is exact.
    """
    started = time.monotonic()
    name = f"t0501-{stage}-{os.getpid()}"
    peak = 0
    method = "process-tree RSS sampled at 4 Hz"
    if container:
        create = ["podman", "create", "--name", name]
        for volume in volumes:
            create.extend(["--volume", volume])
        create.append(container)
        create.extend(command)
        subprocess.run(create, check=True, stdout=subprocess.DEVNULL)
        process = subprocess.Popen(["podman", "start", "-a", name])
        method = "container cgroup memory.peak"
    else:
        process = subprocess.Popen(command, cwd=cwd)
    try:
        while process.poll() is None:
            if container:
                current = _cgroup_value(name, "memory.current")
                if current:
                    peak = max(peak, current)
            else:
                peak = max(peak, _process_tree_rss(process.pid))
            time.sleep(0.25)
    finally:
        code = process.wait()
        if container:
            exact = _cgroup_value(name, "memory.peak")
            if exact:
                peak = max(peak, exact)
            subprocess.run(["podman", "rm", "--force", name], capture_output=True)
    seconds = time.monotonic() - started
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(
        json.dumps(
            {
                "stage": stage,
                "seconds": round(seconds, 3),
                "peakRssBytes": peak,
                "peakRssMethod": method,
                "exitCode": code,
            },
            indent=2,
        )
        + "\n"
    )
    print(f"stage {stage}: {seconds:.1f} s, peak RSS {peak / (1024 * 1024):.0f} MiB", file=sys.stderr)
    return code


def boundary_info(table: dict, region: str) -> dict:
    package = next(
        (entry for entry in table["packages"] if entry["id"].upper() == region.upper()),
        None,
    )
    if package is None:
        raise SystemExit(f"info: {region} is not in the boundary table")
    return package


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    subparsers = parser.add_subparsers(dest="command", required=True)

    boundaries = subparsers.add_parser("boundaries", help="write one boundary file per oblast package")
    boundaries.add_argument("--admin-geojson", required=True, type=pathlib.Path)
    boundaries.add_argument("--out", required=True, type=pathlib.Path)
    boundaries.add_argument("--outline-tolerance", type=float, default=0.004)

    manifest = subparsers.add_parser("manifest", help="write manifest.json for one finished pack")
    manifest.add_argument("--spec", required=True, type=pathlib.Path)
    manifest.add_argument("--boundaries", type=pathlib.Path)

    inform = subparsers.add_parser("info", help="print one package's shell variables")
    inform.add_argument("--boundaries", required=True, type=pathlib.Path)
    inform.add_argument("--region", required=True)

    catalog = subparsers.add_parser("catalog", help="write catalog.json beside the region directories")
    catalog.add_argument("--root", required=True, type=pathlib.Path)
    catalog.add_argument("--spec", required=True, type=pathlib.Path)

    metrics = subparsers.add_parser("metrics", help="record one built oblast in metrics.json")
    metrics.add_argument("--file", required=True, type=pathlib.Path)
    metrics.add_argument("--manifest", required=True, type=pathlib.Path)
    metrics.add_argument("--stages", required=True, type=pathlib.Path)
    metrics.add_argument("--seconds", required=True, type=float)
    metrics.add_argument("--routing-tiles", required=True, type=int)
    metrics.add_argument("--disk-bytes", type=int)
    metrics.add_argument("--build", type=pathlib.Path)

    run = subparsers.add_parser("run", help="run one stage with a measurement report")
    run.add_argument("--stage", required=True)
    run.add_argument("--report", required=True, type=pathlib.Path)
    run.add_argument("--container")
    run.add_argument("--volume", action="append", default=[])
    run.add_argument("--cwd")
    run.add_argument("cmd", nargs=argparse.REMAINDER)

    args = parser.parse_args(argv)
    if args.command == "boundaries":
        table = write_boundaries(args.admin_geojson, args.out, args.outline_tolerance)
        print(f"boundaries: {len(table['packages'])} packages in {args.out}")
        return 0
    if args.command == "manifest":
        spec = json.loads(args.spec.read_text())
        boundaries = json.loads(args.boundaries.read_text()) if args.boundaries else None
        manifest = write_manifest(spec, boundaries)
        print(f"manifest: {spec['region']} {spec['version']}, {len(manifest['components'])} components")
        return 0
    if args.command == "info":
        table = json.loads(args.boundaries.read_text())
        package = boundary_info(table, args.region)
        bbox = ",".join(f"{value:.6f}" for value in package["bbox"])
        print(f"NAME={shlex.quote(package['name']['en'])}")
        print(f"BBOX={shlex.quote(bbox)}")
        print(f"INCLUDES={shlex.quote(','.join(package.get('includes', [])))}")
        return 0
    if args.command == "catalog":
        spec = json.loads(args.spec.read_text())
        catalog = write_catalog(args.root, spec)
        print(f"catalog: {len(catalog['regions'])} regions in {args.root / 'catalog.json'}")
        return 0
    if args.command == "metrics":
        manifest = json.loads(args.manifest.read_text())
        stages = read_stage_reports(args.stages)
        parts = {}
        for entry in manifest["components"]:
            kind = _part_kind(entry["kind"])
            part = parts.setdefault(kind, {"bytes": 0, "seconds": 0.0, "peakRssBytes": 0})
            part["bytes"] += entry["bytes"]
        for report in stages:
            kind = STAGE_PARTS.get(report["stage"], report["stage"])
            if kind not in parts:
                continue
            parts[kind]["seconds"] = round(parts[kind]["seconds"] + report["seconds"], 3)
            parts[kind]["peakRssBytes"] = max(parts[kind]["peakRssBytes"], report["peakRssBytes"])
        record = {
            "region": manifest["region"],
            "version": manifest["version"],
            "seconds": args.seconds,
            "peakRssBytes": max((report["peakRssBytes"] for report in stages), default=0),
            "peakRssMethod": PEAK_METHOD,
            "diskBytes": args.disk_bytes
            if args.disk_bytes is not None
            else sum(entry["bytes"] for entry in manifest["components"]) + args.manifest.stat().st_size,
            "routing": {
                "tiles": args.routing_tiles,
                "bytes": parts.get(ROUTING_KIND, {}).get("bytes", 0),
                "buildSha256": manifest["pipeline"]["routingSource"]["sha256"],
            },
            "parts": parts,
            "stages": [
                {
                    "stage": report["stage"],
                    "seconds": report["seconds"],
                    "peakRssBytes": report["peakRssBytes"],
                    "peakRssMethod": report["peakRssMethod"],
                }
                for report in stages
            ],
        }
        build = json.loads(args.build.read_text()) if args.build and args.build.is_file() else None
        document = write_metrics(args.file, record, build)
        print(f"metrics: {manifest['region']} in {args.file} ({len(document['regions'])} regions)")
        return 0
    if args.command == "run":
        command = args.cmd[1:] if args.cmd and args.cmd[0] == "--" else args.cmd
        return run_stage(args.stage, command, args.report, args.container, args.volume, args.cwd)
    parser.error("unknown command")
    return 2


if __name__ == "__main__":
    sys.exit(main())
