#!/usr/bin/env python3
"""Tests for the all-oblast pipeline helpers in tools/pipeline/oblast_pipeline.py.

The shell pipeline runs the heavy stages; these tests cover the parts that
decide what a package contains and which oblast set is built: the boundary
folds, the manifest component list, the catalog shape and the measurement
record.
"""

import importlib.util
import json
import re
import struct
import tempfile
import unittest
from pathlib import Path

MODULE = Path(__file__).resolve().parents[2] / "tools" / "pipeline" / "oblast_pipeline.py"
SPEC = importlib.util.spec_from_file_location("oblast_pipeline", MODULE)
pipeline = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(pipeline)

SQUARE = [[22.0, 49.0], [23.0, 49.0], [23.0, 50.0], [22.0, 50.0]]
CITY = [[30.4, 50.3], [30.6, 50.3], [30.6, 50.5], [30.4, 50.5]]


def feature(iso, name, english, rings, osm_id=1, extra_tags=""):
    """One GDAL-shaped admin_level=4 feature."""
    tags = f'"ISO3166-2"=>"{iso}",' if iso else ""
    tags += f'"name:en"=>"{english}",' if english else ""
    tags += extra_tags
    return {
        "type": "Feature",
        "properties": {"osm_id": str(osm_id), "name": name, "other_tags": tags.rstrip(",")},
        "geometry": {"type": "MultiPolygon", "coordinates": [[ring + [ring[0]]] for ring in rings]},
    }


class BoundaryTest(unittest.TestCase):
    def test_folds_kyiv_and_sevastopol_into_their_oblast(self) -> None:
        features = [
            feature("UA-32", "Київська область", "Kyiv Oblast", [SQUARE], 1),
            feature("UA-30", "Київ", "Kyiv", [CITY], 2),
            feature("UA-43", "Автономна Республіка Крим", "Autonomous Republic of Crimea", [SQUARE], 3),
            feature("UA-40", "Севастополь", "Sevastopol", [CITY], 4),
        ]
        packages = pipeline.boundary_packages(features)
        self.assertEqual(sorted(packages), ["UA-32", "UA-43"])
        self.assertEqual(packages["UA-32"]["includes"], ["UA-30"])
        self.assertEqual(packages["UA-43"]["includes"], ["UA-40"])
        self.assertEqual(len(packages["UA-32"]["rings"]), 2)
        self.assertEqual(packages["UA-32"]["name"], "Київська область")
        self.assertEqual(packages["UA-32"]["nameEn"], "Kyiv Oblast")

    def test_skips_a_boundary_without_an_iso_tag(self) -> None:
        packages = pipeline.boundary_packages(
            [
                feature("UA-46", "Львівська область", "Lviv Oblast", [SQUARE], 1),
                feature(None, "Республика Крым", "Republic of Crimea", [SQUARE], 2),
            ]
        )
        self.assertEqual(sorted(packages), ["UA-46"])

    def test_bbox_spans_every_folded_ring(self) -> None:
        packages = pipeline.boundary_packages(
            [
                feature("UA-32", "Київська область", "Kyiv Oblast", [SQUARE], 1),
                feature("UA-30", "Київ", "Kyiv", [CITY], 2),
            ]
        )
        self.assertEqual(packages["UA-32"]["bbox"], [22.0, 49.0, 30.6, 50.5])

    def test_closes_an_unclosed_ring(self) -> None:
        rings = pipeline.geometry_points(
            {"type": "Polygon", "coordinates": [[[22.0, 49.0], [23.0, 49.0], [23.0, 50.0]]]}
        )
        self.assertEqual(rings[0][0], rings[0][-1])

    def test_info_lookup_is_case_insensitive(self) -> None:
        table = {"packages": [{"id": "UA-46", "name": {"uk": "a", "en": "b"}, "bbox": [1, 2, 3, 4]}]}
        self.assertEqual(pipeline.boundary_info(table, "ua-46")["id"], "UA-46")


class SimplifyTest(unittest.TestCase):
    def test_collinear_points_collapse_to_the_corners(self) -> None:
        dense = []
        for step in range(10):
            dense.append([22.0 + step / 10.0, 49.0])
        for step in range(10):
            dense.append([23.0, 49.0 + step / 10.0])
        for step in range(10):
            dense.append([23.0 - step / 10.0, 50.0])
        for step in range(10):
            dense.append([22.0, 50.0 - step / 10.0])
        simplified = pipeline.simplify_ring(dense + [dense[0]], 0.01)
        self.assertGreaterEqual(len(simplified), 4)
        self.assertLessEqual(len(simplified), 6)
        self.assertEqual(simplified[0], simplified[-1])

    def test_keeps_every_point_at_zero_tolerance(self) -> None:
        ring = SQUARE + [SQUARE[0]]
        self.assertEqual(pipeline.simplify_ring(ring, 0.0), ring)


ROUTING_LISTING_HASH = "3ce123a83370fdc1ba033e1c8951f66e6bdd2e1ee61e9459643165301ae0e7d5"


def write_routing_directory(directory: Path) -> None:
    """The two-file routing directory whose listing hash the app test repeats."""
    (directory / "tiles" / "0" / "003").mkdir(parents=True)
    (directory / "tiles" / "0" / "003" / "110.gph").write_bytes(b"tile-a")
    (directory / "valhalla.json").write_bytes(b"{}")


class DirectoryComponentTest(unittest.TestCase):
    def setUp(self) -> None:
        self._temp = tempfile.TemporaryDirectory()
        self.addCleanup(self._temp.cleanup)
        self.pack = Path(self._temp.name)
        self.routing = self.pack / "routing"
        write_routing_directory(self.routing)

    def test_listing_is_sorted_path_size_hash_lines(self) -> None:
        self.assertEqual(
            pipeline.directory_listing(self.routing),
            "tiles/0/003/110.gph\t6\t30e5f1e49470e0e241c3d7c75b2b8be5b246abd8268686363a8d092d90a728f4\n"
            "valhalla.json\t2\t44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a\n",
        )

    def test_component_hashes_the_listing_and_sums_the_sizes(self) -> None:
        entry = pipeline.directory_component("routing", "routing", "valhalla-tile-directory", 1, "v1", self.pack)
        self.assertEqual(entry["sha256"], ROUTING_LISTING_HASH)
        self.assertEqual(entry["bytes"], 8)
        self.assertEqual(entry["type"], "directory")
        self.assertEqual(entry["file"], "routing")

    def test_a_changed_file_changes_the_component_hash(self) -> None:
        (self.routing / "tiles" / "0" / "003" / "110.gph").write_bytes(b"tile-b")
        entry = pipeline.directory_component("routing", "routing", "valhalla-tile-directory", 1, "v1", self.pack)
        self.assertNotEqual(entry["sha256"], ROUTING_LISTING_HASH)

    def test_a_symbolic_link_is_refused(self) -> None:
        (self.routing / "link.gph").symlink_to(self.routing / "valhalla.json")
        with self.assertRaises(SystemExit):
            pipeline.directory_listing(self.routing)

    def test_a_directory_without_files_is_refused(self) -> None:
        empty = self.pack / "empty"
        empty.mkdir()
        with self.assertRaises(SystemExit):
            pipeline.directory_component("routing", "empty", "valhalla-tile-directory", 1, "v1", self.pack)


class ManifestTest(unittest.TestCase):
    def setUp(self) -> None:
        self._temp = tempfile.TemporaryDirectory()
        self.addCleanup(self._temp.cleanup)
        self.root = Path(self._temp.name)
        self.pack = self.root / "pack"
        self.pack.mkdir()
        for name in ("map.pmtiles", "contours.pmtiles", "elevation.bin", "features.bin"):
            (self.pack / name).write_bytes(b"x")
        for name in pipeline.SEARCH_FILES:
            (self.pack / name).write_bytes(b"x")
        (self.pack / "way-alerts.geojson").write_text(
            json.dumps({"type": "FeatureCollection", "features": [{"properties": {"kind": "ford"}}]})
        )
        (self.pack / "relations.json").write_text(json.dumps({"relations": [{"network": "iwn"}], "ways": []}))
        write_routing_directory(self.pack / "routing")
        self.dem = self.root / "dem"
        self.dem.mkdir()
        (self.dem / "Copernicus_DSM_COG_10_N49_00_E023_00_DEM.tif").write_bytes(b"x")

    def spec(self) -> dict:
        return {
            "region": "ua-46",
            "version": "2026-11-04",
            "pack": str(self.pack),
            "bbox": "22.6,48.7,25.4,50.6",
            "osmTimestamp": "2026-11-04T21:20:00Z",
            "tool": "build-oblasts.sh",
            "footprint": "exact oblast polygon, no buffer",
            "pbfArchive": "ukraine-latest.osm.pbf",
            "pbfSha256": "a" * 64,
            "mapArchive": "ukraine-final.pmtiles",
            "mapSha256": "b" * 64,
            "contoursArchive": "ua-contours.pmtiles",
            "contoursSha256": "c" * 64,
            "demSource": str(self.dem),
            "extractor": "osm-extract 0.1.0 way-alerts v1",
            "routingBuildArchive": "tiles.tar",
            "routingBuildSha256": "d" * 64,
            "routingBuild": "valhalla_build_tiles",
            "routingTiles": 78,
            "routingGrades": "none",
            "landPolygons": "stub",
        }

    def test_every_part_is_declared_once_with_its_bytes(self) -> None:
        manifest = pipeline.write_manifest(self.spec())
        kinds = [entry["kind"] for entry in manifest["components"]]
        files = [entry["file"] for entry in manifest["components"]]
        self.assertEqual(len(kinds), len(set(kinds)))
        self.assertEqual(len(files), len(set(files)))
        self.assertIn("routing", kinds)
        self.assertIn("features", kinds)
        routing = next(entry for entry in manifest["components"] if entry["kind"] == "routing")
        self.assertEqual(routing["file"], "routing")
        self.assertEqual(routing["type"], "directory")
        self.assertEqual(routing["sha256"], ROUTING_LISTING_HASH)
        self.assertEqual(manifest["pipeline"]["routingSource"]["sha256"], "d" * 64)

    def test_boundary_table_supplies_the_bbox_and_the_fold(self) -> None:
        boundaries = {
            "packages": [
                {"id": "UA-46", "bbox": [22.6, 48.7, 25.4, 50.6], "includes": []},
                {"id": "UA-32", "bbox": [29.2, 49.1, 32.1, 51.5], "includes": ["UA-30"]},
            ]
        }
        manifest = pipeline.write_manifest(self.spec(), boundaries)
        self.assertEqual(manifest["pipeline"]["bbox"], [22.6, 48.7, 25.4, 50.6])
        self.assertNotIn("includes", manifest["pipeline"])

    def test_missing_dem_cells_are_recorded_not_invented(self) -> None:
        spec = self.spec()
        spec["region"] = "ua-43"
        boundaries = {"packages": [{"id": "UA-43", "bbox": [32.2, 44.2, 34.2, 46.2], "includes": ["UA-40"]}]}
        manifest = pipeline.write_manifest(spec, boundaries)
        elevation = manifest["pipeline"]["elevationSource"]
        self.assertEqual([tile["tile"] for tile in elevation["tiles"]], [])
        self.assertIn("Copernicus_DSM_COG_10_N44_00_E032_00_DEM.tif", elevation["missingTiles"])
        self.assertEqual(manifest["pipeline"]["includes"], ["UA-40"])

    def test_a_missing_component_fails_the_build(self) -> None:
        (self.pack / "relations.json").unlink()
        with self.assertRaises(SystemExit):
            pipeline.write_manifest(self.spec())


def write_region_pack(root: Path) -> Path:
    pack = root / "ua-46" / "2026-11-04"
    pack.mkdir(parents=True)
    components = [
        {"kind": "map", "file": "map.pmtiles", "format": "pmtiles", "formatVersion": 3,
         "version": "2026-11-04", "bytes": 10, "sha256": "a" * 64, "standIn": False, "zoomLevels": [8, 14]},
        {"kind": "search-terms", "file": "terms.fst", "format": "search-section", "formatVersion": 2,
         "version": "2026-11-04", "bytes": 5, "sha256": "b" * 64, "standIn": False},
        {"kind": "search-places", "file": "places.bin", "format": "search-section", "formatVersion": 2,
         "version": "2026-11-04", "bytes": 7, "sha256": "c" * 64, "standIn": False},
        {"kind": "features", "file": "features.bin", "format": "place-features", "formatVersion": 1,
         "version": "2026-11-04", "bytes": 3, "sha256": "e" * 64, "standIn": False},
        {"kind": "routing", "file": "routing", "type": "directory", "format": "valhalla-tile-directory",
         "formatVersion": 1, "version": "2026-11-04", "bytes": 70, "sha256": "d" * 64, "standIn": False},
    ]
    manifest = {
        "region": "ua-46",
        "version": "2026-11-04",
        "packageFormat": 1,
        "osmTimestamp": "2026-11-04T21:20:00Z",
        "pipeline": {"bbox": [22.6, 48.7, 25.4, 50.6]},
        "components": components,
    }
    (pack / "manifest.json").write_text(json.dumps(manifest))
    return pack


def write_catalog_spec(root: Path) -> dict:
    boundaries = root / "boundaries" / "boundaries.json"
    boundaries.parent.mkdir()
    boundaries.write_text(
        json.dumps(
            {
                "packages": [
                    {
                        "id": "UA-46",
                        "name": {"uk": "Львівська область", "en": "Lviv Oblast"},
                        "includes": [],
                    }
                ]
            }
        )
    )
    return {
        "generated": "2026-11-15T00:00:00Z",
        "version": "2026-11-04",
        "osmTimestamp": "2026-11-04T21:20:00Z",
        "pbfArchive": "ukraine-latest.osm.pbf",
        "pbfSha256": "a" * 64,
        "mapArchive": "ukraine-final.pmtiles",
        "mapSha256": "b" * 64,
        "contoursArchive": "ua-contours.pmtiles",
        "contoursSha256": "c" * 64,
        "routingBuildSha256": "d" * 64,
        "landPolygons": "stub",
        "boundaries": str(boundaries),
    }


class CatalogTest(unittest.TestCase):
    def setUp(self) -> None:
        self._temp = tempfile.TemporaryDirectory()
        self.addCleanup(self._temp.cleanup)
        self.root = Path(self._temp.name)
        write_region_pack(self.root)
        self.spec = write_catalog_spec(self.root)
        self.boundaries = Path(self.spec["boundaries"])

    def test_catalog_groups_the_search_section_and_sums_the_size(self) -> None:
        catalog = pipeline.write_catalog(self.root, self.spec)
        region = catalog["regions"][0]
        parts = {part["kind"]: part["size_bytes"] for part in region["parts"]}
        self.assertEqual(parts["search"], 15)
        self.assertEqual(parts["map"], 10)
        self.assertEqual(parts["routing"], 70)
        self.assertEqual(region["size_bytes"], 95)
        self.assertEqual(region["name"], {"uk": "Львівська область", "en": "Lviv Oblast"})
        self.assertEqual(region["outline"], "outlines/ua-46.geojson")
        self.assertEqual(catalog["build"]["routingBuildSha256"], "d" * 64)

    def test_catalog_refuses_a_region_outside_the_boundary_table(self) -> None:
        self.boundaries.write_text(json.dumps({"packages": []}))
        with self.assertRaises(SystemExit):
            pipeline.write_catalog(self.root, self.spec)


class AddonTest(unittest.TestCase):
    def setUp(self) -> None:
        self._temp = tempfile.TemporaryDirectory()
        self.addCleanup(self._temp.cleanup)
        self.root = Path(self._temp.name)
        self.pack = self.root / "ua-46-elevation-30m" / "2026-11-04"
        self.pack.mkdir(parents=True)
        (self.pack / "elevation.bin").write_bytes(b"GRME" + b"x" * 96)
        self.boundaries = {"packages": [{"id": "UA-46", "bbox": [22.6, 48.7, 25.4, 50.6], "includes": []}]}
        self.spec = {
            "region": "ua-46",
            "version": "2026-11-04",
            "pack": str(self.pack),
            "osmTimestamp": "2026-11-04T21:20:00Z",
            "tool": "build-oblasts.sh",
            "demSource": str(self.root / "dem"),
        }

    def test_the_addon_is_its_own_package_with_one_elevation_component(self) -> None:
        manifest = pipeline.write_addon_manifest(self.spec, self.boundaries)
        self.assertEqual(manifest["region"], "ua-46-elevation-30m")
        self.assertEqual(manifest["pipeline"]["addon"], {"id": "elevation-30m", "region": "ua-46"})
        self.assertEqual([entry["kind"] for entry in manifest["components"]], ["elevation"])
        self.assertEqual(manifest["components"][0]["bytes"], 100)
        self.assertEqual(json.loads((self.pack / "manifest.json").read_text()), manifest)

    def test_the_catalog_attaches_the_addon_to_its_region_only(self) -> None:
        pipeline.write_addon_manifest(self.spec, self.boundaries)
        write_region_pack(self.root)
        catalog = pipeline.write_catalog(self.root, write_catalog_spec(self.root))
        self.assertEqual([entry["id"] for entry in catalog["regions"]], ["ua-46"])
        entry = catalog["regions"][0]
        self.assertEqual(entry["addons"], [{"id": "elevation-30m", "version": "2026-11-04", "size_bytes": 100}])
        self.assertEqual(entry["size_bytes"], 95)

    def test_an_older_addon_version_in_the_out_dir_is_not_listed(self) -> None:
        pipeline.write_addon_manifest(self.spec, self.boundaries)
        old_pack = self.root / "ua-46-elevation-30m" / "2026-10-01"
        old_pack.mkdir(parents=True)
        (old_pack / "elevation.bin").write_bytes(b"GRME" + b"y" * 40)
        pipeline.write_addon_manifest({**self.spec, "version": "2026-10-01", "pack": str(old_pack)}, self.boundaries)
        write_region_pack(self.root)
        catalog = pipeline.write_catalog(self.root, write_catalog_spec(self.root))
        self.assertEqual(
            [(addon["id"], addon["version"]) for addon in catalog["regions"][0]["addons"]],
            [("elevation-30m", "2026-11-04")],
        )

    def test_an_addon_of_another_version_than_its_region_is_not_listed(self) -> None:
        old_pack = self.root / "ua-46-elevation-30m" / "2026-10-01"
        old_pack.mkdir(parents=True)
        (old_pack / "elevation.bin").write_bytes(b"GRME" + b"y" * 40)
        pipeline.write_addon_manifest({**self.spec, "version": "2026-10-01", "pack": str(old_pack)}, self.boundaries)
        write_region_pack(self.root)
        catalog = pipeline.write_catalog(self.root, write_catalog_spec(self.root))
        self.assertNotIn("addons", catalog["regions"][0])

    def test_an_addon_without_its_region_package_fails_the_catalog(self) -> None:
        pipeline.write_addon_manifest(self.spec, self.boundaries)
        with self.assertRaises(SystemExit):
            pipeline.write_catalog(self.root, write_catalog_spec(self.root))


def pmtiles_header(min_zoom=0, max_zoom=7, tile_type=1, bounds=(-180.0, -85.0511287798, 180.0, 85.0511287798)) -> bytes:
    """A PMTiles v3 header with the fields the world packager reads."""
    raw = bytearray(pipeline.PMTILES_HEADER_BYTES)
    raw[:7] = b"PMTiles"
    raw[7] = 3
    raw[99] = tile_type
    raw[100] = min_zoom
    raw[101] = max_zoom
    raw[102:118] = struct.pack("<4i", *(round(value * 10_000_000) for value in bounds))
    return bytes(raw)



class WorldPackageTest(unittest.TestCase):
    def setUp(self) -> None:
        self._temp = tempfile.TemporaryDirectory()
        self.addCleanup(self._temp.cleanup)
        self.root = Path(self._temp.name)
        self.source = self.root / "world-z7.pmtiles"
        self.source.write_bytes(pmtiles_header() + b"tiles")
        self.out = self.root / "out"
        self.spec = {
            "pmtiles": self.source,
            "out": self.out,
            "version": "2026-11-16",
            "osmTimestamp": "2026-11-16T04:00:00Z",
            "mapArchive": "protomaps 20261116",
            "mapSha256": "f" * 64,
            "schema": "protomaps basemap 4.15.2",
            "tool": "oblast_pipeline.py world",
        }

    def test_package_has_one_map_component_and_a_world_outline(self) -> None:
        manifest = pipeline.write_world_package(self.spec)
        (entry,) = manifest["components"]
        self.assertEqual(entry["kind"], "map")
        self.assertEqual(entry["zoomLevels"], [0, 7])
        self.assertEqual(entry["bytes"], self.source.stat().st_size)
        written = json.loads((self.out / "world" / "2026-11-16" / "manifest.json").read_text())
        self.assertEqual(written["region"], "world")
        self.assertEqual(written["pipeline"]["bbox"], pipeline.WORLD_BBOX)
        self.assertIn("Natural Earth (public domain)", written["pipeline"]["mapSource"]["attribution"])
        outline = json.loads((self.out / "outlines" / "world.geojson").read_text())
        self.assertEqual(outline["features"][0]["geometry"]["type"], "Polygon")

    def test_an_archive_of_another_zoom_range_is_refused(self) -> None:
        self.source.write_bytes(pmtiles_header(min_zoom=8, max_zoom=14))
        with self.assertRaises(SystemExit):
            pipeline.write_world_package(self.spec)

    def test_a_raster_archive_is_refused(self) -> None:
        self.source.write_bytes(pmtiles_header(tile_type=2))
        with self.assertRaises(SystemExit):
            pipeline.write_world_package(self.spec)

    def test_a_file_that_is_not_pmtiles_is_refused(self) -> None:
        self.source.write_bytes(b"x" * pipeline.PMTILES_HEADER_BYTES)
        with self.assertRaises(SystemExit):
            pipeline.write_world_package(self.spec)

    def test_catalog_entry_is_a_world_region_without_a_parent(self) -> None:
        pipeline.write_world_package(self.spec)
        entry = pipeline.world_catalog_entry(self.out)
        self.assertEqual(entry["kind"], "world")
        self.assertNotIn("parent", entry)
        self.assertEqual(entry["outline"], "outlines/world.geojson")
        self.assertEqual(entry["parts"], [{"kind": "map", "version": "2026-11-16", "size_bytes": entry["size_bytes"]}])
        self.assertEqual(entry["name"]["en"], "World overview")

    def test_world_catalog_replaces_its_own_entry_and_keeps_the_oblasts(self) -> None:
        pipeline.write_world_package(self.spec)
        (self.out / "catalog.json").write_text(
            json.dumps(
                {
                    "format": 1,
                    "attribution": ["© OpenStreetMap contributors"],
                    "regions": [{"id": "ua-46", "kind": "region"}, {"id": "world", "version": "old"}],
                }
            )
        )
        catalog = pipeline.write_world_catalog(self.out)
        self.assertEqual([region["id"] for region in catalog["regions"]], ["ua-46", "world"])
        self.assertEqual(catalog["regions"][1]["version"], "2026-11-16")
        self.assertEqual(
            catalog["attribution"],
            ["© OpenStreetMap contributors", "Natural Earth (public domain)", "Basemap schema © Protomaps"],
        )

    def test_the_newest_world_version_wins_by_date_then_build_number(self) -> None:
        for version in ("2026-11-09", "2026-11-16", "2026-11-16-2", "2026-11-16-10"):
            self.spec["version"] = version
            pipeline.write_world_package(self.spec)
        self.assertEqual(pipeline.world_catalog_entry(self.out)["version"], "2026-11-16-10")

    def test_a_world_manifest_of_another_region_is_refused(self) -> None:
        pipeline.write_world_package(self.spec)
        path = self.out / "world" / "2026-11-16" / "manifest.json"
        manifest = json.loads(path.read_text())
        manifest["region"] = "ua-46"
        path.write_text(json.dumps(manifest))
        with self.assertRaises(SystemExit):
            pipeline.world_catalog_entry(self.out)

    def test_a_world_map_of_the_wrong_size_is_refused(self) -> None:
        pipeline.write_world_package(self.spec)
        (self.out / "world" / "2026-11-16" / "map.pmtiles").write_bytes(b"short")
        with self.assertRaises(SystemExit):
            pipeline.world_catalog_entry(self.out)

    def test_a_catalog_rebuild_without_the_world_pack_does_not_drop_its_entry(self) -> None:
        catalog_test = CatalogTest("test_catalog_groups_the_search_section_and_sums_the_size")
        catalog_test.setUp()
        self.addCleanup(catalog_test.doCleanups)
        (catalog_test.root / "catalog.json").write_text(json.dumps({"regions": [{"id": "world"}]}))
        with self.assertRaises(SystemExit):
            pipeline.write_catalog(catalog_test.root, catalog_test.spec)

    def test_an_oblast_catalog_rebuild_keeps_the_world_entry(self) -> None:
        catalog_test = CatalogTest("test_catalog_groups_the_search_section_and_sums_the_size")
        catalog_test.setUp()
        self.addCleanup(catalog_test.doCleanups)
        self.spec["out"] = catalog_test.root
        pipeline.write_world_package(self.spec)
        catalog = pipeline.write_catalog(catalog_test.root, catalog_test.spec)
        self.assertEqual([region["id"] for region in catalog["regions"]], ["ua-46", "world"])
        self.assertIn("Natural Earth (public domain)", catalog["attribution"])


class MetricsTest(unittest.TestCase):
    def test_one_record_per_region_and_the_last_build_wins(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "metrics.json"
            pipeline.write_metrics(path, {"region": "ua-46", "seconds": 90, "peakRssBytes": 1,
                                          "peakRssMethod": "x", "diskBytes": 2, "routing": {},
                                          "parts": {}, "stages": []})
            pipeline.write_metrics(path, {"region": "ua-46", "seconds": 120, "peakRssBytes": 3,
                                          "peakRssMethod": "x", "diskBytes": 4, "routing": {},
                                          "parts": {}, "stages": []})
            pipeline.write_metrics(path, {"region": "ua-32", "seconds": 60, "peakRssBytes": 1,
                                          "peakRssMethod": "x", "diskBytes": 2, "routing": {},
                                          "parts": {}, "stages": []}, build={"version": "v"})
            document = json.loads(path.read_text())
            self.assertEqual([entry["region"] for entry in document["regions"]], ["ua-32", "ua-46"])
            self.assertEqual([entry["seconds"] for entry in document["regions"]], [60, 120])
            self.assertEqual(document["build"], {"version": "v"})


REPO_ROOT = Path(__file__).resolve().parents[2]
ELEVATION_MODULE = REPO_ROOT / "tools" / "pipeline" / "build-elevation.py"


def load_elevation():
    spec = importlib.util.spec_from_file_location("build_elevation", ELEVATION_MODULE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ElevationGuardTest(unittest.TestCase):
    def test_a_dem_folder_without_any_tile_for_the_bbox_aborts(self) -> None:
        try:
            elevation = load_elevation()
        except ImportError as error:
            self.skipTest(f"build-elevation.py needs {error.name}")
        with tempfile.TemporaryDirectory() as empty:
            output = Path(empty) / "elevation.bin"
            with self.assertRaises(SystemExit) as raised:
                elevation.build_grid(empty, (24.0, 49.0, 25.0, 50.0), output, skip_missing=True)
            self.assertIn("no GLO-30 tile covers", str(raised.exception))
            self.assertFalse(output.exists())


    def test_one_arcsec_cells_copy_the_wide_pixels_north_of_fifty(self) -> None:
        try:
            elevation = load_elevation()
        except ImportError as error:
            self.skipTest(f"build-elevation.py needs {error.name}")
        import numpy

        pixel_width = 1.5 / 3600
        pixel_height = 1.0 / 3600
        columns = 2400
        values = numpy.tile(numpy.arange(columns, dtype=numpy.float32), (3600, 1))
        full = elevation.cells_for_tile(
            values, 24.0, 51.0, pixel_width, pixel_height, 24.0, 50.0, 25.0, 51.0, 3600
        )
        self.assertEqual(full.shape, (3600, 3600))
        self.assertFalse((full == elevation.NO_DATA).any())
        centres = 24.0 + (numpy.arange(3600) + 0.5) / 3600
        expected = numpy.floor((centres - 24.0) / pixel_width + 1e-6).astype(numpy.int16)
        self.assertTrue((full == expected).all())
        part = elevation.cells_for_tile(
            values, 24.0, 51.0, pixel_width, pixel_height, 24.1, 50.2, 24.3, 50.3, 3600
        )
        self.assertEqual(part.shape, (360, 720))
        self.assertFalse((part == elevation.NO_DATA).any())
        self.assertTrue((part == expected[360:1080]).all())

    def test_three_arcsec_cells_still_average_the_wide_pixels(self) -> None:
        try:
            elevation = load_elevation()
        except ImportError as error:
            self.skipTest(f"build-elevation.py needs {error.name}")
        import numpy

        values = numpy.tile(numpy.arange(2400, dtype=numpy.float32), (3600, 1))
        cells = elevation.cells_for_tile(
            values, 24.0, 51.0, 1.5 / 3600, 1.0 / 3600, 24.0, 50.0, 25.0, 51.0, 1200
        )
        self.assertEqual(cells.shape, (1200, 1200))
        self.assertEqual(int(cells[0, 600]), 1200)
        self.assertEqual(int(cells[0, 601] - cells[0, 600]), 2)

    def test_a_grid_above_the_size_the_app_reads_aborts(self) -> None:
        try:
            elevation = load_elevation()
        except ImportError as error:
            self.skipTest(f"build-elevation.py needs {error.name}")
        import numpy

        values = numpy.zeros((3600, 3600), dtype=numpy.float32)
        elevation.load_tile = lambda *_: (values, 24.0, 50.0, 1.0 / 3600, 1.0 / 3600)
        elevation.GRID_MAX_BYTES = 100
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder) / "elevation.bin"
            with self.assertRaises(SystemExit) as raised:
                elevation.build_grid(folder, (24.0, 49.0, 25.0, 50.0), output, arcsec=1)
            self.assertIn("above the 100 B", str(raised.exception))
            self.assertFalse(output.exists())

    def test_the_size_cap_matches_the_app_reader(self) -> None:
        try:
            elevation = load_elevation()
        except ImportError as error:
            self.skipTest(f"build-elevation.py needs {error.name}")
        reader = (REPO_ROOT / "regions/src/main/java/nl/r1a/grassmap/regions/RegionElevation.kt").read_text()
        match = re.search(r"MAX_COMPONENT_BYTES = (\d+)L \* 1024L \* 1024L", reader)
        self.assertIsNotNone(match)
        self.assertEqual(int(match.group(1)) * 1024 * 1024, elevation.GRID_MAX_BYTES)

    def test_the_grid_accepts_one_and_three_arcsec_only(self) -> None:
        try:
            elevation = load_elevation()
        except ImportError as error:
            self.skipTest(f"build-elevation.py needs {error.name}")
        self.assertEqual(elevation.GRID_ARCSEC_CHOICES, (1, 3))


if __name__ == "__main__":
    unittest.main()
