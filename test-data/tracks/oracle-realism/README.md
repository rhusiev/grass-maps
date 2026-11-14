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

`walk-outlier-5km.truth.json` and `oracle-realism.json` record `outlierMeters`
as `250.0`. The injected jumps measure 245-260 m in the GPX and the generator
call used 250 m, so 250.0 is the value that describes the file. An earlier
sidecar value of 200.0 did not describe the jump. The GPX is unchanged. The
test asserts the two fix indices and the 250 m magnitude.

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

All 30 bounds pass since T-0422, and the analyser is closer to the authored truth
than OsmAnd on all 30 comparisons. `truthBoundsAreMet` holds the comparison;
`fixtureIsIntact` still checks the six GPX files, the six sidecars, the recorded
OsmAnd metadata, the emulation cases and the recorded analyser snapshot.

| Track | Distance | Moving time | Average speed | Ascent | Descent |
|---|---|---|---|---|---|
| `hike-dropout-8km` | 8,840.1 m (+0.62 %) | 7,992 s (-0.10 %) | 1.106 m/s (+0.72 %) | 397.3 m (-2.7) | 0.1 m (+0.1) |
| `hike-steps-6km` | 5,159.0 m (-0.13 %) | 4,494 s (-0.13 %) | 1.148 m/s (+0.01 %) | 296.4 m (-3.6) | 0.0 m (0.0) |
| `ride-realistic-15km` | 14,959.5 m (-0.06 %) | 2,994 s (-0.20 %) | 4.997 m/s (+0.14 %) | 116.2 m (-3.8) | 0.1 m (+0.1) |
| `walk-drift-3km` | 3,129.3 m (+0.49 %) | 2,398 s (-0.08 %) | 1.305 m/s (+0.58 %) | 9.2 m (+4.2) | 0.0 m (0.0) |
| `walk-jitter-4km` | 4,067.9 m (+0.62 %) | 2,996 s (-0.13 %) | 1.358 m/s (+0.76 %) | 29.7 m (-0.3) | 0.9 m (+0.9) |
| `walk-outlier-5km` | 4,922.4 m (+0.64 %) | 3,497 s (-0.09 %) | 1.408 m/s (+0.73 %) | 23.8 m (-1.2) | 0.4 m (+0.4) |

Bounds: distance and average speed 1.5 %, moving time 0.5 %, ascent and descent
max(10 %, 5 m). The tightest margins are moving time at 0.30 percentage points,
distance at 0.86, average speed at 0.74, ascent on `walk-drift-3km` at 0.8 m of
its 5 m allowance, and descent on `walk-outlier-5km` at 3.3 m of 5.

### What each figure needed

- **Distance.** `TrackGeometry` drops a fix whose step in and step out both
  exceed the track's own median speed (six times it, floored at 5 m/s), which is
  what removes the two 250 m fixes of `walk-outlier-5km` without a ceiling that
  would mangle a fast track. It then simplifies the remaining line with
  Ramer-Douglas-Peucker at eight times the measured fix noise, where the noise is
  the median absolute residual of a centred five-point quadratic fit: 2.5-5.2 m
  on these six, 0.39-0.55 m on the clean oracle, whose bend is not mistaken for
  noise because a quadratic fits a bend exactly. The total is the haversine sum
  between the surviving points. On `walk-jitter-4km` the raw sum is 11,340.8 m
  and the cleaned line 4,067.9 m against an authored 4,042.8 m.
- **Moving time.** A step counts when the straight run of the cleaned line it
  belongs to averaged at least 0.5 m/s over the time actually observed in it,
  which is what survives the jitter: a single 2 s step of a 1.35 m/s walk can
  measure 0.1 m/s when the fixes wander 4 m, while thirty steps of the same walk
  average to the walk.
- **Dropouts.** Bridging them is wrong for moving time and right for distance.
  The 79 s loss of `hike-dropout-8km` is 16-20 sampling intervals; counting it
  would report 8,071 s against 8,000 s (+0.89 %), past the 0.5 % bound, so its
  time is unobserved while its chord stays in the distance (87 m of the 8,785.7 m
  on the long walk, 240 m of 14,968.2 m on the ride). The authored truth agrees:
  it is the span minus each dropout.
- **Average speed.** It follows: the cleaned distance over the observed time.
- **Ascent and descent.** `ElevationSmoothing` averages the heights over two
  minutes before `ElevationFilter` sees them, and only when the measured height
  noise exceeds 0.4 m (0.71-1.73 m here, 0.03-0.11 m on the clean oracle). The
  average is a centred boxcar whose window shrinks at the ends; that is what
  keeps the endpoint noise out of the first and last step, and it costs about
  half a window of climb at each end of a profile - 2.7-3.8 m of these 120-400 m
  climbs, which is why they read slightly low. The same shrink is most of why
  `walk-drift-3km` reads 9.2 m instead of the 13.5 m that the 6 m barometric
  drift plus the 5 m authored climb put into the file.
- **Drift.** It remains the one figure the emitted data cannot support exactly:
  the authored climb is 5 m, the file rises 13.5 m, and an estimator that
  reported the observed rise would miss the 5 m bound. 9.2 m is inside the 10 m
  allowance because the average's shrinking ends absorb part of the drift, not
  because the analyser can tell a drift from a climb. A filter that reported the
  whole rise would fail this bound on data that is doing what the fixture asks.
- **Outliers.** The two 250 m fixes leave the line, so they no longer set the
  maximum speed either (OsmAnd shows 0:08 min/km, 125 m/s). Their contaminated
  ascent and descent fall from 43.5/20.1 m to 23.8/0.4 m against a truth of
  25/0 m.

Do not widen a tolerance to make a figure fit. A missing bound needs a different
estimator, and a bound the emitted data cannot reach needs to be reported as
such, not relaxed.
