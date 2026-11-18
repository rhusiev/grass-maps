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
the only place `sac_scale` changes a pedestrian route. The T-0301 runs used the
phase-A pack, which carries no elevation, so `use_hills` and `max_grade` had no
effect there; the values stay in the document because they are the mapping the
T-0308 elevation pack needs.

## Route preferences (T-0308, three steps in T-0421)

The preferences are per-profile settings, not a global switch.
`RouteOptions` stores five independent steps - hills, steps, trail difficulty,
busy roads and rough surfaces - each `Avoid`, `Normal` or `DontMind`. Each
profile shows and sends only its own set (`RouteAttribute.forProfile`): walk has
hills and steps, hike adds trail difficulty, bike has hills, busy roads and
rough surfaces. `plugins/valhalla` applies them only to the costing named by that
request, so setting bike to avoid hills cannot change the walk body. `Normal`
keeps the tuned profile value.

`use_hills` and `use_roads` read as willingness, 0 to 1. `avoid_bad_surfaces`
reads as avoidance, 0 to 1. `step_penalty` is seconds per step transition.
`max_hiking_difficulty` is a `sac_scale` limit, 0 to 6, and a hard filter.

| Attribute | Profile | Avoid | Normal | Don't mind |
|---|---|---|---|---|
| Hills | walk / hike / bike | `use_hills` 0.0 | tuned 0.5 / 0.9 / 0.4 | `use_hills` 1.0 |
| Steps | walk / hike | `step_penalty` 480 | tuned 30 / 20 | `step_penalty` 0 |
| Trail difficulty | hike | `max_hiking_difficulty` 2 | tuned 5 | `max_hiking_difficulty` 6 |
| Busy roads | bike | `use_roads` 0.0 | tuned 0.3 | `use_roads` 1.0 |
| Rough surfaces | bike | `avoid_bad_surfaces` 1.0 | tuned 0.5 | `avoid_bad_surfaces` 0.0 |
| Any | car | unchanged | unchanged | unchanged |

The design handoff of 2026-11-13 drops busy roads and rough surfaces from the
foot sheets, so walk and hike no longer send `use_living_streets` or
`use_tracks`. The Q43 rule that "Don't mind" on foot keeps the extremes now
applies to the foot keys that remain: `step_penalty` 0, `use_hills` 1.0 and
`max_hiking_difficulty` 6. Pedestrian costing has no `use_roads` or
`avoid_bad_surfaces`. Car is out of v1 and keeps its profile. The direct engine
has no costing, so a preference never moves its geodesic; it accepts the request
unchanged.

Valhalla 3.9.1 silently ignores a key it does not know, so only a run that
changes the route proves a key. Measured offline on `/hdd/t-0518-merged`
(Lviv and Ternopil oblast tiles) with the `valhalla_service` container, one
`route` per step and `trace_attributes` with `map_snap` for the edge counts.
Each pair differs only in the one key. The avoid and don't-mind routes differ in
every row except `max_hiking_difficulty`:

| Key | Pair (Lviv) | Avoid | Don't mind |
|---|---|---|---|
| bike `use_hills` | centre - Vynnyky | 11.896 km, 2516 s, 198 edges | 10.702 km, 2797 s, 193 edges |
| bike `use_roads` | centre - Vynnyky | 12.010 km, 2594 s, 211 edges | 10.038 km, 2161 s, 153 edges |
| bike `avoid_bad_surfaces` | centre - Briukhovychi | 11.223 km, 2597 s, 197 edges | 9.496 km, 2541 s, 183 edges |
| pedestrian `use_hills` | centre - Briukhovychi | 9.334 km, 7505 s, 147 edges | 8.961 km, 7082 s, 147 edges |
| pedestrian `step_penalty` | centre - Sykhiv | 6.975 km, 5366 s, 213 edges | 6.663 km, 5126 s, 216 edges |

Of 18 pairs per key, bike hills changed 9, bike `use_roads` 18, bike
`avoid_bad_surfaces` 3, pedestrian `use_hills` 17 and pedestrian `step_penalty`
17, for avoid against don't mind. A key that does not change a pair means the
pair has no alternative, not that the key is ignored.

`step_penalty` 480, 600 and 1000 give identical routes on all 18 pairs, so the
engine clamps the value at 480 or lower and 480 is the strongest avoidance.

`max_hiking_difficulty` is not proven by effect. Across 150 random pedestrian
pairs inside 23.3-23.6 E, 48.7-49.0 N, limits 1 and 6 returned the same route every
time, because the pack holds almost no edge above `sac_scale` 1 (one 0.609 km
`sac_scale:1` segment in the scan). The key is documented in Valhalla's
pedestrian costing and the app sends it, but the hike Avoid value 2 has no
measured route. A pack with mountain trails is the next check.

The settings model is `RoutingOptionsSettings`, one entry per `ProfileId`,
defaulting every unset profile to `RouteOptions()`. The planner reads it through
`RoutingOptionsAccess`; the profile is still the one the planner was built with,
so this task does not add a profile picker. The route preview override travels in
`RoutePlanState.overridePreferences`, never in the profile store, and `clear()`
drops it with the plan. `ValhallaPreferencesTest` checks every step on every
profile, and `RoutePlannerOptionsTest` checks a disagreeing override and the
profile default after a reset.

The Lviv pack used for the measurement is built with
`tools/pipeline/build-valhalla-pack.sh`: an osmium polygon extract of Lviv
oblast, GLO-30 `.hgt` tiles at 1 arcsec, and `valhalla_build_tiles` with
`additional_data.elevation` set, which adds edge grades in the same pass. The
phase-A country pack is untouched.

The acceptance routes and their default/avoid ascents are in
`test-data/routing/hills/bike-hills.json`; the capture command and the result
table are below.

Capture used the offline container on port 18002, the committed
`valhalla-profiles.json` for the tuned body, and the same Lviv GLO-30 DEM that
built the pack:

```sh
python3 scripts/bench/route-validation/hills.py \
  --pairs test-data/routing/hills/candidates.json \
  --profiles test-data/routing/valhalla-profiles.json \
  --dem /hdd/drive/Projects/Life/grass-maps-bench/data/lviv-elev30-int16.tif \
  --report /hdd/t-0308-hills/hills-report.json \
  --out test-data/routing/hills/bike-hills.json \
  --only 'Drohobych - Skole,Zolochiv - Peremyshliany,Boryslav - Turka,...' \
  --valhalla http://127.0.0.1:18002
```

The 100 candidate pairs are hand-picked towns in Lviv oblast, 3-40 km apart.
The script routes each pair with the tuned bicycle profile and with
`use_hills: 0.0`, samples the returned line every 25 m, and sums the positive
height changes. Full avoidance lowered the ascent on 22 of the 100; the 10
largest drops are committed. Avoidance is a cost preference, not a guarantee:
on a road with no flatter alternative it can leave the line unchanged or even
pick a slightly hillier line when the length or road weights dominate.

| Route | tuned km | tuned ascent m | avoid km | avoid ascent m | drop m |
|---|---|---|---|---|---|
| Bryukhovychi - Novoyavorivsk | 34.1 | 646 | 34.4 | 612 | 34 |
| Drohobych - Skole | 54.8 | 1111 | 52.8 | 856 | 255 |
| Boryslav - Turka | 53.0 | 1201 | 53.9 | 1147 | 54 |
| Truskavets - Skole | 48.6 | 708 | 47.8 | 696 | 12 |
| Truskavets - Turka | 61.8 | 1418 | 62.7 | 1357 | 61 |
| Truskavets - Sambir | 43.5 | 780 | 43.3 | 770 | 10 |
| Stebnyk - Mykolaiv | 51.3 | 610 | 51.2 | 594 | 16 |
| Stebnyk - Bolekhiv | 51.0 | 679 | 50.8 | 665 | 14 |
| Zolochiv - Peremyshliany | 38.4 | 912 | 38.3 | 725 | 187 |
| Peremyshliany - Rohatyn | 30.4 | 503 | 30.9 | 493 | 10 |

`BikeHillRoutesTest` recomputes each ascent from the committed integer height
samples and fails if any avoid line is not lower or if the recorded figure
drifts. The route shape is stored as a polyline6 string, the same encoding the
engine returns; the heights are a comma-separated string, one integer per 25 m
of route, so the fixture stays compact.

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
`yes` on every segment of this run because the capture script applied the old
default that filled it in; the engine no longer emits `access` (see below).
`sac_scale` is `none` on all but a handful, as above.

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
`edge.surface`, `edge.use`, `edge.road_class`, `edge.sac_scale` and
`edge.length`. The line is chunked at 50 km (the pack's trace limit is 200 km)
and the route is re-cut into one segment per matched edge.

Three attributes come from Valhalla directly. `surface` is Valhalla's `Surface`
enum; the round-10 palette maps all seven values of the T-0301 walk run onto its
four classes. `highway` is `edge.use`, mapped to the OSM vocabulary (`walkway` →
`footway`, `service_road` → `service`, a plain road takes its `road_class`), and
`sac_scale` is Valhalla's enum in the OSM spelling (0 → `none`, 2 →
`mountain_hiking`, ...).

`access` is not among them. The trace does not carry the way's OSM access tag,
and a way Valhalla routed is passable for the profile by construction, so a
value would be invented. The private-way warning therefore has no source until
an engine carries OSM access tags; v1 does not. The T-0301 CSV and summary still
show the old fabricated `access=yes` because they are the committed record of
that run, not a description of the current engine.

`grade` does not come from Valhalla: the phase-A pack was built without
elevation and every edge reports the `-500` "no data" sentinel. The engine
samples the mounted region DEM through `ElevationAccess` at the ends of a window
of at least 50 m of route centred on each segment and stores
`(end - start) / window * 100` as a percent. A short edge therefore reads the
grade of the stretch around it: a 10 m edge over a 2 m step reads 4 %, not
20 %, and a genuine 200 m climb at 20 % still reads 20 %. Without a mounted DEM
the key is absent rather than wrong.

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
