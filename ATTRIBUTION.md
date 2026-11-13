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
| Photon public geocoder (`photon.komoot.io`) | Apache-2.0 (service software); ODbL-1.0 (results) | Online place search (T-0212); used under the public instance's fair-use terms, results are © OpenStreetMap contributors, and the search screen names both |
| `test-data/regions/fixture-mini` map tiles | ODbL-1.0 | © OpenStreetMap contributors; extracted from the bench Ukraine build |
| `test-data/regions/fixture-mini/way-alerts.osm.pbf` and `test-data/regions/lviv-way-alerts` | ODbL-1.0 | © OpenStreetMap contributors; the fixture's authored OSM input and the central-Lviv probe extracted from the 2026-11-04 Geofabrik Ukraine extract (sha256 `ff46165a…`) |
| `test-data/regions/fixture-mini/route-relations.osm.pbf` and `test-data/regions/lviv-hiking-relations` | ODbL-1.0 | © OpenStreetMap contributors; the fixture's hiking and cycling route input (real way geometry from the fixture bbox, authored route relations and node numbers) and the Skole probe extracted from the same Ukraine extract (sha256 `ff46165a…`) |
| `test-data/regions/fixture-mini` contours | Copernicus DEM GLO-30 terms | © DLR e.V. and © Airbus Defence and Space GmbH, provided under Copernicus by the EU and ESA |
| `test-data/regions/fixture-mini` elevation grid | Copernicus DEM GLO-30 terms | © DLR e.V. and © Airbus Defence and Space GmbH, provided under Copernicus by the EU and ESA |
| `test-data/map-goldens` screenshots | ODbL-1.0 / Copernicus DEM GLO-30 terms | Rendered captures of the `fixture-mini` map tiles and contours for the T-0102 fixture and T-0108 Standard style golden tests |
| Noto fonts | SIL OFL 1.1 | map labels and UI text; `app/src/main/assets/fonts` uses the Protomaps basemaps-assets Noto Sans glyph PBFs |
| `app/src/main/assets/sprites` Standard sprite | Apache-2.0 | one single-colour SDF sheet shared by the light and dark Standard variants; of its 57 glyphs, 37 are Google Material Symbols Rounded, 2 are Osmic, and 18 are route-relation OSMC plates and shapes authored here; the vendored glyphs are unmodified, and the 15 shield frames are authored here. The per-icon source, licence, commit and sha256 are listed below |
| valhalla-mobile | MIT | Android/iOS wrapper around the Valhalla engine, used by `plugins/valhalla` |
| Ferrostar core | BSD-3-Clause | Rust route-following core with UniFFI Kotlin bindings, used by `navigation`; the `core` artifact only, no UI or map binding |
| Valhalla routing engine | MIT | C++ engine packaged inside valhalla-mobile; routing tiles are OSM-derived data (ODbL-1.0) |
| `plugins/style-standard` Standard style | Apache-2.0 (this repository) | Authored for Grass Maps from the T-0020 Protomaps-derived layer list and the phase-A palette; OsmAnd's standard style is design inspiration only, no GPL content is translated |
| `test-data/location/mock-walk.gpx` | Apache-2.0 (this repository) | Synthetic 60-point GPX walk authored for T-0022; no OSM data |
| `test-data/tracks/*.gpx` | Apache-2.0 (this repository) | Synthetic OsmAnd-, Garmin- and Strava-shaped exports authored for T-0401, including two damaged files; no OSM data and no real activity |
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
this table.

Thirty-seven of the 57 glyphs are Google Material Symbols Rounded,
Apache-2.0, taken from commit `10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7`
(2026-10-23) of
<https://github.com/google/material-design-icons>. Each file is the weight-400
`fill1` 24 px variant, vendored byte-for-byte, except `electronics`, which uses
the outlined `tv` because the filled TV collapses into a solid block at map
size. The URL in each row is the exact file at that commit; the sha256 is of
the file as vendored. Two more glyphs, `animal` and `bench`, are Osmic,
CC0-1.0, listed after the table. The 18 `osmc_*` plates and shapes are authored
in this repository for the hiking route-relation layer (T-0418) from the
documented `osmc:symbol` grammar; no third-party OSMC symbol asset is copied,
and they are Apache-2.0 like the rest of it. The 15 road-shield frames are
authored here too.

| Icon | Material symbol | Source path | Licence | Commit | sha256 |
|---|---|---|---|---|---|
| `aerodrome` | `flight` | [`symbols/web/flight/materialsymbolsrounded/flight_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/flight/materialsymbolsrounded/flight_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `0749f1226aa2bd1942e3c73f571fe8a0ef445cb1119b924f01e2c4d1eff9bd72` |
| `arrow` | `navigation` | [`symbols/web/navigation/materialsymbolsrounded/navigation_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/navigation/materialsymbolsrounded/navigation_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `56d76dae9410898a4a7cd03db02138884c76253211a4e897b5c8df0a8a6eb3ac` |
| `artwork` | `palette` | [`symbols/web/palette/materialsymbolsrounded/palette_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/palette/materialsymbolsrounded/palette_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `d058ad95558c491154a9239ce9eb03c3bb1ceb06c709dd4889b882773fa7b7b7` |
| `attraction` | `star` | [`symbols/web/star/materialsymbolsrounded/star_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/star/materialsymbolsrounded/star_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `513a0f450ac62c6bdc6c6cb8701896ea4ac7a82121b9e19cc52596fd7ce3b5fc` |
| `bar` | `local_bar` | [`symbols/web/local_bar/materialsymbolsrounded/local_bar_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/local_bar/materialsymbolsrounded/local_bar_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `a6c0bef0e7428aec22bb638eb4e149b674e551accc37aaab500b8f13ed1cc03b` |
| `beach` | `beach_access` | [`symbols/web/beach_access/materialsymbolsrounded/beach_access_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/beach_access/materialsymbolsrounded/beach_access_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `d0340d513024aad80c70200a0099b66c78e76fce3a5cf6f55e40f8fec040ceee` |
| `beauty` | `content_cut` | [`symbols/web/content_cut/materialsymbolsrounded/content_cut_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/content_cut/materialsymbolsrounded/content_cut_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `5d2dab4959bb57628e5dc609d08ec356514c90f04456832eb0115e5974057df7` |
| `books` | `menu_book` | [`symbols/web/menu_book/materialsymbolsrounded/menu_book_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/menu_book/materialsymbolsrounded/menu_book_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `5c777c23b8535d621de7d5c2edaed226613d5864b84e8e9118beac596e615e69` |
| `building` | `apartment` | [`symbols/web/apartment/materialsymbolsrounded/apartment_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/apartment/materialsymbolsrounded/apartment_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `e4385e82c3e0b686407af7b0529c8076a4ade779b126302ab4f687a5e48424e0` |
| `bus_stop` | `directions_bus` | [`symbols/web/directions_bus/materialsymbolsrounded/directions_bus_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/directions_bus/materialsymbolsrounded/directions_bus_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `14bf0043a7af68a2fa5bd232e8ae5b481e96507f586b5137d7addf6a6e2564b0` |
| `cafe` | `local_cafe` | [`symbols/web/local_cafe/materialsymbolsrounded/local_cafe_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/local_cafe/materialsymbolsrounded/local_cafe_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `cafefce9df21a834d4ba282a0d2640ae3ab1aa0bd96750bf06b21c000320858c` |
| `capital` | `circle` | [`symbols/web/circle/materialsymbolsrounded/circle_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/circle/materialsymbolsrounded/circle_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `9b27570d46ef5626156a81ce051a563a588f27f68fbf3076547f0ec823a9cb6f` |
| `clothes` | `checkroom` | [`symbols/web/checkroom/materialsymbolsrounded/checkroom_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/checkroom/materialsymbolsrounded/checkroom_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `ba5a64a10a3ab74cb4b2c8ab7b3cd4765775c6acf32e07de0f0351a0ad45f2de` |
| `convenience` | `shopping_basket` | [`symbols/web/shopping_basket/materialsymbolsrounded/shopping_basket_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/shopping_basket/materialsymbolsrounded/shopping_basket_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `2f325149d67b303aa44eb71821e60095b67f60764c95f65416e04a0fddfbc460` |
| `drinking_water` | `water_drop` | [`symbols/web/water_drop/materialsymbolsrounded/water_drop_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/water_drop/materialsymbolsrounded/water_drop_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `b16dbaebe4927e7b9b5b6b199dce49f77a83191ae7a7902a3fb6ee5d51265e10` |
| `electronics` | `tv` | [`symbols/web/tv/materialsymbolsrounded/tv_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/tv/materialsymbolsrounded/tv_24px.svg) | Apache-2.0 | `10ecc1479061` | `b71c6f73dc5a1958ab8838f92625b310667c07b40f4bcdd3c610339d2e39d904` |
| `fast_food` | `fastfood` | [`symbols/web/fastfood/materialsymbolsrounded/fastfood_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/fastfood/materialsymbolsrounded/fastfood_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `f944c4bc358c657ef92d7c7eac12274214a54a6b67db2be99e16b13e37afd240` |
| `ferry_terminal` | `directions_boat` | [`symbols/web/directions_boat/materialsymbolsrounded/directions_boat_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/directions_boat/materialsymbolsrounded/directions_boat_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `d0aa6f2336ff9a1e75f00ea5256440eef44074749693d67d58df36786ada5ba7` |
| `forest` | `forest` | [`symbols/web/forest/materialsymbolsrounded/forest_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/forest/materialsymbolsrounded/forest_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `6ed7036b7019b94e7fe2b6fac0f3219bb94c1e0f111ee4f5731594e0fb9dbe84` |
| `garden` | `local_florist` | [`symbols/web/local_florist/materialsymbolsrounded/local_florist_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/local_florist/materialsymbolsrounded/local_florist_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `8f532ce13386a2b3c88cb5b80d801018f381319f9773289ead41abc31c45a0db` |
| `library` | `local_library` | [`symbols/web/local_library/materialsymbolsrounded/local_library_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/local_library/materialsymbolsrounded/local_library_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `cd8bc3347679652b29850bcac6e13c55c4d1e37f1caa1066d24422651741af4a` |
| `marina` | `anchor` | [`symbols/web/anchor/materialsymbolsrounded/anchor_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/anchor/materialsymbolsrounded/anchor_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `ee0be1c20a35b7f0c5ec4f443f27818d8dfbff63cf226157e9ab14b256d0bcca` |
| `museum` | `museum` | [`symbols/web/museum/materialsymbolsrounded/museum_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/museum/materialsymbolsrounded/museum_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `f05ad985ec3595614c31f6055d2e5b91d904a2b47eabcbd99af83d3cb64cb1d6` |
| `park` | `park` | [`symbols/web/park/materialsymbolsrounded/park_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/park/materialsymbolsrounded/park_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `53691cc139f527e0b654352b2ac5e2a654a2915e038cd2951726a821384a504e` |
| `peak` | `landscape` | [`symbols/web/landscape/materialsymbolsrounded/landscape_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/landscape/materialsymbolsrounded/landscape_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `0ded699d6b2bbed0395e6ebede13d709328d5dbc5a676602d6660ae2b287a820` |
| `post_office` | `local_post_office` | [`symbols/web/local_post_office/materialsymbolsrounded/local_post_office_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/local_post_office/materialsymbolsrounded/local_post_office_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `79c58d2904ea40293309029871012c63068a0a6a3ec433b5da3bdb936ebc5496` |
| `restaurant` | `restaurant` | [`symbols/web/restaurant/materialsymbolsrounded/restaurant_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/restaurant/materialsymbolsrounded/restaurant_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `45cb5f6db38ce6d7e8755f211a3fa0b6f5cf6cb54f7a66c78ae42235496f9b8b` |
| `school` | `school` | [`symbols/web/school/materialsymbolsrounded/school_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/school/materialsymbolsrounded/school_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `646355683fd4f5e651793944f437e638fb6838934eb948d422453b283ff4ef7a` |
| `stadium` | `sports_soccer` | [`symbols/web/sports_soccer/materialsymbolsrounded/sports_soccer_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/sports_soccer/materialsymbolsrounded/sports_soccer_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `f04c114207da523d2c8d69727e169e29c97cca31bd78a18febe63c6ab31e2989` |
| `supermarket` | `shopping_cart` | [`symbols/web/shopping_cart/materialsymbolsrounded/shopping_cart_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/shopping_cart/materialsymbolsrounded/shopping_cart_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `6c30a8f242422626aff151755502ca0ac447436c8a66988039a25ab163fbbf00` |
| `theatre` | `theater_comedy` | [`symbols/web/theater_comedy/materialsymbolsrounded/theater_comedy_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/theater_comedy/materialsymbolsrounded/theater_comedy_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `3a0a1dfe969e3742353eb7afa5e0e54aef8ecc495162dc2771fdfdb397c5d2d0` |
| `toilets` | `wc` | [`symbols/web/wc/materialsymbolsrounded/wc_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/wc/materialsymbolsrounded/wc_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `c837e4c47c6e200948d19a64008a48a7878625fe713b6e7d57e8ceda28d31b93` |
| `townhall` | `location_city` | [`symbols/web/location_city/materialsymbolsrounded/location_city_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/location_city/materialsymbolsrounded/location_city_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `37fc44b5fa347903f538a558f10355cd7f5addd4fbe963910a69daa783944d05` |
| `townspot` | `square` | [`symbols/web/square/materialsymbolsrounded/square_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/square/materialsymbolsrounded/square_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `b53e6f9dcd8c7e412a54727e236e0458ef70a8ab09ef32ea69dcd56bb8cec0b8` |
| `train_station` | `train` | [`symbols/web/train/materialsymbolsrounded/train_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/train/materialsymbolsrounded/train_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `47e8a0a4bf2811ac86cfdf0ebaf046443dc3c99d38f7bdd99d1fb4beaa1d90be` |
| `university` | `account_balance` | [`symbols/web/account_balance/materialsymbolsrounded/account_balance_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/account_balance/materialsymbolsrounded/account_balance_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `036b151d3b3e53f72396d6d15d1560750f0146614cbf4c3f671969701f1fb6dc` |
| `zoo` | `pets` | [`symbols/web/pets/materialsymbolsrounded/pets_fill1_24px.svg`](https://raw.githubusercontent.com/google/material-design-icons/10ecc14790612ca8fd164e3f4ffe9c16fc50d5b7/symbols/web/pets/materialsymbolsrounded/pets_fill1_24px.svg) | Apache-2.0 | `10ecc1479061` | `36de9fdf4f0dd9b9df0240bd3a88ad26a648bfbffe8fd53caf1de4a8f4114fb9` |

### Osmic glyphs

Two glyphs are Osmic, CC0-1.0, taken from commit
`94b418619c72fd2caa0edfa08c7a959ac3f48b2a` of
<https://github.com/gmgeo/osmic> and vendored byte-for-byte. Commit `476a666`
replaced them with Material Symbols Rounded glyphs; this revision restored the
Osmic files because the 13 px icon review chose them by eye. The Material
`cruelty_free` and `chair` did not read as the map's animal and bench, and
`chair` in particular reads as furniture rather than a bench. CC0 imposes no
attribution obligation, but the source and commit are recorded here so the
files stay traceable to their upstream.

| Icon | Upstream path | Licence | Commit | sha256 |
|---|---|---|---|---|
| `animal` | [`shop/pet-14.svg`](https://raw.githubusercontent.com/gmgeo/osmic/94b418619c72fd2caa0edfa08c7a959ac3f48b2a/shop/pet-14.svg) | CC0-1.0 | `94b418619c` | `d7a1014b983f149806f779c2a9f4ac6655d946ad9d005232b1dc8e3d2d46146d` |
| `bench` | [`outdoor/bench-14.svg`](https://raw.githubusercontent.com/gmgeo/osmic/94b418619c72fd2caa0edfa08c7a959ac3f48b2a/outdoor/bench-14.svg) | CC0-1.0 | `94b418619c` | `535be45fb80bf0f4d36d3e86a5a87239201a6b75e36ff595813d09d627a00fea` |

`stadium` used the Material `stadium` glyph at `476a666` and now uses the
Material `sports_soccer` glyph. The same review chose it, because the `stadium`
glyph reads as an empty bowl at 13 px.

### Route-relation OSMC glyphs

The eighteen `osmc_*` glyphs were authored for T-0418. They draw the
background plate and foreground shape that an `osmc:symbol` value names, so the
hiking route layer needs no artefact from a differently licensed shield set.
The Waymarked Trails shield renderer (`waymarkedtrails-shields`) was checked
and not used: its repository is GPL-3.0. These shapes are plain geometry
written here, not a copy or a trace of that work, and the OSMC grammar they
implement is the documented tag value.

| Icon | Source path | Licence |
|---|---|---|
| `osmc_bg_square` | `tools/sprites/icons/osmc_bg_square.svg` | Apache-2.0 (this repository) |
| `osmc_bg_round` | `tools/sprites/icons/osmc_bg_round.svg` | Apache-2.0 (this repository) |
| `osmc_bg_circle` | `tools/sprites/icons/osmc_bg_circle.svg` | Apache-2.0 (this repository) |
| `osmc_bg_frame` | `tools/sprites/icons/osmc_bg_frame.svg` | Apache-2.0 (this repository) |
| `osmc_fg_bar` | `tools/sprites/icons/osmc_fg_bar.svg` | Apache-2.0 (this repository) |
| `osmc_fg_corner` | `tools/sprites/icons/osmc_fg_corner.svg` | Apache-2.0 (this repository) |
| `osmc_fg_backslash` | `tools/sprites/icons/osmc_fg_backslash.svg` | Apache-2.0 (this repository) |
| `osmc_fg_slash` | `tools/sprites/icons/osmc_fg_slash.svg` | Apache-2.0 (this repository) |
| `osmc_fg_diamond` | `tools/sprites/icons/osmc_fg_diamond.svg` | Apache-2.0 (this repository) |
| `osmc_fg_dot` | `tools/sprites/icons/osmc_fg_dot.svg` | Apache-2.0 (this repository) |
| `osmc_fg_cross` | `tools/sprites/icons/osmc_fg_cross.svg` | Apache-2.0 (this repository) |
| `osmc_fg_x` | `tools/sprites/icons/osmc_fg_x.svg` | Apache-2.0 (this repository) |
| `osmc_fg_triangle` | `tools/sprites/icons/osmc_fg_triangle.svg` | Apache-2.0 (this repository) |
| `osmc_fg_triangle_turned` | `tools/sprites/icons/osmc_fg_triangle_turned.svg` | Apache-2.0 (this repository) |
| `osmc_fg_stripe` | `tools/sprites/icons/osmc_fg_stripe.svg` | Apache-2.0 (this repository) |
| `osmc_fg_lower` | `tools/sprites/icons/osmc_fg_lower.svg` | Apache-2.0 (this repository) |
| `osmc_fg_rectangle` | `tools/sprites/icons/osmc_fg_rectangle.svg` | Apache-2.0 (this repository) |
| `osmc_fg_right` | `tools/sprites/icons/osmc_fg_right.svg` | Apache-2.0 (this repository) |

### Shield frames

The 15 road-shield frames are authored here; they are not glyphs and were
not touched by the Material Symbols change.

| Icon | Source | Source path | Licence | Commit |
|---|---|---|---|---|
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
