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
| `app/src/main/assets/sprites` Standard sprite | CC0-1.0 / Apache-2.0 | one single-colour SDF sheet shared by the light and dark Standard variants; glyphs from Osmic, OSM Carto, Temaki and Maki, shield frames authored here. The per-icon source, licence and commit are listed below |
| valhalla-mobile | MIT | Android/iOS wrapper around the Valhalla engine, used by `plugins/valhalla` |
| Ferrostar core | BSD-3-Clause | Rust route-following core with UniFFI Kotlin bindings, used by `navigation`; the `core` artifact only, no UI or map binding |
| Valhalla routing engine | MIT | C++ engine packaged inside valhalla-mobile; routing tiles are OSM-derived data (ODbL-1.0) |
| `plugins/style-standard` Standard style | Apache-2.0 (this repository) | Authored for Grass Maps from the T-0020 Protomaps-derived layer list and the phase-A palette; OsmAnd's standard style is design inspiration only, no GPL content is translated |
| `test-data/location/mock-walk.gpx` | Apache-2.0 (this repository) | Synthetic 60-point GPX walk authored for T-0022; no OSM data |
| `test-data/places/opening-hours/lviv-oracle.tsv` | ODbL-1.0 | © OpenStreetMap contributors; `opening_hours` values from the Lviv bench extract and their expected states, computed once with opening_hours.js 3.15.0 (LGPL-3.0, used only as a local generator and not distributed) |
| `places/src/test/resources/search/lviv` search index | ODbL-1.0 | © OpenStreetMap contributors; the Lviv search section built by the T-0005/P4a spike from `lviv-places.sqlite` (2026-11-04 input) |
| `places/src/test/resources/search/lviv-addresses` search index and `search/addresses.tsv` | ODbL-1.0 | © OpenStreetMap contributors; the Lviv city search section (places and house numbers) and its 50-probe query set, built by `tools/pipeline/osm-extract` from the 2026-11-04 Geofabrik Ukraine extract (sha256 `ff46165a…`) |
| `places/src/test/resources/search/zolochiv` search index | ODbL-1.0 | © OpenStreetMap contributors; the town of Золочів and its surroundings, built by `tools/pipeline/osm-extract` and `rust/grass-core`'s `build-section` from the same 2026-11-04 Geofabrik Ukraine extract (sha256 `ff46165a…`), places only |
| `rust/grass-core/data/transliteration.tsv` | Apache-2.0 (this repository) | Authored for T-0205 from the Cabinet of Ministers resolution 55 (2010) transliteration rules and a documented pragmatic variant; no OSM content |
| `test-data/search/normalisation-vectors.tsv` | Apache-2.0 (this repository) | Authored for T-0205; place names only as inputs, no derived database |
| `osm-extract` (pipeline tool) | this repository; `osmpbf` MIT OR Apache-2.0 | `tools/pipeline/osm-extract`; OSM-derived output is ODbL-1.0 |
| Rust crates used by `rust/grass-core` | Unlicense/MIT (`fst`), MIT (`levenshtein_automata`), MIT/Apache-2.0 (`memmap2`, `uniffi`, `thiserror`), MIT OR Apache-2.0 AND Unicode-3.0 (`unicode-normalization`) | See `rust/grass-core/Cargo.lock` |
| JNA | Apache-2.0 OR LGPL-2.1 | uniffi Kotlin bindings runtime (JVM and Android), used by `places` |
| Icons | per-icon table below | the Standard map sprite glyphs and their licences |

## Standard sprite icons

`app/src/main/assets/sprites/standard.json` names every icon in the single SDF
sheet. `tools/sprites/build_sdf_sprite.py` builds the sheet from the SVGs under
`tools/sprites/`; `tools/sprites/sources.json` is the machine-readable copy of
this table. The arrow is the OSM Carto oneway arrow rotated 90 degrees so the
glyph points up, because the style's line placement adds `icon-rotate: 90`.

| Icon | Source | Source path | Licence | Commit |
|---|---|---|---|---|
| `aerodrome` | Osmic | `transport/airport-14.svg` | CC0-1.0 | `94b418619c72` |
| `animal` | Osmic | `shop/pet-14.svg` | CC0-1.0 | `94b418619c72` |
| `arrow` | OSM Carto | `symbols/oneway.svg` | CC0-1.0 | `003cefdd6791` |
| `artwork` | Maki | `icons/art-gallery.svg` | CC0-1.0 | `28e2a3602e4b` |
| `attraction` | Maki | `icons/star.svg` | CC0-1.0 | `28e2a3602e4b` |
| `bar` | Osmic | `eat-drink/bar-14.svg` | CC0-1.0 | `94b418619c72` |
| `beach` | Maki | `icons/beach.svg` | CC0-1.0 | `28e2a3602e4b` |
| `beauty` | Osmic | `shop/beauty-14.svg` | CC0-1.0 | `94b418619c72` |
| `bench` | Osmic | `outdoor/bench-14.svg` | CC0-1.0 | `94b418619c72` |
| `books` | Osmic | `shop/books-14.svg` | CC0-1.0 | `94b418619c72` |
| `building` | Maki | `icons/building.svg` | CC0-1.0 | `28e2a3602e4b` |
| `bus_stop` | Osmic | `transport/bus-stop-14.svg` | CC0-1.0 | `94b418619c72` |
| `cafe` | Osmic | `eat-drink/cafe-14.svg` | CC0-1.0 | `94b418619c72` |
| `capital` | Maki | `icons/circle.svg` | CC0-1.0 | `28e2a3602e4b` |
| `clothes` | Osmic | `shop/clothes-14.svg` | CC0-1.0 | `94b418619c72` |
| `convenience` | Osmic | `shop/convenience-14.svg` | CC0-1.0 | `94b418619c72` |
| `drinking_water` | Osmic | `outdoor/drinking-water-14.svg` | CC0-1.0 | `94b418619c72` |
| `electronics` | Osmic | `shop/electronics-14.svg` | CC0-1.0 | `94b418619c72` |
| `fast_food` | Osmic | `eat-drink/fast-food-14.svg` | CC0-1.0 | `94b418619c72` |
| `ferry_terminal` | Osmic | `transport/ferry-14.svg` | CC0-1.0 | `94b418619c72` |
| `forest` | Osmic | `nature/tree-coniferous-14.svg` | CC0-1.0 | `94b418619c72` |
| `garden` | Temaki | `icons/garden_bed.svg` | CC0-1.0 | `6d9ac860d1d6` |
| `library` | Osmic | `amenity/library-14.svg` | CC0-1.0 | `94b418619c72` |
| `marina` | Maki | `icons/harbor.svg` | CC0-1.0 | `28e2a3602e4b` |
| `museum` | Osmic | `tourism/museum-14.svg` | CC0-1.0 | `94b418619c72` |
| `park` | Maki | `icons/park.svg` | CC0-1.0 | `28e2a3602e4b` |
| `peak` | Osmic | `nature/peak-14.svg` | CC0-1.0 | `94b418619c72` |
| `post_office` | Osmic | `amenity/post-office-14.svg` | CC0-1.0 | `94b418619c72` |
| `restaurant` | Osmic | `eat-drink/restaurant-14.svg` | CC0-1.0 | `94b418619c72` |
| `school` | Maki | `icons/school.svg` | CC0-1.0 | `28e2a3602e4b` |
| `stadium` | Maki | `icons/stadium.svg` | CC0-1.0 | `28e2a3602e4b` |
| `supermarket` | Osmic | `shop/supermarket-14.svg` | CC0-1.0 | `94b418619c72` |
| `theatre` | Osmic | `tourism/theatre-14.svg` | CC0-1.0 | `94b418619c72` |
| `toilets` | Osmic | `amenity/toilets-14.svg` | CC0-1.0 | `94b418619c72` |
| `townhall` | Osmic | `administration/town-hall-14.svg` | CC0-1.0 | `94b418619c72` |
| `townspot` | Maki | `icons/square.svg` | CC0-1.0 | `28e2a3602e4b` |
| `train_station` | Osmic | `transport/railway-station-14.svg` | CC0-1.0 | `94b418619c72` |
| `university` | Maki | `icons/college.svg` | CC0-1.0 | `28e2a3602e4b` |
| `zoo` | Temaki | `icons/zoo.svg` | CC0-1.0 | `6d9ac860d1d6` |
| `NL:S-road-1char` | this repository | `tools/sprites/shields/NL:S-road-1char.svg` | Apache-2.0 | - |
| `NL:S-road-2char` | this repository | `tools/sprites/shields/NL:S-road-2char.svg` | Apache-2.0 | - |
| `NL:S-road-3char` | this repository | `tools/sprites/shields/NL:S-road-3char.svg` | Apache-2.0 | - |
| `NL:S-road-4char` | this repository | `tools/sprites/shields/NL:S-road-4char.svg` | Apache-2.0 | - |
| `NL:S-road-5char` | this repository | `tools/sprites/shields/NL:S-road-5char.svg` | Apache-2.0 | - |
| `US:I-1char` | this repository | `tools/sprites/shields/US:I-1char.svg` | Apache-2.0 | - |
| `US:I-2char` | this repository | `tools/sprites/shields/US:I-2char.svg` | Apache-2.0 | - |
| `US:I-3char` | this repository | `tools/sprites/shields/US:I-3char.svg` | Apache-2.0 | - |
| `US:I-4char` | this repository | `tools/sprites/shields/US:I-4char.svg` | Apache-2.0 | - |
| `US:I-5char` | this repository | `tools/sprites/shields/US:I-5char.svg` | Apache-2.0 | - |
| `generic_shield-1char` | this repository | `tools/sprites/shields/generic_shield-1char.svg` | Apache-2.0 | - |
| `generic_shield-2char` | this repository | `tools/sprites/shields/generic_shield-2char.svg` | Apache-2.0 | - |
| `generic_shield-3char` | this repository | `tools/sprites/shields/generic_shield-3char.svg` | Apache-2.0 | - |
| `generic_shield-4char` | this repository | `tools/sprites/shields/generic_shield-4char.svg` | Apache-2.0 | - |
| `generic_shield-5char` | this repository | `tools/sprites/shields/generic_shield-5char.svg` | Apache-2.0 | - |

Every asset, font, style and sample data file gets its attribution in the same
change that adds it. The code dependency allowlist is in `AGENTS.md`.
