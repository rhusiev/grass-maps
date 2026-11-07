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
| Noto fonts | SIL OFL 1.1 | map labels and UI text; `app/src/main/assets/fonts` uses the Protomaps basemaps-assets Noto Sans glyph PBFs |
| Protomaps sprites | MIT | `app/src/main/assets/sprites` (light sprite sheet, basemaps-assets) |
| valhalla-mobile | MIT | Android/iOS wrapper around the Valhalla engine, used by `routing` |
| Ferrostar core | BSD-3-Clause | Rust route-following core with UniFFI Kotlin bindings, used by `navigation`; the `core` artifact only, no UI or map binding |
| Valhalla routing engine | MIT | C++ engine packaged inside valhalla-mobile; routing tiles are OSM-derived data (ODbL-1.0) |
| `plugins/style-standard` bench style | BSD-3-Clause | The T-0020 phase-A dense style generated from protomaps/basemaps; replaced by the authored styles in T-0603 |
| `test-data/location/mock-walk.gpx` | Apache-2.0 (this repository) | Synthetic 60-point GPX walk authored for T-0022; no OSM data |
| Icons | chosen per icon set | the licence is recorded in this file before the icons land |

Every asset, font, style and sample data file gets its attribution in the same
change that adds it. The code dependency allowlist is in `AGENTS.md`.
