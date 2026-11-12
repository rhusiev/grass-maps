# T-0401 track fixtures

Twenty GPX files that stand in for what OsmAnd, Garmin Connect and Strava
export. They are synthetic: the coordinates are around Lviv, the names are
invented, and no real activity or person is behind them. They are Apache-2.0
like the rest of this repository and contain no OSM-derived data.

`generate-fixtures.py` writes every file and prints the figures that
`TrackFixturesTest` asserts. Run it after changing a shape:

```sh
python3 test-data/tracks/generate-fixtures.py
```

The printed table is the oracle, not a recording of the production analyser.
Points and ascent/descent follow from the authored elevation sequence, one step
per point, with a segment boundary counting as no step. The test tolerates
1e-6 m on the elevation sums.

## What each file covers

| File | Exporter shape | What it exercises |
|---|---|---|
| `osmand-walk-two-segments.gpx` | OsmAnd 4.9, `osmand` namespace | two `trkseg` blocks, elevation and time on every point |
| `osmand-morning-run.gpx` | OsmAnd 4.9 | a UTF-8 Cyrillic name, a six-point run |
| `osmand-multi-track.gpx` | OsmAnd 4.9 | two `trk` elements in one document; one record per track |
| `osmand-waypoints.gpx` | OsmAnd 4.9 | three `wpt` children with `sym`, one without elevation |
| `osmand-no-elevation.gpx` | OsmAnd car profile | no `ele` on any point, so the elevation range is absent |
| `osmand-no-time.gpx` | OsmAnd | elevation but no timestamps |
| `osmand-single-point.gpx` | OsmAnd | a one-point track: valid data, no drawable line |
| `osmand-planned-route.gpx` | OsmAnd route export | a `trk` and a `rte` in one file |
| `garmin-connect-hike.gpx` | Garmin Connect | `gpxtpx` namespace, per-point heart rate and cadence extensions, metadata |
| `garmin-connect-autopause.gpx` | Garmin Connect | three segments split by auto-pause |
| `garmin-fenix-run.gpx` | Garmin Fenix | two interval segments with a recovery gap |
| `garmin-oregon-gpx10.gpx` | Garmin Oregon | GPX 1.0 with the default namespace |
| `garmin-waypoints-route.gpx` | Garmin Connect | two waypoints and one route beside the track |
| `strava-run.gpx` | `StravaGPX` | metadata, heart-rate extension, eight points |
| `strava-ride-pauses.gpx` | `StravaGPX` | four auto-pause segments and a power extension |
| `strava-no-elevation.gpx` | `StravaGPX` | a GPS-only export with no `ele` |
| `strava-route-and-track.gpx` | `StravaGPX` | a saved route and its track together |
| `strava-ukrainian-name.gpx` | `StravaGPX` | a UTF-8 track name with a non-ASCII alphabet |
| `broken-partial.gpx` | OsmAnd with a damaged body | one readable segment plus every skippable part: missing and non-numeric coordinates, out-of-range coordinates, a broken `ele`, a broken `time`, an empty segment and a track with no readable segment |
| `broken-empty-tracks.gpx` | Garmin Connect with empty blocks | an empty track, an empty segment and a point without coordinates, with two readable segments left |

`broken-partial.gpx` is the file the milestone names: it imports partly and the
import returns one warning line per skipped part, for example
`trk[0]/trkseg[0]/trkpt[2]: missing coordinates`. `TrackFixturesTest` asserts
the complete list in document order.

## Where the profiles are asserted

- `app/src/test/.../TrackFixturesTest` imports each file through the real
  `file.gpx` provider and a `FileTrackStore` in a temporary directory.
- `plugins/gpx/src/test/.../GpxFixtureShapeTest` covers the non-track entities:
  waypoints, routes, GPX 1.0 and the UTF-8 name.
- `tracks/src/test/.../FileTrackStoreTest` covers the store itself with a tiny
  test document format, so a store defect is not hidden behind the parser.
