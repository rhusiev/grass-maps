# Realistic ground-truth oracle

This directory extends `../oracle/` with six tracks that carry emulated GPS and
barometric noise, and a truth column recorded before that noise was added. The
harness is `tracks/src/test/.../RealisticTracksOracleTest`.

The clean synthetic oracle proves agreement with OsmAnd on generated geometry.
This one asks a different question: what do the analyser and OsmAnd do when the
fixes are noisy, one track loses its signal, one has outlier fixes and one has
barometric drift? The answer is in `oracle-realism.json` and in the test's
failure message.

## Files

- `<name>.gpx` and `<name>.truth.json`, copied verbatim from the generator. The
  sidecars hold the authored distance, moving time, ascent, descent and average
  speed plus the emulation parameters.
- `oracle-realism.json`, with `truth`, `osmand`, `ours` and `emulation` per
  track. Truth comes from the sidecar; `osmand` is what OsmAnd 5.4.10 showed on
  the Moto g32; `ours` is `TrackAnalyzer` on the same file.

## Limitation

The inputs are generated around Lviv; no device recorded them. Truth is the
authored path before emulation, not a second measurement. The oracle therefore
measures robustness to the listed noise, not agreement with a second real
recording.

## What each track exercises

| File | Authored | Emulation |
|---|---|---|
| `hike-dropout-8km.gpx` | 8,785.7 m, 8,000 s, +400 m | 3 m jitter, 2.5 m elevation noise, 75 s dropout |
| `hike-steps-6km.gpx` | 5,165.7 m, 4,500 s, +300 m | 3 m jitter, 2 m elevation noise, 60 s dropout |
| `ride-realistic-15km.gpx` | 14,968.2 m, 3,000 s, +120 m | 5 m jitter, 2 m elevation noise, 45 s dropout |
| `walk-drift-3km.gpx` | 3,113.9 m, 2,400 s, +5 m | 2.5 m jitter, 1 m elevation noise, 6 m barometric drift |
| `walk-jitter-4km.gpx` | 4,042.8 m, 3,000 s, +30 m | 4 m jitter, 1.5 m elevation noise, 30 s dropout |
| `walk-outlier-5km.gpx` | 4,891.0 m, 3,500 s, +25 m | 3.5 m jitter, 1.5 m elevation noise, 250 m fixes at points 400 and 900 |

The sidecars record `outlierMeters` as `200.0`, while the injected jumps measure
245-260 m in the GPX and the task describes them as 250 m. The sidecars are
committed unchanged, so `oracle-realism.json` keeps `200.0`; the test asserts
the two fix indices and quotes the real 250 m in its comments.

## How the figures were recorded

The run was on 2026-11-14 against OsmAnd 5.4.10 on a Moto g32, Android screen
1080x2400. The six files were pushed to `/sdcard/Download` and imported one at a
time: hamburger, My Places, TRACKS, three-dot, Import, Downloads, tap the row.
The track opens on Overview; Options, then Analyze by intervals, shows the nine
readings. Every value was read from a full-size PNG. `uiautomator dump` gave the
exact row bounds, because the picker changed the row order between imports and
one tap landed on the neighbouring file.

After each reading the track was deleted (Options, Delete, OK). The final track
list is the starting one: five tracks in `import`, `Боржава CCX v2` and
`Боржава CCX v1`, 67.7 km, 4,101 m uphill, 3,563 m downhill, 11:48:07, 0.2 MB.
The six pushed GPX files and the temporary dump were removed from the phone.
`stay_on_while_plugged_in` was restored to 0; it was 7 before the run.

## Tolerances

| Field | Tolerance | Why |
|---|---|---|
| Distance | 1.5 % | The authored path is exact; 1.5 % is the bound the milestone asks for, not a fit to the observed gap |
| Moving time | 0.5 % | Timestamps are authored; there is no display rounding to excuse |
| Average speed | 1.5 % | Truth average speed is authored distance / authored moving time |
| Ascent | max(10 %, 5 m) | The authored climb is exact, but `ElevationFilter` is a heuristic over 1-2.5 m noisy samples; the percentage catches a real gap on a large climb, the five metres keep a 5 m climb from being judged by a percentage of almost nothing |
| Descent | max(10 %, 5 m) | Same reason as ascent; descent is what noise invents on a track whose authored descent is zero |

OsmAnd's distance is the displayed value, rounded to 10 m below 10 km and 100 m
above, so its distance error includes display rounding. Its average speed is the
displayed distance divided by the displayed moving time; the pace strings are
recorded but not compared, because whole-minute-per-kilometre rounding is
coarser than the bound.

## Current result

The first run is red: 28 of 30 bounds are missed. Only `hike-steps-6km` ascent
(323.1 m against 300 m) and `ride-realistic-15km` moving time (2,991 s against
3,000 s) pass. Two bounds passing is not a reason to treat the rest as noise.

- **Distance.** `TrackAnalyzer` sums haversine steps over the raw fixes. Jitter
  makes that sum 12-181 % longer than the authored path. OsmAnd's distance shows
  the same inflation, so its display is also a raw-fix sum; ours is marginally
  closer only because OsmAnd rounds the display. On `walk-jitter-4km` the
  authored 4,042.8 m becomes 11,340.8 m for us and 11,400 m for OsmAnd.
- **Moving time.** A step counts only when its raw speed is at least 0.5 m/s.
  Jitter cancels the forward step on 26-136 samples per track, so our moving time
  loses 0.3-6.9 %. On the 75 s dropout track ours is 7,452 s against 8,000 s.
- **Average speed.** It inherits the inflated distance and the shortened moving
  time, so it misses on every track.
- **Ascent and descent.** Noise turns the 5 m authored climb of `walk-drift-3km`
  into 22.2 m of ascent and 8.7 m of descent. The monotone 400 m climb of
  `hike-dropout-8km` gains 40.8 m of apparent descent.
- **Dropout.** OsmAnd reports 8,043 s in motion against 8,071 s of duration, so
  it excludes 28 s somewhere. It does not treat the whole 75 s gap as a pause;
  the gap is under our 120 s pause threshold too. Which 28 s it excludes is
  unverified.
- **Outliers.** The 260.8 m step over 2 s at point 899 is visible in OsmAnd's max
  pace of 0:08 min/km. Neither side filters it before the maximum. Its ascent
  and descent (51/28 m against truth 25/0 m) are contaminated as well.
- **Drift.** The 6 m drift plus 1 m elevation noise produces 21/8 m in OsmAnd and
  22.2/8.7 m for us on a track whose authored climb is 5 m.

Do not widen a tolerance to make this green. A green bound needs a different
distance and moving-time estimator, not a larger percentage.
