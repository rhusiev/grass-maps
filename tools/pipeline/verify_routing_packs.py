#!/usr/bin/env python3
"""Verifies the per-oblast routing parts of T-0515, read-only.

The phone run cannot be repeated on a desk, so this script checks four claims
from the packs and the pack code:

  grid      every package holds exactly the country tiles whose grid cell
            intersects the oblast polygon, and no other tile
  join      every copied tile is byte-identical to the country tile, and its
            header graphid and base corner decode to its own path, so two
            oblasts cannot disagree about a GraphId at a border
  dupes     how many tiles two packages both carry, their extra copies and bytes
  runtime   the app requires neither admins.sqlite nor timezones.sqlite and the
            local config clears both, so the engine is never pointed at them

Usage:
  tools/pipeline/verify_routing_packs.py
  tools/pipeline/verify_routing_packs.py --packs DIR --country DIR --repo DIR

The script writes nothing. It exits 1 when any check fails and prints one line
per finding plus one PASS/FAIL line per check.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import struct
import sys
import tarfile
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path

from oblast_pipeline import directory_files, directory_listing

DEFAULT_PACKS = Path("/hdd/t-0501-oblasts")
DEFAULT_COUNTRY = Path("/hdd/t-0501-work/pipeline/country-routing")
COUNTRY_TAR = "tiles.tar"
COUNTRY_INDEX = "index.bin"
BOUNDARIES = "boundaries"
MANIFEST = "manifest.json"
ROUTING_DIRECTORY = "routing"
ROUTING_TILES = "tiles"
LEGACY_ROUTING_ARCHIVE = "routing.tar"
TILE_SUFFIX = ".gph"
EXPECTED_PACKAGES = 25
RUNTIME_DATABASES = ("admins.sqlite", "timezones.sqlite")
PACK_PLUGIN = Path(
    "plugins/valhalla/src/main/java/nl/r1a/grassmaps/plugins/valhalla/ValhallaPack.kt"
)
PACK_INSTALLER = Path("app/src/measure/java/nl/r1a/grassmaps/app/PackInstaller.kt")

# midgard::Tiles over {-180,-90}..{180,90}: tileid = row * columns + column,
# row-major from the south-west corner (valhalla/baldr/tilehierarchy.cc).
LEVEL_COLUMNS = {0: 90, 1: 360, 2: 1440}
LEVEL_DEGREES = {0: 4.0, 1: 1.0, 2: 0.25}
# Groups of three decimal digits per directory, padded per level (graphtile.cc).
LEVEL_PATH_DIGITS = {0: 6, 1: 6, 2: 9}
COARSE_LEVEL = 2

# GraphId: level in bits 0-2, tileid in bits 3-32, id above (baldr/graphid.h).
TILEID_SHIFT = 3
LEVEL_MASK = 0x7
TILEID_MASK = (1 << 30) - 1

# GraphTileHeader, 272 bytes and trivially copyable (baldr/graphtileheader.h).
GRAPHID_MASK = (1 << 46) - 1
BASE_OFFSET = 8
VERSION_OFFSET = 16
VERSION_LENGTH = 16
DATASET_OFFSET = 32
COUNTS_OFFSET = 40
NODE_COUNT_MASK = (1 << 21) - 1
EDGE_COUNT_SHIFT = 21
TILE_SIZE_OFFSET = 224
INDEX_ENTRY = struct.Struct("<QII")
PERCENT = 100.0


@dataclass(frozen=True)
class Cell:
    """One grid cell of one Valhalla level."""

    level: int
    column: int
    row: int

    @property
    def origin(self) -> tuple[float, float]:
        size = LEVEL_DEGREES[self.level]
        return (self.column * size - 180.0, self.row * size - 90.0)

    @property
    def bounds(self) -> tuple[float, float, float, float]:
        west, south = self.origin
        size = LEVEL_DEGREES[self.level]
        return (west, south, west + size, south + size)


@dataclass(frozen=True)
class TileHeader:
    """The header fields a border join depends on."""

    graphid: int
    cell: Cell
    longitude: float
    latitude: float
    version: str
    dataset_id: int
    node_count: int
    edge_count: int
    tile_size: int
    file_size: int

    @property
    def is_empty(self) -> bool:
        return self.node_count == 0 and self.edge_count == 0


@dataclass(frozen=True)
class Country:
    """The one country build every package is sliced from."""

    headers: dict[str, TileHeader]
    digests: dict[str, str]
    sizes: dict[str, int]

    @property
    def payload(self) -> int:
        return sum(self.sizes.values())


@dataclass(frozen=True)
class Package:
    """One oblast package and its routing part."""

    region: str
    directory: Path
    sizes: dict[str, int]
    archive: tarfile.TarFile | None = None

    def names(self) -> set[str]:
        return set(self.sizes)

    def read(self, name: str) -> bytes:
        """One tile's bytes, from the routing directory or the legacy tar."""
        if self.archive is not None:
            return self.archive.extractfile(name).read()
        return (self.directory / ROUTING_DIRECTORY / ROUTING_TILES / name).read_bytes()


def decoded_cell(path: str) -> Cell:
    """The cell a tile path names, for example `2/000/769/807.gph`."""
    parts = path.split("/")
    level = int(parts[0])
    groups = [int(part) for part in parts[1:-1]]
    groups.append(int(parts[-1][: -len(TILE_SUFFIX)]))
    tileid = 0
    for group in groups:
        tileid = tileid * 1000 + group
    row, column = divmod(tileid, LEVEL_COLUMNS[level])
    return Cell(level=level, column=column, row=row)


def tileid_of(cell: Cell) -> int:
    """The tileid a cell is written with, row-major from the south-west."""
    return cell.row * LEVEL_COLUMNS[cell.level] + cell.column


def encoded_path(cell: Cell) -> str:
    """The path a cell is written to, the inverse of `decoded_cell`."""
    tileid = tileid_of(cell)
    width = LEVEL_PATH_DIGITS[cell.level]
    padded = str(tileid).rjust(width, "0")
    groups = [padded[index : index + 3] for index in range(0, width, 3)]
    return "/".join([str(cell.level), *groups]) + TILE_SUFFIX


def read_header(data: bytes) -> TileHeader:
    """Reads the fixed header a Valhalla tile starts with."""
    word, longitude, latitude = struct.unpack_from("<Qff", data, 0)
    version = struct.unpack_from(f"<{VERSION_LENGTH}s", data, VERSION_OFFSET)[0]
    counts = struct.unpack_from("<Q", data, COUNTS_OFFSET)[0]
    graphid = word & GRAPHID_MASK
    level = graphid & LEVEL_MASK
    row, column = divmod((graphid >> TILEID_SHIFT) & TILEID_MASK, LEVEL_COLUMNS[level])
    return TileHeader(
        graphid=graphid,
        cell=Cell(level=level, column=column, row=row),
        longitude=longitude,
        latitude=latitude,
        version=version.rstrip(b"\0").decode("ascii", "replace"),
        dataset_id=struct.unpack_from("<Q", data, DATASET_OFFSET)[0],
        node_count=counts & NODE_COUNT_MASK,
        edge_count=(counts >> EDGE_COUNT_SHIFT) & NODE_COUNT_MASK,
        tile_size=struct.unpack_from("<I", data, TILE_SIZE_OFFSET)[0],
        file_size=len(data),
    )


def geometry_rings(geometry: dict) -> list[list[tuple[float, float]]]:
    """The outer ring of every polygon in a Polygon or MultiPolygon boundary."""
    coordinates = geometry["coordinates"]
    polygons = [coordinates] if geometry["type"] == "Polygon" else coordinates
    return [[(point[0], point[1]) for point in polygon[0]] for polygon in polygons]


def point_in_ring(x: float, y: float, ring: list[tuple[float, float]]) -> bool:
    """Ray casting: true when the point is inside the ring."""
    inside = False
    count = len(ring)
    for index in range(count):
        x1, y1 = ring[index]
        x2, y2 = ring[(index + 1) % count]
        if (y1 > y) != (y2 > y) and x < x1 + (y - y1) * (x2 - x1) / (y2 - y1):
            inside = not inside
    return inside


def turn(a: tuple, b: tuple, c: tuple) -> float:
    """Positive when c lies to the left of a -> b."""
    return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])


def segments_cross(first: tuple, second: tuple) -> bool:
    """True when the two segments cross each other properly."""
    (start, end), (left, right) = first, second
    return turn(start, end, left) * turn(start, end, right) < 0 and (
        turn(left, right, start) * turn(left, right, end) < 0
    )


def ring_hits_cell(ring: list[tuple[float, float]], cell: Cell) -> bool:
    """True when the cell overlaps the polygon."""
    west, south, east, north = cell.bounds
    longitudes = [point[0] for point in ring]
    latitudes = [point[1] for point in ring]
    if max(longitudes) < west or min(longitudes) > east:
        return False
    if max(latitudes) < south or min(latitudes) > north:
        return False
    corners = ((west, south), (east, south), (east, north), (west, north))
    if any(point_in_ring(x, y, ring) for x, y in corners):
        return True
    edges = [(corners[index], corners[(index + 1) % 4]) for index in range(4)]
    count = len(ring)
    for index in range(count):
        start = ring[index]
        end = ring[(index + 1) % count]
        if west <= start[0] <= east and south <= start[1] <= north:
            return True
        if any(segments_cross((start, end), edge) for edge in edges):
            return True
    return False


class Report:
    """Collects one line per finding and one verdict per check."""

    def __init__(self) -> None:
        self.failures: list[str] = []
        self.checks: dict[str, bool] = {}

    def line(self, check: str, text: str) -> None:
        self.checks.setdefault(check, True)
        print(f"{check:8} {text}")

    def fail(self, check: str, text: str) -> None:
        self.checks[check] = False
        self.failures.append(text)
        print(f"{check:8} FAIL {text}")

    def finish(self) -> int:
        print()
        for check, ok in sorted(self.checks.items()):
            print(f"{check:8} {'PASS' if ok else 'FAIL'}")
        if self.failures:
            print(f"\n{len(self.failures)} failure(s)")
            return 1
        print("\nall checks passed")
        return 0


def load_country(root: Path, report: Report) -> Country:
    """Reads the country extract once and cross-checks its tar index."""
    archive = tarfile.open(root / COUNTRY_TAR)
    members = {
        member.name: member
        for member in archive.getmembers()
        if member.name.endswith(TILE_SUFFIX)
    }
    index = archive.extractfile(COUNTRY_INDEX).read()
    entries = len(index) // INDEX_ENTRY.size
    if len(index) % INDEX_ENTRY.size or entries != len(members):
        report.fail(
            "country", f"{COUNTRY_INDEX} does not describe the {len(members)} tiles"
        )
    headers: dict[str, TileHeader] = {}
    digests: dict[str, str] = {}
    sizes: dict[str, int] = {}
    for name, member in members.items():
        data = archive.extractfile(member).read()
        headers[name] = read_header(data)
        digests[name] = hashlib.sha256(data).hexdigest()
        sizes[name] = member.size
    for offset in range(0, entries * INDEX_ENTRY.size, INDEX_ENTRY.size):
        _offset, tileid, size = INDEX_ENTRY.unpack_from(index, offset)
        level = tileid & LEVEL_MASK
        tileid_bits = (tileid >> TILEID_SHIFT) & TILEID_MASK
        row, column = divmod(tileid_bits, LEVEL_COLUMNS[level])
        name = encoded_path(Cell(level=level, column=column, row=row))
        if name not in members:
            report.fail("country", f"{COUNTRY_INDEX} names {name}, which the tar lacks")
        elif members[name].size != size:
            report.fail(
                "country", f"{name}: {members[name].size} B, the index says {size} B"
            )
    archive.close()
    report.line(
        "country",
        f"{len(members)} tiles, {sum(sizes.values()):,} B, "
        f"{COUNTRY_INDEX} agrees with every path",
    )
    return Country(headers=headers, digests=digests, sizes=sizes)


def read_packages(root: Path, report: Report) -> dict[str, Package]:
    """Every oblast package under the packs root, with its routing part open."""
    packages: dict[str, Package] = {}
    for directory in sorted(root.iterdir()):
        if not directory.name.startswith("ua-") or not directory.is_dir():
            continue
        version = sorted(item.name for item in directory.iterdir())[0]
        part = directory / version
        tiles = part / ROUTING_DIRECTORY / ROUTING_TILES
        legacy = part / LEGACY_ROUTING_ARCHIVE
        if tiles.is_dir():
            sizes = {
                path.relative_to(tiles).as_posix(): path.stat().st_size
                for path in tiles.rglob(f"*{TILE_SUFFIX}")
            }
            packages[directory.name] = Package(
                region=directory.name, directory=part, sizes=sizes
            )
        elif legacy.is_file():
            archive = tarfile.open(legacy)
            sizes = {
                member.name: member.size
                for member in archive.getmembers()
                if member.name.endswith(TILE_SUFFIX)
            }
            packages[directory.name] = Package(
                region=directory.name, directory=part, sizes=sizes, archive=archive
            )
        else:
            report.fail(
                "grid", f"{directory.name}: no {ROUTING_DIRECTORY}/ in {part}"
            )
    return packages


def read_manifest(package: Package) -> dict:
    return json.loads((package.directory / MANIFEST).read_text())


def ownership(
    packages: dict[str, Package],
) -> tuple[dict[str, list[str]], dict[str, int]]:
    """Which packages carry each tile, and each tile's size."""
    owners: dict[str, list[str]] = defaultdict(list)
    sizes: dict[str, int] = {}
    for package in packages.values():
        for name, size in package.sizes.items():
            owners[name].append(package.region)
            sizes[name] = size
    return owners, sizes


def check_build(packages: dict[str, Package], report: Report) -> None:
    """Every manifest names the same routing build digest."""
    sources = {
        read_manifest(package)["pipeline"]["routingSource"]["sha256"]
        for package in packages.values()
    }
    if len(sources) != 1:
        report.fail("join", f"packages name {len(sources)} different routing builds")
        return
    report.line("join", f"every package names routing build {sources.pop()}")


def component_matches(package: Package, part: dict) -> bool:
    """The manifest's size and hash equal the package's routing files."""
    if package.archive is not None:
        path = package.directory / LEGACY_ROUTING_ARCHIVE
        digest = hashlib.sha256(path.read_bytes()).hexdigest()
        return part["bytes"] == path.stat().st_size and part["sha256"] == digest
    root = package.directory / ROUTING_DIRECTORY
    listing = directory_listing(root)
    files = [path for _, path in directory_files(root)]
    return (
        part.get("type") == "directory"
        and part["bytes"] == sum(path.stat().st_size for path in files)
        and part["sha256"] == hashlib.sha256(listing.encode("utf-8")).hexdigest()
    )


def check_package(package: Package, country: Country, report: Report) -> None:
    """Grid coverage, manifest agreement and byte identity for one package."""
    manifest = read_manifest(package)
    boundary_dir = package.directory.parent.parent / BOUNDARIES
    boundary = json.loads((boundary_dir / f"{package.region}.geojson").read_text())
    rings = geometry_rings(boundary["geometry"])
    expected = {
        name
        for name, header in country.headers.items()
        if any(ring_hits_cell(ring, header.cell) for ring in rings)
    }
    actual = package.names()
    missing = sorted(expected - actual)
    extra = sorted(actual - expected)

    declared = manifest["pipeline"]["routingSource"]
    if declared["tiles"] != len(actual):
        report.fail(
            "grid",
            f"{package.region}: manifest says {declared['tiles']} tiles "
            f"and the package holds {len(actual)}",
        )
    part = next(item for item in manifest["components"] if item["kind"] == "routing")
    if not component_matches(package, part):
        report.fail(
            "grid",
            f"{package.region}: the routing component does not match its files",
        )
    if missing:
        report.fail(
            "grid",
            f"{package.region}: {len(missing)} covering tiles missing, {missing[0]}"
        )
    if extra:
        report.fail(
            "grid",
            f"{package.region}: {len(extra)} tiles outside the polygon, {extra[0]}"
        )
    report.line(
        "grid",
        f"{package.region}: {len(actual)} tiles, {len(expected)} covering, "
        f"missing {len(missing)}, extra {len(extra)}",
    )

    for name in package.sizes:
        data = package.read(name)
        if hashlib.sha256(data).hexdigest() != country.digests[name]:
            report.fail(
                "join", f"{package.region}: {name} differs from the country build"
            )
            continue
        header = read_header(data)
        if header.cell != decoded_cell(name):
            report.fail("join", f"{package.region}: {name} graphid names another cell")
        if header.tile_size != header.file_size:
            report.fail("join", f"{package.region}: {name} tile_size is not its length")
        corner = (header.longitude, header.latitude)
        if not header.is_empty and header.cell.origin != corner:
            report.fail("join", f"{package.region}: {name} base is not its cell origin")
    report.line(
        "join",
        f"{package.region}: {len(package.sizes)} tiles identical to the build",
    )


def border_pair(owners: dict[str, list[str]]) -> tuple[str, str] | None:
    """An adjacent pair of level-2 tiles that two different oblasts hold."""
    level_two = {
        name: regions
        for name, regions in owners.items()
        if int(name.split("/")[0]) == COARSE_LEVEL
    }
    by_cell: dict[tuple[int, int], str] = {}
    for name in level_two:
        cell = decoded_cell(name)
        by_cell[(cell.row, cell.column)] = name
    for (row, column), name in sorted(by_cell.items()):
        for neighbour_cell in ((row, column + 1), (row + 1, column)):
            neighbour = by_cell.get(neighbour_cell)
            if neighbour and not set(level_two[name]) & set(level_two[neighbour]):
                return name, neighbour
    return None


def check_border(
    owners: dict[str, list[str]], country: Country, report: Report
) -> None:
    """The tile headers on both sides of an oblast border agree."""
    pair = border_pair(owners)
    if pair is None:
        report.fail("join", "no adjacent level-2 tiles with different owners")
        return
    for name in pair:
        header = country.headers[name]
        report.line(
            "join",
            f"border {name} in {owners[name]}: "
            f"graphid 0x{header.graphid:016x} level {header.cell.level} "
            f"tileid {tileid_of(header.cell)} "
            f"base ({header.longitude}, {header.latitude})",
        )


def check_duplicates(
    owners: dict[str, list[str]],
    sizes: dict[str, int],
    country: Country,
    report: Report,
) -> None:
    """Counts the tiles two packages both carry, and their bytes."""
    copies = sum(len(regions) for regions in owners.values())
    extra_copies = copies - len(owners)
    extra_bytes = sum(
        sizes[name] * (len(regions) - 1) for name, regions in owners.items()
    )
    carried = sum(sizes[name] * len(regions) for name, regions in owners.items())
    shared = sum(1 for regions in owners.values() if len(regions) > 1)
    share = (copies / len(country.headers) - 1.0) * PERCENT
    report.line(
        "dupes",
        f"{shared} tiles in 2+ packages, {extra_copies} extra copies, "
        f"{extra_bytes:,} B extra, {copies} copies is "
        f"+{share:.1f} % over {len(country.headers)}",
    )
    report.line(
        "dupes", f"carried {carried:,} B against {country.payload:,} B of unique tiles"
    )
    by_level: dict[int, list[int]] = defaultdict(lambda: [0, 0, 0])
    for name, regions in owners.items():
        level = int(name.split("/")[0])
        by_level[level][0] += len(regions)
        by_level[level][1] += 1
        by_level[level][2] += sizes[name] * (len(regions) - 1)
    for level in sorted(by_level):
        level_copies, unique, extra = by_level[level]
        report.line(
            "dupes",
            f"level {level}: {level_copies} copies of {unique} tiles, "
            f"{extra:,} B extra",
        )
    unowned = sorted(set(country.headers) - set(owners))
    if unowned:
        report.line(
            "dupes",
            f"{len(unowned)} country tiles are in no package, "
            f"{sum(country.sizes[name] for name in unowned):,} B",
        )


def function_body(source: str, signature: str) -> str | None:
    """The braces of one Kotlin function, so a check reads only that function."""
    start = source.find(signature)
    if start < 0:
        return None
    opening = source.find("{", start)
    depth = 0
    for index in range(opening, len(source)):
        if source[index] == "{":
            depth += 1
        elif source[index] == "}":
            depth -= 1
            if depth == 0:
                return source[opening : index + 1]
    return None


def check_runtime(repo: Path, packages: dict[str, Package], report: Report) -> None:
    """The engine is never pointed at the admin or the timezone database."""
    shipped = [
        f"{package.region}/{name}"
        for package in packages.values()
        for name in RUNTIME_DATABASES
        if (package.directory / name).exists()
    ]
    if shipped:
        report.fail("runtime", f"packages ship a runtime database: {shipped[:3]}")

    plugin = (repo / PACK_PLUGIN).read_text()
    inspect = function_body(plugin, "fun inspect(")
    if inspect is None:
        report.fail("runtime", "ValhallaPack.inspect is not in the pack plugin")
    else:
        for name in RUNTIME_DATABASES:
            if name in inspect:
                report.fail("runtime", f"ValhallaPack.inspect still requires {name}")
    for key in ("admin", "timezone"):
        if f'"{key}" to JsonPrimitive(NONE)' not in plugin:
            report.fail(
                "runtime", f'the local config does not clear "{key}" for the engine'
            )
    paths = function_body(plugin, "private fun devicePaths(")
    if paths is None:
        report.fail(
            "runtime", "ValhallaPackConfig.devicePaths is not in the pack plugin"
        )
    else:
        for name in RUNTIME_DATABASES:
            if name in paths:
                report.fail("runtime", f"the local config still points at {name}")

    installer = (repo / PACK_INSTALLER).read_text()
    required = re.search(r"val REQUIRED_FILES = listOf\(([^)]*)\)", installer)
    if required is None:
        report.fail(
            "runtime", f"PackInstaller has no REQUIRED_FILES list in {PACK_INSTALLER}"
        )
    else:
        for name in RUNTIME_DATABASES:
            if name in required.group(1):
                report.fail("runtime", f"PackInstaller still requires {name}")
    report.line("runtime", "no package ships admins.sqlite or timezones.sqlite")
    report.line(
        "runtime",
        "no required-file list names either database and the local config clears both",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--packs", type=Path, default=DEFAULT_PACKS)
    parser.add_argument("--country", type=Path, default=DEFAULT_COUNTRY)
    parser.add_argument(
        "--repo", type=Path, default=Path(__file__).resolve().parent.parent.parent
    )
    arguments = parser.parse_args()

    report = Report()
    country = load_country(arguments.country, report)
    packages = read_packages(arguments.packs, report)
    if len(packages) != EXPECTED_PACKAGES:
        report.fail("grid", f"{len(packages)} packages, expected {EXPECTED_PACKAGES}")

    check_build(packages, report)
    for package in packages.values():
        check_package(package, country, report)

    owners, sizes = ownership(packages)
    check_border(owners, country, report)
    check_duplicates(owners, sizes, country, report)
    check_runtime(arguments.repo, packages, report)
    return report.finish()


if __name__ == "__main__":
    sys.exit(main())
