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

## Commands

```sh
# Desktop gate, the same harness with the geometry follower
./gradlew :navigation:testDebugUnitTest --tests '*ReplayNoiseTest*'

# Instrumented Ferrostar run (emulator or device must be attached)
ANDROID_SERIAL=emulator-5580 ./gradlew :navigation:connectedDebugAndroidTest
```

What was not measured: the Moto g32, and the instrumented test's timing. No CI
job runs the instrumented test, so CI does not prove the native snapping path.
