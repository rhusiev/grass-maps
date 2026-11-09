# Attribution

This file lists the licence of every third-party asset and data set. The only
bundled data is the small test region under `test-data/`; the entries below it
are the planned sets, recorded here so their licences are checked before the
first product asset lands.

| Asset or data | Licence | Notes |
|---|---|---|
| MapLibre Native | BSD-2-Clause | map renderer, reached through maplibre-compose |
| Protomaps basemaps | BSD-3-Clause | vector basemap schema and build tooling |
| OpenStreetMap data | ODbL-1.0 | © OpenStreetMap contributors; region packs are derived data |
| `test-data/regions/fixture-mini` map tiles | ODbL-1.0 | © OpenStreetMap contributors; extracted from the bench Ukraine build |
| `test-data/regions/fixture-mini` contours | Copernicus DEM GLO-30 terms | © DLR e.V. and © Airbus Defence and Space GmbH, provided under Copernicus by the EU and ESA |
| `test-data/map-goldens` screenshots | ODbL-1.0 / Copernicus DEM GLO-30 terms | Rendered captures of the `fixture-mini` map tiles and contours for the T-0102 fixture and T-0108 Standard style golden tests |
| Noto fonts | SIL OFL 1.1 | map labels and UI text; `app/src/main/assets/fonts` uses the Protomaps basemaps-assets Noto Sans glyph PBFs |
| Protomaps sprites | MIT | `app/src/main/assets/sprites` (light sprite sheet, basemaps-assets) |
| valhalla-mobile | MIT | Android/iOS wrapper around the Valhalla engine, used by `routing` |
| Ferrostar core | BSD-3-Clause | Rust route-following core with UniFFI Kotlin bindings, used by `navigation`; the `core` artifact only, no UI or map binding |
| Valhalla routing engine | MIT | C++ engine packaged inside valhalla-mobile; routing tiles are OSM-derived data (ODbL-1.0) |
| `plugins/style-standard` Standard style | Apache-2.0 (this repository) | Authored for Grass Maps from the T-0020 Protomaps-derived layer list and the phase-A palette; OsmAnd's standard style is design inspiration only, no GPL content is translated |
| `test-data/location/mock-walk.gpx` | Apache-2.0 (this repository) | Synthetic 60-point GPX walk authored for T-0022; no OSM data |
| `test-data/places/opening-hours/lviv-oracle.tsv` | ODbL-1.0 | © OpenStreetMap contributors; `opening_hours` values from the Lviv bench extract and their expected states, computed once with opening_hours.js 3.15.0 (LGPL-3.0, used only as a local generator and not distributed) |
| `places/src/test/resources/search/lviv` search index | ODbL-1.0 | © OpenStreetMap contributors; the Lviv search section built by the T-0005/P4a spike from `lviv-places.sqlite` (2026-11-04 input) |
| `places/src/test/resources/search/lviv-addresses` search index and `search/addresses.tsv` | ODbL-1.0 | © OpenStreetMap contributors; the Lviv city search section (places and house numbers) and its 50-probe query set, built by `tools/pipeline/osm-extract` from the 2026-11-04 Geofabrik Ukraine extract (sha256 `ff46165a…`) |
| `osm-extract` (pipeline tool) | this repository; `osmpbf` MIT OR Apache-2.0 | `tools/pipeline/osm-extract`; OSM-derived output is ODbL-1.0 |
| Rust crates used by `rust/grass-core` | Unlicense/MIT (`fst`), MIT (`levenshtein_automata`), MIT/Apache-2.0 (`memmap2`, `uniffi`, `thiserror`), MIT OR Apache-2.0 AND Unicode-3.0 (`unicode-normalization`) | See `rust/grass-core/Cargo.lock` |
| JNA | Apache-2.0 OR LGPL-2.1 | uniffi Kotlin bindings runtime (JVM and Android), used by `places` |
| Icons | chosen per icon set | the licence is recorded in this file before the icons land |

Every asset, font, style and sample data file gets its attribution in the same
change that adds it. The code dependency allowlist is in `AGENTS.md`.
