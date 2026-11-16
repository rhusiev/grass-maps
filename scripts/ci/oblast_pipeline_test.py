#!/usr/bin/env python3
"""Tests for the all-oblast pipeline helpers in tools/pipeline/oblast_pipeline.py.

The shell pipeline runs the heavy stages; these tests cover the parts that
decide what a package contains and which oblast set is built: the boundary
folds, the manifest component list, the catalog shape and the measurement
record.
"""

import importlib.util
import json
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

    def test_an_addon_without_its_region_package_fails_the_catalog(self) -> None:
        pipeline.write_addon_manifest(self.spec, self.boundaries)
        with self.assertRaises(SystemExit):
            pipeline.write_catalog(self.root, write_catalog_spec(self.root))


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


ELEVATION_MODULE = Path(__file__).resolve().parents[2] / "tools" / "pipeline" / "build-elevation.py"


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

        values = numpy.array([[10.0, 20.0, 30.0]])
        pixel_width = 1.5 / 3600
        cells = elevation.nearest_columns(values, 24.0, 51.0, pixel_width, 0, 1, 24.0, 6, 3600)
        self.assertEqual(cells[0, 0], 10)
        self.assertEqual(cells[0, 2], 20)
        self.assertEqual(cells[0, 3], 30)
        self.assertEqual(cells[0, 5], 30)

    def test_the_grid_accepts_one_and_three_arcsec_only(self) -> None:
        try:
            elevation = load_elevation()
        except ImportError as error:
            self.skipTest(f"build-elevation.py needs {error.name}")
        self.assertEqual(elevation.GRID_ARCSEC_CHOICES, (1, 3))


if __name__ == "__main__":
    unittest.main()
