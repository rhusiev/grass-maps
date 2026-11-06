# Pipeline tooling

Build tooling for region packages. It is not an app module and ships with the
product.

## Test region

`build-test-region.sh` builds `test-data/regions/fixture-mini/<version>/`, the
small region package the JVM tests read. It uses the same split stage as real
oblast packs: `pmtiles extract` over the Planetiler archive built in the bench
repository (`grass-maps-bench`).

```sh
tools/pipeline/build-test-region.sh
```

The defaults read `/hdd/drive/Projects/Life/grass-maps-bench`; set
`GRASS_MAPS_BENCH` or pass `--bench`, `--map-source`, `--contours-source` and
`--pmtiles` when the bench repository lives elsewhere. The output is
deterministic: the same source archives produce the same bytes and the same
manifest, so re-running the command on the same bench build reproduces the
committed package.

The package is committed because tests must run without the bench repository or
a multi-gigabyte download. The manifest records each file's size and sha256;
`TestRegionFixtureTest` fails when a committed byte changes.

### What the package contains

| Component | Source | Stand-in |
|---|---|---|
| `map.pmtiles` | `ukraine-final.pmtiles`, Protomaps basemap profile, z12-14 | no |
| `contours.pmtiles` | `lviv-90m-20m.pmtiles`, Copernicus GLO-30 contours, z9-14 | no |
| `elevation.bin` | generated marker file | yes |
| `search.idx` | generated marker file | yes |

The bounding box is `23.940,49.940,23.960,49.960` (about 1.5 km around
Ivano-Frankove, Lviv oblast). It is rural, so its z12-14 tiles stay small; the
place has roads, paths and land cover but no dense city centre. `map.pmtiles`
starts at z12 instead of the product's z8 because the z8 tile would dominate the
fixture's size while adding nothing a unit test needs.

The two stand-ins exist so the package has the five components of
`data-formats.md` section 2 and the manifest and mount checks have something to
read. They are not in the real elevation or search formats; the manifest marks
them `"standIn": true`, and a test asserts that flag so nobody mistakes them for
real data. They are replaced when the `regions` module and the Rust core land
their readers and the pipeline builds the real components.

### Real stages and stand-ins

Reused from the real pipeline:

- the Planetiler vector tile build (`ukraine-final.pmtiles`)
- the Copernicus GLO-30 contour build (`lviv-90m-20m.pmtiles`)
- the `pmtiles extract` split stage that real oblast packs use

Stand-ins:

- the elevation grid: no GLO-30 resample step is run here
- the search index: no index build is run here

No routing data is included: routing is a country-level pack in the design, and
the fixture has no engine graph.

### Manifest

`manifest.json` follows `data-formats.md` section 2: region, version, package
format, OSM timestamp, and per component the format and its version, size,
sha256 and stand-in flag. `pipeline` records the source archive sha256 and the
bounding box, so the package can be traced back to the bench build.

### Licensing

The map and contour tiles are derived from OpenStreetMap data (ODbL-1.0, ©
OpenStreetMap contributors) and Copernicus DEM GLO-30. `ATTRIBUTION.md` records
both.
