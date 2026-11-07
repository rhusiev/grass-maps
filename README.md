# Grass Maps

Grass Maps is an Android app for offline maps, routing and navigation. Kotlin
and Compose build the UI. A Rust core will hold search, analysis and region
data. MapLibre Native sits behind our own map interface, so the map binding
stays a one-module swap.

This repository holds the milestone-0 skeleton: the Gradle build, the module
layout, the plugin boundary rule, the command bus, and the plugin host. No
product feature is implemented yet.

## Build

Requirements: JDK 21 and an Android SDK with platform 37.

```sh
export JAVA_HOME=$HOME/dotfiles/local/share/jdk
./gradlew :app:assembleDebug
```

Create an untracked `local.properties` with the absolute SDK path before the
first build:

```properties
sdk.dir=/home/you/.local/share/android-sdk
```

The build uses Gradle 9.3.1, AGP 9.1.0 with built-in Kotlin, compileSdk 37,
targetSdk 36 and minSdk 29. The root buildscript puts Kotlin Gradle plugin
2.4.21 on the classpath: AGP's built-in Kotlin reads it, and maplibre-compose
0.20 is compiled with Kotlin 2.4 metadata. Do not apply the standalone Kotlin
Android plugin.

### Rust core

`rust/grass-core` is the offline search reader behind `PlaceProvider`. Gradle
builds it with `cargo` for the host (JVM tests) and for `arm64-v8a` and
`x86_64`, generates the Kotlin uniffi bindings from the host library, and
packages the stripped `.so` files from `places/build/rust-jni`. The toolchain is
pinned by `rust/grass-core/rust-toolchain.toml` (1.93.1 with the two Android
targets), and the NDK is found through `ANDROID_NDK_HOME` or the newest
`$ANDROID_HOME/ndk/*`. A build without either fails at configuration with a
message naming the missing part.

The index itself is not bundled in the app. The debug build reads it from
`<externalFilesDir>/search/<region>/`; the committed Lviv section under
`places/src/test/resources/search` is the quality oracle for the P4a probes.
`./gradlew :places:test` needs no device and no emulator.

```sh
rustup toolchain install 1.93.1 --profile minimal \
    --target aarch64-linux-android,x86_64-linux-android
```

## Checks

`./check.sh` is the gate every agent and the orchestrator runs before merging.
On this machine it re-executes itself under the `gm-build` slot, so Gradle
never runs outside the slot:

```sh
./check.sh                 # everything below, including reproducibility
./check.sh --skip-repro    # the fast loop; CI runs reproducibility separately
```

It runs Spotless with ktlint from the root, Android lint,
`:app:assembleDebug`, the JVM unit tests, `checkTranslations`,
`checkPluginBoundaries`, `checkPluginManifests`, and two unsigned release
builds at different paths compared byte for byte. The test count is reported;
an empty suite fails the gate, so deleting the last test cannot pass CI.

`checkTranslations` checks every module with `src/main/res/values*/strings.xml`:
each key must exist in English (`values`) and Ukrainian (`values-uk`), with a
non-blank value in both, and no key may exist only in the translation. The
checker parses XML on the JVM, so it needs no emulator or Robolectric.

User-facing text goes through the `Strings` service in `plugin-api`. Core and
feature modules receive it as a `plugin-api` interface and never take an
Android `Context`. `core-services` implements it over the application's
resources; `app` wires `AndroidStrings` in the composition root.

`checkPluginBoundaries` enforces the plugin module dependency rule.
`checkPluginManifests` validates every `src/main/plugin.json`: unique plugin
ids, namespaced command and stream ids, command implementations, setting
defaults and scopes, dispatch policies, and stream serializers. Plugin modules
wire both checks into `check`.

```sh
./scripts/ci/android-smoke.sh app/build/outputs/apk/debug/app-debug.apk
```

The device check installs and launches the debug APK and fails on a crash or
on a StrictMode policy violation. CI runs it on an API 29 x86_64 emulator.
Debug builds install the policy through `DebugApplication`, which extends the
main `GrassMapsApplication` and is also the marker the script requires, so an
APK without the policy cannot pass by staying silent.

### Benchmarks (emulator, trend only)

The emulator benchmark records cold start and frame pacing for every change so
the trend is visible per commit:

```sh
./scripts/ci/android-benchmark.sh app/build/outputs/apk/debug/app-debug.apk
```

On this machine the script takes the `gm-emulator` slot itself and boots the
`grassmaps-bench` AVD when no device is attached. It prints the JSON on stdout
and writes the same document to `build/benchmarks/`, which is not committed.
CI runs the measurement in its own job on an API 35 x86_64 emulator and uploads
the JSON as an artifact named after the commit.

The document is keyed by `commit`, `ci`, `timestamp` and `device`, and holds
`cold_start.total_time_ms` from `am start -W`, `cold_start.displayed_ms` and
`cold_start.process_to_first_frame_ms` from the launch logcat, and
`frame_pacing` from `dumpsys gfxinfo framestats`: frame-time p50/p90/p99, frame
intervals when a window draws more than one frame, late intervals over 1.5
vsync, the system's own frame percentiles, and the interaction commands. One
warm-up launch is discarded so the first install does not skew the trend.
Frame pacing covers app-window UI frames only; MapLibre's SurfaceView frames
carry no FrameTimeline vsync id, so a map needs the SurfaceFlinger latch method
from the bench `RESULTS.md`.

These numbers are trends. They are not the Moto g32 acceptance gate and must
not be compared across GPU modes or hosts. A launch that exceeds the
ActivityManager wait is recorded as `status: timeout` with its wait time, not
treated as a failed run.

The benchmark job is advisory. A failed emulator host leaves the job red and
the data missing for that commit, but it does not fail the workflow; the gate,
API 29 smoke and reproducibility jobs are the blocking checks.

GitHub Actions (`.github/workflows/ci.yml`) runs on every push and pull
request. Format, lint, build, unit tests, boundaries and manifest lint are one
fast job; reproducibility, the API 29 emulator smoke and the trend benchmarks
run as separate jobs. The
iOS compile job is named as pending until an iOS or Kotlin Multiplatform target
exists, and its summary says that it verified nothing.

## Test data and contract suite

`test-data/regions/fixture-mini/` is a small region package for JVM tests. It is
built by `tools/pipeline/build-test-region.sh` with the same `pmtiles extract`
stage real oblast packs use, and its manifest records each file's size and
sha256. `plugin-api` publishes the shared paged-provider contract suite as test
fixtures; `PagedProviderContractSuite` covers limits, paging, ordering,
concurrency and cancellation. See `tools/pipeline/README.md`.

`test-data/styles/bench-light.json` is the phase-A dense bench style. The
T-0020 spike checks that every layer, filter and expression in it compiles to a
maplibre-compose layer node, and `plugins/style-standard` generates its provider
data from the same file.

`test-data/location/mock-walk.gpx` is a 60-point synthetic walk around Lviv at
one fix per second. The debug build replays it through `MockGpxLocationSource`;
`core-services` tests parse and replay the same file. It is authored data, not
an OSM extract.

## Module map

Each namespace is `nl.r1a.grassmap.<module>`, with dots in place of hyphens in
the module name. Only `app` is an application module; its application ID is
`nl.r1a.grassmap`.

| Module | Owns | Depends on |
|---|---|---|
| `plugin-api` | base types, domain value types, host interfaces, service contracts, capability contracts, manifest schema, contract versions | Kotlin stdlib, coroutines, serialization |
| `plugin-ui-api` | Compose UI contracts for compiled-in UI plugins | `plugin-api` |
| `core` | command bus, plugin host, stream registry, capability registry, lifecycle, undo/redo stacks | `plugin-api` |
| `core-services` | settings and profiles, storage, location, network gate, i18n, clock, logging | `core`, `plugin-api` |
| `map` | the `MapHost` implementation, style composer, layer slots, gestures, camera, location puck | `plugin-api` |
| `regions` | catalogue, download, verify, mount, update, source resolver, routing pack handling | `plugin-api` |
| `routing` | engine host, request builder, route model assembly, the Valhalla adapter | `plugin-api`, valhalla-mobile (MIT) |
| `navigation` | route follower, off-route detection, reroute policy, guidance | `routing`, `plugin-api` |
| `alerts` | alert sources, scheduling, sound, vibration and banner output | `navigation`, `plugin-api` |
| `places` | place and search provider hosts, favourites store | `plugin-api` |
| `tracks` | GPX import and export, track store, analysis engine | `plugin-api` |
| `ui-shell` | screens, bottom bar and sheet, configurable button and widget grid, command palette, theme, strings | `plugin-api`, `plugin-ui-api` |
| `control` | JSON-RPC control channel and MCP server, peer auth, allowlist, privacy switch | `core`, `plugin-api` |
| `devtools` | debug harness: benchmark hooks, GPX replay, screenshot and state probes | `core`, `control`, `plugin-api` |
| `app` | composition root, Android entry points, foreground service, manifest, resources | every module |
| `plugins/example` | example of a first-party plugin module | `plugin-api` |
| `plugins/style-standard` | the Standard and Touring map styles as a `MapLayerProvider` | `plugin-api` |
| `plugins/synthetic-data` | the synthetic elevation `DataSource` and its real contour overlay | `plugin-api` |
| `plugins/gpx` | GPX 1.0/1.1 as a `FileFormat` provider | `plugin-api` |

## Map seam

`MapHost` in `plugin-api` is the only map contract. `map` binds it to
maplibre-compose 0.20 (OpenGL) and no other module imports that library. A
`MapLayerProvider` supplies MapLibre style-spec JSON; `map` compiles it to
Compose layer nodes and draws the layers in fixed slots. The logical source IDs
are `world` (z0-z7), `region` (z8-z14) and `contours` (z9-z14). The host
resolves each ID to a mounted archive and skips layers whose source is absent.

The Standard and Touring variants share one `BaseStyle.Json` document, so a
variant switch updates layers in place instead of reloading the style. A base
style carries sources and glyphs but no layers; the consumer applies the
provider's layers with `applyStyleDiff`, and only `setStyle` may reload. The
T-0020 spike uses the phase-A Protomaps-derived bench style as its reproduction
baseline; the authored Standard and Touring palettes are T-0603.

The debug app resolves `lviv-protomaps.pmtiles` from its external files
directory and `lviv-contours-20m.pmtiles` for the contour overlay. With no
Lviv archive it copies the small T-0015 fixture (`fixture-mini-map.pmtiles`)
out of its assets and starts at Ivano-Frankove.

### Location puck

`MapHost.setLocation(LocationFix?)` draws or clears the own-position puck. The
puck is not a Compose overlay: `map` builds a GeoJSON point and draws it with a
`CircleLayer` in the `Markers` slot. The exact maplibre-compose 0.20 API is
`rememberGeoJsonSource(GeoJsonData.JsonString(featureCollectionJson))` plus
`CircleLayer(id, source, radius, color, strokeColor, strokeWidth)`; both live in
`map/style/ComposedMapLayers.kt` and no library type leaves `map`.

The source is keyed on the GeoJSON data value, so each fix produces a new
definition that the library reconciles into the same source. That is one update
per fix at 1 Hz, not a rebuild per frame. A null fix removes the source and
layer. The puck has no sprite asset and no deadband yet; T-0103 owns the
deadband and smooth animation.

A map gesture does not stop following in T-0022. The camera-mode button and the
free/follow/heading cycle are T-0106; until then the follow button only toggles
`Free` and `Follow`.

## Map controls

Zoom in, zoom out, north-up and follow-position are ordinary commands:
`core.map.zoom_in`, `core.map.zoom_out`, `core.map.north_up` and
`core.map.follow`. They run on the `Main` dispatcher, are not undoable and are
not allowlisted for the control channel. The control catalog therefore lists
none of them.

The commands are process-scoped, but the map host is not. maplibre-compose 0.20
owns `MapState` inside the composition, so `app` keeps a `MapSessionHolder` in
`AppContainer`: `MapScreen` binds the current host in a `DisposableEffect` and
clears it on dispose. A command before the first bind, or after the surface
goes away, returns `AppError.NotReady` instead of writing to a dead engine.
Camera restoration across Activity recreation is deferred with the rest of the
map session (architecture section 8.1); the holder is not a camera store.

`core.map.follow` toggles a process-scoped `LocationController`. The controller
collects a `core-services` `LocationSource` and, on every fix, calls
`MapHost.setLocation`. While following it also calls `moveCamera(center)`; while
free it does not move the camera. `setTrackingMode(Follow/Free)` mirrors the
state. The controller holds the map session, not a surface, so it keeps running
across Activity recreation and pushes the latest fix to a newly bound host.

The app builds the four commands once, gives them to `PluginHost` as
`hostCommands`, and appends matching `GeneratedCommand` entries owned by
`PluginId("core")` to the bus catalog. `PluginHost.command` returns a host
command directly, so dispatching one never creates or activates a plugin.

The button order is per profile and stored by `ButtonLayoutStore` in
`filesDir/controls/button-layout.json`. The file is a JSON object keyed by
profile id; a write goes to a temporary sibling, is flushed and moved over the
target atomically, so a failed write leaves the old order. A stored order must
be a permutation of the four known command IDs, and a missing, corrupt,
partial, repeated or old three-button order reads as
`zoom_in, zoom_out, north_up, follow`. The active profile is the fixed
`default` profile until T-0601. The bar itself lives in `ui-shell` and only
receives control IDs, labels and callbacks, so it never touches the command bus
or the map host.

The release build installs `NoLocationSource`, so it draws no puck. A debug
build installs `MockGpxLocationSource` over the bundled `mock-walk.gpx` and
replays one fix per second, looping at the end. The mock lives in
`DebugApplication` and `LoggingLocationSource` in the debug source set; the
release application never references them.

## DataSource seam

`DataSource` in `plugin-api` is the capability contract `data.source` 1.0.0.
It publishes typed way attributes (paged by limit and cursor, with a declared
maximum), engine-neutral cost modifiers that carry way geometry, and one
optional map layer. Values stay keyed by OSM way id and region build, so a
provider from an older build is stale and never applied.

The first-party `data.synthetic` plugin in `plugins/synthetic-data` exercises
that seam. Its elevation attributes and its two cost modifiers are authored
T-0023 fixtures over the fixture-mini bbox, not region data. Its map layer is
real: a line layer on the `contours` source, source-layer `contours`, in the
overlays slot, filtering the archive's index lines with `idx == true` (the
archive stores a Boolean; an integer comparison matches nothing, which also
leaves the T-0020 style contour layers blank). The drawn contours are real and
the attributes remain synthetic.

`app` resolves the provider through `ProviderRegistry` off Main and adds the
layer under `PluginId("data.synthetic")`; a missing provider or a rejected layer
is logged and the map keeps running. `DataSourceAccess` is the typed handle
routing will read cost modifiers through in T-0024.

## Plugin boundary rule

A module under `plugins/` may depend on `:plugin-api`. It may also depend on
`:plugin-ui-api` when it contributes Compose UI. No other project dependency is
allowed in any configuration.

`./gradlew checkPluginBoundaries` checks every module under `plugins/` and
fails with each violating module, configuration and dependency. Plugin modules
wire this task into `check`.

Feature modules never import `core` or `core-services`. They take the host and
service interfaces they need in their constructors, and `app` wires the
implementations. The only feature-to-feature edges are `navigation -> routing`
and `alerts -> navigation`.

## Plugin manifests

Every module under `plugins/` carries `src/main/plugin.json`. At build time the
`generatePluginRegistry` task reads those manifests and writes
`GeneratedPlugins.kt` and `GeneratedStreams.kt` into `app`, so the host never
scans the classpath or uses reflection.

A manifest declares the plugin id, version, the `plugin-api` version, the
implementation class, and its contributions: commands with parameter and result
schemas, their dispatch policy (undoable, required switch, control allowlist and
dispatcher), providers with contract versions and static capabilities, streams
with a value type and serializer, and settings with defaults and scopes. The host
rejects a command whose code policy disagrees with its manifest. The example
plugin declares one command, one stream and one provider.

`checkPluginManifests` fails the build when a manifest breaks the rules above,
including a declared command whose implementation class is missing from the
plugin's source, a command id in the reserved `core` host namespace, or a policy
field the generator does not understand. Each plugin module's `check` and
`app:check` depend on it.

## Debug control channel

A debug build starts a JSON-RPC 2.0 control channel on the Linux abstract
socket `nl.r1a.grassmap.control`. Two gates protect it: the peer UID from
`SO_PEERCRED` must be the app's or the adb daemon's that carries `adb forward`,
and the peer must send the 256-bit bearer token from the app-private
`files/control-token` file. Only commands whose generated
registry entry has `allowlistedForControl` run. A release build does not contain
`:control` or `:devtools` at all, because the app depends on them in the debug
configuration only.

`tools/control/grassctl.py` lists commands, invokes an allowlisted command,
prints stream values and saves a screenshot. `tools/control/mcp_server.py`
exposes the same channel over stdio MCP. See `tools/control/README.md` for the
method list, the token handling and the CLI examples.

## Routing seam

`RoutingEngine` in `plugin-api` is the engine contract. `routing` binds it to
valhalla-mobile 0.6.4 and keeps every library type inside that module. The
engine reads a pack directory copied to the device's external files, writes a
local `valhalla.local.json` whose paths point at that pack, and creates the
native actor on the first route.

`MapHost.setRoute(Route?)` draws the route as one GeoJSON `LineString` in the
`Routes` slot, using the same source pattern as the location puck. The app's
debug control channel exposes `core.route.test_walk`, which routes a short walk
in the fixture area or in Lviv and, with `{"modifiers": true}`, asks the
synthetic DataSource for cost modifiers and routes again. Valhalla takes a
multiplier as `linear_cost_factors`; when the synthetic line has no graph edges
near it, the adapter retries with `exclude_polygons` around the same line.

## Navigation seam

`NavigationSession` in `navigation` binds Ferrostar's Rust core to our route
model. It takes a `Route`, a `Flow<LocationFix>` and a `RoutingEngine`, and
emits `NavigationState`: phase, on-route relation, distance from the line,
snapped position, remaining distance and time, and the next instruction. No
Ferrostar type leaves the module; `FerrostarRouteFollower` is the only file
that names one.

The `com.stadiamaps.ferrostar:core` AAR (0.59.0) is used without `ui-compose`
or `ui-maplibre`; the native core is the `uniffi.ferrostar.NavigationSession`
created by `createNavigationSession`. Route conversion carries geometry,
bounds, distance, waypoints and steps. Steps are split at the instruction
distances because our `Route` has no per-step geometry. Elevation and typed way
attributes stay in our model; Ferrostar's route has neither.

Off-route uses Ferrostar's `StaticThreshold` deviation detector with the
configured `maxDeviationMeters`. When the core reports no deviation (including
its own horizontal-accuracy gate) or is unavailable, a `PolylineLocator`
distance over the whole line decides instead. Two consecutive off-route fixes
start a reroute through `RoutingEngine` from the current position to the
original destination, and the session logs the detection-to-route-ready
milliseconds. That is a seam policy, not the final one: T-0312 owns the 20 s
profile-scaled, debounced policy with accuracy gating and rejoin-first.

The debug channel exposes `core.nav.test_follow`. It routes a walk, generates a
GPX from that line with a sideways segment past the deviation threshold, parses
it through `MockGpxLocationSource`, follows it, and draws the replacement route.
`{"area":"fixture"}` and `{"area":"lviv"}` select the area; `tick_ms` and
`detour_m` tune the replay.

## Search seam

`PlaceProvider` in `plugin-api` is the `places.search` 1.0.0 contract:
`search(query, context, limit, cancel)`, `pois(bbox, categories, limit, cancel)`
and `details(placeId, cancel)`. `places` implements it over `rust/grass-core`
through uniffi. The generated Kotlin and every Rust type stay inside `places`
(package `nl.r1a.grassmap.places.core`); no plugin imports them.

The reader uses the P4a spike format unchanged: an FST of folded terms,
delta-varint postings, a fixed 20-byte place table, context entities and a
string pool. It mmaps read-only, validates every offset before handing the
index out, and returns `CorruptData` for a truncated section. Ranking, typo
budgets and neighbour collapsing are the spike's, so the osm-mapidx probe table
stays the quality oracle: 45 of 45 in the top five, asserted by
`RustPlaceProviderQualityTest`.

The index is external data: the provider reads
`<externalFilesDir>/search/<region>/`. The committed Lviv section under
`places/src/test/resources/search` is 10.1 MiB and ODbL-licensed. The debug
channel exposes `core.search.query`:

```sh
tools/control/grassctl.py invoke core.search.query \
  '{"query":"проспект шевч","index":"lviv","position":{"lat":49.8419,"lon":24.0316}}'
```

It times every keystroke, logs the prefix, milliseconds, hit count and top hit,
and returns p50/p99. `{"cancel":true}` runs an already-superseded keystroke and
reports `cancelled=true hits=0`. On the API 35 emulator (`grassmaps-bench`,
host OpenGL), a street prefix measured p50 3.7 ms and p99 5.5 ms per keystroke
including the JNA bridge; the full string of 13 keystrokes took 111 ms. The
first single-letter keystroke after opening the index cost 63.6 ms (cold FST
walk); later single-letter keystrokes stayed under 11 ms. The Moto g32 budget is
T-0203's.

## Tracks seam

`FileFormat` in `plugin-api` is the `file.format` 1.0.0 contract. Its static
members (extensions, MIME types, entity kinds) let the host choose a provider
without activating it. `importFile(bytes, cancel)` returns the tracks, routes
and waypoints it could read plus one warning per skipped part, so a partly
broken file still imports. `export` is a typed `Unsupported` until T-0410.

`plugins/gpx` (`file.gpx`) implements GPX 1.0 and 1.1 for tracks, routes and
waypoints. It reads element names without a namespace or prefix assumption and
hardens the XML parser against doctypes and external entities. A point with
missing or out-of-range coordinates is skipped with a structural locator such
as `trk[0]/trkseg[1]/trkpt[4]`; a broken elevation or time only clears that
field. A document that is not well-formed XML at all is `CorruptData`.

`tracks` owns the import path: resolve the provider through `ProviderRegistry`
in `app`, read the document, store the first track keyed by the SHA-256 of its
bytes, and compute the profile. T-0027 stores in memory; the file store,
folders and undo are T-0401/T-0414.

The profile is Kotlin in `tracks` for T-0027, not the Rust core. `tracks` may
depend only on `:plugin-api` (module map and plugin boundary rule), the only
uniffi build lives in `places`, and a second module packaging `libgrass_core.so`
would collide at APK merge. Moving analysis behind uniffi needs a shared Rust
wrapper module and a design decision; T-0402 (statistics) and T-0403
(elevation correction) own that move.

`TrackAnalyzer` sums every raw elevation delta between consecutive points of a
segment as ascent or descent, with no smoothing and no noise threshold, and
measures ground distance with the haversine formula on the IUGG mean radius
6 371 008.8 m. A segment boundary is not a sample. The oracle is a standalone
Python one-off over `test-data/location/mock-walk.gpx`, recorded in
`TrackImportOracleTest`: ascent 19.2 m, descent 17.6 m, minimum 296.0 m,
maximum 297.6 m and distance 82.5866 m, within 0.01 m on elevation sums and
0.1 m on distance.

The debug channel exposes `core.track.import_test`:

```sh
tools/control/grassctl.py invoke core.track.import_test '{"path":"mock-walk.gpx"}'
```

It reads a debug asset or an absolute path, imports through `file.gpx`, stores
the track and logs the profile. No tracks UI exists yet.

## Follow-ups

`plugin-api` and `plugin-ui-api` are ordinary Android libraries in this
skeleton. Moving them to Kotlin Multiplatform source sets is a follow-up, so
their contracts stay free of Android types now.

CI, `docs/` and the benchmarks belong to the later milestone-0 tasks from
T-0012 onward. The release AI-control switch and its allowlist are T-0114.

## Licence

Apache License 2.0. See `LICENSE` and `NOTICE`. Planned asset licences and
attributions are in `ATTRIBUTION.md`.
