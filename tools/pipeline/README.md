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
`GRASS_MAPS_BENCH` or pass `--bench`, `--map-source`, `--contours-source`,
`--dem-source` and `--pmtiles` when the bench repository lives elsewhere. The
search section and its feature component come from `cargo run --bin
build-section`, so the script also needs the Rust toolchain; set `CARGO_HOME` to
its real path when it is reached through a symlink. The output is deterministic:
the same source archives and the same builder produce the same bytes and the
same manifest. The committed package predates the version-2 search section, so
re-running the script now writes `format.bin` and a version-2 manifest and does
not reproduce the committed bytes until the package is regenerated and
committed. The elevation component is already regenerated: only it and its
manifest entry changed when T-0308 replaced the stand-in.

The package is committed because tests must run without the bench repository or
a multi-gigabyte download. The manifest records each file's size and sha256;
`TestRegionFixtureTest` fails when a committed byte changes. The app also
packages a byte-for-byte copy of `contours.pmtiles` as
`app/src/main/assets/fixture-mini-contours.pmtiles` for the contour goldens;
`FixtureContoursAssetTest` holds the two copies together.

### What the package contains

| Component | Source | Stand-in |
|---|---|---|
| `map.pmtiles` | `ukraine-final.pmtiles`, Protomaps basemap profile, z12-14 | no |
| `contours.pmtiles` | `lviv-90m-10m.pmtiles`, Copernicus GLO-30 10 m contours from the 90 m DEM, z9-14 | no |
| `elevation.bin` | Copernicus GLO-30, 3 arcsec cells (one bbox tile in the fixture) | no |
| `format.bin`, `terms.fst`, `postings.bin`, `places.bin`, `context.bin`, `entities.bin`, `kinds.bin`, `strings.bin` | the search section, built by `rust/grass-core` from `fixture-places.tsv`; a version-2 build writes `format.bin`, the committed version-1 package has no such file | no |
| `features.bin` | the feature component, built in the same pass | no |
| `way-alerts.geojson` | extracted by `osm-extract --way-alerts` from the fixture's own small OSM extract `test-data/regions/fixture-mini/way-alerts.osm.pbf`: two crossings, one tunnel, three hazards, one ford and one gate | no |
| `relations.json` | extracted by `osm-extract --route-relations` from the fixture's own `test-data/regions/fixture-mini/route-relations.osm.pbf`: two parallel hiking routes sharing a track, one hiking route without a symbol, two cycling routes, five cycling node-network numbers, and the bus, proposed, node-only and foot relations that prove the skips | no |

The bounding box is `23.940,49.940,23.960,49.960` (about 1.5 km around
Ivano-Frankove, Lviv oblast). It is rural, so its z12-14 tiles stay small; the
place has roads, paths and land cover but no dense city centre. `map.pmtiles`
starts at z12 instead of the product's z8 because the z8 tile would dominate the
fixture's size while adding nothing a unit test needs.

The contour component is 10 m lines cut from the 90 m averaged GLO-30 surface.
The 10 m spacing is resampled detail, not 90 m ground truth; it is finer than
the DEM cell can resolve.

The elevation grid is real data. `tools/pipeline/build-elevation.py grid`
samples the GLO-30 COG tiles and writes the component; the manifest marks it
`"standIn": false` and records the source tiles and their sha256. The section
below gives the format and the resample rule.

### Elevation grid (T-0308)

Source: Copernicus DEM GLO-30, the 1-degree COG tiles named
`Copernicus_DSM_COG_10_N{lat}_00_E{lon}_00_DEM.tif`, downloaded from
`https://copernicus-dem-30m.s3.amazonaws.com` by the bench `fetch-dem.sh`. The
same tiles built the contours, so map, contours and heights share one surface.
GLO-30 is a surface model: it measures tree canopy and buildings, which biases
heights in forest and inflates ascent. The licence is free and redistributable;
`ATTRIBUTION.md` records it.

Resample: a 3 arcsec cell is the mean of the GLO-30 samples whose centres fall
inside it, with no-data samples skipped and the result rounded to the nearest
int16 metre. At 49 N a cell covers 3x3 source samples; where GLO-30 stores
1/2400-degree longitude spacing at 50 N it covers 2x2. Contours used the same
90 m average, so a contour line and the grid agree on the surface.

`elevation.bin` format version 1, little-endian:

| Bytes | Field |
|---|---|
| 0-3 | magic `GRME` |
| 4-5 | uint16 version, 1 |
| 6-7 | uint16 header bytes, 32 |
| 8-11 | uint32 tile count |
| 12-15 | uint32 index offset, 32 |
| 16-19 | uint32 index entry bytes, 32 |
| 20-23 | uint32 flags, 0 |
| 24-31 | reserved, 0 |

One entry per tile, 32 bytes:

| Bytes | Field |
|---|---|
| 0-3 | int32 west, microdegrees |
| 4-7 | int32 south, microdegrees |
| 8-11 | int32 east, microdegrees |
| 12-15 | int32 north, microdegrees |
| 16-17 | uint16 rows |
| 18-19 | uint16 columns |
| 20-23 | uint32 data offset |
| 24-27 | uint32 data bytes |
| 28-29 | uint16 encoding, 1 = zlib deflate with horizontal predictor |
| 30-31 | int16 no-data value, -32768 |

A tile is a rectangle of cells: the first cell is the north-west cell and row 0
is the northern edge. Cell count is `(east-west)*1200` by
`(north-south)*1200`; a 1-degree tile is 1200x1200. The predictor stores the
first cell of each row as a uint16 and every later cell as `(value - previous)
mod 65536`; the whole array is then zlib-compressed. The reader inflates the
payload, accumulates the predictor per row and bilinearly samples the four
cell centres around a position, returning null when a corner is no-data.

The fixture component is 667 bytes (`23.940,49.940,23.960,49.960`, 24x24
cells); it is built in under a second. A whole-oblast grid is the same rule per
1-degree tile; the bench 90 m GeoTIFF for Lviv oblast is 2.2 MiB, and this
container is expected to land in the same range because it adds an index and
uses the same deflate predictor.

An oblast outline gives an arbitrary bbox, so the all-oblast build passes
`--snap-bbox` to grow it outward to the 3 arcsec cell edges the format stores.
It also passes `--skip-missing`: a 1-degree cell whose GLO-30 tile does not
exist is a sea-only cell in the source dataset, so the grid omits it instead of
inventing heights, and the manifest lists it under
`elevationSource.missingTiles`.

`tools/pipeline/build-elevation.py hgt` writes the other consumer: 1 arcsec
`.hgt` tiles for Valhalla's Skadi sampler, 3601x3601 int16 big-endian nodes per
1-degree cell. A node is the bilinear GLO-30 sample at that node, edges clamped.
`valhalla_build_tiles` reads these tiles when `additional_data.elevation`
points at their directory.

### Valhalla pack with elevation (T-0308)

`build-valhalla-pack.sh` builds the Lviv pack with GLO-30 grades:

```sh
tools/pipeline/build-valhalla-pack.sh \
  --pbf /hdd/drive/Projects/Life/grass-maps-bench/data/ukraine-latest.osm.pbf \
  --polygon /hdd/drive/Projects/Life/grass-maps-bench/data/lviv-oblast.geojson \
  --dem-source /hdd/drive/Projects/Life/grass-maps-bench/data/dem \
  --timezones /hdd/drive/Projects/Life/grass-maps-bench/data/valhalla/timezones.sqlite \
  --out /hdd/t-0308-hills/valhalla-lviv-elev
```

It extracts the oblast with `osmium extract --polygon`, builds admins from the
full extract so boundary relations stay whole, and runs `valhalla_build_tiles`
with `additional_data.elevation` pointing at the HGT directory. Valhalla adds
edge grades in that pass (79 tiles: "Adding elevation to 79 tiles"). The
`--timezones` path reuses the global database from the phase-A pack, so the
build makes no network request; `valhalla_build_timezones` would otherwise
download its polygon. The HGT directory is build input, not a second runtime
source: the script removes it and clears `additional_data.elevation` from the
shipped config, because the region package serves heights from
`elevation.bin`.

Measured on 2026-11-12, 8 cores, `gm-data` slot, the phase-A Ukraine PBF
(883,214,645 B) and the 12 GLO-30 tiles:

| Step | Time |
|---|---|
| HGT tiles, 12 cells, 297 MB | about 40 s (3 s per tile measured) |
| graph build with elevation | 17 s warm, 34 s on the first run |
| tar extract | 2 s |
| whole script | 80 s with the phase-A timezone DB, 158 s on the first run |

| Output | Size |
|---|---|
| `tiles/` (79 files) | 71,639,512 B |
| `tiles.tar` | 71,792,640 B |
| `admins.sqlite` | 12,537,856 B |
| `timezones.sqlite` | 123,133,952 B |
| pack with `tiles/` and `tiles.tar` | 267 MB |
| pack with `tiles.tar` only (what a phone needs) | 207 MB |

`tiles.tar` sha256 `f3510b096d87ff0b1eeda508b41f4d9eecbf3bf68e9f0dcb271509a797a31836`.
The phase-A country pack is untouched; this is a separate Lviv output under
`/hdd`.

### Way-alert features (T-0319A)

`way-alerts.geojson` is a GeoJSON `FeatureCollection` read by
`regions.RegionReader` and the way-alert plugin. Each feature carries `kind`
(`railway_crossing`, `tunnel`, `hazard`, `ford` or `barrier`), `osmType`
(`node` or `way`) and `osmId`; a node is a `Point`, a way a `LineString`.

The component is real extraction output. `tools/pipeline/build-way-alerts.sh`
builds `osm-extract` and runs its `--way-alerts` mode over an OSM extract; the
region build calls it. The same command writes the 1,341 B fixture component
and the 2,921,155 B Lviv component.

Selection rules, exact:

- `railway_crossing`: a node with `railway=level_crossing` or
  `railway=tram_level_crossing`; or a node shared by a `railway=rail` or
  `railway=narrow_gauge` way and a travel `highway=*` way. The shared-node rule
  covers the convention where the crossing is the intersection of the two ways
  and carries no tag of its own. A way with `bridge=*` or `tunnel=*` (other than
  `no`) is grade-separated and does not contribute a shared node. A shared node
  whose own `railway` tag is `signal`, `switch`, `buffer_stop`, `station`,
  `halt`, `stop`, `derail`, `milestone`, `traverser`, `turntable`,
  `water_tower`, `roundhouse` or `junction` is infrastructure, not a crossing,
  and is skipped.
- `tunnel`: a way with `tunnel=*` on a travel `highway=*` way. `no`,
  `building_passage`, `culvert`, `floodway`, `canal` and `pipe` are skipped: a
  building passage and a water culvert are not a passage a rider goes through,
  and a railway or waterway tunnel is not on the route. `tunnel=yes`,
  `covered`, `cave`, `flooded` and any other value are kept.
- `hazard`: a node or a travel-highway way with `hazard=*`, other than `no` or
  `none`. A hazard on a waterway or a pipeline is not on the route and is
  skipped.
- `ford`: a node or a travel-highway way with `ford=*`, other than `no` or
  `none`. `ford=yes`, `seasonal` and `stepping_stones` are kept.
- `barrier`: a node with `barrier=*`, other than `no`, `none`, `kerb`,
  `bollard`, `entrance`, `height_restrictor` and `width_restrictor`. A bollard
  or a kerb is passable by a bike and a building entrance is not a barrier on
  the route.

A node with several alert tags is one feature, strongest first: crossing,
hazard, ford, barrier. The travel highway values are `motorway`, `trunk`,
`primary`, `secondary`, `tertiary`, `unclassified`, `residential`,
`living_street`, `service`, `track`, `path`, `footway`, `cycleway`, `bridleway`,
`pedestrian`, `steps`, `corridor`, `road`, `escape`, `busway`, `bus_guideway`
and their `_link` forms. A `construction`, `proposed` or `platform` value is
not a route.

The fixture's own OSM input is
`test-data/regions/fixture-mini/way-alerts.osm` (614 B PBF beside it). The
fixture bbox has two real fords in the Ukraine extract but no mapped railway
crossing, tunnel or hazard, so a fixture component that keeps the three features
the reader and plugin tests name cannot come from that extract. The authored
input is the fixture's own source, and the real extractor produces the committed
bytes from it. It carries eight features: crossings 9001 and 9007 (the second
from the shared-node rule), tunnel 9002, hazards 9003, 9004 and 9011, ford 9010
and gate 9005. The bollard 9006, the building passage 9012 and the culvert 9013
prove the skips. The manifest marks the component `standIn: false` and records
the PBF sha256, bbox, extraction version and counts under
`pipeline.wayAlertSource`.

### Hiking and cycling route relations (T-0418, T-0419)

`relations.json` is the region's route-relation component. It is not GeoJSON.
The document holds each member way's geometry once, each relation's ordered way
members, and the numbered junctions of the cycling node networks. Two routes
that share a path must draw side by side, and a later relation chart (T-0420)
needs the membership, not only a merged line.

```json
{
  "format": "grass-maps-route-relations",
  "version": 1,
  "ways": [{"id": 896757861, "coordinates": [[23.9472208, 49.9448998]]}],
  "nodes": [{"id": 5556643624, "network": "lcn", "ref": "12",
             "coordinates": [23.9503673, 49.9425100]}],
  "relations": [{
    "id": 9101, "type": "route", "route": "hiking", "network": "lwn", "ref": "ЛГ",
    "name": "Лісова Гряда", "osmcSymbol": "red:white:red_bar",
    "colour": "red", "distanceMeters": 495.8,
    "members": [{"way": 896757861, "role": ""}]
  }]
}
```

`coordinates` are `[longitude, latitude]` WGS84 degrees, seven decimals, in the
way's own direction. `members` is the relation's own order; `role` is the OSM
role and may be empty. Every member way exists in `ways`; the reader treats a
missing one as corrupt data. `route` is `hiking` or `bicycle`. `network` is
mapped to `iwn`, `nwn`, `rwn` or `lwn` for a hiking route and to `icn`, `ncn`,
`rcn` or `lcn` for a cycling route; a relation without the tag, or with a tag
from the other kind, draws as the local tier of its own kind.

`nodes` is the numbered junctions of the cycling node networks. OSM tags the
junction node itself (`lcn_ref`, `rcn_ref`, `ncn_ref` or `icn_ref`), not the
relation, and the network's route relations connect these nodes. `ref` is text
because OSM allows a letter suffix. A document written before T-0419 has no
`nodes` array; the reader opens it with no node numbers.

Selection rules, exact. `tools/pipeline/build-route-relations.sh` runs
`osm-extract --route-relations`:

- keep a relation with `type=route` and `route=hiking` or `route=bicycle`
- skip `state=proposed`, `abandoned`, `disused`, `construction` or `planned`,
  and any relation with `proposed=*` other than `no`/`none`
- skip a relation with no way members; node members (guideposts, route markers)
  are not geometry
- skip a member way whose nodes are incomplete (a way at the clip edge), and
  skip the relation when no complete member way remains
- skip a relation with no point inside the bbox
- keep a node inside the bbox when it carries `lcn_ref`, `rcn_ref`, `ncn_ref`
  or `icn_ref` with a non-empty value; the key decides the network, so a node
  without `network=*` still counts. Hiking node refs (`lwn_ref` and the other
  tiers) are not in this layer
- skip every other route value, including `route=bus` and `route=foot`.
  `route=foot` had 48 relations in the 2026-11-04 Ukraine extract and is not
  in this layer yet
- skip every relation whose `type` is not `route`

The output is deterministic for one PBF and one bbox: ways, node-network
numbers and relations are sorted by id, members keep relation order, and
coordinates are rounded to seven decimals. The plugin assembles each relation
by walking its members in order and reversing a way whose far end is nearer the
previous member's end. A member that does not meet its neighbour is a real gap
and stays a separate line.

Measured on 2026-11-13, 8 cores, `gm-data` slot, the phase-A Ukraine PBF
(883,214,645 B, sha256 `ff46165a…`). Each run decodes the whole PBF three
times, so the wall time is a full-file scan even for a small bbox; the table
records counts and bytes, not a timing comparison with the v1 run:

| Run | Relations | Ways | Nodes | Bytes |
|---|---|---|---|---|
| Fixture input, bbox `23.94,49.94,23.96,49.96` | 5 | 3 | 5 | 6,561 |
| Lviv city bbox `23.90,49.75,24.15,49.95` | 16 | 2,199 | 0 | not kept |
| Cycling probe, bbox `23.930,49.900,23.952,49.918` | 8 | 402 | 0 | 130,097 |

The whole Ukraine extract, with no bbox, keeps 658 route relations and 15,151
member ways: 537 `hiking` and 121 `bicycle`. The bicycle networks are 59 `lcn`,
35 `rcn`, 9 `ncn` and 2 `icn`; 13 have no `network`, and 3 carry a hiking `lwn`
value and draw as local. The hiking networks are 354 `lwn`, 120 `rwn`, 17
`nwn`, 2 `iwn` and 40 without a tag; 3 carry `lcn` and 1 `rcn` and draw as
local. The whole run reports **0 node-network numbers**: the 2026-11-04
snapshot has no node with a cycling `*cn_ref` tag anywhere. Every node member
of a bicycle relation is a guidepost without a number. The Lviv cycling probe
records that gap; its node list is empty.

The fixture's input is `test-data/regions/fixture-mini/route-relations.osm`
(2,228 B PBF beside it). Its ways are real tracks from the fixture bbox
(896757861 and 306496773 for the parallel hiking and cycling routes, 225670351
for the symbol-less hiking route and the national cycling route); the relations
and node tags are authored to exercise the rules: 9101 and 9102 share a way,
9103 is a bus, 9104 is proposed, 9105 has only a node member, 9106 is
`route=foot`, 9108 is a local cycling route, 9109 is a national cycling route,
9110 has only a node member and 9111 is a proposed cycling route. Nodes
5556643624, 5556643405, 5556643401 and 3114545825 carry `lcn_ref` 12, 13, 47
and 48; node 2344261512 carries `rcn_ref` 7; node 387853648 carries `lwn_ref`
3 and must not appear. The real extractor produces the committed bytes.
`regions.RegionReader` reads the component; the `route.relations` plugin
assembles it and draws it.

The `osmc:symbol` grammar is `waycolor:background[:foreground[:foreground2]
[:text:textcolor]]`. The plugin draws the background plate and the main
foreground shape from the Standard sprite's `osmc_*` glyphs, tinted per
feature. `foreground2` and the text suffix are not drawn yet. The 18 plates and
shapes are authored in this repository (Apache-2.0) from the documented
grammar; the Waymarked Trails shield renderer was checked and rejected because
it is GPL-3.0, and no part of it is copied.

### Lviv region pack (T-0319A)

`build-lviv-region.sh` builds a complete `ua-46` pack beside the existing one.
It copies the map bytes from
`/hdd/t-0102-evidence/region/ua-46/2026-11-05-bench`, rebuilds the contour
component from the 10 m contour archive, and builds the search section,
elevation grid and way-alert component from the Ukraine PBF and the GLO-30
tiles:

```sh
tools/pipeline/build-lviv-region.sh --keep-work
```

The output is
`/hdd/t-0319a-way-alerts/lviv-region/ua-46/2026-11-12-1` with `current` beside
it. Measured on 2026-11-13, 8 cores, `gm-data`, the phase-A Ukraine PBF
(883,214,645 B, sha256 `ff46165a...`) and 12 GLO-30 tiles: 89 s for the whole
script with warm caches (238 s cold), 115,164,160 B (110 MiB) including the
manifest. The contour step alone is `gdal_contour` 14.5 s and Planetiler 9.3 s.
The way-alert step alone is 5.3 s over the 78,434,355 B bbox extract, 17.8 MB
peak RSS; the fixture extraction is 0.03 s.

| Component | Bytes |
|---|---|
| `lviv-protomaps.pmtiles` (copied from the 2026-11-05 pack) | 61,793,175 |
| `lviv-contours-10m.pmtiles` (rebuilt from `lviv-90m-10m.pmtiles`) | 19,164,271 |
| search section, eight version-2 files | 12,717,602 |
| `features.bin` | 13,657,687 |
| `elevation.bin`, 12 cells | 3,852,712 |
| `way-alerts.geojson` | 2,921,155 |
| `relations.json` | 1,049,166 |

The Lviv way-alert extraction reads the bbox extract
`22.5,48.9,25.2,50.6` (the map bbox) and finds 18,009 features: 3,343 railway
crossings, 680 tunnels, 51 hazards, 4,109 fords and 9,826 barriers. The
component's sha256 is
`c19a15e26dd0e009f87d69ae4afbb9454369d0d183ada147c37ee9f199b5303d`. The
manifest's `pipeline.wayAlertSource` records the source PBF and digest, the
bbox, `osm-extract 0.1.0 way-alerts v1` and the same counts. A 22,866 B probe
of central Lviv (`24.018,49.834,24.028,49.845`, 135 features) is committed under
`test-data/regions/lviv-way-alerts/` with its provenance and a copy of the pack
manifest, so JVM tests read real extracted Lviv bytes and parse the pack
provenance without the pack.

The component is large because the bbox includes neighbouring oblasts inside the
map rectangle; it is the same area the map covers. The test region package is
122,429 B on disk; `TestRegionFixtureTest` fails if its components grow past
1 MiB.

The T-0418 pack is a separate output at
`/hdd/t-0418-hiking-relations/lviv-region/ua-46/2026-11-12-1`; the T-0319A pack
above was not touched. It adds `relations.json`, 1,049,166 B, to the same
components and records `pipeline.routeRelationSource` with the same PBF digest,
bbox, `osm-extract 0.1.0 route-relations v1` and 87 relations / 2,360 ways. The
whole pack is 115 MiB and took 109 s clean, 174 s while the check gate and an emulator shared the machine, on 2026-11-12 with 8 cores and `gm-data`. The
relations step is 5.09 s over the bbox extract. A 30,438 B Skole probe
(`23.505,49.018,23.53,49.035`, 6 relations, 61 ways) is committed under
`test-data/regions/lviv-hiking-relations/` with its provenance and a copy of
the new pack manifest.

T-0419 adds a cycling probe under `test-data/regions/lviv-cycling-relations/`:
`relations.json` is 130,097 B, eight `route=bicycle` relations and 402 member
ways from the bbox `23.930,49.900,23.952,49.918`, extracted from the same
Ukraine PBF. It carries no node numbers because the source snapshot has no
`*cn_ref` junction there. `provenance.json` records the source digest, the
extraction revision and that data gap. The T-0418 pack was not rebuilt.

## All-oblast build (T-0501)

`build-oblasts.sh` builds every Ukrainian oblast package from one monthly
country build: the country vector tile archive, the country contour archive,
the country Valhalla tile build and the GLO-30 source tiles. Every package is a
slice of those artefacts plus a search section extracted from the same PBF, so
the manifest of every package names the same build digests.

```sh
# A first build, or one whose DEM or routing changed: build both country
# artefacts, then every oblast package.
tools/pipeline/build-oblasts.sh \
  --build-contours --build-routing --routing-elevation \
  --dem /hdd/t-0501-work/dem \
  --out /hdd/t-0501-oblasts

# A monthly rebuild whose DEM and contour archive did not change:
tools/pipeline/build-oblasts.sh \
  --contours /hdd/t-0501-work/pipeline/country-contours/ua-contours.pmtiles \
  --build-routing --routing-elevation \
  --dem /hdd/t-0501-work/dem \
  --out /hdd/t-0501-oblasts
```

The country map archive is an input: the bench Planetiler profile builds it
(`grass-maps-bench`), not this command. The DEM is static, so its contour
archive is rebuilt only when the DEM changes. The contour stage warps the
GLO-30 tiles to a 90 m surface (`gdalwarp -r average`) and cuts the
100/50/10 m lines from that surface, so a contour line and the elevation grid
describe the same terrain; contouring the raw 30 m tiles produces about ten
times the linework and the surface noise T-0417 measured.

On this machine the script re-executes itself under `agent-lock run gm-data`,
so the country build and the oblast loop never run beside another data job.
`--build-contours` and `--build-routing` build the country artefacts from the
PBF and the DEM; without them the command takes existing archives, which is how
a monthly rebuild reuses an unchanged DEM and contour archive.

Oblast set: the `admin_level=4` polygons tagged `ISO3166-2=UA-*`, 27 units.
Kyiv city (UA-30) folds into Kyiv Oblast (UA-32) and Sevastopol (UA-40) into
Crimea (UA-43), leaving 25 packages. A Russian-mirror boundary without an ISO
tag is skipped. `boundary_packages` in `oblast_pipeline.py` holds the rule and
`scripts/ci/oblast_pipeline_test.py` covers both folds.

Per oblast the command runs, in order: `pmtiles extract` for the map (z8-14)
and for the contours, `osmium extract --strategy=smart` for the OSM extract,
`build-elevation.py grid` for the 90 m grid, the search section and feature
component from that extract, the way-alert and route-relation components, and
`valhalla_build_extract --geojson-dir` for the routing part. Each stage writes
a report with its wall time and peak RSS.

The package is `<region>/<version>/` with `map.pmtiles`, `contours.pmtiles`,
`elevation.bin`, the eight search files, `features.bin`,
`way-alerts.geojson`, `relations.json` and the directory `routing/`. `routing/`
holds `valhalla.json` and `tiles/<level>/<...>.gph`, the Valhalla tiles of every
level whose grid cell intersects the oblast polygon. The script extracts them
from the `valhalla_build_extract` tar and drops `index.bin`, which Valhalla reads
only in `tile_extract` mode. The manifest component is `{kind: routing, file:
routing, type: directory, format: valhalla-tile-directory, formatVersion: 1}`.
Its `bytes` is the sum of the file sizes. Its `sha256` is the hash of the
canonical listing: one line `<path>\t<size>\t<sha256>\n` per regular file, path
relative to `routing/` with `/`, sorted by the UTF-8 bytes of the path, every
line ending in LF. `directory_listing` in `oblast_pipeline.py` and
`ComponentListing` in `regions` implement it, and both tests check the vector
`tiles/0/003/110.gph` = `tile-a`, `valhalla.json` = `{}`, listing hash
`3ce123a8...e7d5`. The app mounts every installed `routing/` directory as one
tile directory (see the README section on the region store). A package built
before T-0518 ships `routing.tar`; the app skips its routing and the user
downloads it again. `pipeline.routingSource.sha256` is the digest
of the one country `tiles.tar`, so every installed routing part can be traced to
that build. The footprint is the exact oblast polygon, not a buffered one; a
border pan routes across installed neighbours through the app's merged mount.

`manifest.json` follows `data-formats.md` section 2. `pipeline` carries the
bounding box, the source archives and digests for map, contours, elevation,
search, way alerts, route relations and routing, and the fold under
`includes`. `elevationSource.missingTiles` lists the 1-degree cells the source
DEM does not carry, which are sea-only cells the grid omits instead of
inventing heights.

`catalog.json` follows `server-protocol.md` section 2: `format`, `generated`,
`attribution`, a `build` block with the country digests, and one entry per
region with `id`, `name` (uk and en), `kind`, `parent`, `bbox`, `outline`,
`version`, `size_bytes` and `parts`. The outlines are Douglas-Peucker
simplified at 0.004 degrees; the exact polygons stay under `boundaries/`.

`metrics.json` records, per oblast, the wall time of its build, the peak RSS
over its stages, the bytes on disk, the routing bytes and tile count, and every
stage's time and peak RSS. Host stages are sampled through the process tree at
4 Hz; container stages read the container cgroup's `memory.peak`.

Sea and land polygons: the country map archive was built with the deliberately
empty `land-polygons-split-3857` and `water-polygons-split-3857` stubs, because
`osmdata.openstreetmap.de` was unreachable when it was built. The oblast packs
inherit that, so a coastline has no sea polygon behind it. Every manifest and
the catalog record the stub state; replacing it means rebuilding the country
map archive, not the oblast packages.

### World overview (T-0502)

`oblast_pipeline.py world` packages a z0-z7 whole-world PMTiles archive as
region `world`, in the same layout as an oblast: `<out>/world/<version>/` with
`map.pmtiles` and `manifest.json`, `<out>/outlines/world.geojson`, and a
`world` entry in `<out>/catalog.json` (kind `world`, no parent, one `map`
part). The package is map-only. Its manifest has no `features` component, which
`RegionManifestParser` accepts when no `search-*` component exists.

```
agent-lock run gm-data --timeout 20m -- nice -n 15 ionice -c3 \
  python3 -I tools/pipeline/oblast_pipeline.py world \
    --pmtiles /hdd/t-0502-world/world-z7.pmtiles --out /hdd/t-0502-world/out \
    --version 2026-11-16 --osm-timestamp 2026-11-16T04:00:00Z \
    --map-archive "protomaps-basemap-20261116 (pmtiles extract --maxzoom=7)" \
    --schema "protomaps basemap 4.15.2, planetiler 0.10.2"
```

The command reads the PMTiles header and refuses an archive that is not vector
tiles or does not span exactly z0-z7. It hard-links the archive into the
package (a copy on another file system), so the 190 MB file is not duplicated.
`write_catalog` keeps an existing world entry, so an oblast rebuild into the
same output directory does not drop it.

Measured: `world-z7.pmtiles` is 189,868,812 B, sha256 `539a1623...`. The input
is a Protomaps daily build 20261116 extract (schema 4.15.2, planetiler 0.10.2,
replication time 2026-11-16T04:00:00Z). The package is the same bytes under the
`map` component. The size is over the 50-100 MB design budget on purpose (user
decision for T-0502). Attribution: OpenStreetMap contributors (ODbL), Natural
Earth (public domain) and the Protomaps basemap schema. The pipeline does not
upload anything: `server/publish-packs.py` zips the directory.

### Measured all-oblast build (T-0501)

Conditions: 8 cores, 31 GB RAM, outputs under `/hdd`, `gm-data`, the
2026-11-03 Ukraine extract (883,214,645 B, sha256 `ff46165a…`), the bench
Planetiler archive `ukraine-final.pmtiles` (1,030,529,076 B, `bee6252b…`) and
118 GLO-30 tiles. The DEM covered 121 one-degree cells; three are sea-only and
the source carries no tile for them. The routing build ran with grades.

| Country stage | Time | Peak RSS | Output |
|---|---|---|---|
| `gdalbuildvrt` over 118 DEM tiles | 1 s | - | `ua.vrt` |
| `gdalwarp` to 90 m, cut to the oblast union | 74 s | 2.5 GiB | `ua-90m.tif` |
| `gdal_contour` 10 m | 41 s | 2.2 GiB | `ua-contours.gpkg`, 924 MB |
| Planetiler 100/50/10, z11-14 | 34 s | 3.8 GiB | `ua-contours.pmtiles`, 343,568,573 B |
| `build-elevation.py hgt` | 74 s | 0.6 GiB | 118 HGT tiles, 2.9 GB (build input) |
| `valhalla_build_admins` | 36 s | 0.7 GiB | `admins.sqlite` |
| `valhalla_build_tiles` | 483 s | 8.9 GiB | 1,523 tiles, grades on 1,482 |
| `valhalla_build_extract` | 20 s | 1.1 GiB | `tiles.tar`, 1,068,339,200 B |

Country preparation is 762 s of measured stages. The oblast loop is 1,228 s for
all 25 packages; every package takes 34-70 s and peaks at 3.8-4.8 GiB, almost
all of it `osmium extract --strategy=smart` reading the 883 MB country extract.
Every manifest names `routingSource.sha256` = `335fcbdc…`, the one country
`tiles.tar`.

| Oblast | Build | Peak RSS | Package | Routing | Tiles |
|---|---|---|---|---|---|
| `ua-05` Vinnytsia | 39 s | 3.88 GiB | 145 MiB | 58 MiB | 85 |
| `ua-07` Volyn | 35 s | 3.86 GiB | 85 MiB | 34 MiB | 67 |
| `ua-09` Luhansk | 49 s | 3.82 GiB | 128 MiB | 48 MiB | 89 |
| `ua-12` Dnipropetrovsk | 56 s | 3.90 GiB | 181 MiB | 81 MiB | 99 |
| `ua-14` Donetsk | 58 s | 3.92 GiB | 199 MiB | 84 MiB | 88 |
| `ua-18` Zhytomyr | 38 s | 3.88 GiB | 128 MiB | 58 MiB | 98 |
| `ua-21` Zakarpattia | 54 s | 3.96 GiB | 130 MiB | 42 MiB | 53 |
| `ua-23` Zaporizhzhia | 51 s | 3.87 GiB | 97 MiB | 47 MiB | 91 |
| `ua-26` Ivano-Frankivsk | 56 s | 3.88 GiB | 144 MiB | 66 MiB | 54 |
| `ua-32` Kyiv, with Kyiv city | 63 s | 3.97 GiB | 259 MiB | 138 MiB | 100 |
| `ua-35` Kirovohrad | 41 s | 3.83 GiB | 126 MiB | 62 MiB | 88 |
| `ua-43` Crimea, with Sevastopol | 45 s | 3.90 GiB | 139 MiB | 65 MiB | 93 |
| `ua-46` Lviv | 70 s | 4.76 GiB | 201 MiB | 93 MiB | 78 |
| `ua-48` Mykolaiv | 40 s | 3.87 GiB | 118 MiB | 54 MiB | 83 |
| `ua-51` Odesa | 66 s | 3.90 GiB | 140 MiB | 58 MiB | 116 |
| `ua-53` Poltava | 41 s | 3.86 GiB | 129 MiB | 63 MiB | 94 |
| `ua-56` Rivne | 34 s | 3.88 GiB | 86 MiB | 36 MiB | 70 |
| `ua-59` Sumy | 37 s | 3.85 GiB | 90 MiB | 30 MiB | 81 |
| `ua-61` Ternopil | 36 s | 3.84 GiB | 112 MiB | 58 MiB | 55 |
| `ua-63` Kharkiv | 48 s | 3.90 GiB | 178 MiB | 83 MiB | 105 |
| `ua-65` Kherson | 36 s | 3.87 GiB | 82 MiB | 45 MiB | 96 |
| `ua-68` Khmelnytskyi | 37 s | 3.87 GiB | 116 MiB | 49 MiB | 69 |
| `ua-71` Cherkasy | 63 s | 3.88 GiB | 143 MiB | 68 MiB | 82 |
| `ua-74` Chernihiv | 69 s | 3.88 GiB | 116 MiB | 49 MiB | 104 |
| `ua-77` Chernivtsi | 66 s | 3.84 GiB | 82 MiB | 37 MiB | 42 |

What the numbers say, against `data-formats.md`:

- The 25 packages total 3.27 GiB installed, against the design's 2.3 GB on the
  phone for all Ukraine. The excess is spread over every part: routing
  1.47 GiB against 983 MiB, contours 345 MiB against ~160 MB, search plus the
  feature component 313 MiB against the 100-160 MB search estimate, map
  1,070 MiB against 983 MiB.
- Routing is 1.47 GiB in 2,080 tiles, 37 % more tiles than the 1,523 in the
  country extract, because a border tile belongs to every neighbouring oblast's
  part. Storing a shared border tile once is T-0515's job; until then the
  catalog's per-region routing sizes add up to more than the one build. The
  verification section below measures the duplicates: 480 shared tiles and
  509,421,544 B of extra payload.
- The contour archive is 343,568,573 B for the country and the 25 extracts sum
  to 345 MiB. The design's ~160 MB estimate was for the old 10/20 m ladder; the
  100/50/10 ladder at z11-14 measures about twice that.
- The map component sums to 1,070 MiB. Every oblast carries its own z8-10
  tiles, so the low zooms are repeated across neighbours; the world overview
  (T-0502) and the shared-source resolver (T-0503) are where that shrinks.
- Kyiv city and Sevastopol are inside the Kyiv and Crimea packages rather than
  packages of their own; their manifests name them under `pipeline.includes`.
  Crimea's elevation lists the two sea-only cells the DEM does not carry,
  Odesa's and Kherson's one each.

### 30 m elevation add-on (T-0509)

The oblast pack carries the 3 arcsec (about 90 m) grid. The 30 m grid is a
package of its own, `<region>-elevation-30m`, so a user downloads it only for
the oblasts where track analysis and the altitude widget should use it. The app
reads it first and the 90 m grid where the add-on has no cell.

`build-elevation.py grid --arcsec 1` writes the same `elevation.bin` container
with 1 arcsec cells. Below 50 degrees N a cell is one GLO-30 sample. At N50 and
above the source pixels are 1.5 arcsec wide, so a cell is narrower than a pixel.
Each cell then copies the pixel under its centre (`nearest_columns`). One cell in
three has its centre exactly on a pixel edge, and that cell takes the pixel east
of the edge. `cells_for_tile` chooses the path from the real cell-to-pixel ratio,
not from a rounded integer. The build fails when the grid exceeds 64 MiB,
the size the app reads (`GRID_MAX_BYTES`, equal to `MAX_COMPONENT_BYTES` in
`RegionElevation.kt`; a test keeps them equal). `build-oblasts.sh --elevation-30m` builds the add-on after each
oblast. `--elevation-30m-only --oblast UA-46 --version V` builds only the add-on
beside an existing oblast build. It needs `--dem` and takes `--boundaries PATH`
(default `<out>/boundaries/boundaries.json`) and `--osm-timestamp` (default
`V` at midnight UTC). Use the timestamp of the oblast build, because the add-on
holds no OSM data. The output is `<out>/<region>-elevation-30m/<version>/` with
`elevation.bin` and a `manifest.json` whose one component is `elevation`
and whose `pipeline.addon` is `{"id": "elevation-30m", "region": "ua-46"}`.
`oblast_pipeline.py catalog` attaches it as `addons` on the region entry and
refuses an add-on whose region package is missing. It lists only the add-on
version that equals the region's version, so an older `<region>-elevation-30m/<old>/`
left in `--out` is not listed.

Measured for Lviv (`UA-46`, 2026-11-03, `nice -n 15 ionice -c3`, GLO-30 tiles
on `/hdd`, 12 tiles): 46.5 s for the stage, 49 s for the script, 27,558,702
bytes. The 90 m grid of the same oblast is 4,528,060 bytes, so the 30 m grid is
6.1 times larger. The 25 oblasts' 90 m grids total 89,321,417 bytes. The older
estimates (2.2 MB and 13 MB per oblast) were wrong. A 30 m total for Ukraine is
not measured. Scaling the 90 m total by Lviv's ratio gives about 544 MB, and
that figure is an extrapolation.

The first Lviv build (31,573,392 bytes, 227 s) was wrong. It took the
`block_mean` fallback for the N50 tiles, so every third column was no data.
On a real tile (`N50E024`, `N50E023`) the fixed code gives 3600x3600 cells,
no no-data cell, and exactly the source pixel under each cell centre. Block means
of the 1 arcsec cells differ from the 3 arcsec grid by at most 4.1 m (99th
percentile 1.2 m, mean 0.3 m).

### Routing part verification (T-0515)

`tools/pipeline/verify_routing_packs.py` checks the routing claims that the
phone run cannot repeat on a desk. It reads the packs and the pack code, writes
nothing, and exits 1 on any failure:

    tools/pipeline/verify_routing_packs.py \
        --packs /hdd/t-0501-oblasts \
        --country /hdd/t-0501-work/pipeline/country-routing

Measured on the T-0501 build (2026-11-03, 25 packages, 3.3 GiB):

| Check | Result |
|---|---|
| Grid coverage | 25 of 25 packages exact, 2,080 tiles, 0 missing, 0 extra |
| Border join | 2,080 of 2,080 tiles byte-identical to the country build |
| Duplicates | 480 tiles in two or more packages, 604 extra copies, 509,421,544 B |
| Runtime databases | failed before this change, the pack code now clears both |

The grid rule is every tile of every level whose cell intersects the oblast
polygon, not its bounding box. A bounding-box rule would have added 14 to 55
tiles per package, and the polygons match exactly.
`pipeline.routingSource.selection` states the polygon rule and
`valhalla_build_extract --geojson-dir` implements it. The manifest records the
tile count only, not the list, so `routing/tiles` is the only per-package tile
list.

A border pair: `2/000/807/271.gph` is in ua-63 and `2/000/807/272.gph` is in
ua-09. Their headers read `graphid 0x628b3a` and `0x628b42`, level 2, tileids
807271 and 807272, base corners (37.75, 50.0) and (38.0, 50.0). Each copy is
byte-identical to the country tile, so the two sides of the border cannot name a
different node. A tile that two packages share, `1/050/610.gph` (4,904,168 B, in
ua-32 and ua-74), hashes to `c05ede8a…` in both and in the country build.

The duplicates are structural, not only a border effect. Every level-0 and
level-1 tile is a coarse hierarchy tile that several oblast polygons intersect,
so 14 level-0 tiles are carried 59 times and 109 level-1 tiles 232 times:

| Level | Copies | Unique tiles | Extra bytes |
|---|---|---|---|
| 0 | 59 | 14 | 141,645,296 |
| 1 | 232 | 109 | 138,361,856 |
| 2 | 1,789 | 1,353 | 229,414,392 |
| all | 2,080 | 1,476 | 509,421,544 |

The packages carry 1,574,961,728 B of tile payload against 1,065,583,360 B of
unique tiles, and their `routing.tar` files totalled 1,578,895,360 B before T-0518. Forty-seven
country tiles are in no package (43,176 B, 33 of them empty zero-node tiles).
They are the sea and border cells outside every oblast polygon.

#### What a phone run must still prove

1. The native engine starts with `mjolnir.tile_extract` empty and
   `mjolnir.tile_dir` set, and returns a route. The app writes that config, and
   Valhalla's `GraphReader` falls back to `tile_dir` when no extract loads, but
   only the device proves the AAR.
2. A pack with no `admins.sqlite` and no `timezones.sqlite` starts and routes,
   and the guidance and arrival time stay acceptable. `ValhallaPackTest` pins
   the config values, not the engine.
3. A route across an oblast border with both packs installed runs continuously.
   The ua-63 and ua-09 pair above crosses 38.0 E.
4. A route that needs an oblast that is not installed reaches the missing-region
   prompt. No such code exists today. T-0511 owns it.

#### Open gaps

- `ValhallaPack.inspect` required `admins.sqlite` and `timezones.sqlite`, and
  the local config pointed the engine at both. No T-0501 package ships either
  file. A bare package directory returned `ValhallaPackProblem.Incomplete` for
  its missing `valhalla.json`, and a pack with a config and tiles returned
  `Incomplete` for its missing `admins.sqlite`. Both reach the user as
  `AppError.RoutingPackUnavailable(Incomplete)`, so no T-0501 pack could route.
  This change removes the requirement and clears both paths.
- The two databases exist only in the country build, where `admins.sqlite` is
  12,537,856 B and `timezones.sqlite` is 123,133,952 B. A pack that copied them
  would pay 135,671,808 B for the pair.
- A border tile is stored twice, once in each neighbouring package. The app
  mounts one copy per tile path (T-0518), but the installed packages keep their
  copies on disk, so the merge saves no storage today: its hard links share
  bytes with the packs, not between them.
- `manifest.json` records `pipeline.routingSource.tiles` as a count. A package
  cannot show which tiles it holds without opening `routing/`.
- `DownloadPolicy` allows 1,024 files per package by default (4,096 at most).
  Lviv has 78 tiles, but one entry per tile counts, so a large oblast may need a
  higher limit.

#### Shared tiles: measured, not built

Loose tiles shipped with T-0518 as the `routing/` directory component. The
directory form of the country build measured 1,523 files and 1,065,583,360 B of
payload, 1,068,990,464 B on disk against 1,068,339,200 B for the tar.

Shared tiles remain. `valhalla_build_extract` slices per polygon, so a border
tile lands in every neighbouring package. Shipping it once would remove the extra
copies: 509,421,544 B, of which 280,007,152 B is level 0 and 1 and 229,414,392 B
is level 2. A country-wide hierarchy part would remove the level-0 and level-1
copies from the download: 124,705,872 B shipped once instead of 404,713,024 B
carried now, a saving of 280,007,152 B over the 25 packages. No task names it.

After both changes the country would ship 1,476 unique level-2 tiles,
940,877,488 B, and one hierarchy part of 124,705,872 B, against 1,574,961,728 B
of repeated payload today.

### Search section and features
The search section is real data. `tools/pipeline/fixture-places.tsv` is the
authored place list: four places inside the fixture bbox, one with a phone,
website, `opening_hours` and `wheelchair`, one with a brand, one with no tags and
one with a non-ASCII name. `build-test-region.sh` runs the `build-section` binary
in `rust/grass-core`, which writes the search files and `features.bin` in one
pass: eight files for a version-2 build (including `format.bin`) and seven in
the committed version-1 package. The feature component carries, per place row, the canonical OSM type
and id and the raw tag map; the search table itself keeps its 20-byte row and no
OSM id.

The committed section is stored unpacked because the reader maps its files
directly. The single-file `search.idx` container for real oblast packs is not
built yet; until it is, the package declares each search file as its own
component with the `search-section` format. A section built now is format
version 2 and carries `format.bin`; the committed package predates it and is
version 1, which the reader opens without the header. The feature component is
a single `features.bin` file with the `place-features` format, version 1.

The Lviv section under `places/src/test/resources/search/lviv/` is the P4a spike
output and predates the feature component. Its committed `features.bin` is an
empty component, generated by `build-section --empty-features`, so the reader
opens it and those places report no identity and no tags.

### Search synonyms and brands (T-0204)

`rust/grass-core/data/synonyms.tsv` is the synonym and abbreviation table. It
is data, not code, and it is compiled into the reader with `include_str!`. Four
tab-separated columns, no header row:

| Column | Meaning |
|---|---|
| `section` | `kind` or `abbreviation` |
| `language` | `uk`, `ru` or `en`; provenance, since every row is active for every query |
| `term` | the word a person types, folded on load (lower case, accents and apostrophes dropped) |
| `expansion` | for `kind`, the OSM category value word; for `abbreviation`, the full word |

A `kind` row is read on the query side only. "аптека" resolves the kind word
"pharmacy" as the same query word, so every `amenity=pharmacy` and
`healthcare=pharmacy` row matches even when its name is "Подорожник". The value
word is already indexed from the kind string, so a new kind synonym costs no
index bytes and needs no rebuild. The expansion must be the value word, not the
whole `key=value` string.

An `abbreviation` row is read on both sides. The builder rewrites an occurrence
in a place's own name, so a row spelled "пр-т Червоної Калини" indexes like
"проспект Червоної Калини"; the query rewrites the typed text the same way.
This is what makes "вул."/"вулиця" and "пр-т"/"проспект" the same search in
both directions. A new abbreviation applies to existing names only after the
section is rebuilt.

An entry comes from OSM's vocabulary, not from a place row: the category value
words are OSM's (`pharmacy`, `cafe`, `atm`, `fuel`, `hotel`, `school`,
`supermarket`), and the typed words are the common Ukrainian, Russian and
English forms. Add one line and run `cargo test --locked` in
`rust/grass-core`; `synonym::tests` fails if the shipped table does not parse.
Do not edit the table to fix a single place's name: that is name data and
belongs in OSM.

Measured on the Lviv city fixture (58,160 rows), same extractor snapshot, one
build per row of the table. The committed section is the baseline, but it was
built before the builder's house-number folding took effect in the committed
bytes: it still carries terms such as `10а` and `100х100` beside the folded
`10a` and `100x100`. That drift is older than T-0204, so it is measured on its
own row and not charged to the synonym layer.

| Build | Search section | Terms | `terms.fst` | `postings.bin` | `features.bin` |
|---|---|---|---|---|---|
| Committed section | 3,974,199 B | 13,162 | 131,357 B | 407,673 B | 4,823,038 B |
| Current builder, no abbreviations, no brand aliases | 3,959,552 B (-14,647 B) | 13,016 | 131,453 B | 392,930 B | unchanged |
| Plus abbreviations | 3,958,783 B (-769 B, -0.02%) | 13,014 | 130,702 B | 392,912 B | unchanged |
| Plus brand aliases (the fixture now committed) | 3,967,210 B (+8,427 B, +0.21%) | 13,311 | 133,311 B | 398,730 B | 5,015,181 B (+192,143 B, +3.98%) |

Against the same data without the layer, T-0204 adds 7,658 B (0.19%) to the
search section: -769 B from abbreviations and +8,427 B from brand aliases. The
synonym layer costs no index bytes for kinds. Abbreviations shrink the section
because a name "вул. X" no longer leaves the short word as its own term and
"вулиця" is already in the vocabulary; the 751-byte drop in `terms.fst` is the
two terms `вул` and `пл` disappearing. Brand aliases add 297 distinct terms.
The brand and operator tags that the result row reads add 192,143 B to
`features.bin`, on top of the search section. The whole Ukraine extract has 928,666
place rows against Lviv city's 23,086 (about 40 times), so by row ratio expect
roughly 0.34 MB of brand aliases and 7.7 MB of feature tags for the country.
That is an estimate from the row ratio, not a measured whole-country run; only
the Lviv bbox was built.

The extractor now carries `brand`, `brand:uk`, `brand:en`, `operator`,
`operator:uk` and `operator:en` into the feature tags, and writes the brand (or
the operator when there is no brand) into the row's alias column when the name
does not already contain it. A branch named "Відділення поштового зв’язку №35"
with `brand=Укрпошта` is found by "укрпошта", and its search hit carries the
brand for the result row. The hit's `kind` stays the exact OSM category, which
the `pois` filter and the committed probes match on.

### Search in other scripts (T-0205)

A person may type a Ukrainian name in Cyrillic or in Latin, and the interface
language does not decide which. Two data sources make the index answer both. They
do different jobs and neither replaces the other.

`name:<language>` tags in OSM are real names in other languages, and the
extractor now carries the Latin and Cyrillic ones into the row's alias column.
"Złoczów" is the `name:pl` of Золочів (OSM node 1429712455); "Zolochiv" is its
`name:en`, "Золочев" its `name:ru`. No table can derive "Złoczów": it is a Polish
exonym, not a transliteration of the Cyrillic letters. The alias column keeps
the brand values it already carried and appends these names, so a row's
alternatives stay in one place.

`rust/grass-core/data/transliteration.tsv` is the second source. The builder
romanises a Cyrillic name that has no Latin spelling of its own and indexes the
result beside the name. This is what reaches the places OSM has only in
Cyrillic: Золочівка has no `name:en`, so "zolochivka" matches it only because
the builder wrote that term.

The table holds two conventions:

| Scheme | Source | Used for |
|---|---|---|
| `national` | Ukrainian transliteration, Cabinet of Ministers resolution 55 (2010) | the official spelling a reader has seen on a sign or a passport |
| `pragmatic` | no standard: г as g, и as i, х as h, ц as c, щ as sch | how people type Ukrainian in a Latin keyboard |

One standard would have been enough if people typed standards. They do not: the
official scheme writes Городоцька as "horodotska" and most people type
"gorodocka". The pragmatic scheme is not a rival standard and does not claim to
be; it is the same table with five letters spelled the way a search box sees
them. A third convention, Polish, is deliberately absent: Polish names of
Ukrainian towns are historical exonyms ("Lwów" for Львів, not a romanisation of
"Львів"), and those come from OSM or not at all.

Both schemes are one table of `scheme`, `position`, `source` and `target`
columns. `position` is `any` or `start` because the 2010 rules spell є, ї, й, ю
and я differently at the start of a word. A multi-letter source wins over its
first letter, which is how the official "зг" to "zgh" rule keeps Згода distinct
from Жода. Russian letters (ы, э, ё, ъ) are in the table because Ukrainian OSM
data carries `name:ru`, and they romanise instead of passing through.

Transliteration runs at build time only. The query side is unchanged: it folds
the typed text and matches prefixes, and a Latin query meets the Latin term the
builder wrote. The alternative was to expand a Latin query into Cyrillic
candidates at every keystroke. That inversion is one-to-many in the wrong
direction ("ch" is ч or х, "y" is и, і or й, "i" is і or и), so a word produces
many candidates, each with its own prefix walk and typo automaton, on every
keystroke. The index-time table is paid once, is byte-stable, and keeps the
builder and the app on one folding implementation.

Romanisation is lossy in both directions and this is deliberate. г and ґ are
both g in the pragmatic scheme, и and і are both i, and е and є depend on
position. The variants are indexed *beside* the Cyrillic name and never replace
it, so two places that romanise alike keep their own Cyrillic terms, and a query
that reaches both returns both. The ranking then decides: exact word, then
address length, then area, street and distance from the origin, and the row's
context names the oblast and raion. Nothing is dropped silently. The acceptance
case is not a collision: one place in the Золочів section carries the term
"zloczow", so its top result is not one of two folded rows. A builder test
proves the collision case with a crafted pair ("Гай" and "Ґай", both "gai"),
which asserts that both places come back.

A name that already has a Latin spelling in the data gets no generated variant.
The rule is per row: if the name or any alias carries a Latin letter, the
builder writes no romanised term. OSM's own spelling is a name a person wrote,
and it beats anything a table generates.

The extractor keeps a `name:<language>` value when its suffix looks like a
language tag (`en`, `crh`, `sr-Latn`; not `name:prefix` or `name:source`) and the
value carries a Latin or Cyrillic letter. Arabic and CJK names are not indexed:
no query in this region reaches them, and each one costs bytes and terms. The
values do not go into the feature tags, only into search aliases, so a place's
tag map and the region size stay as they were.

Measured on the Lviv city fixture (58,160 rows: 23,086 places, 35,074 houses),
the same extractor snapshot and one build per row. The baseline is the
current builder without this layer. The search section here means the seven
files the reader maps plus `format.bin`, without `features.bin`.

| Build | Search section | Terms | `terms.fst` | `postings.bin` |
|---|---|---|---|---|
| Baseline, no name variants and no transliteration | 3,967,210 B | 13,243 | 133,311 B | 398,730 B |
| Plus `name:<language>` aliases | 4,017,743 B (+50,533 B, +1.27%) | 16,166 | 162,238 B | 420,336 B |
| Plus national transliteration | 4,251,939 B (+234,196 B over the row above) | 23,467 | 217,154 B | 599,616 B |
| Plus pragmatic transliteration (the shipped build) | 4,384,183 B (+132,244 B over the row above) | 27,191 | 242,754 B | 706,260 B |

Against the baseline the layer adds 416,973 B (9.2% of the whole 8,982,391 B
section, 10.5% of the search section): 50,533 B for the OSM names and 366,440 B
for the romanised variants. The pragmatic scheme is 132,244 B of that. Two
alternatives were measured, not shipped: romanising place rows only saves
166,571 B and loses Latin address search ("stryiska 45"), and dropping the
pragmatic scheme saves 132,244 B and loses "gorodocka". The whole Ukraine
extract has 2,134,952 rows against these 58,160, 36.7 times as many, so by row
ratio expect about 1.9 MB of names and 13 MB of variants. Lviv city is densely
mapped, so that ratio probably overstates both; only the Lviv city bbox was
built.

The committed `lviv-addresses` section keeps the bytes T-0204 built, so its
probe sets stay comparable across tasks. Those bytes do not include this layer;
rebuild with the command above to add it. The difference is a few hundred
kilobytes of terms and no change to a Cyrillic query, as the row above shows.

The same 58,160 rows build in 1.60 s instead of 0.75 s (three runs each, peak
RSS 118 MB instead of 114 MB), and two builds of one input are byte-identical.

The committed section `places/src/test/resources/search/zolochiv` is the
pipeline's own output for the town, and `ZolochivSearchTest` reads it:

```sh
"$CARGO_TARGET_DIR/release/osm-extract" \
  --pbf ukraine-latest.osm.pbf --bbox 24.85,49.78,24.95,49.85 --no-addresses \
  --out build/data/zolochiv.tsv
cargo run --release --locked --manifest-path rust/grass-core/Cargo.toml \
  --bin build-section -- build/data/zolochiv.tsv \
  places/src/test/resources/search/zolochiv
```

The section is 99,022 B over 538 place rows and carries the town's 18 Latin and
Cyrillic name variants, which is why "Złoczów" and "zloczow" find it. The test
asserts that "zloczow", "Złoczów" and "Золочів" return one row as the top
result, that "zolochivka" reaches a village with no Latin name, and that exactly
one town in the section answers to the folded term.

`test-data/search/normalisation-vectors.tsv` is the shared fold. It holds rows
for `fold::normalise` and `fold::canonical_words` and is read by two tests,
never copied: `fold::tests` in the crate and `NormalisationVectorsTest` in the
app, which calls the same two functions through the generated Kotlin bindings.
The builder folds a stored name with the crate; the app folds the typed text
with the library it ships. A change that moves one side alone fails one of the
two tests. Transliteration is not in the file because it has no app-side
counterpart: it runs at build time only, and a query never romanises anything.

### OSM extraction and house numbers

`tools/pipeline/osm-extract` turns an OSM PBF extract into the place list
`build-section` consumes. It is the only stage in this repository that reads
OSM, and it has no dependency on the bench repository: the selection rules the
P4a spike took from osm-mapidx are rewritten here in Rust.

Planetiler's `places` layer is not used: it is a display layer, and it carries
no `addr:housenumber` or `addr:street`. The phase-A `lviv-places.sqlite` is not
used either: it lives outside this repository, is Lviv-only, and its build is
not reproducible from a command here. Its `addresses` table was read to confirm
the shape of the data (about 35,000 houses in Lviv city), and the extractor
reproduces that count from the PBF.

```sh
export CARGO_TARGET_DIR="$PWD/build/rust-test-target"
cargo build --release --locked --manifest-path tools/pipeline/osm-extract/Cargo.toml
```

The binary lands at `$CARGO_TARGET_DIR/release/osm-extract`; the commands below
use that path, so the export above is part of the recipe. `check.sh` sets the
same directory for its Rust test step.

The input is the Geofabrik Ukraine extract downloaded on 2026-11-04:
`ukraine-latest.osm.pbf`, 883,214,645 bytes, sha256
`ff46165a08b21fc4271aa6d5f199e55d0c9b786bcfd8f50e0f7c31f82248347f`. The
`-latest` name rolls, so the digest is what identifies the snapshot. `--bbox`
clips to one region; without it the whole file is processed. `--no-places`
keeps only house numbers, `--no-addresses` keeps only places.

```sh
# The committed Lviv city search section: places and houses, the fixture
# LvivAddressSearchTest reads (places/src/test/resources/search/lviv-addresses).
"$CARGO_TARGET_DIR/release/osm-extract" \
  --pbf ukraine-latest.osm.pbf --bbox 23.90,49.75,24.15,49.95 \
  --out build/data/lviv-city.tsv
cargo run --release --locked --manifest-path rust/grass-core/Cargo.toml \
  --bin build-section -- build/data/lviv-city.tsv \
  places/src/test/resources/search/lviv-addresses

# Lviv-bbox index, the size comparison with the P4a spike.
"$CARGO_TARGET_DIR/release/osm-extract" \
  --pbf ukraine-latest.osm.pbf --bbox 22.5,48.6,26.3,50.7 --out build/data/lviv-bbox.tsv

# Whole-country index.
"$CARGO_TARGET_DIR/release/osm-extract" \
  --pbf ukraine-latest.osm.pbf --out build/data/ukraine.tsv

cargo run --release --locked --manifest-path rust/grass-core/Cargo.toml \
  --bin build-section -- build/data/lviv-bbox.tsv <out-dir>
```

The output is the builder's nine-column TSV: `osm_type, osm_id, lat, lon, name,
kind, context, tags, aliases`. A house row has `kind=addr=house`, its name is
`<addr:street> <addr:housenumber>`, its context is the locality, and its aliases
carry a folded number and a street with its type word.

A letter suffix is one house however it is typed. The builder and the query
side both run `fold::canonical_words`: a digit-led token absorbs the short
letter token after it and folds the Cyrillic lookalikes, so `2-А`, `2а` and
`2a` all become the term `2a`. The same function is covered by the Rust tests
and by nine of the 50 committed probes.

Selection rules, rewritten from osm-mapidx:

- a named element with one of the 22 POI keys, or a named road in `trunk` to
  `pedestrian`, becomes a place; a building whose name is only a house number is
  dropped because its address row says it better
- an element with `addr:housenumber` and `addr:street` (or `addr:place`) becomes
  a house; rows are deduplicated by the street's words (independent of the type
  word and of word order), the number identity (a hyphen before a letter is
  dropped, a separator between numbers is kept, so `12/2` is not `122`) and the
  locality
- the locality is the nearest settlement within 15 km, from a `place=*` node or
  way; a city, town, village, hamlet or borough outranks a closer suburb or
  neighbourhood. In the Lviv bbox run there are 43,368 locality nodes and 10,673
  locality ways
- ways use the mean of their node coordinates for a place; a house way uses its
  first node, as osm-mapidx does
- relations are not indexed, and the run counts the ones that matter: 5,941 of
  347,618 relations carry `addr:housenumber`, `addr:street` or `addr:place`, and
  14,511 carry `place`. Multipolygon buildings and `type=associatedStreet`
  relations are therefore not sources of houses or street names

Measured on 2026-11-09/10, `--release`, one thread, files in page cache, Intel
i5-9300H, 31 GB RAM, Rust 1.93.1:

| Run | Rows | TSV | Extract wall | Extract peak RSS | Index build | Build peak RSS |
|---|---|---|---|---|---|---|
| Lviv city bbox, the fixture (23.90-24.15 E, 49.75-49.95 N) | 58,160 (23,086 places + 35,074 houses) | 9.29 MiB | 2:12 | 1.64 GiB | 1.2 s | 108 MiB |
| Lviv bbox (22.5-26.3 E, 48.6-50.7 N) | 251,304 (137,126 places + 114,178 houses) | 38.25 MiB | 2:23 | 1.68 GiB | 4.4 s | 428 MiB |
| Ukraine | 2,134,952 (928,666 places + 1,206,286 houses) | 344.3 MiB | 3:28 | 2.07 GiB | 40.6 s | 3.55 GiB |

The Lviv city bbox is not the city: it contains about twenty surrounding
settlements, and only 15,665 of its 35,074 house rows carry the context
`Львів`; the rest are Скнилів (3,147), Брюховичі (2,072), Винники (1,859) and
others. Every number in this section is for the bbox as stated.

Search-section sizes (the mapped files including `format.bin`; `features.bin`
is reported separately because the P4a estimate did not include it):

| Run | Search section | Per row | `features.bin` |
|---|---|---|---|
| Lviv city bbox, the fixture | 3.78 MiB | 68 B | 4.78 MiB |
| Lviv bbox | 15.76 MiB | 66 B | 18.07 MiB |
| Lviv bbox, places only (137,126) | 8.24 MiB | 63 B | 8.04 MiB |
| Ukraine | 133.36 MiB (139.8 MB) | 66 B | 159.56 MiB |
| Ukraine, places only (928,666) | 54.85 MiB | 62 B | 55.03 MiB |

The P4a estimate for Ukraine was 100-160 MB. The measured whole-country search
section is 139.8 MB, inside that band near its top. The composition differs
from the estimate: this extractor indexes 928,666 places (named POIs and named
streets; relations are skipped) and 1,206,286 house rows, 2.13 M rows in all.
The places alone are 54.85 MiB; the houses add 78.51 MiB.

Licence: the output is derived from OpenStreetMap data, ODbL-1.0, ©
OpenStreetMap contributors. `ATTRIBUTION.md` records the committed fixtures.

### Real stages and stand-ins

Reused from the real pipeline:

- the Planetiler vector tile build (`ukraine-final.pmtiles`)
- the Copernicus GLO-30 contour build (`lviv-90m-10m.pmtiles`, 10 m lines from the 90 m DEM)
- the `pmtiles extract` split stage that real oblast packs use

Built here rather than reused:

- the search section and its feature component, from the authored place list
  through `rust/grass-core`'s `build-section`
- the elevation grid, from the GLO-30 source tiles through
  `build-elevation.py grid`

No routing data is included: the fixture has no engine graph. Routing tiles are
a part of each region package in the design (data-formats.md section 2).

### Manifest

`manifest.json` follows `data-formats.md` section 2: region, version, package
format, OSM timestamp, and per component the format and its version, size,
sha256, stand-in flag and zoom levels. `pipeline` records the source archive
sha256, the bounding box and the GLO-30 tile hashes, so the package can be
traced back to the bench build.

### Licensing

The map and contour tiles are derived from OpenStreetMap data (ODbL-1.0, ©
OpenStreetMap contributors) and Copernicus DEM GLO-30. `ATTRIBUTION.md` records
both.
