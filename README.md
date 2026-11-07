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

## Follow-ups

`plugin-api` and `plugin-ui-api` are ordinary Android libraries in this
skeleton. Moving them to Kotlin Multiplatform source sets is a follow-up, so
their contracts stay free of Android types now.

CI, `docs/` and the benchmarks belong to the later milestone-0 tasks from
T-0012 onward. The release AI-control switch and its allowlist are T-0114.

## Licence

Apache License 2.0. See `LICENSE` and `NOTICE`. Planned asset licences and
attributions are in `ATTRIBUTION.md`.
