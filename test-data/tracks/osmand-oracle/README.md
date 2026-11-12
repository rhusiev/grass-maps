# T-0402 OsmAnd statistics oracle

The milestone's acceptance is that 10 real tracks match OsmAnd within the
oracle tolerance. This directory holds the tracks and the numbers; the test
`TrackStatisticsOracleTest` is the harness. **The tracked file is empty, so the
test is skipped today.** No OsmAnd figure in the repository is invented.

## What is missing

- 10 real GPX tracks. Use different activities, at least one auto-pause track
  with several `trkseg` blocks, at least one ride and one walk or hike. Put each
  file in this directory under the name `oracle.json` gives.
- For each track, the values OsmAnd shows in the track's details: distance,
  moving time, average speed and maximum speed. Optionally duration, ascent,
  descent, minimum and maximum altitude, when the same screen shows them.

The track files and the numbers must come from the same import. Do not adjust
the GPX or the numbers to make a comparison pass.

## How to record a run

1. On the phone, open OsmAnd (the version used is recorded in `oracle.json`),
   import the GPX file and open the track's details.
2. Write the figures into the `tracks` array of `oracle.json`, one object per
   track. `trackIndex` is 0 for the first `<trk>` in the file and counts up.
3. Record `osmandVersion`, `device` and `recordedOn` at the top level.
4. Run the harness:
   `./gradlew :app:testDebugUnitTest --tests '*TrackStatisticsOracleTest*'`

The harness requires exactly 10 entries once the list is not empty, so a partial
collection fails instead of silently passing. A missing GPX file fails with its
resource name.

## `oracle.json` shape

```json
{
  "schema": 1,
  "osmandVersion": "4.9.10",
  "device": "Moto g32",
  "recordedOn": "2026-11-20",
  "tolerance": {
    "distanceMeters": 1.0,
    "movingTimeMillis": 2000,
    "averageSpeedMetersPerSecond": 0.05,
    "maxSpeedMetersPerSecond": 0.1,
    "ascentMeters": 2.0,
    "descentMeters": 2.0,
    "elevationMeters": 1.0,
    "durationMillis": 1000
  },
  "tracks": [
    {
      "file": "real-01.gpx",
      "trackIndex": 0,
      "distanceMeters": 7421.5,
      "movingTimeMillis": 5400000,
      "averageSpeedMetersPerSecond": 1.374,
      "maxSpeedMetersPerSecond": 2.9,
      "ascentMeters": 219.0,
      "descentMeters": 218.0,
      "minElevationMeters": 296.0,
      "maxElevationMeters": 401.0,
      "durationMillis": 6120000
    }
  ]
}
```

`file`, `distanceMeters`, `movingTimeMillis`,
`averageSpeedMetersPerSecond` and `maxSpeedMetersPerSecond` are required.
`trackIndex` defaults to 0. `durationMillis`, `ascentMeters`, `descentMeters`,
`minElevationMeters` and `maxElevationMeters` are optional and compared when
present.

## Tolerance

The tolerance is chosen, not derived: the first comparison sets it. The values
in `oracle.json` are a proposal, not a measured result. Replace them with
figures the first comparison supports, and say in the commit or MR why.

## Definitions and known differences

`TrackAnalyzer` counts time as moving only between consecutive timestamped
points of one segment, with a gap of at most
`TrackStatisticsPolicy.pauseGapMillis` (default 120 s) and a speed of at least
`TrackStatisticsPolicy.movingSpeedMetersPerSecond` (default 0.5 m/s). A segment
boundary is a pause. Duration is the wall-clock span from the first timestamped
point to the last, pauses included.

Ascent and descent sum every raw elevation difference, with no smoothing and no
noise threshold. A noisy barometric track can therefore read higher than
OsmAnd. The first comparison shows whether a noise threshold belongs in the
policy; do not pre-tune it without that evidence.

OsmAnd has its own thresholds. The comparison exists to find out whether these
defaults agree closely enough; a systematic difference is a reason to change the
policy or to document the definitional gap, never to edit the oracle.

## Privacy

A real GPX can contain the route to a home, a workplace or a person's habits.
Commit only tracks whose publication is acceptable.
