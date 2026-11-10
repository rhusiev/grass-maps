# Valhalla profile validation (T-0301)

The acceptance measurement for the three v1 profiles. 100 seeded waypoint pairs
inside Lviv oblast are routed offline for walk, bike and hike, and each route is
compared with GraphHopper 11.1's stock `foot`, `bike` and `hike` profiles. The
length tolerance in `lviv-summary.json` is derived from this run, and
`plugins/valhalla`'s `RoutingValidationTest` fails when a later run breaks it.

Files:

| File | What it is |
|---|---|
| `valhalla-profiles.json` | the exact costing options the app sends; `ValhallaProfilesTest` keeps it equal to `ValhallaProfiles.kt` |
| `lviv-oblast-pairs.json` | the 100 seeded pairs, with the seed, the separation rule and the oblast polygon digest |
| `lviv-routes.csv` | one row per pair and profile: both engines' status, distance, time and the segment attribute counts |
| `lviv-summary.json` | per-profile counts, distance-delta distribution, the derived tolerance, attribute coverage, engine identities |
| `planner-vectors.json` | one captured bicycle route pair for T-0302: the forward and reverse answer with two alternatives each, used by `routing`'s planner tests |
| `via-vectors.json` | three captured intermediate-stop chains for T-0303: the same three stops on each profile, the bike chain reordered, and the bike chain with one stop removed |

## Profiles

Valhalla has no separate hiking costing. A hike is a pedestrian route with a
higher permitted trail difficulty and a lower hill penalty; every other value is
the pedestrian default, written down so a change is a decision.

| | walk | hike | bike |
|---|---|---|---|
| costing | `pedestrian` | `pedestrian` | `bicycle` |
| speed | 5.0 km/h | 4.5 km/h | 18 km/h (`bicycle_type: hybrid`) |
| stairs | `step_penalty` 30 s per flight | 20 s per flight | none exposed; Valhalla charges a stair edge 8x (`kBicycleStepsFactor`) |
| trails (`sac_scale`) | `max_hiking_difficulty` 1, so T1 only | 5, so T1-T5, T6 refused | not read; bicycle access comes from the graph's bicycle tags |
| hills | `use_hills` 0.5 (neutral) | 0.9 (accept the climb) | 0.4 (a little more willing to climb than the default) |
| path use | `walkway_factor` 1.0, `sidewalk_factor` 0.9 | same | `use_roads` 0.3, just above the 0.25 default, so a usable street beats a detour |
| surface | no weight; foot may use every surface up to `path` | same | `avoid_bad_surfaces` 0.5, hybrid surface speed factors |
| ferries | `use_ferry` 1.0 (allowed) | 1.0 | 0.5 (allowed, penalized) |
| alleys, driveways | 2.0, 5.0 (avoid) | 2.0, 5.0 | Valhalla defaults |

`max_hiking_difficulty` is the pedestrian option that reads `sac_scale`; it is
the only place `sac_scale` changes a pedestrian route. The phase-A pack carries
no elevation, so `use_hills` and `max_grade` have no effect on these runs: they
stay in the document because they are the mapping the pack will need once it is
rebuilt with elevation.

## How the run was made

Machine: this desktop (Intel i5-9300H, 8 threads, 31 GB RAM, Fedora 44 kernel
7.2), `/hdd` hard disk. Not the Moto g32.

Valhalla: `ghcr.io/valhalla/valhalla:latest`, digest
`sha256:bf88b5c5c5131414c66a76ba77a8c5ce803a1c724c2a1f07c25961a874a419fb12`,
version `3.9.1-52228c4e0`, run with `--network none` and the pack mounted
read-only at `/data`:

```sh
docker run -d --rm --name valhalla-t0301 --network none \
  -v /hdd/drive/Projects/Life/grass-maps-bench/data:/data:ro \
  ghcr.io/valhalla/valhalla:latest \
  valhalla_service /data/valhalla/valhalla.json 2
```

The runner sends each request with `docker exec valhalla-t0301 curl`, so the
engine never leaves its network namespace. The pack is the phase-A Ukraine build
(`tiles.tar`, 983 MiB; `admins.sqlite`; `timezones.sqlite`), read-only.

Reference: GraphHopper 11.1, `graphhopper-web-11.1.jar`
(sha256 `8462f758d9ea49edaded557cec5c687a0a24f004ec371cd4daaeb0534824ea33`),
with its stock `foot.json`, `bike.json` and `hike.json` custom models and no
elevation. Its graph was imported from an OSM bbox extract of
`ukraine-latest.osm.pbf` (2026-11-04), bbox `22.6,48.7,25.5,50.7`, made by
`extract_bbox.py` with pyosmium; GraphHopper runs on JDK 21 as
`java -jar graphhopper-web-11.1.jar server config.yml` with `profile: foot`,
`bike` or `hike`, no CH/LM preparation and no elevation provider.

The pairs come from `pairs.py` with seed `20261110`: uniform points inside the
Lviv oblast polygon, rejecting a draw outside the polygon or a pair closer than
2 km or farther than 100 km apart (a one-day trip at these speeds, and well
inside the engine's 250 km limit).

```sh
python3 scripts/bench/route-validation/pairs.py test-data/routing/lviv-oblast-pairs.json
python3 scripts/bench/route-validation/run.py \
  --pairs test-data/routing/lviv-oblast-pairs.json \
  --profiles test-data/routing/valhalla-profiles.json \
  --out test-data/routing/lviv-routes.csv \
  --summary test-data/routing/lviv-summary.json \
  --dem /hdd/drive/Projects/Life/grass-maps-bench/data/lviv-elev30-int16.tif \
  --valhalla http://127.0.0.1:8002 --valhalla-container valhalla-t0301
```

The run needs `numpy`, `Pillow` and (for `pairs.py`) `shapely`. None of them are
build or app dependencies.

## Results

100 pairs per profile, all routed offline by Valhalla, 300 of 300 routes in
total; the reference routed the same 300, so there is no pair Valhalla misses.

| profile | routed | reference routed | missing | delta p50 | p90 | p99 | max | segments |
|---|---|---|---|---|---|---|---|---|
| walk | 100/100 | 100/100 | 0 | 4.3 % | 10.2 % | 18.1 % | 18.2 % | 30 013 |
| bike | 100/100 | 100/100 | 0 | 2.8 % | 11.6 % | 20.8 % | 22.9 % | 30 608 |
| hike | 100/100 | 100/100 | 0 | 4.3 % | 10.2 % | 18.1 % | 18.2 % | 29 909 |

Delta columns are `|valhalla - reference| / reference`. The sign leans negative:
the median signed delta is -4.3 % for walk and hike and -1.5 % for bike,
so Valhalla usually takes the shorter line. The committed tolerance is
**23.0 %**, the worst pair of this run (bike, -22.9 %) rounded up to 0.1 %; the
pooled absolute p99 is 18.2 %. Every pair is inside the bound by construction,
and a later run that breaks it fails `RoutingValidationTest`. The long tail is a
few pairs where GraphHopper's models detour: its `bike.json` refuses
`mtb_rating > 2` and `hike_rating > 1` edges and prefers `bike_network` routes,
where Valhalla's hybrid rides the track.

The walk and hike distributions are close because the pack has no elevation
(so `use_hills` does nothing) and 94 of the 100 pairs route identically; the
largest difference is 972 m. The profile difference is the sac_scale gate, and
only two segments of the whole walk run (five of the bike run) sit on a marked
hiking trail, so random pairs rarely exercise it.

Segments and attribute coverage (`present` means the key is set; the full value
histograms are in `lviv-summary.json`):

| profile | segments | surface | highway | grade | sac_scale | access |
|---|---|---|---|---|---|---|
| walk | 30 013 | 100 % | 100 % | 98.2 % | 100 % | 100 % |
| bike | 30 608 | 100 % | 100 % | 97.8 % | 100 % | 100 % |
| hike | 29 909 | 100 % | 100 % | 98.2 % | 100 % | 100 % |

`surface` is Valhalla's `Surface` enum (`paved_smooth`, `dirt`, `compacted`,
`gravel`, `paved`, `path`, `paved_rough`); `highway` is `edge.use` or
`road_class`. `grade` is missing on the 1.8-2.2 % of segments whose end point
falls outside the Lviv DEM raster, which is clipped to the oblast. `access` is
`yes` on every segment of this run - no random route crossed a restricted way -
and `sac_scale` is `none` on all but a handful, as above.

Warm timings on this desktop, request time only (inside the container, one
warm-up then median of ten):

| case | walk | bike |
|---|---|---|
| Sykhiv, 5.9 / 6.0 km | 38.1 ms | 18.0 ms |
| Zhovkva, 28.9 / 34.8 km | 22.3 ms | 49.1 ms |
| Skole, 106.5 / 123.2 km | 95.9 ms | 133.1 ms |

Against the phase-A medians (44/21, 31/55, 69/155 ms) these match or beat five
of six cases; walk to Skole is 96 ms against 69 ms. The machine was shared with
other agents' Gradle builds during this run, so treat the single slower case as
an observation, not a regression.

## Segments and attributes

`/route` returns no per-edge tags, so `ValhallaRoutingEngine.annotate` sends the
routed line back to `/trace_attributes` with `shape_match: map_snap` and asks for
`edge.surface`, `edge.use`, `edge.road_class`, `edge.sac_scale`,
`edge.access_restriction` and `edge.length`. The line is chunked at 50 km (the
pack's trace limit is 200 km) and the route is re-cut into one segment per
matched edge.

Four attributes come from Valhalla directly. `highway` is `edge.use`, mapped to
the OSM vocabulary (`walkway` → `footway`, `service_road` → `service`, a plain
road takes its `road_class`), and `sac_scale` is Valhalla's enum in the OSM
spelling (0 → `none`, 2 → `mountain_hiking`, ...). `access` is `yes` unless
`edge.access_restriction` names a restriction.

`grade` does not come from Valhalla: the phase-A pack was built without
elevation and every edge reports the `-500` "no data" sentinel. The engine
samples the mounted region DEM through `ElevationAccess` at each segment's end
points and stores `(end - start) / length * 100` as a percent. Without a mounted
DEM the key is absent rather than wrong.

Users see guidance and distance as soon as `/route` returns; attribute-dependent
drawing (surface shading) appears after `annotate` finishes, and a failed trace
leaves the route without attributes rather than failing it. Nothing in the app
calls `annotate` yet: the navigation session (T-0312) schedules it once guidance
is running, and the drawing that reads the values is outside this task's
modules.

## `matchTrack`

The engine declares `hasMatchTrack = true`. Evidence, not expectation:

- the pack's `valhalla.json` lists `trace_route` in `loki.actions`, so the tiles
  support map matching;
- `valhalla-mobile` 0.6.4 exposes `Valhalla.traceRouteRaw(String)` and
  `ValhallaTraceRaw`, which is the map-matching call (`javap` on the AAR's
  `classes.jar`).

## Planning vectors (T-0302)

`planner-vectors.json` is one captured `/route` answer on the same pack, with
`alternates: 1`, for a bicycle pair from Sykhiv towards the centre. It holds the
forward pair and the reverse pair, each with the primary route and one
alternative, and is the fixed fixture `routing`'s `RoutePlannerTest` reads.

The reverse line is not the forward line read backwards: one-way streets make
the reverse route 6 582 m against 7 052 m forward, and the geometry has 288
points against 304. That is the evidence the planner's swap goes back to the
engine instead of reversing a geometry. The pair was picked for exactly this
property; a pedestrian pair on the same streets reverses geometrically, so its
capture could not prove the distinction.

The run used the same offline container and pack as the T-0301 validation:

```sh
docker run -d --rm --name valhalla-t0302 --network none \
  -v /hdd/drive/Projects/Life/grass-maps-bench/data:/data:ro \
  ghcr.io/valhalla/valhalla:latest \
  valhalla_service /data/valhalla/valhalla.json 2
```

The response was decoded with the shared polyline6 rule and stored as route
model fields; the raw response is not committed. The pair returns one
alternative (`alternates: 1`); a longer Lviv pair returns two, and a Lviv-Zhovkva
pair returns none, which is why the preview says "no alternative routes" rather
than inventing one.

## Via vectors (T-0303)

Intermediate stops. `via-vectors.json` is five captured `/route` answers on the
same offline pack: the same start, three stops and destination for walk, bike
and hike; the bike chain with the last two stops swapped; and the bike chain
with the last stop removed. Each entry carries the visited via order, the engine
summary and the leg list, because a stop chain is one route with one leg per hop
and not three separate routes.

Start `49.8290, 23.9520` (Sykhiv), destination `49.8420, 24.0320` (centre). The
three stops are points a few metres off the walking line at
`49.830707, 23.968369`, `49.836774, 24.002926` and `49.840959, 24.013863`; each
was taken from the bicycle route's own shape and then moved about 4 m, so a
returned line has to snap to the street rather than start on the stop.

The capture used a read-only, networkless container, like T-0301:

```sh
docker run -d --rm --name valhalla-t0303 --network none \
  -v /hdd/drive/Projects/Life/grass-maps-bench/data:/data:ro \
  ghcr.io/valhalla/valhalla:latest \
  valhalla_service /data/valhalla/valhalla.json 2
```

Each request is `POST /route` with `units: kilometers`, `language: uk-UA`,
`locations` of type `break` in visit order, and no alternates:
`[start, via1, via2, via3, destination]`. Walk and hike use `pedestrian`, bike
uses `bicycle`.

The acceptance rule is ten metres: the returned line must pass within 10 m of
every stop, and in the asked order. `RoutePlannerViaTest` measures the shortest
distance from each stop to the line and the line vertex nearest each stop; both
checks run on the stored geometry, not on a line the test draws.

| chain | legs | walk | bike | hike |
|---|---|---|---|---|
| all three stops, order 1-2-3 | 4 | 6 548 m | 7 070 m | 6 548 m |
| bike, order 1-3-2 | 4 | | 8 897 m | |
| bike, stops 1-2 only | 3 | | 7 052 m | |

Distance from each stop to the returned line, and the line vertex nearest it:

| chain | distances | nearest-vertex indices |
|---|---|---|
| walk 1-2-3 | 2.76, 2.15, 1.43 m | 53, 171, 210 |
| bike 1-2-3 | 2.76, 5.31, 1.43 m | 121, 222, 253 |
| hike 1-2-3 | 2.76, 2.15, 1.43 m | 53, 171, 210 |
| bike 1-3-2 | 2.76, 1.43, 5.31 m | 121, 252, 285 |
| bike 1-2 | 2.76, 5.31 m | 121, 222 |

The largest distance is 5.31 m, so every stop is inside the 10 m rule, and the
indices increase in every chain, so the line reaches the stops in the asked
order.

Walk and hike return the same line as each other because the pack carries no
elevation and no street on this pair is a marked trail, so the two costings
agree; T-0301 measured the same overlap on random pairs.

Reordering changes the route and not only the list: swapping the last two stops
adds 1 827 m to the bike route, changes the geometry from 310 to 369 points, and
the line reaches the stops in the new order (indices 121, 252, 285). Removing
the last stop takes one leg off the chain, shortens the bike route by 18 m and
returns a different 306-point line. Removing the middle stop is accepted by the
planner and the chain is re-asked with four waypoints, but it is not captured
here: that stop is on the corridor, so its route is the same line.

Alternatives and stops: the Valhalla engine accepts `alternates` together with
four or more locations but answers with no alternatives (one route, status 200),
so a via chain publishes a single option and the panel keeps its "no alternative
routes" line. No capture stores an alternative because the engine produced none.

What was not measured: the g32, the Android binary and timing. Every number is
desktop, on the phase-A pack, and the geometry is a property of the tiles and the
costing options.

## What was not measured

- The g32. Every number here is desktop, like the phase-B routing timings.
- The Android binary. `valhalla-mobile` 0.6.4 bundles its own Valhalla build and
  the AAR carries no version string, so the run uses 3.9.1, the version that
  built the pack. Route lengths are a property of the tiles and the costing
  options; the Android wrapper can change timing, not the graph.
- Attribute fill timing. It is asynchronous by design and not part of the
  acceptance bound.
