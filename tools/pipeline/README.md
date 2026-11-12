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
`--pmtiles` when the bench repository lives elsewhere. The search section and
its feature component come from `cargo run --bin build-section`, so the script
also needs the Rust toolchain; set `CARGO_HOME` to its real path when it is
reached through a symlink. The output is deterministic: the same source
archives and the same builder produce the same bytes and the same manifest.
The committed package predates the version-2 search section, so re-running the
script now writes `format.bin` and a version-2 manifest and does not reproduce
the committed bytes until the package is regenerated and committed.

The package is committed because tests must run without the bench repository or
a multi-gigabyte download. The manifest records each file's size and sha256;
`TestRegionFixtureTest` fails when a committed byte changes.

### What the package contains

| Component | Source | Stand-in |
|---|---|---|
| `map.pmtiles` | `ukraine-final.pmtiles`, Protomaps basemap profile, z12-14 | no |
| `contours.pmtiles` | `lviv-90m-20m.pmtiles`, Copernicus GLO-30 contours, z9-14 | no |
| `elevation.bin` | generated marker file | yes |
| `format.bin`, `terms.fst`, `postings.bin`, `places.bin`, `context.bin`, `entities.bin`, `kinds.bin`, `strings.bin` | the search section, built by `rust/grass-core` from `fixture-places.tsv`; a version-2 build writes `format.bin`, the committed version-1 package has no such file | no |
| `features.bin` | the feature component, built in the same pass | no |
| `way-alerts.geojson` | authored GeoJSON with one railway crossing, one tunnel, one hazard and one off-corridor hazard; the OSM extract that will write it from `railway=level_crossing`, `tunnel=*` and `hazard`/`ford`/`barrier` tags is not built yet | yes |

The bounding box is `23.940,49.940,23.960,49.960` (about 1.5 km around
Ivano-Frankove, Lviv oblast). It is rural, so its z12-14 tiles stay small; the
place has roads, paths and land cover but no dense city centre. `map.pmtiles`
starts at z12 instead of the product's z8 because the z8 tile would dominate the
fixture's size while adding nothing a unit test needs.

The elevation stand-in exists so the manifest and mount checks have something to
read. It is not in the real GLO-30 format; the manifest marks it
`"standIn": true`, and a test asserts that flag so nobody mistakes it for real
data. It is replaced when the elevation pipeline lands.

### Way-alert features (T-0319)

`way-alerts.geojson` is a GeoJSON `FeatureCollection` read by
`regions.RegionReader` and the way-alert plugin. Each feature carries `kind`
(`railway_crossing`, `tunnel`, `hazard`, `ford` or `barrier`), `osmType`
(`node` or `way`) and `osmId`; a node is a `Point`, a tunnel is a
`LineString`. The four authored features sit on, or just off, the straight
synthetic test route from `49.949, 23.943` to `49.949, 23.953`: a crossing at
`23.947`, a tunnel from `23.949` to `23.951`, a hazard at `23.952` and a
second hazard `167 m` north of the line, outside the `30 m` corridor.

The component is a stand-in because the region pipeline does not extract way
features from the OSM PBF yet. When it does, it will select
`railway=level_crossing` nodes, `tunnel=*` ways and `hazard=*`/`ford=*`/
`barrier=*` nodes into the same file, and the manifest's `standIn` flag will
turn false. Keeping the authored file means the JVM tests exercise the real
reader and query rather than a fake source.

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
- the Copernicus GLO-30 contour build (`lviv-90m-20m.pmtiles`)
- the `pmtiles extract` split stage that real oblast packs use

Built here rather than reused:

- the search section and its feature component, from the authored place list
  through `rust/grass-core`'s `build-section`

Stand-in:

- the elevation grid: no GLO-30 resample step is run here

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
