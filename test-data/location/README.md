# Location replay fixture and the 15 m noise acceptance (T-0309)

`mock-walk.gpx` is the replay the debug build ships: 60 points around Lviv at a
one-second cadence, an 82 m loop that the `MockGpxLocationSource` replays as
fixes with a fixed 5 m horizontal accuracy. The file is unchanged since T-0022.

## The noise harness

`ReplayHarness` (`navigation/src/sharedTest`) parses the fixture, moves every
fix exactly 15 m from its track point in a seeded random direction, and measures
the published snapped position against the route line. The seed is 20261110 and
`java.util.Random` is used, so the JVM and Android runs are the same replay.

Two details matter for reproducibility:

- The measurement is the shortest haversine distance from the snapped point to
  any segment of the route, implemented in the harness. It does not use
  `PolylineLocator` or `GeoMath`, so the follower is not measured with its own
  arithmetic; the harness uses the IUGG mean radius 6 371 008.8 m.
- The route is the fixture's line continued straight for 300 m past its last
  point. The track is an 82 m loop, so its end is inside one noise radius of its
  start and a 15 m fix can land nearer the finish; the tail keeps the run from
  ending early. The line the fixes are measured against still contains the
  committed track unchanged.

The bound is **2 m**. The worst measured value over the replay is 0.060 m, so
2 m leaves room for projection and step-line rounding while staying far below
the 15 m noise (a follower that returned the raw fixes would fail) and below the
35 m off-route threshold.

## Results

| engine | run | fixes | worst snapped deviation | raw fix deviation |
|---|---|---|---|---|
| `GeometryRouteFollower` | desktop JVM, `./check.sh --fast` | 60/60 | 0.000 m | 15.000 m |
| `FerrostarRouteFollower` | API 35 x86_64 AVD `grassmaps-bench` | 60/60 | 0.060 m | 15.000 m |

The Ferrostar run is `navigation/src/androidTest/.../FerrostarReplayTest`, three
tests, reproduced identically on a second run. It is not part of `check.sh`
because the Ferrostar core ships Android ABIs only; the desktop gate runs the
same harness and the same session with `GeometryRouteFollower`. The device was
the local AVD, not the Moto g32.

## Guidance panel replay (T-0310)

`plugins/guidance-ui` measures its four facts against the same committed line.
The route is the track's 60 points, 82.587 m, split into three legs at indices
20 and 40 (28.0 m and 55.9 m), with one instruction per maneuver and a different
speed per leg (1.2, 1.6 and 1.0 m/s). The 20 replay points are every third
`trkpt` from the first to the fifty-fifth:

| # | trkpt | time (UTC) | along (m) |
|---|---|---|---|
| 1 | 0 | 09:00:00 | 0.0 |
| 2 | 3 | 09:00:03 | 4.2 |
| 3 | 6 | 09:00:06 | 8.4 |
| 4 | 9 | 09:00:09 | 12.5 |
| 5 | 12 | 09:00:12 | 16.8 |
| 6 | 15 | 09:00:15 | 20.9 |
| 7 | 18 | 09:00:18 | 25.2 |
| 8 | 20 | 09:00:20 | 28.0 |
| 9 | 23 | 09:00:23 | 32.1 |
| 10 | 26 | 09:00:26 | 36.3 |
| 11 | 29 | 09:00:29 | 40.6 |
| 12 | 32 | 09:00:32 | 44.8 |
| 13 | 35 | 09:00:35 | 48.9 |
| 14 | 38 | 09:00:38 | 53.1 |
| 15 | 40 | 09:00:40 | 55.9 |
| 16 | 43 | 09:00:43 | 60.1 |
| 17 | 46 | 09:00:46 | 64.4 |
| 18 | 49 | 09:00:49 | 68.6 |
| 19 | 52 | 09:00:52 | 72.8 |
| 20 | 55 | 09:00:55 | 77.0 |

The expected next turn, second turn, next stop distance and next stop time are
computed in the test from the route's instructions and segments, one span at a
time, without calling the panel or `RouteProgress`. The 20 fixes run through
the real `NavigationSession` with the geometry follower, and the panel reads the
published state the way the app will: the distance along is the route total
minus the remaining distance, and the status comes from the phase and relation.
Over the 20 points the worst mismatch is 0.000000 m and 0.000000 s for each of
the four facts, with no turn text changing. The points cover all three legs,
both leg ends, every instruction and both sides of the one-metre pass rule:
points 8 and 15 sit exactly on a leg end, so the turn at that end is behind the
driver and the next stop advances.

## Commands

```sh
# Desktop gate, the same harness with the geometry follower
./gradlew :navigation:testDebugUnitTest --tests '*ReplayNoiseTest*'

# The guidance panel's 20-point acceptance
./gradlew :plugins:guidance-ui:testDebugUnitTest --tests '*GuidanceReplayTest*'

# Instrumented Ferrostar run (emulator or device must be attached)
ANDROID_SERIAL=emulator-5580 ./gradlew :navigation:connectedDebugAndroidTest
```

What was not measured: the Moto g32, and the instrumented test's timing. No CI
job runs the instrumented test, so CI does not prove the native snapping path.
The guidance panel was measured on the desktop JVM only; no emulator or device
run, and the panel is not wired into the app yet.
