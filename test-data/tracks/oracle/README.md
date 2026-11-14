# Synthetic OsmAnd analysis oracle

The T-0402 acceptance needs ten real tracks and the figures OsmAnd shows for
them. Those tracks do not exist yet. This directory is the synthetic stand-in
the review asked for: nine generated `<trk>` recordings, imported into OsmAnd
on the user's phone, with OsmAnd's own analysis recorded as the reference.

The test is `tracks/src/test/.../SyntheticOsmAndTracksOracleTest`. It parses
each GPX file in this directory, runs `TrackAnalyzer`, and compares the result
with `oracle.json`.

## Limitation

The inputs are synthetic. The geometry and elevation are generated around Lviv,
not recorded by a device. The reference is OsmAnd 5.4.10's analysis of those
files on a Moto g32. The oracle therefore proves agreement with OsmAnd. It does
not prove correctness on real GPS or barometric noise. The same statement is
the `limitation` field of `oracle.json`.

This directory does not replace `../osmand-oracle/`. That directory is the
milestone gate and still needs ten real tracks. This one exists so the
comparison harness and the analyser can be checked without waiting for them.

## What each track exercises

| File | Shape |
|---|---|
| `walk-city-5km.gpx` | 5 km city walk, 1.4 m/s, flat with a gentle ripple |
| `hike-12km-climb.gpx` | 12 km hike, 1.1 m/s, net +600 m with undulations |
| `ride-20km.gpx` | 20 km ride, 5.5 m/s, gentle hills |
| `walk-with-pause.gpx` | 5 km at 1.4 m/s, one 20-minute stop in the middle |
| `short-800m.gpx` | 800 m at 1.2 m/s |
| `hilly-3km.gpx` | 3 km with repeated 10-15 % grades |
| `long-30km.gpx` | 30 km at 1.3 m/s |
| `walk-slow-2km.gpx` | 2 km at 0.9 m/s |
| `hike-steep-down-10km.gpx` | 10 km, 1.1 m/s, net -500 m |

Every file is one `<trk>` with one `<trkseg>`. Points are 15-20 m apart, with
the spacing varied per point. Each point carries `lat`, `lon`, `ele` and
`time`. The dates are distinct and the coordinates stay in the Lviv area. The
files are Apache-2.0 like the rest of the repository and describe no real
person or activity.

The generator lived in the session scratchpad and is not committed. The nine
GPX files are the artefact; they are small enough to read and diff.

## How the figures were recorded

The recording ran on 2026-11-14 against OsmAnd 5.4.10 on a Moto g32, Android
screen 1080x2400. For each file:

1. Push the file to `/sdcard/Download`.
2. Open OsmAnd, hamburger, My Places, TRACKS, the three-dot menu, Import,
   Downloads, and tap the file row.
3. Open the track, then Options, then Analyze by intervals. The statistics
   screen shows Distance, Start, End, Average elevation, Min/Max,
   Ascent/Descent, Time in motion, Average pace and Max pace.
4. Record every value, then delete the track from OsmAnd (Options, Delete,
   confirm) so the phone is left as it was.

The taps must be computed from the 1080x2400 PNG, not from a scaled preview.
A preview at 900x2000 makes every coordinate 1.2 times too small, so a tap read
from it lands about 20 % high. The first tap of this recording hit Analyze on
map instead of Analyze by intervals for that reason. `uiautomator dump` gives
exact bounds when a row is ambiguous.

`averageSpeedMetersPerSecond` is `60 / pace`, as the task specified. The raw
pace string is kept beside it as a record because OsmAnd rounds the display to
whole minutes per km. At 18 min/km that rounding is 5.6 %, larger than the 2 %
speed tolerance. The test therefore compares `distanceMeters /
movingTimeSeconds`, both exact in the JSON, and does not compare the
pace-derived figure.

The pause track is the one that separates moving time from total time. OsmAnd
reported `1:19:38` total and `59:24` in motion. The generated file has a
1,200 s gap at the halfway point.

A pre-existing `walk-5km` probe track was already on the phone and was left
untouched. The imported tracks were all deleted; the track list and its totals
matched the starting state after the run.

## Tolerances

The test compares relative to the OsmAnd figure:

| Field | Tolerance | Why |
|---|---|---|
| Distance | 1 % | OsmAnd rounds the display to 10 m below 10 km and 100 m above, so up to 0.5 % is display rounding alone |
| Moving time | 2 % | OsmAnd reports seconds; 2 % covers its pause threshold on the one deliberate stop |
| Average speed | 2 % | Compared as distance / moving time, not as 60 / the pace rounded to whole minutes per km |
| Ascent | 10 % or 0.5 m | OsmAnd filters the elevation series and rounds the display to whole metres; the analyser follows the same filter, and half a displayed metre is the reference's own resolution |
| Descent | 10 % or 0.5 m | Same as ascent; it is the smaller side on the climb tracks, so the same absolute gap is a larger percentage there |

The first run found the elevation gap on the near-flat tracks. On
`walk-city-5km`, for example, OsmAnd reported 1 m of ascent while the raw
deltas sum to 31.9 m: the 1-2 m ripple is below OsmAnd's noise floor.
`TrackAnalyzer` now applies OsmAnd's published uphill algorithm: the monotonic
and 70 % slope filters, a Ramer-Douglas-Peucker simplification at 7 m, and the
sums between the surviving turning points. That is also where
`ELEVATION_NOISE_THRESHOLD_METERS` comes from.

A single hysteresis on the elevation sums was tried first and cannot match the
nine tracks: `hilly-3km` needs about 4 m, while `long-30km` needs about 2 m.
The extremum model is OsmAnd's own, so the analyser uses it instead. On the
nine tracks every ascent and descent is within 0.7 m of OsmAnd, and the test
accepts a difference inside 10 % or inside half a displayed metre, whichever is
larger. OsmAnd's integer is a display quantum, not a truth: on a track whose
whole gain is 1-2 m, 0.3 m is the reference's rounding. Do not widen the
tolerance.
