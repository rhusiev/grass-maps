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

`rust/grass-regions` is the region reader behind `RegionAccess` (T-0321). It is
wired the same way and from its own package, `nl.r1a.grassmap.regions.core`:
`:regions` builds a host library for the JVM tests and one per ABI, generates
the bindings from the host library, and packages `libgrass_regions.so`, so the
two crates do not collide at APK merge. `./gradlew :regions:test` needs no
device and no emulator.

The index itself is not bundled in the app. The debug build reads it from
`<externalFilesDir>/search/<region>/`; the committed Lviv section under
`places/src/test/resources/search` is the quality oracle for the P4a probes.
`./gradlew :places:test` needs no device and no emulator.

```sh
rustup toolchain install 1.93.1 --profile minimal \
    --target aarch64-linux-android,x86_64-linux-android
```

### Measurement build

`./gradlew :app:assembleMeasure` builds the self-measuring variant for a phone
that is away from adb. It is signed with the debug key and is not a release:
`measure` is its own build type (not debuggable, application id suffix
`.measure`) with one launcher screen in `app/src/measure` and its pack assets.
`release` and `benchmark` do not include that source set, so neither the
screen nor the pack can ship.

The screen plans the Lviv bench route once cold and ten times warm for the walk
profile, then for bike (warm only, because walk mounted the engine), and follows
the reroute replay five times per profile through the real `NavigationSession`
with the 20 s gate: 22 m walking and 56 m cycling. It writes
`<externalFilesDir>/measurement/report.json`, shows it, and offers a share
sheet; nothing is uploaded. The report carries a `definitions` object that
states what each number includes and excludes, and the screen shows the same
text. Every reroute the session installs is listed with its fix indices, so the
headline `first_reroute_ms` (the acceptance number) is distinguishable from
`all_reroutes_median_ms`. The schema is `grass-maps-measurement/2`.

The Lviv Valhalla pack is a generated input, not a tracked file, so point the
build at a directory that contains `valhalla-lviv/`:

```sh
./gradlew :app:assembleMeasure -PmeasurePackDir=/path/to/measure-pack/assets
```

Without the property the APK still builds and the screen reports the pack as
absent. On the first launch the screen copies the pack to
`<externalFilesDir>/valhalla/` and reports the bytes and milliseconds it wrote.

## Checks

`./check.sh` is the gate every agent and the orchestrator runs before merging.
On this machine it re-executes itself under the `gm-build` slot, so Gradle
never runs outside the slot:

```sh
./check.sh                 # everything below, including reproducibility
./check.sh --fast          # the fast loop; CI runs reproducibility separately
./check.sh --micro         # the tight loop: fast minus Android lint
```

`--skip-repro` is an alias for `--fast`. `--micro` also skips Android lint,
which is the next slowest step; lint runs at pre-push and in CI, so a `--micro`
run is iteration evidence only. The gate runs Spotless with ktlint from
the root, Android lint, `:app:assembleDebug`, the JVM unit tests,
`checkTranslations`, `checkPluginBoundaries`, `checkPluginManifests`,
`lintStyleRules`, `:plugins:example:check`, `checkPrivacyGuards`, and two
unsigned release builds at different paths compared byte for byte. The test
count is reported; an empty suite fails the gate, so deleting the last test
cannot pass CI. Every step prints its duration, and the total is printed at the
end.

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
defaults and scopes, dispatch policies, and stream serializers. The plugin
modules and the planner wire both checks into `check`.

`checkPrivacyGuards` enforces the privacy rules in `AGENTS.md`. It resolves
every module's debug and release runtime classpath and fails on a Play Services,
ads or tracker artifact, including one that arrives transitively; the signatures
come from the checked-in `ci/privacy-denylist.txt`, so the guard is offline and
deterministic. It also inspects both merged app manifests and fails on
`ACCESS_BACKGROUND_LOCATION`, an account authenticator or account permission.
The same check asserts that `MainActivity` keeps
`android:screenOrientation="portrait"` and that the application keeps
`android:allowBackup="true"`. Every failure names the exact coordinate or
manifest entry. Build-logic unit tests cover both rules and run with the rest of
`buildSrc` in the gate.

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
ActivityManager wait is recorded as `status: timeout` with its wait time. The
measurement then waits up to 120 s for the logcat `Displayed` line, and the
cold-start summary falls back to process-to-first-frame when ActivityManager
never completed. A run with neither a first frame nor a frame-pacing row fails
and keeps its JSON: a green job with no sample is worse than a red one.

The benchmark job is advisory. A failed emulator host leaves the job red and
the data missing for that commit, but it does not fail the workflow; the gate,
API 29 smoke, native follower replay and reproducibility jobs are the blocking
checks.

The job also used to pass while measuring nothing. Every stored JSON from
2026-11-09 to 2026-11-11 held five timed-out launches and zero frame rows,
because the read happened before the app's first frame. The measurement now
waits for the `Displayed` line: run `42989063035` produced five
process-to-first-frame samples (median 34.3 s) with no retry, and run
`42983215413` produced five on a second attempt after the first died at the
post-boot input call (median 41.0 s). The
frame-pacing window still returned no rows; treat frame pacing as unmeasured
until a run reports them.

The fragile part is the runner's emulator, not the measurement. Between
2026-11-09 and 2026-11-11 seven runs failed before the measurement started,
all with `cmd: Failure calling service ...: Broken pipe (32)` (for example
`42739619598` and `42959054167`); the smoke failed twice in the same window.
The
runner's own post-boot calls include an unconditional `input keyevent 82`,
which happens before this script and cannot be gated from here. The repo's
part is fixed: the job leaves the animation calls to the script and gates its
own adb calls on the package and settings services
(`scripts/ci/lib/device.sh`). If a service dies after that, the job is red and
the log names the call; it is not retried. The measured boot times and the
frequency are under "Runner limits".

### Map pacing (emulator, trend only)

The map draws into a SurfaceView, so `dumpsys gfxinfo` cannot see its frames.
`scripts/bench/map-pacing.sh` traces SurfaceFlinger with Perfetto during a fixed
gesture run and times map frames from the layer's `BufferTX` counter, which
drops on every latch:

```sh
./scripts/bench/map-pacing.sh app/build/outputs/apk/debug/app-debug.apk
```

The run pools three traces by default, prints the interval summary and writes
one JSON document per commit to `build/benchmarks/map-pacing/<sha>.json`. The
document holds the map-frame interval percentiles, the late-interval count and
share over 1.5 vsync, missed vsyncs, the trace digests, the device, and the
refresh rate. The gesture run is a four-pass pan followed by a double-tap zoom;
pinch, rotate and the two-finger tap need injected multi-touch and are not part
of this run.

`scripts/bench/map-frame-timeline.py` does the timing when traces already
exist. Its rules (a counter fall is one latch, a gap over 250 ms is gesture
idle, over 1.5 periods is late) have a unit test:

```sh
python3 scripts/bench/map_frame_timeline_test.py
```

The late-interval share is the M1 dropped-frame measure. Its acceptance gate is
the Moto g32 at 90 Hz with the phone's prefixed `BufferTX - *SurfaceView[...]`
track; emulator numbers are trends and must not be compared across GPU modes or
hosts.

### Reroute latency (Moto g32)

`scripts/bench/reroute-latency.sh` measures automatic rerouting on the phone
with the app's own timing:

```sh
./scripts/bench/reroute-latency.sh app/build/outputs/apk/debug/app-debug.apk
```

Each sample is `NavigationRun.lastRerouteMillis`: the wall time from the fix
that spends the last metre of the off-route budget to the installation of the
replacement route. It includes the Valhalla `/route` call and the route-model
rebuild, and excludes the 20 s travel gate, fix pacing, map redraw and the
planner. The script invokes `core.nav.test_follow` once per sample, parses the
`reroute_ms=` field of the summary and prints the median and p95 per profile.
One warm-up sample per profile is discarded, because it mounts the Valhalla
graph.

The run is phone-only and refuses an emulator: the emulator trend for this
number is T-0025. It needs a debug APK, the routing pack installed and covering
Lviv, and the `gm-phone` slot; `REROUTE_NO_LOCK=1` skips the slot. Defaults are
20 samples for walk and bike, `lviv` waypoints, a 250 ms wall tick and a 60 m
detour.

GitHub Actions (`.github/workflows/ci.yml`) runs on every push and pull
request. Format, lint, build, unit tests, boundaries and manifest lint are one
fast job; reproducibility, the API 29 emulator smoke, the native follower
replay and the trend benchmarks run as separate jobs. The
iOS compile job is named as pending until an iOS or Kotlin Multiplatform target
exists, and its summary says that it verified nothing.

The native follower replay is a real gate, not a trend job. Ferrostar's core is
an Android-only AAR, so every JVM test uses the `GeometryRouteFollower`
stand-in; `:navigation:connectedDebugAndroidTest` is the only check that runs
the code deciding where the user is on the route. It runs on API 29, the same
image as the smoke and goldens jobs and the module's `minSdk`, because the
replay asserts snapped positions rather than rendered colours.

### CI run cost

Measured on run `42965559623` (2026-11-11, a pull-request run with every job
green):

| Job | Duration |
|---|---|
| Format, lint, build, unit tests, translations, boundaries | 11 min 21 s |
| Byte-reproducible release APK | 11 min 32 s |
| API 29 emulator smoke and strict mode | 16 min 14 s |
| Emulator benchmarks (cold start and frame pacing, trend only) | 23 min 09 s |
| Style and fixture map goldens (API 29, swangle) | 86 min 02 s |
| iOS target compile (pending, no iOS target yet) | 5 s |

The goldens job dominates: it took 86 minutes while every other job finished
within the first 24, so it is the critical path and the run's wall time. Every
other job runs in parallel, so it adds runner minutes but not wall time until
the goldens job finishes. The native follower replay first ran in run
`42979708400` at 7 min 04 s (6 min 43 s in the emulator step). The goldens
job's 210-minute bound is there because successful runs took 1 h 56 m to
2 h 01 m at the previous 150-minute bound; it is the first job to split when
its duration is next addressed.

A job that regularly uses most of its timeout is the same risk as one that
fails. Both produce a red run that says nothing about the code. Set a timeout
from measured durations with a margin, and when successful runs approach the
bound, split the job or raise the bound with a measured reason. Do not delete a
slow job without recording where its work went.

### Runner limits

GitHub-hosted `ubuntu-latest` runners have no usable `/dev/kvm`. The action
logs `ProbeKVM: This user doesn't have permissions to use KVM` and starts the
emulator with `-accel off`, so every boot below is software emulation:

| Run | Image | Boot |
|---|---|---|
| `42979708400` smoke | API 29 | 117 s |
| `42969584876` smoke | API 29 | 153 s |
| `42983215413` benchmark, first attempt | API 35 | 468.6 s, then `input` Broken pipe |
| `42983215413` benchmark, second attempt | API 35 | 616.3 s |

600 s is ample for API 29 and too short for a cold API 35 image; the benchmark
sets 900 s. The action does not pass `-verbose`, so the boot log holds only the
emulator's normal output and the `sys.boot_completed` polls.

`sys.boot_completed` is the right boot signal, but it turns 1 before the binder
services are stable. In the `42969584876` smoke, the 51 empty polls ended at
`Emulator booted.` and `Boot completed in 153186 ms`; the install then failed
with `Broken pipe (32)` at 06:17:45. The emulator did boot; the package service
died. `init.svc.bootanim` would be no better. Waiting for a real `am start` to
succeed is stronger, but it is app-specific and still runs after the action's
own `input keyevent 82`.

Measured frequency: 7 of 91 benchmark jobs between 2026-11-09 and 2026-11-11
failed this way (about 8 %), and 2 of 91 smoke jobs. A system-image install failed
once with `Error reading Zip content from a SeekableByteChannel`; successful
installs took 18-29 s from the runner's SDK cache. A pre-pulled image needs a
custom runner image: GitHub-hosted runners are ephemeral and the action still
calls `sdkmanager`, so the repo cannot remove that failure.

The honest limit is that these jobs depend on a software-emulated runner that
sometimes loses its services or downloads a corrupt image. A self-hosted runner
with `/dev/kvm` is the fix. Without one, a red emulator job is a runner event,
not a code event, and the log names which call died.

## Test data and contract suite

`test-data/regions/fixture-mini/` is a small region package for JVM tests. It is
built by `tools/pipeline/build-test-region.sh` with the same `pmtiles extract`
stage real oblast packs use, and its manifest records each file's size and
sha256. Its search section is the seven files the reader maps plus the
`features` component, built in one pass from the authored
`tools/pipeline/fixture-places.tsv`, so the four fixture places carry canonical
OSM ids and tag maps instead of a stand-in marker. Its `elevation.bin` is a real
3 arcsec GLO-30 grid, so `RegionElevation` and the Valhalla grade path read real
heights. Its `way-alerts.geojson` is extracted from the fixture's own small OSM
extract, so the reader and plugin read real pipeline output rather than an
authored list. Its `relations.json` is extracted from the fixture's own hiking
input, so the route-relation reader and layer tests read real pipeline output.
`plugin-api` publishes the shared paged-provider contract suite
as test fixtures; `PagedProviderContractSuite` covers limits, paging, ordering,
concurrency and cancellation. See `tools/pipeline/README.md`.

`test-data/search/coordinate-notations.tsv` is the T-0206 coordinate vector set:
18 accepted notations with their points and 13 refused near misses with their
reason. `plugins/coordinates` reads it to prove the parser, and the app's
`CoordinateRoundTripTest` formats each point through `plugins/share` in all
three notations and parses it back.

`test-data/styles/bench-light.json` is the phase-A dense bench style. The
T-0020 spike checks that every layer, filter and expression in it compiles to a
maplibre-compose layer node. The Standard style keeps that layer list as its
reproduction baseline but owns its authored document in
`plugins/style-standard/src/main/styles/standard.json`.

`test-data/location/mock-walk.gpx` is a 60-point synthetic walk around Lviv at
one fix per second. The debug build replays it through `MockGpxLocationSource`;
`core-services` tests parse and replay the same file. It is authored data, not
an OSM extract.

`test-data/routing/` holds the v1 profile validation: the costing document the
engine sends, 100 seeded Lviv-oblast pairs, the 100x3 route table and the
derived length tolerance. Its README records the engines, their versions and the
commands; `plugins/valhalla`'s tests fail when the table stops meeting the
tolerance or loses an attribute.

## Module map

Each namespace is `nl.r1a.grassmap.<module>`, with dots in place of hyphens in
the module name. The `planner` module keeps its historical
`nl.r1a.grassmap.plugins.route.planner` namespace so the plugin id and
implementation class do not change. Only `app` is an application module; its
application ID is `nl.r1a.grassmap`.

| Module | Owns | Depends on |
|---|---|---|
| `plugin-api` | base types, domain value types, host interfaces, service contracts, capability contracts, manifest schema, contract versions | Kotlin stdlib, coroutines, serialization |
| `plugin-ui-api` | Compose UI contracts for compiled-in UI plugins | `plugin-api` |
| `core` | command bus, plugin host, stream registry, capability registry, lifecycle, undo/redo stacks | `plugin-api` |
| `core-services` | settings and profiles, storage, location, network gate, i18n, clock, logging | `core`, `plugin-api` |
| `map` | the `MapHost` implementation, style composer, layer slots, gestures, camera, location puck | `plugin-api` |
| `regions` | catalogue, download, verify, mount, update, source resolver, routing pack handling | `plugin-api` |
| `routing` | route model, the `routing.engine` contract seam, its stable JSON form, the start/end planner state machine, and the reuse history store | `plugin-api` |
| `navigation` | route follower, off-route detection, reroute policy, guidance, following a track | `routing`, `plugin-api` |
| `alerts` | alert sources, scheduling, sound, vibration and banner output | `navigation`, `plugin-api` |
| `planner` | the start/end plan, its intermediate stops, its undo and redo history, its reuse commands and its state and history streams; the shell draws the preview and the turn stepper from it | `plugin-api`, `routing` |
| `places` | place and search provider hosts, favourites store | `plugin-api` |
| `tracks` | GPX import and export, the track file store and sidecars, analysis engine | `plugin-api` |
| `ui-shell` | screens, the navigation screen, bottom bar and sheet, configurable button and widget grid, command palette, theme, strings | `plugin-api`, `plugin-ui-api` |
| `control` | JSON-RPC control channel and MCP server, peer auth, allowlist, privacy switch | `core`, `plugin-api` |
| `devtools` | debug harness: benchmark hooks, GPX replay, screenshot and state probes | `core`, `control`, `plugin-api` |
| `app` | composition root, Android entry points, foreground service, manifest, resources | every module |
| `plugins/example` | example of a first-party plugin module | `plugin-api` |
| `plugins/style-standard` | the Standard and Touring light and dark palettes and their one-zoom-earlier Touring threshold, as a `MapLayerProvider` | `plugin-api` |
| `plugins/synthetic-data` | the synthetic elevation `DataSource` and its real contour overlay | `plugin-api` |
| `plugins/gpx` | GPX 1.0/1.1 import and export as a `FileFormat` provider | `plugin-api` |
| `plugins/info-widgets` | the altitude, coordinates, GPS info and current / average / max speed widget data providers | `plugin-api` |
| `plugins/dev-tools` | the release development-tools panel and its GPX replay start/stop commands | `plugin-api` |
| `plugins/map-controls` | the free, follow and heading camera mode, the compass reset, the position-marker offset seam and the scale-bar layout | `plugin-api` |
| `plugins/point-info` | the distance and azimuth from the fix or map centre to a long-pressed point | `plugin-api` |
| `plugins/osm-places` | the `places.card` provider that maps OSM tags to place-card rows and evaluates `opening_hours` | `plugin-api` |
| `plugins/online-search` | the opt-in `places.search` provider over the public Photon geocoder, behind the online-search switch | `plugin-api` |
| `plugins/favourites-ui` | the favourites lists state, the create, move, recolour, Home and Work commands, and the browsing search, sorts and description rendering | `plugin-api` |
| `plugins/links` | the pure `geo:` and osm.org link parsers, including the osm.org short-link decoder | `plugin-api` |
| `plugins/share` | the decimal, DMS and DDM coordinate notations, the osm.org and `geo:` link builders, the copy, share and open-in commands, and track export file names and MIME types | `plugin-api` |
| `plugins/coordinates` | the coordinate notation parser the search field reads, and the vectors it shares with the formatters | `plugin-api` |
| `plugins/valhalla` | the v1 `routing.engine` provider: profile mapping, the native Valhalla adapter, the async segment attributes | `plugin-api` |
| `plugins/direct-engine` | the second `routing.engine` provider: one geodesic per leg with the haversine length, its leg segments and its honest empty capabilities | `plugin-api` |
| `plugins/guidance-ui` | the guidance panel's four facts and its Compose turn card, drawn from the navigation state the app holds | `plugin-api`, `plugin-ui-api` |
| `plugins/nav-simulator` | the simulated run at 5 km/h through the production navigation session, its start and stop commands and its status stream | `plugin-api`, `navigation`, `routing` |
| `plugins/track-layer` | the imported-track line layer and the controller that keeps it on the map | `plugin-api` |
| `plugins/route-layer` | the route line's colour and pattern decision: slope and surface palettes, the unpaved dash and the steps stripe, drawn through `MapHost` | `plugin-api` |
| `plugins/track-library` | the track library state, its folder definitions, the rename, describe, move and delete actions with one-step undo, and the four fixed orders and name search | `plugin-api` |
| `plugins/contours` | the contour overlay for the Standard and Touring variants and the one-row layers panel that toggles it | `plugin-api`, `plugin-ui-api` |
| `plugins/route-relations` | the hiking and cycling relation geometry assembler, the shared-way lane assignment, the OSMC waymark mapping, the cycling node-network numbers and the `MapHost` layers over an inline GeoJSON source | `plugin-api` |
| `plugins/route-warnings` | the warning list for fords, steps, barriers, railway crossings and private ways, with its profile filter and distance order | `plugin-api` |
| `plugins/proximity-alerts` | the proximity schedule for favourites, track waypoints and chosen place kinds: the three chosen radii, the one-per-approach latch and its lookup refresh | `plugin-api` |

## Place card

The `places.card` contract (`PlaceCardContract`, version 1.2.0) maps one
place's already-loaded `PlaceDetails` onto a `PlaceCard`. The provider gets no
index, file or network handle, so the tags it draws can only be the tags the
`places.search` contract returned. `plugins/osm-places` is the first provider;
`ui-shell`'s `PlaceCardHost` resolves its label keys through `Strings` and drops
an absent field instead of drawing an empty row. The debug command
`core.place.card_show` opens a card for an index place id, and the search screen
opens the same card from a result row (T-0203).

Version 1.2.0 adds two optional parts (T-0210): `PlaceCard.coordinates`, three
`PlaceCardEntry` rows in decimal, DMS and DDM, and `PlaceCard.shortcuts`, the
copy, share and open-in actions. A provider that leaves both empty is still
valid. `plugins/osm-places` maps the tags; `app` adds the two parts through
`plugins/share`, which owns every format and link rule, so the provider stays a
tag mapper and `ui-shell` draws rows it was given. A contact row that carries a
`PlaceCardLink` is clickable: the shell hands the link to the platform seam,
which opens `tel:`, `mailto:` or the web address (`https:` is added when the tag
omits a scheme). A `website` or `contact:*` tag is untrusted region data, so a
web target must be `http` or `https` in any letter case; any other scheme,
including `intent:`, `file:`, `content:` and `javascript:`, is a typed
`Unsupported` failure and never reaches `ACTION_VIEW`.

`PlaceCard.openingHours` keeps the raw OSM string. `PlaceCard.openingState` is
`OPEN`, `CLOSED` or `UNKNOWN` at the moment the card was built, evaluated on
the Europe/Kyiv wall clock by the `opening_hours` parser in
`plugins/osm-places`. The parser covers `24/7`, `;` and `||` rule lists,
weekday and date ranges, several time ranges, `PH`, month ranges, `off` and
comments after `"`; a value it does not understand is `UNKNOWN`, never a crash.
`ui-shell` draws the state as its own row and keeps the raw hours beside it.

The JVM parity test reads
`test-data/places/opening-hours/lviv-oracle.tsv`: 500 distinct Lviv values at
ten fixed instants, with the expected state computed once by
`opening_hours.js` 3.15.0 and committed. npm is not needed at build or CI time.
The oracle records its source query, the package version and the one candidate
value excluded because the library reports an inconsistent unknown window for
it.

## Sharing seam

`PlatformActions` in `plugin-api` is the platform seam for sharing (T-0210,
T-0410). It has five calls: copy text to the clipboard, share text, share a
file, save a file through the system document picker, and open a URI. The
contract carries no Android type, so `plugins/share` and the track export ask
for the effect and `app` performs it. `AndroidPlatformActions` is the only
place that names a `ClipboardManager`, an `Intent` or a `FileProvider`. It
starts the system chooser for text and files and `ACTION_VIEW` for a URI. A URI
that no app handles is `Unsupported`. The text and the bytes of a share are
never logged. `openUri` also parses the scheme itself and builds an intent only
for `http`, `https`, `tel`, `mailto` and `geo`; every other scheme is
`Unsupported` before an intent exists.

A shared track is written to `cache/shared-tracks/` and exposed by the one
main-manifest `FileProvider` at `${applicationId}.track.files`; the send intent
grants read on that URI alone. A saved track goes through
`ACTION_CREATE_DOCUMENT`:
`MainActivity` binds the picker launcher, `DocumentSaveCoordinator` owns the
pending answer across an activity recreation, and the chosen `content:` URI is
written with `ContentResolver.openOutputStream`. No storage permission is
declared. The authority, the single `cache-path` and the permission absence are
checked by `TrackExportManifestTest`; the launcher/writer seam is checked
without Android by `DocumentSaveCoordinatorTest`. The picker intent's filter and
suggested file name are a value, `DocumentCreateRequest`, because a JVM test
cannot build an `Intent`; `TrackSaveTest` names them.

`plugins/share` owns the three coordinate notations and the link builders as
pure functions:

- decimal keeps six places and the sign, for example `49.821740, 24.023910`;
- DMS prints whole degrees and minutes and seconds to two places, with a
  hemisphere, for example `49°49'18.26"N, 24°01'26.08"E`;
- DDM prints whole degrees and minutes to four places, with a hemisphere, for
  example `49°49.3044'N, 24°01.4346'E`.

Rounding is half up on the absolute value, in integer units of the last printed
digit, so a boundary carries into the minute or the degree instead of printing
`60`. The formatters use `Locale.ROOT`, so the decimal point stays a dot in
Ukrainian. An osm.org link is `https://www.openstreetmap.org/<type>/<id>` over
the `OsmType` and `osmId` from `PlaceDetails`. A `geo:` URI is
`geo:lat,lon?q=lat,lon(label)`, with the label percent-encoded. A place with no
identity has no element to link, so the choice is a typed `PlaceLink.Geo` for
the same point and never a guessed osm.org URL.

That is the output direction. The input direction is `plugins/coordinates`
(T-0206), which reads those three notations plus hemisphere letters and
symbols; the two modules share `test-data/search/coordinate-notations.tsv`
rather than a formatting constant, and the app round-trip test proves each
direction agrees with the other.

The plugin declares four control-allowlisted commands on the default dispatcher:
`share.copy_coordinates`, `share.share_place`, `share.share_location` and
`share.open_point`. Each returns the exact text or URI it handed to
`PlatformActions`. The location command takes the newest fix and falls back to
the map centre; both flows emit their newest value immediately, so the
collection stops at one value. The card's copy, share and open-in buttons carry
the same built values, and the copy button writes the decimal pair.

The emulator harness cannot observe the clipboard or a chooser, so the clipboard
over `adb` and the intents themselves remain device checks; the unit tests prove
the value through a recording `PlatformActions` instead.

## Links in

`plugins/links` owns the pure parsers for links another app sends (T-0211). A
`geo:` URI follows RFC 5870: `lat,lon`, an optional altitude, `;` parameters and
a query. The query form Android documents for sharing a place is
`?q=lat,lon(label)`, where the body pair is only a fallback (`geo:0,0?q=` is
standard); a `q` that is a coordinate pair therefore wins over the pair in the
body, and its parenthesised suffix is the label. A `q` that is not a pair is a
name this app cannot look up offline, so the body pair is used and the text is a
label. `?z=` sets the zoom when it is in the map's 1..20 range; any other value
is ignored rather than obeyed. An osm.org page link is `/node/<id>`, `/way/<id>`
or `/relation/<id>`, and `/go/<code>` is a short link. `http` and `https` are
both accepted, with or without `www.`, for `openstreetmap.org` and `osm.org`.

A `geo:` link without `z` centres the map at zoom 16, a short link at the zoom
its code encodes, and an osm.org permalink at the zoom it names. Both permalink
forms are read: the current `#map=zoom/lat/lon` fragment, including a layer code
after `&`, and the pre-2013 `?lat=&lon=&zoom=` query. An element URL carries a
position only when it also carries the `#map=` fragment. A bare element URL has
no coordinates, and this app has no offline element lookup, so it opens the map
without a pin and without a guessed point; the route is a typed
`LinkTarget.Element` and the user is told the place has no coordinates.

The short-link decoder is osm.org's own algorithm: each character carries three
bits of longitude and three of latitude in an interleaved Morton code, and
trailing `-` characters name the zoom's remainder. It needs no network. Upstream
Ruby uses arbitrary-precision integers, so this port rejects a code longer than
21 characters instead of overflowing: 21 characters, 63 bits, is more than the
11 a zoom-25 link needs. The vectors in `OsmShortLinkTest` are the worked
examples from the OSM wiki's Shortlink page (`0EEQjE--` is
`#map=9/51.5110/0.0550`, `0EEQjE--?m` adds a marker) and the four `@`/`~` pairs
of the reference test suite `test/lib/short_link_test.rb`, whose expected points
come from `lib/short_link.rb`. The deprecated `@` form decodes exactly like `~`.

MainActivity is `singleTop` and handles `onNewIntent`, so a link that arrives
while the app is alive reaches the running instance instead of stacking a second
one. Its `ACTION_VIEW` filters declare `DEFAULT` and `BROWSABLE` for `geo:` and
for the four osm.org hosts over both schemes, restricted to the paths the parser
opens: the root permalink, `/node/`, `/way/`, `/relation/` and `/go/`. Android
flattens the data lists of one filter, so the paths apply to every host. That
makes the app a candidate for the default maps app without offering itself for
`/search` or `/changeset`.

`app` reads the intent before a map surface exists, so `MapLinks` parks the move
until the screen binds the host, then leaves follow or heading mode and moves the
camera once. A move after a follow recentre wins the first frame. The holder
keeps every bound surface, not only the newest, because two Activity instances
can be alive while a second one opens a link; when the newer one finishes, the
older surface's host stays current and its commands keep working. A link the
router refuses, and a failed camera move, show a message instead of leaving the
map silently unchanged.

`buildSrc` runs `ManifestLinkRules` over every merged manifest in the same
`checkPrivacyManifest` task as the privacy guards, so a merge that drops a
filter fails the offline gate. The command that answers the same question on
hardware is
`adb shell cmd package query-activities -a android.intent.action.VIEW -d geo:49.8,24.0`.
This change ships no device run, so that command stays unexercised until one is
made.

## Map seam

`MapHost` in `plugin-api` is the only map contract. `map` binds it to
maplibre-compose 0.20 (OpenGL) and no other module imports that library. A
`MapLayerProvider` supplies MapLibre style-spec JSON; `map` compiles it to
Compose layer nodes and draws the layers in fixed slots. The logical source IDs
are `world` (z0-z7), `region` (z8-z14) and `contours` (z9-z14). The host
resolves each ID to a mounted archive and skips layers whose source is absent.

`MapHost.setTracks(List<MapTrack>)` draws the stored tracks. `map` turns the
list into one GeoJSON `MultiLineString` per track in the `Overlays` slot, so a
segment gap is never crossed and a pan or a zoom never rebuilds the geometry
from Compose. The track layer (T-0401) is its caller and the route layer
(T-0408) writes only the route line; the puck and markers stay on their own
host calls.

The Standard light and dark variants share one `BaseStyle.Json` document, so a
variant switch updates layers in place instead of reloading the style. A base
style carries sources and glyphs but no layers; the consumer applies the
provider's layers with `applyStyleDiff`, and only `setStyle` may reload.
`plugins/style-standard/src/main/styles/standard.json` holds one layer list and
four palettes; the provider resolves the palette tokens and returns
`standard` (light), `standard-dark`, `touring` and `touring-dark`, all over the
same base document. `zoomOffsets` starts every Touring layer one zoom earlier,
which is the threshold half of map-style.md section 1. The full authored
Touring layer list, its dash ladder and the styles button remain T-0109.
Symbol text and halo colours in the palettes are `rgb()` arrays: the current
text path drops the green channel from a hex text colour, so the style avoids
that form until the render path is fixed.

The map resolves its sources in a fixed order:

1. exactly one installed region package whose `current` pointer, manifest and
   declared map/contours files validate, read from the app's region store;
2. the whole-oblast archive `lviv-protomaps.pmtiles` (plus
   `lviv-contours-10m.pmtiles`) pushed to the app's external files directory;
3. the small T-0015 fixture (`fixture-mini-map.pmtiles`) copied out of the
   debug assets.

An installed region that fails to mount is never silently ignored. The map
still opens on the next source, and the screen shows the slim
"Map data needs re-download" banner. The mount validates the `current`
pointer, the manifest and the map/contours file size and PMTiles v3 header
only; full sha256 verification stays in the download pipeline, so a cold start
never hashes an oblast. A package whose `map.pmtiles` is truncated or is not a
PMTiles archive is therefore corrupt at mount time too. The mount never writes,
downloads or quarantines; those actions belong to `RegionDownloader`.

The debug-only `core.map.set_style` command selects a style id and applies its
layers in place; the golden harness uses it to capture the light and dark
Standard variants from one install. The debug-only `core.map.set_camera`
command moves the camera to an exact latitude, longitude, zoom, bearing and
tilt and returns only after MapLibre's idle event. Both are registered only in
the debug source set, so no style or camera command is compiled into a release
build. The T-0102 golden harness uses the camera command before an
`adb exec-out screencap -p` capture:

```sh
tools/control/grassctl.py invoke core.map.set_camera \
  '{"lat":49.8419,"lon":24.0316,"zoom":13,"bearing":0,"tilt":0}'
```

The control channel's own `devtools.screenshot` uses `PixelCopy` on the
activity window. On this emulator that path does not see the map's
`SurfaceView`, so it returns a black map area and cannot carry a map golden.
`screencap` reads the composited display instead, and the camera command still
comes from the control channel.

The Standard goldens in `test-data/map-goldens/standard/light/` and
`standard/dark/` are ten fixture places at z12, z13 and z14, captured in both
variants. They run in CI on the API 29 emulator with the `swangle` software
renderer. `swiftshader_indirect` zeroes the green channel of every text colour,
which turns light labels on the dark style magenta; `swangle` renders them
correctly, and the comparator and its 5 % tolerance are unchanged. The
committed set is that renderer's own output: 1080x1920 API 29 `swangle`
captures, promoted from the CI artifact rather than by raising the tolerance.
Recapture with `--update` on the same emulator, never from a different device
size:

```sh
./scripts/ci/android-map-goldens.sh --profile standard --style both --update
```

The Standard and fixture profiles default to contours off, which is how their
references stay valid; the script always passes that state to the app. The
contour references in `test-data/map-goldens/contours/` are the fixture centre
at z12, z13 and z14 with the committed 10 m archive on, in all four variants
(`light`, `dark`, `touring-light`, `touring-dark`):

```sh
./scripts/ci/android-map-goldens.sh --profile contours --style all --update
```

The T-0015 fixture goldens in `test-data/map-goldens/fixture/` keep the T-0102
coverage and run on the same emulator after the style set. The whole-oblast
goldens are local acceptance on the `grassmaps-bench` emulator and never enter
CI:

```sh
./scripts/ci/android-map-goldens.sh --profile fixture --update
./scripts/ci/android-map-goldens.sh --profile oblast --update \
  --region-package /hdd/t-0102-evidence/region/ua-46/2026-11-05-bench
```

The first `--update` run writes the reference set; every later run compares
against it. Each image is reduced to its centre 16:9 area, then to the central
half of that area, which drops the status bar, scale bar, control bar and
attribution, and then to a 108x192 grid of mean RGB blocks. At a fixed camera
the central 540x960 window covers the same ground on any 1080-wide device, so a
16:9 and a 20:9 phone compare directly. The test fails when more than 5 % of
blocks differ by more than 32 on any channel. Block means absorb GPU
anti-aliasing and label rasterisation; a blank, wrong or shifted map still
changes most blocks. The run writes `comparison.json` and one diff image per
position.

A position over the tolerance is re-captured once through the control channel
before the run calls it a failure. The compare run passes the serial the
capture used, so the channel's `adb forward` and token read address that
emulator. A phone attached to the same machine then changes nothing.
`GOLDEN_SERIAL` overrides the device. Without a serial, `map_goldens.py` takes
the only attached emulator and refuses to guess when several are attached.

### Location puck

`MapHost.setLocation(LocationFix?)` draws or clears the own-position puck. The
puck is not a Compose overlay: `map` builds a GeoJSON point and draws it with a
`CircleLayer` in the `Markers` slot. The exact maplibre-compose 0.20 API is
`rememberGeoJsonSource(GeoJsonData.JsonString(featureCollectionJson))` with
`CircleLayer` nodes for the dot and accuracy circle and a `SymbolLayer` for the
stale age text; all of them live in `map/style/ComposedMapLayers.kt`, and no
library type leaves `map`.

The source is keyed on the displayed position. Between 1 Hz fixes the puck
interpolates from the previous fix to the current one over the interval the fix
timestamps declare, at display-frame rate. An update is suppressed when the fix
reports under 0.5 m/s and the position moved under 1 screen pixel, which keeps
MapLibre issue #4821 away from a stationary fix. Before the first live fix the
puck is a grey ring at the last known place with its age, and a poor-accuracy
fix adds an accuracy circle. A null fix removes the source and every layer.

A map gesture does not stop following in T-0022. The my-position button cycles
`Free`, `Follow` and `Heading` (T-0106). Heading uses the fix bearing while the
fix moves, else the device compass, and degrades to `Follow` when neither exists.

A two-finger tap zooms out one level. When the distance-by-tap setting is on,
the same gesture measures instead: the two touch points are projected to the
map and the ground distance between them is shown in a badge for four seconds.
The setting is a typed seam, `DistanceByTapSetting` on `AppContainer`, off by
default until T-0016 binds it to the shared settings service; a debug build
turns it on with `--ez t0105_distance_by_tap true`. The recogniser
accepts exactly two pointers held at most 300 ms that travel at most 12 dp; the
contract lives in `MapGestures`, which disables the library's own two-finger tap
binding so one gesture has one owner. The emulator cannot inject a second
pointer, so the recogniser decision and the projection-to-haversine maths are
unit tests and the pointer loop is unexercised on a device.

A one-finger long press selects the map point under the finger (T-0112): the
finger must stay within the 12 dp touch slop for 500 ms, and a second finger or
an early move produces no selection. `MapGestures` disables the library's own
long-press binding for the same one-owner reason. The selection is
process-scoped, so a recomposition or a rebuilt surface keeps it. The
`point.info` plugin publishes the distance and the initial bearing (azimuth)
from the current fix, or the map centre when there is no fix, to the selected
point, computed with Vincenty's WGS84 inverse formula; the label shows the two
rows and clears the selection. The device hold cannot be injected by the
emulator harness used in CI, so the recogniser is a unit test and the pointer
loop is unexercised on a device.

## Contours

`plugins/contours` draws the region's `contours` component as a map overlay
(T-0417). The manifest declares the component need; the app mounts
`contours.pmtiles` from the installed region, the pushed whole-oblast archive
or the committed fixture. The plugin publishes one `map.layers` provider and no
base style: it supplies four overlay layers in the `Overlays` slot, and the host
adds them under `map.contours` so turning the overlay off removes exactly those
layers. The ladder is cumulative: z12 adds the 100 m index, z13 adds the 50 m
lines to it, and z14 adds the 10 m lines to both.

| Layer | Standard | Touring | Width / opacity |
|---|---|---|---|
| `contour-index` (100 m) | z12+ | z11+ | 1.3 dp / 0.85 |
| `contour-50` | z13+ | z12+ | 1.0 dp / 0.7 |
| `contour-10` | z14+ | z13+ | 0.6 dp / 0.55 |
| `contour-label` | z14+ | z13+ | 10 pt italic, 1.5 halo |

The palette is map-style.md section 3: minor/index `#dcc2a6`/`#c9a27c` in
Standard light, `#2e343c`/`#3e4650` in Standard dark, `#d6b48e`/`#bf9364` in
Touring light and `#24403a`/`#30554c` in Touring dark. Label text and halo come
from the same section's label pair. The Touring contour references also use the
Touring base palette and its one-zoom-earlier threshold from
`plugins/style-standard`; T-0109 still owns the full Touring layer list, its
dash ladder and the styles button. The 50 m band filters `ele % 50 == 0` and
excludes the 100 m index; the 10 m band filters `ele % 10 == 0` and excludes the
50 m lines, so the coarser line is never drawn twice. The index and label
layers filter the archive's Boolean `idx == true`, which the pack sets only on
100 m lines.

The Lviv pack and the committed fixture are built from Copernicus DEM GLO-30
averaged to 90 m, with `gdal_contour -i 10`. The pack stores 100 m index lines
from z11, 50 m lines from z12 and all 10 m lines from z13; the style then draws
100 / 50 / 10 m at z12 / z13 / z14 in Standard and one zoom earlier in Touring.
The fixture manifest records `lviv-90m-10m.pmtiles` and its sha256, and the
committed component is 40,899 B. The Lviv component is 19,164,271 B,
`aee7e909…`; the full built archive is 20,858,533 B, `d5a1ba5d…`, with 207,208
written features. Counted over the Lviv pack bbox, the archive holds 4,018
features at z11, 9,352 at z12, 69,498 at z13 and 109,297 at z14 (tile-clipped
records; a line crossing several tiles is counted once per tile). On 2026-11-12,
8 cores under `gm-data`, `gdal_contour` took 14.5 s and Planetiler 9.3 s.

The 10 m lines are resampled detail from the 90 m DEM, not 90 m ground truth:
the 3 arcsec mean removes the 30 m source's tree and building noise but cannot
recover detail finer than its cell. They are useful for shape and spacing, not
for surveying. The same note belongs to any elevation or slope read from the
grid. The app packages a byte copy of the fixture component as
`app/src/main/assets/fixture-mini-contours.pmtiles`; `FixtureContoursAssetTest`
fails if the copy and
`test-data/regions/fixture-mini/2026-11-03-1/contours.pmtiles` diverge.

The switch persists in `settings/contours.json` and defaults to on.
`ContoursSettings` reads it off Main; the one-row layers panel in the app calls
`setEnabled`, and `ContoursAccess.apply` removes and re-adds the owned layers.
The golden harness passes `--ez map.contours.enabled true|false` at launch
without writing the file, which keeps a capture deterministic. The standard and
fixture golden profiles run with contours off so their references stay valid;
the `contours` profile runs them on at the fixture centre in all four variants.

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

`core.map.follow` cycles a process-scoped `LocationController` through the free,
follow and heading camera modes; `map` and `plugins/map-controls` own the state
behind it. The controller collects a `core-services` `LocationSource` and, on
every fix, calls `MapHost.setLocation`. It recenters once when follow or heading
starts and after a host bind; after that the map host's frame loop owns the
centre and interpolates the puck. In heading mode the controller adds the travel
bearing, or the compass bearing, and leaves the centre to the frame loop. It
drops to follow when neither bearing exists. In free mode it does not move the
camera. `setTrackingMode` mirrors the state. `core.map.north_up` is the compass
button: it sets the bearing to 0 and leaves heading mode for follow, so the map
does not rotate away from north on the next fix. The controller holds the map
session, not a surface, so it keeps running across Activity recreation and
pushes the latest fix to a newly bound host.

The position-marker offset is a `PositionOffsetFraction` resolved against the
viewport the map currently occupies, then passed to `MapHost.setPositionOffset`.
The host turns it into camera padding, so the map centre and the puck sit at
that screen point. The offset is supplied per profile through
`PositionOffsetProvider`; until T-0601 the app uses the centred provider and the
`map-controls` plugin declares the profile-scoped
`map.controls.position_offset_y` setting with a default of 0.

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

## Scale bar and units

The scale bar is a Compose overlay in `ui-shell`; the round distance it spans
comes from `ScaleBar.reading` in `plugins/map-controls`. Round distances step 1,
2 and 5 per decade, and the chosen step is the largest one that fits 96 dp. The
bar width is the distance divided by the map's ground scale, so the label and
the drawn line describe the same span. The bar reads metres below a kilometre
and kilometres from one, or feet below a mile and miles from one.

The ground scale is `WebMercator.metersPerPixel` in `plugin-api`: the sphere of
6378137 m projected onto 512 px tiles, `2 * PI * R * cos(latitude) / (512 *
2^zoom)`. maplibre-compose lays a map out in dp, so one map pixel is one dp and
the value is metres per dp; the scale bar uses it directly, with no density
factor, and `MapLibre`'s `metersPerDpAtLatitude` returns the same formula.
`map`'s `PuckGeometry` delegates to the same helper, so the puck's accuracy
circle, the deadband and the scale bar share one definition. The bar collects
the camera itself, so a pan or zoom recomposes the bar alone.

The unit system is `UnitSystem` in `plugin-api`, and `core-services`
`UnitSettings` stores it as `filesDir/settings/units.json` with metric as the
default; a missing or corrupt file reads as metric. `MapScreen` resolves the
settings object off Main and collects its flow, so a change redraws the bar.
Until a settings screen exists, the control-channel command
`core.settings.units` sets it: `{"units":"imperial"}` or the bare string. The
command is allowlisted for control and runs on the IO dispatcher.

The release build installs `AndroidLocationSource`, which reads the platform
`LocationManager` GPS and network providers; there is no Play Services and no
fused provider. Location is foreground-only: the manifest declares
`ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`, never a background
permission, and the system dialog opens only when the user taps the position
control. The asked flag lives in `filesDir/location/permission-asked`, so a
denied answer never prompts again on its own; the control then explains why
location helps.

A debug build replays `MockGpxLocationSource` over the bundled `mock-walk.gpx`
one fix per second, looping at the end. Launching the activity with
`--ez t0103_platform_location true` uses the real platform source instead, which
is how the permission flow is exercised on the emulator.

A release build starts on the platform source and can switch to the same
committed replay through the development-tools panel. That panel is behind the
AI-control switch and stays invisible and unusable until T-0610 supplies the
switch; `LoggingLocationSource` remains debug-only. The fixture is the same
`test-data/location/mock-walk.gpx` and now ships in the main assets, so the
release panel can parse it.

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
archive stores a Boolean; an integer comparison matches nothing). It draws over
the shipped contour overlay when both are on, which is acceptable for this
debug seam. The drawn contours are real and the attributes remain synthetic.

`app` resolves the provider through `ProviderRegistry` off Main and adds the
layer under `PluginId("data.synthetic")`; a missing provider or a rejected layer
is logged and the map keeps running. `DataSourceAccess` is the typed handle
routing will read cost modifiers through in T-0024.

## Plugin boundary rule

A module under `plugins/` may depend on `:plugin-api`. It may also depend on
`:plugin-ui-api` when it contributes Compose UI. No other project dependency is
allowed in any configuration.

The route planner is a top-level feature module (`planner`), not a `plugins/*`
module. It depends on `:routing`, the same domain edge `navigation` already has,
because the planning state machine is shared with future guidance work. It
reaches no core internals and still asks the host for the engine through
`RoutingEngineAccess`, so the plugin boundary that matters - the contract - is
unchanged.

One exception remains, `plugins/nav-simulator`: it depends on `:navigation`
because the simulated run is the production `NavigationSession` driven by
synthetic fixes, and on `:routing` for the stable route JSON form a passed route
uses. It reaches no core internals, changes no navigation policy and lets the
session decide arrival.

`./gradlew checkPluginBoundaries` checks every module and fails with each
violating module, configuration and dependency. Modules wire this task into
`check`.

Feature modules never import `core` or `core-services`. They take the host and
service interfaces they need in their constructors, and `app` wires the
implementations. The only feature-to-feature edges are `navigation -> routing`,
`alerts -> navigation` and `planner -> routing`.

One test-only edge is declared separately in `moduleTestDependencyAllowlist`:
`navigation`'s tests depend on `plugins/direct-engine` (T-0305), because the
follower's acceptance test feeds the real engine's route through the Ferrostar
mapper. `plugins/guidance-ui`'s tests depend on `navigation` (T-0310), because
the panel's acceptance feeds the real session's published state into the panel.
Both reach no runtime classpath.

## Plugin manifests

Every `plugins/*` module carries `src/main/plugin.json`, and so does the
compiled-in `planner`. At build time the `generatePluginRegistry` task reads
those manifests and writes `GeneratedPlugins.kt` and `GeneratedStreams.kt` into
`app`, so the host never scans the classpath or uses reflection.

A manifest declares the plugin id, version, the `plugin-api` version, the
implementation class, and its contributions: commands with parameter and result
schemas, their dispatch policy (undoable, required switch, control allowlist and
dispatcher), providers with contract versions and static capabilities, streams
with a value type and serializer, widgets over a declared stream, and settings
with defaults and scopes. The host rejects a command whose code policy disagrees
with its manifest. The example plugin declares one command, one stream and one
provider.

A widget contribution names a title (an i18n key), a value type, the stream its
value comes from and the shortest period between samples. The stream must be one
the same plugin declares and the two value types must match. Values stay streams,
so the shell and `control.stream.subscribe` bind a widget by id without
importing the owning module; layout and drawing are the shell's (T-0605).
`WidgetCadence` caps widgets at 10 Hz, and the info widgets sample at 5 Hz.

On activation the host passes `PluginServices` to the plugin: observable streams,
the location source, DEM height access, the camera flow and the clock. A plugin
has a no-argument constructor, so it reads these inputs from the host instead of
constructing platform services itself.

`checkPluginManifests` fails the build when a manifest breaks the rules above,
including a declared command whose implementation class is missing from the
plugin's source, a command id in the reserved `core` host namespace, a widget
over an undeclared stream, or a policy field the generator does not understand.
Every module that carries a manifest wires the check into `check`, as does
`app:check`.

## Speed widgets

The info widgets include three speeds (T-0316), fed by the location fix stream:
`info.widgets.speed.current`, `info.widgets.speed.average` and
`info.widgets.speed.max`. Their values are metres per second and the shell
formats them in the user's units. A value is null until an interval has been
measured, so one fix shows a dash rather than a zero.

The speed is the haversine ground distance between two consecutive fixes
divided by the time between them. The fix's own `speedMetersPerSecond` is not
read, so the widget reports what the fixes show, not the GNSS chip's Doppler
estimate, and every source answers the same way. Average is the distance
travelled inside a 60 s window of fix time over the time it took
(`SpeedTracker.AVERAGE_WINDOW_MILLIS`), so a longer interval weighs more than a
shorter one. One 20 km/h interval inside the window moves a steady 5 km/h
average to 5.25 km/h. Max is the largest current value since the plugin
activated. `SpeedTracker.resetMax()` clears it and the next measured interval
starts it again. Nothing binds that reset yet, because the widget action belongs
to the shell's widget UI in T-0605.

Not measured: the chip's Doppler speed, accuracy weighting, outlier rejection
and smoothing. A stationary phone can read a small non-zero speed from position
noise, and a long gap between fixes is one interval, so the reading is the
average over the whole gap.

The acceptance line is "on a replay at known speeds the widgets are within
0.5 km/h." The acceptance tests are `SpeedTrackerTest` for the derivation and
`SpeedWidgetProviderTest` for the three streams, both on a deterministic 1 Hz
replay.

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

`RoutingEngine` in `plugin-api` is the engine contract (1.3.0). `plugins/valhalla`
is the v1 provider: it binds the contract to valhalla-mobile 0.6.4 and keeps
every library type inside that module. The engine reads the pack directory from
the host's `RoutingPackAccess` service. It inspects that directory on the first
route, so a missing, incomplete or unreadable pack becomes the typed
`AppError.RoutingPackUnavailable`. The planner publishes `PackMissing` or
`PackUnusable` in `RoutePlanState.failure`. The shell shows the matching string.
The control channel answers `-32005` with a `routing_pack_*` kind. The engine
writes a local `valhalla.local.json` and creates the native actor on the first
route.

The app does not trust the pack's own `valhalla.json` for paths. It replaces
`tile_extract`, `tile_dir`, `admin` and `timezone` with the paths on this
device. It clears the optional `landmarks`, `traffic_extract`, `transit_dir`,
`transit_feeds_dir` and `additional_data.elevation` entries unless the pack
ships them. The tiles may be a `tiles.tar` extract or a `tiles/` directory.
`app` resolves the provider through `ProviderRegistry`, so a build with no pack
registers an engine that answers that typed failure. It does not fail at
startup.

`annotate` fills the segment attributes after the route is already returned:
the routed line goes back to Valhalla's `trace_attributes` with `map_snap`, is
cut into one segment per graph edge, and carries `surface`, `highway`,
`sac_scale` and `access`; `grade` is sampled from the mounted region DEM. The
phase-A pack has no elevation, so the T-0301 runs kept `use_hills` inert; the
T-0308 Lviv pack bakes GLO-30 grades into its edges. The per-profile options,
the preference mapping, the Lviv pack build and the 10-route ascent measurement
are in `test-data/routing/` and `tools/pipeline/`.

`RouteRequest.options` carries the chosen profile's three preferences (hills,
busy roads and rough surfaces, each Avoid, Normal or Don't mind). The planner
reads the profile defaults from `RoutingOptionsSettings` through
`RoutingOptionsAccess`, so a bike change cannot leak into walk. A route preview
can override the three values for that route only; the override lives in
`RoutePlanState.overridePreferences` and is dropped by `clear()`, so the stored
profile is never written. `plugins/valhalla` maps each step to its costing for
the request's profile only (the table is in `test-data/routing/README.md` and
`ValhallaPreferencesTest`); the direct engine has no costing, so its geodesic
does not change. The app logs the values the request carried as
`route-preferences` beside the existing route summary; values only, no points
or labels.

`plugins/direct-engine` is the second `routing.engine` provider (T-0305), and
the proof that the engine seam is real: the planner and the follower consume
its routes exactly as they consume Valhalla's, and only the source of the
answer changes. A route is the geodesic between each pair of waypoints, and its
length is the haversine of that pair, not the sum of the drawn line. One vertex
per kilometre keeps the sampled arc within about 2 cm of the true geodesic, so
the line the map draws and the length the follower counts down agree within a
millimetre; the longest accepted route is 250 km, 250 segments. A leg across
the antimeridian is refused, because the line would come back with a longitude
jump the map would draw the long way round. A chain of waypoints becomes one
route with one leg per hop in visit order and one segment per leg, so a via is
routed through and not straightened away. `annotate`
returns the route unchanged and the engine declares no attribute keys, no cost
modifiers, one route (a geodesic has no alternative) and no track matching;
walk, bike and hike are accepted, car is not. Durations use picked cruising
speeds (1.4 m/s walking, 1.1 m/s hiking, 4.2 m/s cycling) because a line with no
surface has no better number. `app` still resolves `valhalla` by plugin id, so
choosing the direct engine, as a fallback when no pack is mounted or as a
setting, is not wired yet.

`MapHost.setRoute(Route?)` draws the route as one GeoJSON `LineString` in the
`Routes` slot, using the same source pattern as the location puck. One turn
arrow per turning maneuver is a second layer in that slot (T-0311): the existing
`arrow` SDF sprite is the token, drawn white with the route green as a 1.5 dp
halo at 0.4 of its 31 dp cell. An arrow sits at the maneuver's distance along
the geometry and points along the segment that leaves that point; start,
arrival, continue, keep-straight, ferry and transit instructions get none. A
via adds a leg and a new route revision replaces the whole set. The app's debug
control channel exposes `core.route.test_walk`, which routes a short walk
in the fixture area or in Lviv and, with `{"modifiers": true}`, asks the
synthetic DataSource for cost modifiers and routes again. Valhalla takes a
multiplier as `linear_cost_factors`; when the synthetic line has no graph edges
near it, the adapter retries with `exclude_polygons` around the same line. The
debug-only `core.map.set_route` draws a committed `RouteJson` fixture for the
T-0311 goldens; those capture points plan no route and mount no routing pack.
Its optional `colour=slope|surface` and `dark=true` pick the T-0408 palette, so
a golden captures the same decision the map screen draws.

The route line is coloured by `plugins/route-layer` (T-0408). The colour and
pattern decision is a pure read of the annotate pass's own segments: each
segment's `grade` picks one of the round-10 slope colours, or its `surface`
picks one of the four surface colours, and its `surface` and `highway` pick the
pattern. The slope scale is the seven stops of the design handoff's round 10
chart palette, -15, -8, -3, flat, +3, +8 and +15 percent on the hike scale,
with the light and dark sets in `RouteRound10Palette`; a value exactly on a stop
belongs to the bin below it, which is the chart's own rule. The surface set is
asphalt, paving, ground and gravel, and `plugins/route-layer` carries the same
OSM value table as the T-0406 strips. The runs always partition the geometry:
a part between annotated segments, or a tail after the last one, keeps the
route's existing colour, so a continuous line never shows a hole. A route whose
segments carry none of `grade`, `surface` or `highway` returns an empty style,
and the map keeps the plain single-colour line, which is why the T-0311
`route-turns` goldens do not change. An active guidance session asks for that
plain line too: `RouteLayer` takes `RouteLineDrawing.Plain` while a navigation
session runs, because the design's navigation screen draws one route line, and
`RouteLineDrawing.Coloured` only for a planned or inspected route.

Unpaved surfaces are dashed: ground and gravel. Asphalt and paving are solid,
and a surface value the palette has no class for is solid too, because the
design gives no rule for it. Steps are the design's stripe ink, #202124 light
and #e8eaed dark; MapLibre cannot draw a 45° pattern along a line and
`map-style.md` forbids `line-pattern`, so the host draws that ink as a dash over
the run's own colour. A steps way keeps the stripe when its surface is also
unpaved. The dash keeps the design's 4 dp period and 2 dp gap; those are the
only pattern constants the line adds, and the existing 2 px gap between strip
segments in the T-0406 chart stays where the design draws it.

`map` draws the coloured line from one GeoJSON FeatureCollection whose features
carry `colour` and `pattern` properties: three layers draw it, the solid line,
the dashes, and the stripe ink. The layer count is fixed and the collection is
built once per route or style change, never on a camera move, so a route cut
into hundreds of graph edges costs one data build and no extra layer. The
drawing has no `line-gradient`; colour is data-driven per feature.

Two parts of the row have no design value and are not drawn. Altitude and speed
colouring need a scale, and the canon gives slope, surface and way-type sets
only, so `RouteColourMode` carries slope and surface and no altitude or speed
mode is guessed. The track half of "gradient colouring" is refused for the
reason T-0406 recorded and for one more: a track has no surface or smoothness
attribute, and `MapTrack` carries geometry without heights, so no gradient can
be computed in the track layer. `TrackColouring.Gradient` logs that gap and
draws the track's own colour instead.

`RouteColouringTest` in `plugins/route-layer` holds the palette values, the bin
edges, both colour modes in both themes, the paved solid and unpaved dashed
rule, the steps stripe and its precedence, a mixed four-segment route, the gap
and tail fills, the controller's one host write and the plain drawing.
`RouteColourLinesTest` in `map` holds the feature split, the clipping guard, the
three layer filters and the dash ratio. `RouteLineDrawingTest` in `app` holds
the guidance plain line against the planned route's slope read, through the same
draw call the map screen makes. `TrackLayerTest` holds the gradient refusal. The
goldens `route-colours-z14` and `route-colours-z15` draw a committed
four-segment route with a grade, surface and way type on each segment, in both
styles.

`routing.RoutePlanner` is the planning state (T-0302), with intermediate stops
(T-0303): a start, its vias in visit order and a destination go in as one
`RouteRequest`, the engine's own list comes back as selectable options, and a
swap routes the reversed journey with the via order reversed rather than
reversing a line. `add_via`, `remove_via` and `move_via` edit the chain and ask
the engine again, so a reorder changes the drawn line and not only the list.
`planner` owns the commands and the `route.planner.state` stream;
it resolves the engine through `RoutingEngineAccess` in `plugin-api`, never by
naming the provider. The map picks points with its long press and with a search
result; `ui-shell` draws the preview from the stream and `MapHost.setRoute`
draws the selected option plus its two ends. The preview derivation,
`routePlanPreview` in `ui-shell`, returns exactly four entries - time, distance,
arrival and Start - for a route with vias as well, all four being totals of the
whole chain. `app` converts a stored favourite and a search result into a stop
with `Favourite.toRouteStop` and `PlaceHit.toRouteStop`, keeping the source's
coordinate and name. The response vectors, the ten-metre measurements per
profile and the reorder and removal evidence are in
`test-data/routing/README.md`.

The planning edit history is T-0304. `undo` and `redo` restore whole published
states, including the route each edit produced: undo asks no engine, because the
line the user saw is what it must return and a second request could return
another route. Five edits, five undos and five redos end at the same states;
`undoAvailable` and `redoAvailable` report the reach. Selecting an alternative
and stepping through turns are views, not edits, so they are not on the stack,
and a new edit drops what could have been redone.

The annotate pass is T-0409. A produced route is published first and then
handed back to the same engine through `RoutingEngine.annotate`, on the
plugin's own scope, so the preview, the map and the warnings never wait for it.
When the answer arrives the planner replaces the selected option's segments and
nothing else: the geometry, the totals, the arrival, the selection, the other
alternatives and the reuse history stay as published. Each produced option is
asked for once, re-selecting it asks nothing, and an answer for a route a newer
edit superseded is dropped. A failed pass keeps the unannotated route and is
logged through the plugin's `Logger`.

The panel's coordinate field reads typed text with the same `Coordinates.parser`
the search field uses, through `routePointEntry` in `plugin-api`; blank text is
not a refusal, and a refused notation changes nothing. The stop list rows move a
via earlier or later and remove it; each is one edit. The turn stepper walks
`RoutePlanOption.instructions` with their text, distance along the route and the
geometry point to show; a route with no instructions - the direct engine's
geodesic line - has exactly one step, arriving at the destination.

`RouteHistoryStore` in `routing` keeps the last 20 produced routes in
`history/route-history.json`, newest first, keyed by the waypoint chain and not
by geometry, so a reuse asks the engine again against the current map data.
`route.planner.history` publishes the list and `route.planner.reuse_history`
reopens one entry as a single edit. The store shares the search history's
privacy switch: with it off the file is neither read nor written and stays byte
identical, and the search-history setting command turns both stores off.
`FavouritePointSummary.position` and `FavouritePointRow.position` carry the
stored coordinate, so a plugin resolves a favourite with
`FavouritePointRow.toRouteStop` instead of reading the app's own record.

## Navigation seam

`NavigationSession` in `navigation` binds Ferrostar's Rust core to our route
model. It takes a `Route`, a `Flow<LocationFix>` and a `RoutingEngine`, and
emits `NavigationState`: phase, on-route relation, distance from the line,
snapped position, remaining distance and time, ETA, and the next instruction.
No Ferrostar type leaves the module; `FerrostarRouteFollower` is the only file
that names one.

The `com.stadiamaps.ferrostar:core` AAR (0.59.0) is used without `ui-compose`
or `ui-maplibre`; the native core is the `uniffi.ferrostar.NavigationSession`
created by `createNavigationSession`. Route conversion carries geometry,
bounds, distance, waypoints and steps. Steps are split at the instruction
distances because our `Route` has no per-step geometry. Elevation and typed way
attributes stay in our model; Ferrostar's route has neither.

Guidance numbers come from the route's own model, not from a second
calculation. `RouteProgress` in `plugin-api` builds spans from the route: the
per-maneuver times of `Route.instructions` when their distances and durations
agree with the totals, else the per-leg times of `Route.segments`, else one
uniform span. It lives in `plugin-api` because the guidance panel counts from
it too: one span model keeps the session's remaining time and the panel's turn
and stop times from drifting apart.
Remaining time is the unused part of the current span plus every span still
ahead; remaining distance is the route total minus the distance along it; the
ETA is the current fix's epoch time plus that remaining time, so a stale fix
carries a stale arrival time instead of hiding it. A source whose totals
disagree is dropped rather than corrected. The follower's own duration is
never published: the mapper prorates Ferrostar's step durations by distance, so
using them would average a slow stretch into a fast one. `snappedPosition`
still comes from the follower.

A route planned through `planner` carries the same model.
`RoutePlanOption` keeps the engine's `segments` and `instructions`, and
`selectedRoute()` restores them for guidance, so the panel counts down from the
numbers the preview showed. Both types are serializable, attributes included,
so the plugin state round-trips unchanged.

`GeometryRouteFollower` is the fallback when the native core cannot start, and
the follower the desktop gate can run. It projects onto the line with
`PolylineLocator`, reports no deviation verdict, and reports remaining distance
in the route's own distance space.

Off-route is declared at `NavigationConfig.maxDeviationMeters`, 35 m, and the
engine's threshold is that same value: Ferrostar's `StaticThreshold` is built
with it and the session's geometry floor compares against it. When the core
returns a verdict it is authoritative, including its 15 m horizontal-accuracy
gate; the floor answers only when there is no verdict, which also covers an
unavailable core. Two consecutive off-route fixes confirm it. A fix that is off
the current step but still on the route never counts.

Rerouting adds a distance budget on top of that confirmation. `RerouteGate`
converts `offRouteDelaySeconds` (20 s) with the profile's own gate speed, 1.1 m/s
walking and 2.8 m/s cycling, so the gate spends 22 m and 56 m. A fix spends the
budget by moving, not by the clock: a stationary fix spends nothing, and one
fix that jumps more than four gate speeds for its elapsed time is a new reading
rather than travel. A confirmed off-route fix starts the request only when both
conditions agree. Returning to the line resets the budget, which is the cancel.

The request is speculative: the engine computes while the current line is still
published, and the replacement line is announced in the state only when it is
ready. One request is in flight at a time and every attempt resets the budget,
so a burst of off-route fixes cannot queue a storm. A fix worse than
`minimumHorizontalAccuracyMeters` is dropped before the follower and the gate,
so it neither starts nor cancels a reroute; the last good fix and its guidance
stay on screen. A reroute continues the plan: the request carries the current
position, every intermediate stop not yet passed, and the destination. Arrival
uses one number too: the core's end-of-step advance takes
`arrivalDistanceMeters` instead of the old hardcoded 10 m.

The 15 m noise acceptance replays the committed `test-data/location/mock-walk.gpx`
with every fix moved exactly 15 m in a seeded random direction (seed
20261110), through the full session, and measures every published snapped
position against the route line with the harness's own haversine geometry. The
route continues 300 m past the fixture's last point because the track is an
82 m loop whose end sits inside one noise radius of its start; the measured
line still contains the committed track unchanged. The bound is 2 m: the
measured worst is 0.060 m, so 2 m leaves numerical and step-line margin while
staying far below the 15 m noise and the 35 m off-route threshold. Results and
the run command are in `test-data/location/README.md`. The native core ships
Android ABIs only, so the Ferrostar run is an instrumented test and not part of
`./check.sh --fast`; the desktop test runs the same harness with
`GeometryRouteFollower`.

The debug channel exposes `core.nav.test_follow`. It routes a walk or bike
ride, generates a GPX from that line with a sideways segment past the deviation
threshold, parses it through `MockGpxLocationSource`, follows it, and draws the
replacement route. `{"area":"fixture"}` and `{"area":"lviv"}` select the area;
`profile`, `tick_ms` and `detour_m` tune the replay. The GPX timestamps advance
with distance at the profile's gate speed, so the off-route budget sees travel
even when the wall tick is fast. The summary carries `inaccurate_fixes`,
`off_route_fixes`, `reroutes` and `reroute_ms`; the phone benchmark is
`scripts/bench/reroute-latency.sh`.

`plugins/nav-simulator` is the simulated run (T-0315). `NavigationSimulator`
places one synthetic fix per simulated second 1.389 m further along the route,
so the run holds 5 km/h whatever the vertex spacing is. The fixes go through the
production `NavigationSession` and `GeometryRouteFollower`; arrival is the
session's own 15 m check, and the simulator never sets a phase. Its clock
advances only with the fixes, so a JVM test runs the whole route without
sleeping. The control-allowlisted `nav.simulator.start` command uses the
selected route from `route.planner.state`, or a route in the stable `RouteJson`
form passed as `route`, with an optional `profile`; `nav.simulator.stop` ends
the fix flow, so the session publishes `Stopped` and no arrival.
`nav.simulator.state` publishes the phase, fix count, arrival and remaining
distance.

The acceptance line is "a run at 5 km/h moves along the route and fires arrival."
The acceptance test is `NavigationSimulatorTest`.

### Navigation camera

`NavigationCameraController` in `navigation` drives the camera during guidance
(T-0313). It consumes the published `NavigationState` and writes one
`CameraUpdate` per change through `MapHost.animateCamera`; only `Following` and
`Rerouting` drive, while `Paused`, `Idle`, `Arrived` and `Stopped` leave the
camera alone. It is pure logic plus the host seam and an injected clock, so a
JVM test with a fake host and a fake clock covers every branch. Auto zoom and
auto-centre default on; tilt is off.

Speed is the ground distance between consecutive fixes over the time between
them, not `LocationFix.speedMetersPerSecond`, the same rule as the speed widgets
(T-0316). The first fix measures no interval, so it does not set the zoom. The
auto-zoom table is:

| Band | Speed | Zoom |
|---|---|---|
| Walk | up to 7 km/h | 17.0 |
| Bike | above 7, up to 20 km/h | 16.0 |
| Car | above 20 km/h | 15.0 |

The edges have 1 km/h of hysteresis. A faster band is entered at the current
band's upper edge plus 1 km/h and a slower band at the slower band's edge minus
1 km/h: a walker becomes a cyclist above 8 km/h, and a cyclist becomes a walker
below 6 km/h. A speed exactly on 7 or 20 km/h, or drifting across by less than
the margin, changes nothing. A zoom is written only when the band changes, and
auto zoom off writes no zoom at all.

A manual pan is reported through `onManualPan`. The camera then stays free for
five seconds (`RECENTRE_DELAY_MILLIS`) of clock time, and the next fix recentres
it on that fix's position; a second pan restarts the five seconds. The delay is
evaluated when a state arrives, so a source that stops publishing fixes leaves
the camera free until the next one. Auto-centre off never recentres after a pan,
and turning the switch back on releases the pan at the next fix. A paused or
ended session drops the speed interval, so the time spent paused cannot read as
a very slow speed and drop the zoom.

Tilt is on by default in round 5. With it on the pitch is 45 degrees; turning it
off writes 0 degrees and restores the flat camera. The pitch gesture stays
disabled (T-0104), so the navigation screen's camera sheet is the only way the
navigation camera pitches.

The controller does not set a tracking mode. The screen that binds it leaves the
host in `TrackingMode.Free`, otherwise the host's own follow loop and this
controller both write the centre. The navigation screen (T-0320) binds the three
switches and reports a one-finger map drag through `onManualPan`; the
`clearPan()` call after the screen's own recentre keeps the next fix on the
position.

## Guidance panel

`plugins/guidance-ui` is compiled-in chrome over the navigation state the app
already holds (T-0310). It shows four facts and no more: the next turn with the
distance and time to it, the second next turn, and the distance and the time to
the next intermediate stop. `GuidancePanel` fixes the vocabulary - a fifth kind
needs a fifth enum value, and a repeated or reordered fact fails construction.
The stop rows are absent when the route has no via.

`guidancePanel` is pure: it takes the route, the distance along it and the
guidance status, and it reads no camera, location or clock. The app maps a
published `NavigationState` onto `GuidanceInput` (`alongMeters` is the route
total minus the remaining distance), derives the panel once per state and
remembers it. `GuidancePanelContent` then only draws the round 5 turn card from
that model, so a camera or gesture frame cannot recompute guidance and the panel
is not on the map's draw path. The navigation screen hosts it at the top of the
map; the app derives the panel once per published state.

Turns come from `routeTurns` in `plugin-ui-api`, the same enumeration the
planner's turn stepper walks, so one route has one turn order. A turn passed by
more than one metre is behind the driver. Time is
`RouteProgress.durationBetween`. A stop is a leg end in `Route.segments`; a
chain whose leg distances do not add up to the route total has no stop fact
rather than a guessed one. Segments that carry per-edge attributes came from an
annotate pass, and they are graph edges rather than legs, so they show no stop
either; the task that schedules annotation must keep the leg model if the panel
is to show a stop. Arrival shows the arrival fact alone and off route adds a
notice beside the same facts.

The acceptance replays the committed `test-data/location/mock-walk.gpx` through
the geometry follower at 20 points - every third track point from the first to
the fifty-fifth - on a three-leg route with two intermediate stops and a
different speed per leg. The panel's input is each published `NavigationState`,
mapped exactly as the app maps it. Its two turns and its stop distance and time
are compared with a route model the test computes from `Route.instructions` and
`Route.segments` in its own arithmetic; the worst mismatch is 0 for all four
facts over all 20 points. Results are in `test-data/location/README.md`.

## Navigation screen

The navigation screen is T-0320. It appears for `Following`, `Rerouting`,
`Paused` and `Arrived`, and `Done` on the arrival card calls
`NavigationController.dismiss()`, which clears the finished state. Starting a
route from the planner starts the live session directly; the planner's own Start
command still answers `Unsupported`, so the screen is reached through the
planner, `core.nav.start` or the debug replay.

The round 5 layout is `ui-shell`'s `NavigationScreenHost`: the read-only turn
card at the top (`plugins/guidance-ui`), the next-stop and current-speed glass
chips under it, up to two alert banners, and the floating bar with End, the ETA
and the expand chevron. The chevron opens the trip sheet: destination, next
stop, current/average/max speed, the Camera and alerts row, and End and Pause at
the bottom. Camera and alerts open one tap further and change this trip only;
Reset to profile restores auto zoom, auto-centre and 3D, and turns every alert
switch off. A manual map pan shows the recentre button above the bar and leaves
the camera free for five seconds when auto-centre is on.

The screen state is derived once per published `NavigationState` by
`navigationScreenState` in `app`: it reads the guidance panel, the three speed
streams, the active alert banners and the live camera and alert settings, and
never reads the map camera or an alert stream itself. The speed readout is whole
km/h from 10 km/h and one decimal below; it is a dash while paused or after a
lost fix. The ETA reads the state's remaining distance, remaining duration and
`etaEpochMillis`, not the clock. The turn card is the same green/amber/grey
canon as round 3; off route, paused and arrival replace the turn facts with
their own title and strip, and a lost fix dims the last known turn. The status
bar takes the card's colour while the screen is open.

The screen's strings live in `ui-shell` and `plugins/guidance-ui`, in English and
Ukrainian. The round 5 copy the app did not have is in the two bundles: Off
route, Finding a new route…, Keep going - the new route starts where you are,
Last known turn - waiting for GPS, No guidance, alerts or rerouting until you
resume, Recenter, Camera and alerts, Auto zoom, Auto-center, 3D view, Reset to
profile, the speed labels, and the alert names for railway crossing, tunnel,
ford, barrier, hazard, lost and regained GPS and a recalculated route.

`core.nav.show_screen` is a debug-only command for deterministic captures. It
draws the committed `test-data/navigation/screen-route.json` and fills the
screen with one fixed state (`following`, `offroute`, `paused`, `arrived`), an
optional trip sheet, a speed, a list of alert kinds and the dark flag. The
screen is the production composable; only the input state is fixed, so this is
a fixture, not a static screenshot. Clearing it returns the screen to the live
session.

The goldens are `test-data/navigation-goldens/light|dark/following.png`,
`expanded.png` and `alert.png`, captured at 1080x1920 on the API 29 AVD. Each is
the whole frame, compared with the map goldens' 108x192 mean-RGB block grid over
the full frame and the same 5 % tolerance. Capture or compare with:

```sh
./scripts/ci/android-navigation-goldens.sh            # compare
./scripts/ci/android-navigation-goldens.sh --update   # recapture
```

The goldens job runs the same script after the map sets. A live emulator replay
with `core.nav.start` and `test-data/navigation/nav-route.json` shows the next
and second turn and the current speed; `screen-live-route.json` adds the next
stop. The alert banner is exercised by the alert golden, the deterministic
fixture and the alert host tests, because the debug replay's Lviv line has no
mounted way-alert data. The `ui-shell` control test covers the control set, not
the composed screen (the module has no Compose UI test dependency); the device
check covers the drawn controls, and the pan recentre button is exercised on
the emulator.

## Navigation service and location lifecycle

`NavigationController` in `app` owns the one live session (T-0314). It starts
`NavigationService` before the session begins and stops it when the session
ends; no other component starts the service. The service declares
`foregroundServiceType="location"`, so the system shows it as a location
foreground service and `dumpsys activity services` reports `isForeground=true`
with type `0x8`.

The notification shows the next turn's text as its title and the distance to it
as its text. It offers Pause and Stop while guidance runs, Resume and Stop while
paused, and only Stop after arrival. It is public and ongoing, so it survives
the lock screen. Pausing publishes `NavigationPhase.Paused`; the session drops
every fix before the follower and before the reroute gate, so guidance stops and
no reroute can start. An arrival or a stop is terminal: a pause that lands after
that phase is published leaves it unchanged. Arrival ends the location session
but keeps the notification's Stop action until the screen's Done or the
notification's Stop; every other ending removes the service. Resuming continues
from the next fix; a fix that arrived during the pause is never replayed. A
paused session that is stopped ends in `Stopped`, never in `Arrived`, because no
arrival can be detected while fixes are dropped.

The control commands `core.nav.start`, `core.nav.pause`, `core.nav.resume` and
`core.nav.stop` drive the controller. `core.nav.start` takes a route in the
`RouteJson` form, so it needs no routing pack; the emulator acceptance uses
`test-data/navigation/nav-route.json`. A second start while a session runs
reports `started=false` and changes nothing. A stop without a session changes
nothing.

Location is shared, not process-scoped (T-0314, user decision Q10 on
2026-11-08). The map's collector and an active session subscribe to
`SharedLocationSource` in `core-services`: it registers the platform providers
once and stops them when the last subscriber leaves. The activity subscribes at
`STARTED` and stops at `STOPPED`, so leaving the app with no navigation releases
location. During navigation the service holds a subscription, so location
continues while the app is in the background. The app never asks for
`ACCESS_BACKGROUND_LOCATION`.

Android 14 and later require the `FOREGROUND_SERVICE_LOCATION` permission and a
declared `location` type; `startForeground` passes the type explicitly. Android
13 and later show the notification only with the `POST_NOTIFICATIONS` runtime
permission, so the activity asks for it when guidance becomes active; a refusal
leaves guidance running without the notification. The service returns
`START_NOT_STICKY`: after a process death there is no session to resume, and a
restarted service would hold location for nothing. If the location permission
is revoked while the service starts, it catches `SecurityException`, ends the
session and removes itself.

## Alerts

`alerts` owns the alert host (T-0317). `AlertHost` reads the published
`NavigationState` and the fix flow through `onState`, `onFix` or `attach`, and
emits `AlertEvent`s on a typed stream. Every alert is off by default and has its
own switch in `AlertSettings`; the signal switch covers both lost and regained
because one gap is one announcement.

| Alert | Switch | Default | Fires on |
|---|---|---|---|
| Signal lost, regained | `signal` | off | a fix-time gap of 10 s or more |
| Route recalculated | `reroute` | off | a new `NavigationState.routeRevision` |
| Off route | `offRoute` | off | the transition into `RouteRelation.OffRoute` |
| Arrival | `arrival` | off | the transition into `NavigationPhase.Arrived` |

A fix-time gap of at least 10 s (`AlertHost.SIGNAL_GAP_MILLIS`) counts as a lost
signal. The host compares consecutive fix timestamps and announces lost, then
regained, on the first fix after the gap. The detection is retrospective on
purpose: a pure host has no wall clock, and the later fix is the evidence that
the earlier ones were missing. A gap of 9.999 s emits nothing. A timestamp that
does not advance starts a new baseline and announces nothing, so a duplicate
fix or a clock step is not a signal loss.

The other rules keep one episode to one announcement. Off route is announced
once when the relation becomes `OffRoute`, and re-arms only after the relation
leaves it, so the two fixes of the confirmation and a stream of off-route fixes
are one banner. A reroute is announced once per `routeRevision` change; the
first state only sets the baseline, so a host attached to a running session does
not announce a reroute from before it was attached. Arrival is announced once on
the transition into `Arrived`; a second `Arrived` state is the same arrival, and
the latch re-arms only when the phase leaves `Arrived`, which is a new trip.
Latches advance while their switch is off, so enabling an alert in the middle of
an episode waits for the next episode instead of replaying a past one.

`AlertEvent` carries an `AlertBanner`, the effects, a per-host sequence number
and the fix time that decided it. The banner holds the kind, the severity, the
replacement route's own distance for a reroute, the gap length for a lost
signal, the way feature's distance and its own `WayAlertFeatureKind`, so a ford
and a barrier get their own copy while sharing the hazard switch, and a
proximity place's own name and the distance that decided its alert. It holds no
text: the screen resolves the kind through i18n and formats the distance with
the unit system. The event is `@Serializable`, so `AlertEvent.serializer()` is
the stream's serializer for the control channel and the navigation screen.

Sound and vibration are values, not platform calls: `AlertEffect.Sound` and
`AlertEffect.Vibration` each carry the alert kind, and the host forwards them to
an `AlertEffectSink`. The default `NoopAlertEffectSink` does nothing, which is
what keeps the host JVM-testable. `AndroidAlertEffects` is a minimal platform
sink with a short notification tone and a vibration pattern; it is not reached
by the JVM tests. Real audio for turn beeps is T-0318 and is described below.

The acceptance replays one deterministic fix timeline through the production
`NavigationSession` and the geometry follower: on route, 20 m off, a reroute
with a longer replacement, a 15 s fix gap, then arrival. With every switch off
it emits no event and no effect while the session still reports the off-route
fixes, the reroute and the arrival. With the reroute switch on it emits one
notice carrying the replacement's distance; with the signal switch on it emits
lost then regained once. The host tests cover the 10 s boundary, a 9.999 s gap,
a repeated arrival, off-route episodes and the JSON round trip. The navigation
screen collects the alert stream through `NavigationAlertBanners`, which keeps
at most two banners, clears a way alert once its feature is behind and gives an
info banner four seconds and a warning eight. Way alerts are T-0319.

## Turn beeps

Turn beeps are the beep-only guidance mode (T-0318). `BeepHost` reads the same
published `NavigationState` as the alert host, asks `BeepSchedule` whether a
turn is due, and sends the cue to a `BeepSink`. Beeps are off by default. The
app wires `AndroidBeepSink`; `NoopBeepSink` keeps a JVM test free of Android.

The distance is one beep about ten to eleven seconds before the turn, at the
profile's nominal speed:

| Profile | Distance | At the profile speed |
|---|---|---|
| Walk, Hike | 15 m | 5 km/h, about 11 s |
| Bike | 50 m | 18 km/h, about 10 s |
| Car | 150 m | 50 km/h, about 11 s |

One value cannot serve every profile: 100 m is 72 s of walking and seven
seconds of driving. `BeepDistance` holds the three values, and
`core.nav.start` sets the profile on the host before a session starts.

A turn is armed when the next instruction is seen farther than the distance and
beeps on the first later state that reports the same turn at or inside it. A
turn first seen inside the distance is not armed, so attaching to a running
session does not beep late. A turn that is passed while the signal is gone, or
while the session is paused, is disarmed: the same 10 s fix gap as the signal
alert clears the approach, and a paused state clears it. A turn is beeped at
most once per route revision.

The once-per-turn key is `BeepTurnKey(routeRevision, instructionIndex)`. The
index is the position in `activeRoute.instructions` of the maneuver whose
absolute route position is the travelled distance plus the distance ahead.
Two turns with the same text therefore remain two turns. A reroute changes the
revision and re-arms every turn on the replacement route. When the route carries
no matching instruction, the fallback key is the instruction text, the street
and a ten-metre bucket of the maneuver's route position.

The typed request is `BeepAudioRequest(AudioStream.Music, AudioFocusMode.Duck,
1_500 ms)`. The beep chooses the music stream with the navigation-guidance
usage, because it is guidance audio: it follows media volume and is not
silenced by the notification stream or a ringer mode. It asks for
`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`, so other audio keeps playing quieter
rather than stopping. `AudioFocusMode.Pause`
(`AUDIOFOCUS_GAIN_TRANSIENT`) and the notification and system streams exist as
typed alternatives. Focus is requested before the 500 ms tone and abandoned
after the 1.5 s window; a later beep supersedes the earlier window. A refused
focus request keeps the beep silent. `AndroidBeepSink` implements the Android
side; `BeepAudio` and the JVM tests drive the same policy without Android.

The probe tag is `GrassMapsBeep` (`LogcatBeepProbe.TAG`). One line is logged
for each beep that the audio policy accepted:

```
beep instruction="Turn left at the square" distance_m=14.9 stream=music route_revision=0 turn=1
```

The device run turns beeps on with the control command `core.nav.beeps`, whose
parameters are `enabled` and an optional `profile` (`walk`, `bike`, `hike`,
`car`). The command reports the profile and distance it applied. The debug
route used for the g32 run has two turns at 40 m and 60 m, so the beeps land at
25 m and 45 m along the route.

`core.nav.beep_probe` is the deterministic device probe: it takes a route in
the stable JSON form, an optional profile and `tick_ms`, replays the route's
vertices at the profile's speed through a real `NavigationSession` and the
geometry follower, and feeds every published state into the same beep host. It
returns `beeps`, `fixes` and `arrived`, and each beep writes the line above.
The g32 run used `test-data/navigation/beep-route.json`, logged two beeps at
14.8 m and 13.8 m and held `GAIN_TRANSIENT_MAY_DUCK` for the beep window; the
transcript is in `test-data/navigation/evidence/t-0318-g32-beeps.txt`.

The live path is also wired: `AppContainer` attaches the host to
`NavigationController.state`, and `core.nav.start` sets the profile before a
session starts. On the g32 probe the live replay did not deliver fixes to that
controller, so the device evidence comes from the probe command; the live
attachment itself was not exercised on the phone. What remains: a screen that
exposes the beep mode and its distance, the way alerts described next, and the
real music-app pause behaviour. The pause mode only requests focus; whether a
particular player pauses on the loss is the player's decision, so it was not
measured here. The duck request is visible in `dumpsys audio`; whether music is
audibly quieter needs a player that is actually playing.

## Way alerts

Way alerts are T-0319. They come from the mounted region, not from Valhalla:
the engine does not return hazards, fords or barriers, and T-0301 verified its
tiles do not carry them. `RegionReader` in `regions` reads the
`way-alerts.geojson` component and answers a corridor query along the active
route. A package without the component is valid and reports no features.

Since T-0321 the component read, its parse and both queries run in
`rust/grass-regions` behind a uniffi `RegionReader`. The Kotlin `RegionReader`
keeps the mount contract - it parses the manifest, selects the component and
checks its format, version, declared size and file - and maps the native values
onto `WayAlertHit` and `RouteRelations`, so no caller or plugin sees a native
type. The parsed feature list is cached inside the native reader against the
component path and declared size, and the read is off Main. The Kotlin
corridor maths and GeoJSON parser are gone; the fixtures that pinned them were
not edited.

The kinds are a railway crossing, a tunnel and a hazard. A ford or a barrier
node uses the hazard kind and the hazard switch. Each kind has its own switch,
and all three are off by default, like the T-0317 alerts. The product scope
gives crossings and hazards to bike and hike, and tunnels to bike only;
walking and driving get no way alerts, so their distance is null and the
feature is skipped. The component is extracted from OSM (T-0319A):
`osm-extract --way-alerts` writes it from the region's PBF, the fixture uses its
own small OSM input, and the Lviv pack carries 18,009 extracted features. A
package without the component still opens and reports no features.

The first corridor query pays the read and the parse; every later query on the
same mount only runs the geometry. Measured on this machine (Fedora, Intel Core
i5-9300H, 8 threads, JVM 21.0.12.1, the component in page cache, a fresh reader
per sample, 15 samples after three warmups, median): the committed test region
(8 features, 1,341 B) takes 2.2 ms against 1.1 ms for the Kotlin reader at dev
`bcf212b`, and the Lviv pack (18,009 features, 2,921,155 B) 19.8 ms against
36.5 ms. Of the Lviv figure, 12.6 ms is the native read and parse measured
inside the crate; the rest is the manifest read, the uniffi call and the model
mapping. At the test region's size the fixed cost of a fresh native reader is
larger than the parse it saves. The relation component (130,097 B) takes 2.6 ms
against 1.7 ms, parsed from JSON values rather than typed ones; at that size
the difference is under a millisecond. The APK grows by 1,360,027 B: 573,776 B
for `arm64-v8a`, 638,680 B for `x86_64`, and the remaining 147,571 B is the
generated Kotlin bindings and their dex metadata (the two `.so` files are the
only new native entries in the APK, inferred, not measured separately). A build
with an empty `regions/build/rust` and no generated bindings takes 224 s, with
the Gradle caches otherwise warm; the crate's own `cargo test` takes 1.7 s warm
and 11.6 s with an empty target directory.

The corridor is 30 m wide and the warning is 20 to 40 seconds of travel before
the feature:

| Feature | Hike, 5 km/h | Bike, 18 km/h |
|---|---|---|
| Railway crossing | 50 m, 36 s | 150 m, 30 s |
| Tunnel | - | 200 m, 40 s |
| Hazard, ford, barrier | 30 m, 22 s | 100 m, 20 s |

The corridor is also the answer to "close enough to matter": the route line
and the OSM geometry both carry metre-level noise, a barrier at the verge is
still on the route, and 30 m keeps a parallel railway or a service road one
street away out of the result. The distance is wider than a turn beep because
the rider must see the feature, not only react to a maneuver.

`WayAlertSchedule` in `plugins/way-alerts` queries the region on each route
revision change and caches the hits. A feature is announced on the first state
that puts it ahead of the rider at or inside the distance for the active
profile; a feature first seen inside the distance is announced at once, because
it is a hazard on the road, not a maneuver the rider can anticipate. A paused
phase keeps the cache and the announcements and emits nothing. A terminal phase
clears both, so a new trip starts clean even when its route revision starts at
zero again.

The once-per-feature key is
`WayAlertKey(routeRevision, elementType, osmId)`. The element type keeps a node
and a way with the same numeric id apart. A feature is announced once per
revision; a reroute changes the revision and re-arms every feature on the
replacement route. The device feature off the corridor is never returned by
the reader, so it cannot alert at all.

`AlertHost.onWayAlert` is the typed path: it checks the switch for the kind,
drops a key it has already announced in this revision, and emits the same
`AlertEvent` as the other alerts. The banner carries `featureDistanceMeters`
next to the kind and severity, and the effects are the usual sound and
vibration for the kind. `core.nav.way_alerts` turns the switches on or off on a
device; its parameters are `enabled` and an optional `kind` (`all`, `tunnel`,
`railway_crossing`, `hazard`).

The committed test region has a crossing at `23.947`, a tunnel from `23.949` to
`23.951` and a hazard at `23.952` on the straight synthetic test route from
`49.949, 23.943` to `49.949, 23.953`, plus a second hazard 167 m north of it.
The file is an authored stand-in until the OSM extraction writes it; the
manifest says so. The tests read it through the real `RegionReader`, drive the
real schedule, and bridge the cues into the real `AlertHost` for the all-off
and typed-banner cases.

## Proximity alerts

Proximity alerts are T-0413. They announce a favourite, a waypoint of a stored
track, or a place of a kind the user picked, once per approach, while a session
is active. The design handoff (round 6) says they use the blue info banner and
that place kinds are chips the user picks. It states no radius, so the three
radii are chosen here, one constant per category in `ProximityRadius`:

| Category | Switch | Radius | Walking, 5 km/h | Cycling, 18 km/h |
|---|---|---|---|---|
| Favourite | `favourite` | 200 m | 2 min 24 s | 40 s |
| Track waypoint | `trackWaypoint` | 100 m | 1 min 12 s | 20 s |
| Chosen place kind | `poi` | 250 m | 3 min | 50 s |

All three switches are off by default in `AlertSettings`, like every other
alert. A kind that is off produces no event and no effect, because
`AlertHost.onPlaceAlert` checks the switch before it builds a banner.

"Once" is one approach. It is not one fix, and it is not a time window.
`ProximityAlertSchedule` latches a place when the fix is inside its radius. It
releases the latch only when the fix is 50 m beyond the radius
(`ProximityRadius.RELEASE_METERS`). A fix that stays inside the radius
announces once, and a traveller who leaves the radius and comes back gets a
second alert. The latch key is the category plus the place's own id: a
favourite's id, the track id and the waypoint index, or the search index's
place id. A place that drops out of the lookup box and returns is a new
approach. The host keeps no latch of its own, so the producer is the only owner
of "once".

The schedule asks `ProximityPlaceSource` for the places near the fix when the
session starts and every 200 m of travel, inside a box 500 m across. The app's
source (`LibraryProximityPlaces`) reads the favourites library, the waypoints of
every stored track, and the offline index for the chosen place kinds. Each part
is read on its own, so an unreadable favourites file or an unmounted index still
leaves the other two working. The lookup box is wider than the largest radius
plus one refresh step, so a place cannot enter a radius unseen.

A place of a kind the user did not pick takes its latch without an alert. A
kind chosen later therefore does not replay a place the traveller is already
next to, which is the same rule as a switch that was off.

The banner names the category and carries the place's own name and the distance
that decided the alert: "Favourite nearby" and "Home · in 180 m", in English and
Ukrainian. The navigation screen maps the three kinds to the blue info banner
with its place glyph. A proximity banner clears with the other informational
banners, after 4 s.

The app starts the host in `AppContainer.proximityAlerts` beside the way-alert
host and feeds it the published navigation state. A session counts as active
from its first state past Idle until it is stopped, so a pause or an arrival
keeps the latches and a finished trip clears them. The chosen place kinds live
in `AppContainer.proximityPoiKinds`; nothing writes that list yet, because the
alerts page from the design handoff does not exist, so place kinds stay silent
on a device. The acceptance is `ProximityReplayTest`: a track replay through
the production session past one place of each category raises one alert of
each, and the same replay with a switch off raises none of that kind.

## Route warnings

Route warnings are T-0409. `RouteWarningScanner` in
`plugins/route-warnings` answers one list for a planned route and a profile,
ordered by distance along the route. The list names five kinds: a ford, steps,
a barrier, a railway crossing and a private way. `app` builds the scanner over
the mounted region (`AppContainer.routeWarnings`) and asks it once for the
selected route and the plan's profile. A new selected route or another profile
is a new scan; a cancelled scan writes nothing, so a late answer cannot replace
a newer one. A region or invalid-input failure logs the outcome and shows no
warnings, and the plan itself is unaffected.

The ford, the barrier and the railway crossing are region features, read
through the same `RegionAccess.wayAlertFeatures` corridor query as the way
alerts, not from Valhalla tiles. The 30 m corridor is the way-alert one, for
the same reason: metre-level noise in both lines, and a parallel railway or a
service road one street away stays out. Steps and a private way are route
attributes: they come from the route's annotated segments, where the T-0301
`trace_attributes` pass puts `highway=steps` and `access=private`. The region
component does not carry either one. The planner runs that pass after it
publishes a route, so the live list gains the two route-attribute kinds when
the engine answers; `RouteWarningScanner` documents that a route which is not
annotated yet yields the region half only.

| Kind | Walk | Hike | Bike | Source |
|---|---|---|---|---|
| Ford | yes | yes | yes | region node or way |
| Steps | yes | yes | yes | route segment `highway=steps` |
| Barrier | yes | yes | no | region node |
| Railway crossing | yes | yes | yes | region node |
| Private way | yes | yes | yes | route segment `access=private` |

A barrier is dropped for a bike because the region component keeps gates,
stiles and similar passable barriers but drops bollards and kerbs, and it does
not carry the barrier's own value. A bike can open a gate or lift the bike over
a stile, so the bike list leaves the whole kind out instead of warning on every
openable gate. A railway crossing applies to walk as well: the round-5
navigation design gives walking crossing warnings, and a crossing is a hazard at
any speed. The list has no alert distance of its own; the along-route distance
is the only distance a warning carries. Car is outside v1 and is invalid input
to the scanner.

Each warning carries its kind, its distance along the route, the OSM element
type and id when a region feature supplied it, and the profiles it applies to.
Steps and private ways have no OSM id, because a route segment does not carry
the way id. A region node is listed once even when the route meets it twice, and
a run of consecutive segments of the same kind is one warning, because the
engine cuts one OSM way into one segment per graph edge.

The committed test region already has the three region features: the ford at
`49.9510, 23.9440`, the gate at `49.9510, 23.9460` and the shared railway
crossing at `49.9515, 23.9460`. Steps and private access are made by the test
route's own segments, so the fixture needed no new file and no authored feature
is presented as extracted. `RouteWarningScannerTest` drives the real
`RegionReader` over that package for the five-kind order, the two profile
filters, the repeated-node case, the empty route and the region failure.

The round-6 design's two surfaces are the panel's. `ui-shell` draws the amber
"N warnings on this route" row above the route preferences entry, with the
distinct kinds in distance order, and only while the selected route has
warnings; a tap expands the "On this route" list, one row per warning with its
kind, the private way's `access=private` or the bike's "push the bike", and the
distance. `app` maps the scanner's kind onto the panel's own glyph, keeping the
scanner's order. The row's count is also the list's, so the two never disagree.
The design's marker on the route line is not drawn: `MapHost` has no marker
set, and the composed-layers compiler rejects circle layers as not rendered
yet, so no drawable layer type carries one point per warning.

`RouteWarningsContentTest` covers the count, the summary, the row order, the
kind text, the details and the distances, and the absent row at zero warnings.
`RouteWarningsWiringTest` drives the real scanner over the same package for the
five kinds and the profile filter, and counts scans to prove one scan per route
and profile, a rescan on a profile or route change, and the failure path.

## Hiking and cycling route relations

Hiking and cycling route relations are T-0418 and T-0419. `RegionReader` reads
the mounted region's `relations.json` component: each member way's geometry
once, each relation's ordered way members with its `route`, `network`, `ref`,
`name`, `colour` and `osmc:symbol`, and the numbered junctions of the cycling
node networks. Since T-0321 the decode runs in `rust/grass-regions` and the
Kotlin side rebuilds the model from the native values. A package without the
component opens and draws nothing.

`plugins/route-relations` assembles each relation by walking its members in
relation order and reversing a way that points back against the previous
member. Shared ways carry a lane rank and count, so two routes that share a
path draw side by side instead of on top of each other, across the two route
kinds as well. Hiking bands use the style's hiking network colours (`iwn`
`#d9483b`, `nwn` `#4a3fd6`, `rwn` `#e07a2f`, `lwn` `#6fbf3a`) at 0.55 opacity
from z9. Cycling bands use `icn` `#d93030`, `ncn` `#e0602a`, `rcn` `#b03ac7`
and `lcn` `#2f5fe0` at 0.45 opacity from z11. A route without a network, or
with a tag from the other kind, draws as the local tier of its own kind.

The waymark is the `osmc:symbol` background plate and foreground shape, drawn
from eighteen `osmc_*` SDF glyphs authored here and tinted per feature. The
`foreground2` and text parts of the tag are not drawn yet. A route with a shape
the layer does not carry keeps its band and draws no mark. The geometry comes
from the component and `MapHost.addLayer` with an inline GeoJSON source, never
from `queryRenderedFeatures`.

A cycling node-network junction draws from its tagged OSM node at z15: a white
disc, a green ring and the `*cn_ref` number in medium type. The marker is a
`LayerType.Circle` plus a text symbol layer, so it does not need a sprite glyph
and does not move when a route line is redrawn.

The extraction rules and the measured Lviv and fixture runs are in
`tools/pipeline/README.md`. The fixture carries two hiking routes sharing one
real track, two cycling routes on the same tracks, and five node numbers. The
golden cases `hiking-routes-z13`, `hiking-routes-z14`, `cycling-routes-z12` and
`cycling-node-numbers-z15` drive the same parser, assembler, symbol mapping,
node placement and map layers through the debug
`core.map.set_route_relations` command.

## Search seam

`PlaceProvider` in `plugin-api` is the `places.search` 2.1.0 contract:
`search(query, context, limit, cancel)`, `pois(bbox, categories, limit, cancel)`
and `details(placeId, cancel)`. `places` implements it over `rust/grass-core`
through uniffi. The generated Kotlin and every Rust type stay inside `places`
(package `nl.r1a.grassmap.places.core`); no plugin imports them.

Version 2.1.0 makes `SearchContext.viewport` a filter rather than a ranking
bias and adds `PlaceHit.isCoordinate`. No shipped provider implemented the
2.0.0 wording. When the caller searches "this area", every hit the provider
returns is inside the box, edges included: `BoundingBox.contains` is inclusive,
and the Rust session filters candidates before it applies the result limit, so
an out-of-view place cannot consume one of the limit's slots. A box that is not
finite, ordered or inside WGS84 is `InvalidInput`. `MapHost.visibleBounds()` is
the seam for the bounds themselves; the map reports the north-up box that
covers the rotated viewport, and null before it has a size.

A coordinate typed into the search field is one result row at that point.
`plugins/coordinates` owns the parser, and `PlacesAccess` asks it before it
opens a section, so the row appears on a device with no region installed and a
point outside the installed region still shows and still moves the map. The
row carries `isCoordinate` and no category, address or brand: nothing about it
comes from an index, so `details` has no record behind its id and the shell
moves the camera instead of opening a card. The parser reads decimal, DMS and
DDM with explicit signs or hemisphere letters, as a prefix or a suffix, with
symbols or plain spaces. `test-data/search/coordinate-notations.tsv` lists the
accepted notations and the near misses that must stay refused: three numbers, a
minute or second at 60, an axis out of range, a longitude-first pair, a sign
that contradicts its hemisphere, and a bare integer pair that is as likely a
house number. The same file drives an app test that formats every accepted
point with `plugins/share` in all three notations and parses it back, so the
writing and reading directions cannot drift apart.

`details` returns `PlaceDetails`: the place record plus its canonical OSM
`osmType` and `osmId` and its raw `tags` map. Those come from the region
package's `features` component, which is keyed by the same place row id the
search table issued (`regionBuild:rowIndex`). The search record therefore keeps
its fixed 20-byte row and needs no OSM id column. A place the component does not
name still returns details, with a null identity and an empty tag map; a place
with a feature entry and no tags returns the same empty map. A missing,
truncated or unknown-version features file is `NotReady`, `CorruptData` or
`Unsupported` respectively, never an empty map.

The reader is format version 2: the P4a spike layout plus `format.bin` (magic
`GRASSSEC`, then a u16 version) and a house-number row flagged `FLAG_ADDRESS`.
A house is a place row whose name is the street and number and whose context is
the locality, so "Стрийська 45" and "вулиця Стрийська 45" are the same query.
Street-type words ("вулиця", "проспект", ...) are not indexed for a house and
are dropped from a query that also carries a number: every address on such a
street shares them, and in the Lviv run they saturated the postings budget, so
an exact house lost to a nearer address that merely shared the type word. A
letter suffix is one house however it is typed: the builder and the query both
fold a digit-led token to one canonical term, so `2-А`, `2а` and `2a` match the
same row. A query with no letter at all returns nothing, so a bare house number
cannot flood the list. A section without `format.bin` is version 1 and still
opens; a version newer than 2 is `Unsupported`. `build-section` now writes
version 2.

The reader is an FST of folded terms, delta-varint postings, a fixed 20-byte
place table, context entities, a string pool and the feature component. It
mmaps read-only, validates every offset before handing the index out, and
returns `CorruptData` for a truncated section. Ranking and typo budgets are the
spike's, so the osm-mapidx probe table stays a quality oracle. The score is
computed from the committed `queries.tsv` and asserted as 1.0 by
`RustPlaceProviderQualityTest`, so the table stays 45 of 45. The house-number
oracle is 50 fixed Lviv addresses, top one, asserted by `LvivAddressSearchTest`
with a 45/50 floor; the test prints every miss by name. The T-0204 kind, brand
and wording set is `kind-brand-queries.tsv`, read and scored by
`KindBrandSearchTest` against the house-number fixture; it covers the synonym
table (`rust/grass-core/data/synonyms.tsv`), street abbreviations in both
directions, and brand tags.

Search answers a name typed in Cyrillic or in Latin. Two data sources make that
work, and `tools/pipeline/README.md` has their measurements. OSM's
`name:<language>` tags are carried into a row's aliases, so a Polish exonym
("Złoczów" for Золочів) is found because OSM has it. A Cyrillic name with no
Latin spelling gets one from
`rust/grass-core/data/transliteration.tsv`, applied by the builder: the
official 2010 transliteration plus a pragmatic variant for how people type.
Transliteration is index-time only, so the query path stays one fold and one
prefix walk. `ZolochivSearchTest` asserts the acceptance case: "zloczow",
"Złoczów" and "Золочів" return the same top result from the committed
`search/zolochiv` section.

`normaliseText` and `canonicalWords` cross the uniffi boundary, and
`test-data/search/normalisation-vectors.tsv` is read by `fold::tests` in the
crate and by `NormalisationVectorsTest` through those bindings, so the builder
and the app cannot fold a name differently.

The index is external data: the provider reads
`<externalFilesDir>/search/<region>/`. The committed Lviv section under
`places/src/test/resources/search` is 10.1 MiB and ODbL-licensed; its
`features.bin` is an empty placeholder because the P4a spike predates the
component, so those places have no identity or tags. The house-number fixture
`places/src/test/resources/search/lviv-addresses` is 8.57 MiB: places and house
rows, built by `tools/pipeline/osm-extract` from the 2026-11-04 Ukraine extract
over the Lviv city bbox. `search/zolochiv` is 99,022 B and is the same pipeline's
output for one town, places only, for the transliteration tests. The extraction
stage, its commands, the Lviv-bbox and whole-country size measurements, and
their conditions are in `tools/pipeline/README.md`. The debug channel exposes
`core.search.query`:

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
walk); later single-letter keystrokes stayed under 11 ms.

## Search screen

The search screen (T-0203) is one field for every kind of place: a name, a
street or a house number. `ui-shell`'s `SearchScreenHost` draws the field, the
result list and the map markers. `places`' `SearchScreenSession` holds the one
query path: it calls `PlacesAccess.search` and publishes a `SearchScreenState`
that carries the newest result set. The screen never reaches an index directly.

A keystroke travels through the command bus as `core.search.screen` with a
sequence number. The command is registered with the `core.search.screen.query`
cancellation key, so a newer keystroke stops the running one. The session also
drops a result whose sequence is not the newest started, which covers a caller
that bypasses the bus; the replay harness is such a caller. A superseded
keystroke returns `Cancelled` and publishes nothing, so a slow older result can
never replace a newer one.

The list and the markers are two views of one result set. `searchResultView`
maps the hits once, and the screen draws its `rows` and its `markers` from that
one value. Tapping a row moves the camera to the place and opens the place card
through the same `PlaceCardAccess` path the debug command uses. A coordinate
row is the exception: it has no card behind it, so `MapScreen` moves the camera
and opens nothing when `PlaceHit.isCoordinate` is set (T-0206).

A keystroke may also carry `viewport` as `{"south": .., "west": .., "north":
.., "east": ..}`. That is "search this area": the provider keeps only results
inside the box, so the list and the markers never show a place off the map.
The shell asks `MapHost.visibleBounds()` for the bounds; the command parameter
and the provider filter are the seam, and the on-screen control that turns the
option on belongs to the search screen.

Markers draw as a Compose overlay above the map surface, not as a style layer.
`SearchMarkerProjection` uses `WebMercator`'s two constants and the camera
bearing, then adds the same position offset the map host was given, so a puck
that sits off-centre keeps its markers under it. Tilt is not applied: the
shipped app never tilts the camera, and a tilting mode would need the engine's
marker slot instead. The overlay changes no style, sprite or golden reference.
It draws nothing until the field has results, and a golden capture runs with the
field empty.

The keystroke budget is measured through the screen's own session.
`SearchKeystrokeBenchTest` types the committed probe set letter by letter on the
JVM and fails when p99 passes 50 ms. On this machine (Fedora, Intel Core
i5-9300H, 8 threads, JVM 21.0.12.1) 478 keystrokes from 48 probes measured p50
0.95 ms, p95 4.79 ms and p99 6.62 ms; the first keystroke cost 29.59 ms because
it opens the index.

The phone run is `scripts/bench/search-keystrokes.sh`. It installs the debug
APK, copies the committed section into the app's external files directory,
starts the activity and invokes `core.search.replay` with the committed probe
set. That command types each query letter by letter through
`SearchScreenSession.submit` and returns p50, p95 and p99 in the app, so the
number includes the uniffi bridge and not the adb round trip.


## Search history

`places`' `SearchHistoryStore` (T-0207) keeps the last 20 completed searches,
newest first, in `history/search-history.json` under the app's private files
directory. The number is `DEFAULT_MAX_ENTRIES`. Twenty rows cover a few days of
real use and read without paging. Twenty also bound the file at about 7 kB when
every query and context is at its widest.

A search is one terminal keystroke the screen completed, not every letter on
the way there. The store replaces its newest entry when the next query extends
or shortens it, so typing "lviv" leaves one row and not four. A repeated query
moves to the front with a new time.

An entry holds the query and the exact request context, so a replay calls
`PlaceProvider.search` again with the same values: the region `index`, the
`limit`, the viewport filter, the map position and the language. The language
is stored even though no caller sets it yet. The store also records the
completion time from the injected `Clock`. That time is the only ordering key,
and file order breaks a tie.

The file is JSON with a `format` version and an `entries` array. A write goes
through a temporary sibling, an fsync and an atomic move, like the favourites
store, so a failed write leaves the previous list. One entry looks like this:

```json
{"query":"lviv","index":"lviv","limit":5,"atMillis":1794000000000,"position":{"lat":49.83,"lon":24.03},"language":"uk"}
```

History is disposable data, not a document the user authored, so its failure
rules differ from favourites. A missing or unreadable file is an empty history.
A corrupt file also reads as empty, and the next search replaces it. A file
whose `format` is newer than this build is never overwritten: `remember` and
`list` return `Unsupported` until the app updates, and only an explicit clear
removes it. A query that is blank, longer than 256 characters, or carries a
limit or box outside the search contract is `InvalidInput` and writes nothing.

The switch is `core-services`' `SearchHistorySettings`, stored in
`settings/search-history.json` and on by default. It is a local setting, not an
entry in `NetworkSwitches`. That registry and its request counters describe
contact that leaves the device, while this switch gates a local file. With the
switch off, `remember` returns success without reading or writing the file and
`list` returns empty without opening it. The store therefore proves the rule
that a switch off stops the store write, not only a screen element. An explicit
clear still works while history is off, because erasing history is the user's
own action.

`core.search.history_clear` empties the store, including a temporary sibling a
crash may have left, and returns `cleared=true entries=0`.
`core.search.history_list` returns the entry count and deliberately no query
text, because control-channel output is logged. Both are on the control
allowlist and run on IO. The settings screen arrives later. Until then
`core.settings.search_history` sets the switch from the control channel with
`{"enabled":false}` or a bare boolean.

### Why the store is a file and not SQLite or the Rust core

The design left the history database open between SQLite and the Rust core's
own store. This task settles it as one small JSON file in the same app-private
storage as the other user data. The rejected options:

- SQLite (`history.db`) was the earlier default. The list is at most 20 rows of
  a few hundred bytes, so a database adds a schema, a migration surface and an
  Android-only dependency that the plain JVM tests cannot open. Every store in
  this repository is a checked, atomically written file, and this change keeps
  one pattern rather than adding a second.
- The Rust core's store does not fit. The core is a read-only index reader that
  answers queries over region data. A writable user-data store there would
  cross the uniffi boundary for data the app already owns, and would put a
  write path into a component whose contract is reads.
- A key in the future global `settings.json` does not fit either. History is a
  list with its own clear action and its own privacy switch, not a scalar
  preference. It moves into the versioned settings file when that file exists,
  as `settings/units.json` and `settings/search-history.json` will.

The file is app-private and is never backed up.

## Favourites lists seam

`plugins/favourites-ui` (T-0213) owns the lists screen state and its commands:
create a list, move a point, recolour a list, set Home, set Work and undo, plus
the two browsing controls of T-0214, `set_query` and `set_sort`. The plugin
depends on `:plugin-api` only. It writes through `FavouritesListsAccess` in
`plugin-api.host`, which `app` implements as `StoreFavouritesLists` over the
merged favourites store, so every action writes `favourites/favourites.gpx` and
its `<osmand:points_groups>` definitions before the plugin publishes a new
state. The plugin never parses GPX and there is no second store.

The published `FavouriteListsState` keeps the library's file-order `points` and
adds a derived `browse` view: the trimmed query, one of four fixed sorts and the
rows the sheet draws. The sorts are file order, name A-Z, name Z-A and list then
name; names compare after lowercasing by UTF-16 code units, never through a
locale collator, and equal names keep file order. Search folds case and accents
over a point's name and its rendered description, so `cafe` finds `Café`; a
blank query restores every point and no match is an empty row list. A stored
description is untrusted text: the plugin renders simple markup to plain text,
decodes known entities, drops every tag with its attributes, drops `script` and
`style` content whole, and caps both the scanned input and the rendered output.
The sheet never sees markup.

A list is an OsmAnd group. Moving a point changes its `<type>`; if the point's
appearance matched its old list's, it adopts the destination's appearance.
Recolouring a list changes the group colour and every member's
`osmand:color`, including points that inherited the list's look. Home and Work
are written as `<grassmap:special>` in our own GPX namespace inside
`<extensions>`, which OsmAnd and plain GPX readers ignore. A file that names
the same special twice keeps the first, so the library holds one Home and one
Work at most.

Undo is scoped per action, like the favourites delete receipt, and is not the
T-0304 history: each action returns one receipt and the plugin keeps them on a
small stack. An undo whose point or list no longer matches is a typed no-op
(`FavouriteUndo.NOT_APPLICABLE`), never an error and never a partial write.
The state is published as `favourites.ui.lists`; `ui-shell` draws the sheet and
`app` wires the callbacks. The debug-only commands
`core.favourites.lists_show` and `core.favourites.lists_hide` open it until the
main UI has a favourites entry point.

## Tracks seam

`FileFormat` in `plugin-api` is the `file.format` 1.0.0 contract. Its static
members (extensions, MIME types, entity kinds) let the host choose a provider
without activating it. `importFile(bytes, cancel)` returns the tracks, routes
and waypoints it could read plus one warning per skipped part, so a partly
broken file still imports. `export(entities, cancel)` writes GPX 1.1 (T-0410).

`plugins/gpx` (`file.gpx`) implements GPX 1.0 and 1.1 for tracks, routes and
waypoints. It reads element names without a namespace or prefix assumption and
hardens the XML parser against doctypes and external entities. A point with
missing or out-of-range coordinates is skipped with a structural locator such
as `trk[0]/trkseg[1]/trkpt[4]`; a broken elevation or time only clears that
field. A document that is not well-formed XML at all is `CorruptData`.
Export writes the elements GPX 1.1 defines, in schema order, and keeps a stored
track's colour and width as `<grassmap:colour>` and `<grassmap:widthDp>` inside
`<trk><extensions>`. That namespace is
`https://grassmaps.app/gpx/1`, the same one favourites use. The importer ignores
it, so a round-trip returns the same geometry, names, elevations and times; the
sidecar stays the store's source of truth for styling. A document the writer
cannot represent (out-of-range coordinates, a non-finite elevation, an empty
segment or a control character in a name) is `InvalidInput` instead of a file
that lost data. Tests cover every fixture in both directions, multi-track list
export, escaping, cancellation and the refusal cases.

A waypoint keeps its `<name>`, its `<desc>` and its coordinates (T-0411). The
symbol and the type have no field, because this build has no icon vocabulary:
an import drops `<sym>` and `<type>` and an export writes neither, so
`garmin-waypoints-route.gpx` loses the `Trail Head` symbol its author chose and
keeps everything else. A description is kept as written, line breaks included,
and a control character in it is `InvalidInput` rather than a document that
cannot be parsed again.

The official `gpx.xsd` is not vendored: it is absent from the repository and
says nothing about a licence on topografix.com, so adding it would be a user
decision. `GpxSchema11` instead encodes the schema's sequence, cardinality,
simple-type and namespace rules and validates every exported document.

`tracks` owns the import path: resolve the provider through `ProviderRegistry`
in `app`, read the document, store every track it contains and compute each
profile. T-0401 adds the file store behind that path:

- a document is `<sha256>.gpx` under the app's private `tracks/` directory,
  written exactly as it arrived;
- a sidecar `<id>.json` sits beside it with the styling (`colour`, `widthDp`),
  the cached profile, the document index, the record's `kind` and the hash it
  belongs to, plus the library metadata of T-0414: the name, the description
  and the folder id;
- the id is the document hash. A document with several records appends
  `:<index>` to every one, so one file stays one file and each record keeps a
  stable id. The record order is every `<trk>` first, then every `<rte>`;
- a saved route also has `<id>.route.json`, its recompute sidecar (T-0411).

A record is a `<trk>` or a `<rte>`. `TrackKind` says which, it comes from the
element family the record was read from, and the library sidecar stores it, so
a row can say `Route` without guessing from a name. An older sidecar has no
`kind` and reads as a track. The document's `<wpt>` entries are parsed on every
load and carried on each record the document produced, so an export writes them
back; a document holding several records contributes each waypoint once.

The GPX is the source of truth for geometry; a reload reads it again and
refuses a sidecar whose document hash no longer matches the file. A save writes
a hidden `.<name>.part` sibling, flushes it and moves it over the target
atomically, so an interrupted write leaves the previous file intact. Importing
the same bytes again updates the same records and keeps their colour. A missing
or corrupt sidecar, a hash mismatch or a document the format rejects skips that
one track and logs it; the rest of the library still loads. Every call returns
a typed `Outcome`, and file I/O runs on `Dispatchers.IO`. Every successful write
raises the store's revision, so a view that caches the library reloads instead
of keeping a snapshot.

`ImportReport` carries the stored records and the skipped parts. The warning
line is `<locator>: <what happened>`, for example
`trk[0]/trkseg[0]/trkpt[2]: missing coordinates` or
`trk[0]/trkseg[1]: empty segment dropped`. That line is log and test text:
the library screen (T-0414) writes its own localized sentence from the stable
code and locator.

`MapHost.setTracks` draws the library. `plugins/track-layer` owns the
controller: it collects the store's drawable list and writes it to the bound
host, which draws one line per track in the overlays slot. The colour and width
come from the sidecar, and a new track gets a deterministic palette colour
derived from its hash, so the same file draws the same colour on every device.
A segment with fewer than two points has no line. The layer cannot colour a
track by gradient (T-0408): a track has neither surface nor smoothness, and the
drawable form carries no heights, so `TrackColouring.Gradient` logs the gap and
keeps the sidecar colour. The library's own colour stays the only track colour
the map draws.

The library screen (T-0414) is `TrackLibraryHost` in `ui-shell`, drawn from the
`track.library` plugin's state. `plugins/track-library` owns the folder filter,
the name search and the four fixed orders (newest, nearest, name, longest), and
the screen draws its rows and the selected track's bottom bar. Nearest measures
from the map centre to the first point of each track; a track without a start
goes last and no origin falls back to name order. A description keeps only
`b`, `i`, `em`, `strong`, `p`, `br`, `ul`, `ol`, `li` and `a` as native styled
text. A link keeps its `href` only when the address is `http` or `https`, and
the screen shows the full address before the system browser opens it. A script,
style, frame, image, SVG or form is dropped with its content, so an import loads
no remote image. A press on a row selects the track, and the bottom bar then
offers Move, Rename, Share, Save and Delete; Delete happens at once and Undo
restores the track, and Save opens the system document picker for that one
track. `StoreTrackLibrary` in `app`
is the only writer: a rename, description or folder change rewrites that
track's sidecar, and the folder definitions live in `.library.json` beside the
documents. The sidecar also caches the track's first point for the nearest
order; a load derives it from the document when an older sidecar has none. The GPX document is never rewritten. A delete parks the sidecar and
document in `.trash`; the in-memory token is process-scoped and a reload keeps
every parked token, so two deletes undo in reverse order and each restores its
track to the same folder. The same scoped receipt stack reverses folder and
track changes one step at a time. A folder index the build cannot read leaves
each sidecar's folder id in place, so only the folder name is unresolved and a
later edit cannot unfil the track. The state follows the store's revision,
which rises after every write, so a track that the share-sheet import stores
appears on an already open library without a restart. The debug channel opens
the screen with `core.track.library_show` until the map's track selection grows
an entry (T-0415); T-0416 owns the share-sheet import.

The library screen draws edge to edge, so the scrolling content clears the
window's status-bar inset and both overlay bars are anchored to the bottom edge.
The 20 dp above the header is visual spacing, not the whole top margin: the
action bar's content clears the navigation-bar inset, the Undo notice stacks
above it, and the rows reserve the height of both. A control that misses its
inset lands under a system bar, where its label and touch target are unreadable
and untappable.

Two follow-ups are open for the library. The one-tap delete has no confirmation
because the design's undo snackbar is the canon; a confirm is a product
decision if the undo is judged too easy to miss. The screen's only entry is the
debug command until T-0415 adds the map entry. A route has no in-UI Save yet:
`TrackAccess.savePlannedRoute` is the whole path and the route preview's action
row belongs to the planner and `ui-shell`, so the preview gains that control in
its own change.

A planned route is saved as its own document (T-0411). `PlannedRouteSaver` takes
the route the planner is showing - `RoutePlanState.toPlannedRoute` reads the
selected option's geometry and the effective options, so a one-route override is
saved with the route rather than the profile default - and writes a document
holding one `<rte>` with one `<rtept>` per geometry point, never a `<trk>`: a
plan drawn as a recording would be a lie the file could not take back. The record
is `TrackKind.Route`, the id is the document hash like every other record, and a
save of an unchanged route updates one record instead of adding a second.

`<id>.route.json` is the recompute sidecar, a second file on purpose. It carries
`version`, `profile`, the three option steps, `start`, `destination` and `vias`,
and the chain in visit order is what the engine receives. The option keys and
values are the shared wire spellings of `RouteOptions` and `RoutePreference`, so
this file and the planner's state stream cannot drift. It is a separate file
because the library sidecar decides whether a record exists: losing this one
costs the recompute and never the track.

`PlannedRouteRecomputer` asks the engine for the line again and falls back to the
stored line when there is no engine in the build, no sidecar, a sidecar this
build cannot read (another `version`, an unknown profile or option, a
coordinate outside WGS84) or an engine that answers with no route. Opening a
saved route therefore never fails on a routing problem, and a missing or broken
sidecar is the diminished state the requirement asks for rather than a failed
open. The record kind also decides an export's element, so a saved route leaves
as a `<rte>` and comes back as a route.

The library row names a saved route: its `meta` line starts with `Route` and the
`Route` string is a normal localized string. A travelled or imported track needs
no label - it is what the library is - and its row stays as it was. The kind is
`TrackKind` on the sidecar, on `TrackSummary`, on `TrackLibraryEntry` and on the
row, so nothing derives it from a name.

Twenty committed fixtures under `test-data/tracks` cover OsmAnd, Garmin and
Strava exports, a GPX 1.0 file with a default namespace, waypoints and routes,
multi-segment and multi-track documents, absent elevation and time, a UTF-8
name, and two damaged documents. `TrackFixturesTest` imports each one through
the real `file.gpx` provider and the file store and asserts its track, segment
and point counts, ascent, descent and elevation range; the damaged files also
assert the exact warning lines. The three documents that hold a `<rte>` beside a
`<trk>` contribute two records each since T-0411, and the table's sums include
the route's authored heights: a route adds no segment boundary and no
timestamp.

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

T-0402 extends the analysis with the figures a track detail shows.
`TrackAnalysis` also carries duration, moving time, average speed and maximum
speed, cached in the same sidecar. A load parses the GPX anyway, so the store
recomputes the analysis from it and rewrites the sidecar when the cached copy
differs. A sidecar written by T-0401 therefore gains the timing on its first
load, and a later policy change cannot leave a stale profile behind. Distance
and elevation keep their T-0401 definitions.

Timing uses consecutive timestamped points of one segment only. A segment
boundary is a pause. A gap longer than 120 s is a pause, and a step slower than
0.5 m/s is standing still; neither counts as moving time. Duration is the
wall-clock span from the first timestamped point to the last, pauses included.
Average speed is distance divided by moving time. Maximum speed is the fastest
step inside a segment and outside a pause gap. A track without timestamps has
no timing, one point has no time span, and a track without elevation has no
elevation range. `TrackStatisticsPolicy` holds the thresholds. They are chosen,
not derived from OsmAnd.

`TrackElevationCorrection` fills a missing height from the mounted region's DEM
(T-0403). Only a point without a height is sampled; a height in the file is
never replaced, and a point outside the DEM, in a grid gap, or behind a failed
read keeps no height and is counted. The step takes an `ElevationAccess` and
does no I/O of its own, so a test scripts the source. With no region mounted it
returns the track unchanged and reports `NoDem`. The importer corrects before
it computes the profile, and the store corrects again when it reloads a
document, because the GPX stays the source of truth and the correction is
derived from the DEM mounted now rather than written into the file. Each
`StoredTrack` carries an `ElevationCorrectionReport`: the source, the counts of
filled, kept and unfilled points, and the failed reads. A corrected profile
differs from the cached one, so the first load after the correction rewrites
the sidecar once with the DEM heights.

`test-data/tracks/fixture-mini-stripped.gpx` is a seven-point track at cell
centres of the committed fixture-mini grid, heights stripped except the first,
which stays 1000.0 m, plus one point outside the tile. The app test
`TrackElevationCorrectionOracleTest` imports it through the real `file.gpx`
provider, the file store and `RegionElevation`, and compares the filled heights
with the committed cell values within 1.0 m, the oracle's `elevationMeters`
tolerance. It also asserts the real height is untouched, the outside point
stays empty, and the same result comes back after a reload. That tolerance is
chosen, not derived, as `test-data/tracks/osmand-oracle/README.md` records; the
test proves the correction preserves the DEM's answer, not that the grid agrees
with the field.

`test-data/tracks/generate-fixtures.py` prints the per-track statistics oracle,
and `TrackFixturesTest.everyTimedFixtureHasItsExpectedStatistics` asserts it
exactly. The other half of the milestone, 10 real tracks against OsmAnd, has no
data in the repository: `test-data/tracks/osmand-oracle/README.md` lists what
is missing, and `TrackStatisticsOracleTest` reads `oracle.json` and is skipped
while it is empty. It rejects a partial list of fewer than 10 tracks.

`TrackExport` in `app` turns one stored track or a selected list into one
`PlatformFile` through the resolved `file.format` provider, and hands it to
`PlatformActions.shareFile` or `PlatformActions.saveFile`. `plugins/share`
names the file (a track name, or `grass-maps-tracks.gpx` for a list) and picks
the extension-specific MIME type. The track library screen's Share action calls
this path for one track (T-0414). Its Save action calls `saveTrack`, which
builds the same file and asks the picker for it, and reports the save, the
dismissal or the typed error as a short notice (T-0410).

The debug channel exposes `core.track.import_test`:

```sh
tools/control/grassctl.py invoke core.track.import_test '{"path":"mock-walk.gpx"}'
```

It reads a debug asset or an absolute path, imports through `file.gpx`, stores
every track and logs one profile line per track plus the warning lines.

### Share-sheet import

Another app can share a GPX to Grass Maps (T-0416). The activity declares one
`ACTION_SEND` filter with `DEFAULT` and four MIME types: `application/gpx+xml`,
`application/octet-stream`, `application/xml` and `text/xml`. Android's MIME
map has no entry for the `gpx` extension, so a file manager or a messenger
sends the document as `application/octet-stream`, and a sender that types an
XML document has the same GPX in hand. Without those types the row never
appears for a real file. The cost is that the app also appears for another
binary or XML share; it then reads the document and refuses it with a notice
instead of guessing.

`TrackShareIntent` in `app` is the rule and names no Android type. The stream
extra names the document, the data URI is the fallback a sender uses instead,
and only a `content:` or `file:` URI is a document. `geo:` and `https:` links
stay on the link path, so `ACTION_VIEW` can share the rule without changing
T-0211. The activity is `singleTop`, so a share that arrives while it runs
reaches `onNewIntent` and the same import.

`ContentResolverSharedReader` opens the URI through the grant the intent
carries. The app declares no storage permission and never resolves a filesystem
path. The provider's display name names the document; a name without an
extension gets `.gpx` when the sender typed the share as GPX. The read is
bounded at 16 MiB, which is about 130 000 points or 36 hours at one point per
second, and it stops before keeping a larger document. The bound matters
because `file.gpx` builds a DOM at several times the document size, so a bigger
share would spend the app's heap rather than the importer's own memory. An
oversize document, like one that is empty or not a track, is `InvalidInput` and
becomes the notice's "not a track" message. The parse and the store write run
on `Dispatchers.IO`, never on Main.

`TrackShareImport` imports through the same `TrackAccess` the library uses, so
the document hash stays the track id: sharing the same file twice updates one
record and keeps its colour. That write raises the store's revision, and the
`track.library` plugin reloads its state from it, so the imported track shows
on an open library and the notice names the same record. `MapScreen` draws the
result in the round-6
`t-imported` notice: a dark line at the bottom of the map that names the
imported track, counts the parts a partial document skipped, and offers Open,
which shows the existing analysis panel. The notice clears itself after 8
seconds and a failed import says what could not be read and offers no action.
The notice shares the bottom-centre slot with the distance badge and the
region banner and the three stack, so a share that arrives over a banner draws
below it. The strings are English and Ukrainian.

A share arrives as a URI, not a path, so the debug build declares its own
provider (`nl.r1a.grassmap.share-test` in `src/debug/`, not exported and absent
from release). It serves the committed `mock-walk.gpx` at three addresses:
`walk` (a GPX document whose display name has no extension, so the provider's
type names the format), `notes` (a document that is not a track) and
`unreadable` (a read that throws `SecurityException`).

The acceptance ran on the API 29 emulator with the debug APK:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell cmd package query-activities -a android.intent.action.SEND \
    -c android.intent.category.DEFAULT -t application/gpx+xml
adb shell am start -W -n nl.r1a.grassmap/nl.r1a.grassmap.app.MainActivity \
    -a android.intent.action.SEND -c android.intent.category.DEFAULT \
    -t application/gpx+xml \
    --eu android.intent.extra.STREAM content://nl.r1a.grassmap.share-test/walk
adb logcat -d | grep GrassMapsTracks
```

`query-activities` lists the app's activity for the share. On the API 29 image
it lists two activities, the app and the system Email composer, so `am start`
without a component opens the system chooser; the documented command names the
activity to make the check deterministic and does not change what the activity
receives. The import logged

```
I GrassMapsTracks: share import name="walk.gpx" tracks=1 skipped=0 ids=20ab81f65815
```

and `run-as nl.r1a.grassmap ls files/tracks` then held one
`20ab81f65815...gpx` document and its `.json` sidecar. A second send logged the
same id and the directory still held one document. No StrictMode violation was
logged. The notice is at `/hdd/t-0416-evidence/share-notice.png` (local
evidence, not committed); on the debug build the development-tools panel covers
its left end.

`ShareImportInstrumentedTest` in `app/src/androidTest` drives the same path
through the activity without `adb`: it launches `MainActivity` with
`ACTION_SEND` and the `content:` URI and asserts the notice, the stored track,
the duplicate update, the non-track document and the provider that refuses the
read. Run it with

```sh
adb shell am instrument -w -e class nl.r1a.grassmap.app.ShareImportInstrumentedTest \
    nl.r1a.grassmap.test/androidx.test.runner.AndroidJUnitRunner
```

The four tests passed on the same API 29 emulator. `TrackShareImportTest` and
`SharedDocumentBytesTest` cover the rule, the real `file.gpx` import, the
duplicate update and the size bound without a device, and
`TrackShareWiringTest` holds the manifest filter, the debug-only provider and
the string keys.

## Elevation chart

The chart behind Details plots the analysis engine's own profile (T-0404).
`ElevationProfiler` in `tracks` turns a track into `ElevationProfile`: one
`ElevationSample` per point that carries a height, with the cumulative distance
measured by the same haversine step as the statistics, the elapsed time since
the first timestamp and the point's own timestamp. It does not interpolate,
smooth or thin, so a chart point is an engine sample and not a second reading of
the file. A point without a height adds no sample and keeps the distance; a
segment boundary adds no distance. T-0403's correction runs before the profile,
so a track whose heights the DEM filled charts those corrected heights. `hasElapsedTime` and `hasTimeOfDay` are true
only when every sample carries the value and the last one moves forward, which
is what offers the Duration and Time of day axes. A profile with fewer than two
samples draws nothing.

A planned route has no heights of its own, so `lineProfile` reads the mounted
region's DEM at the route's geometry points and spreads the route's own duration
over the distance, with the departure taken from the plan's recorded arrival.
`RouteElevation` in `app` does that read, sampling at most 240 points with an
even stride, and returns no profile when the DEM cannot answer. The heights are
the region's, not the routing engine's: Valhalla's `/route` carries no elevation.

`ElevationChartState` in `plugin-ui-api` is the chart in the app's state: the
engine's points, the chosen axis, the selected range with its statistics, and
the climbs the engine found. `elevationChartContent` in `ui-shell` maps them onto
the axis and nothing else. The Distance axis uses the sample distance, the
Duration axis its elapsed time and the Time of day axis its timestamp, so the two
time axes plot the engine's timing rather than an even division of the totals. A
range dragged on the chart is held in distance: the touch position maps back
through the chosen axis to a distance on the drawn line, so a drag on the
Duration axis selects the same stretch the line covers.

The analysis panel's chart is a range selector (T-0405); the route preview's
chart stays a read-only drawing. A drag across the plot reports two distances
through the `onRange` callback and draws the selected band and its two handles
from `ElevationChartState.selection`. The band is the design's faint fill with a
dashed edge and a handle at each end. A drag shorter than 2 % of the plot leaves
the previous selection alone; the Clear action beside the range row removes it.
The range row under the chart shows the engine's distance, ascent, descent and
time, and no maximum grade: the design's `max 25 %` needs a slope window that is
not defined, so it is not drawn.

The drawing uses the round-10 chart palette: seven slope colours from -15 % to
+15 % on the hike scale, the area under the line filled per segment with the
colour of that segment's slope, a hairline grid and a line over the fill. The
light and dark palettes are the same roles in the two themes; `ui-shell` picks
one per theme and the chip colours follow it. A route's chart sits under the
preview behind a Details toggle, and a track's sits in the analysis panel behind
the same toggle. The axis chips stay beside the chart.

`ElevationSegments` in `tracks` owns the range and segment model, over the same
profile the chart draws. A range sums the profile's own steps between its two
ends and interpolates the ends along the drawn line; a step at a segment
boundary (zero distance) adds nothing, which is the statistics' own rule. A
range over a whole profile therefore equals `TrackAnalyzer`'s distance, ascent,
descent and duration, and `ElevationSegmentsTest` checks exactly that on a
synthetic track. A range's duration is the elapsed-time span of its ends and is
absent when the profile has no complete timing.

A climb is a maximal run of consecutive samples in which no step descends: a
flat step keeps the run alive and a descending step of any size ends it. The run
is listed when it gains at least 30 m at an average grade of at least 3 %. Both
figures are chosen, not derived: 30 m sits just below the smallest climb the
round-10 design draws (32 m) and 3 % is the floor the route-analysis research
records for ClimbPro-style lists. A descent uses the same thresholds in the
other direction. Because the profile is not smoothed, a single noisy descending
step can split a climb, and the gains count the profile's raw steps rather than
hand-picked key ranges; both are the engine's no-smoothing policy and neither is
measured against real tracks yet. The model has descents, but the design has no
descent rows, so the panel lists climbs only; a dragged range that loses height
shows its descent in the range row. The climb list's rows are the design's
numbering badge, the climb's range, its gain, its average grade and its time,
and a tapped row selects that range on the chart. The list is capped at 192 dp
and scrolls, so a track with many climbs does not push the panel past the
screen. The row's title is the range, because a track carries no climb names:
the design's names are summits, and the engine has no summit source to name them
from. A badge takes the chart's slope colour for the climb's average grade and
its number is the darker or lighter of the two ink colours with the higher
contrast, which reproduces the design's white on the steeper badge and dark on
the lighter ones.

`ElevationSegmentsTest` holds the fixed expected list for a synthetic track: two
climbs, their ends, gains, grades and times. Further tests cover a reversed drag,
clamping to the profile, a segment boundary that adds no gain, an untimed
profile, an empty, one-sample and flat profile, the two thresholds, a single
descending step that ends a climb, and a sustained descent.

The surface and way-type strips (T-0406) sit under the chart inside the same
card, over the chart's own plot width, so a boundary falls under the distance it
ends. `RouteStretches` in `tracks` reads the routing engine's annotated route
segments: the annotate pass already cut the line into one segment per graph edge
with `surface` and `highway` on it, and the strips only place those ends on the
profile. A segment's `startIndex` and `endIndex` give its two ends through the
cumulative haversine distance of the geometry, which is the step and the
constant the profile measures with, so the strips cannot drift from the axis. A
route's profile is built over its whole geometry, with a null height where the
240-sample stride skipped a point, so the axis is the route's own line and a
segment's shape range lands on it unchanged. `ElevationChartState.stretches`
carries the result into `plugin-ui-api`, and `ui-shell` groups each value into
the round-10 classes: asphalt, paving, ground and gravel for the surface, and
street, footway, path, track and steps for the way type. A value the palette has
no class for is left out and leaves a gap, never guessed into a class.

Each row's percentage is the summed length of that class's segments over the
summed length of the row's classified segments, so the percentages add up to
100 % whenever the line is classified throughout, and the design's 2 px gaps are
drawn from the pixels without changing the fractions. Whole percentages are
taken with a largest-remainder step, so 34 % + 33 % + 33 % is what a third each
reads as. Adjacent segments of one class are merged into one piece, which keeps
a route cut per graph edge from becoming a ladder of gaps. Steps are the design's
45° stripes rather than a sixth colour: a 4 dp period with a 1.6 dp stroke in
#202124 on light and #e8eaed on dark, over the card's own surface. The palette
is the round-10 one, with Ground and Path sharing one green and Gravel and Track
one amber in both themes, as the design does. The legend keeps the classes in
the order they first appear along the line, and the percentages block under the
strips repeats them in descending order, as the round-10 route details draws it.
A track carries no attributes of its own, so a GPX analysis shows no strips
until something annotates it; the chart state and the panel are shared, so a
track that gains the attributes draws them without another change.

`core.track.analysis` opens the track analysis panel, by id or for the newest
stored track:

```sh
tools/control/grassctl.py invoke core.track.analysis '{"id":"<track id>"}'
```

That command is the temporary entry point. The map's track selection opens the
same panel in T-0415, and the track library screen is still T-0414. No device
screenshot of the chart exists yet, the route profile is unverified against a
real routing pack, the climb thresholds are chosen rather than measured against
real tracks, and the drag gesture has only content tests until the chart is
exercised on a device. The strips have content and engine tests only: no
screenshot of either theme exists, and no real Valhalla annotate has been drawn
as a strip.

The plugin boundary rule decides where the pieces live: `ui-shell` and a plugin
may depend on `plugin-api` and `plugin-ui-api` but not on `tracks`, and a plugin
cannot read the track store, so `app` maps the engine profile onto the chart
state. The range selection needs the engine's arithmetic for an arbitrary drag,
which a precomputed state cannot carry, so the panel reports the dragged
distances through a callback and `app` answers with `ElevationSegments`' own
statistics. That keeps one range model, in the engine, and the callback is the
host seam the earlier note expected; no `plugins/analysis-ui` module is created.

### Following a track

A track can be followed as the guidance route (T-0412). The analysis card's
Follow track action asks where to start, because a line has a direction the file
chose and the user may be nowhere near its first point. The sheet is the round-6
`t-follow-start` design: the track's name and size, then From the start, From
the end reversed, and From the nearest point, each with the walk to its join,
then Start. From the nearest point is selected when the sheet opens and is the
default the design names.

`plugin-api` carries the vocabulary: `TrackFollowStart` (`Start`, `Reversed`,
`Nearest`) and `TrackFollowPlan`, the three options with their join points and
walk distances. `navigation` builds them in `trackFollowPlan` and
`trackFollowRoute`. Both measure with the module's own `PolylineLocator`, which
is now public, so a followed track's distances are in the same space
`GeometryRouteFollower` reports remaining distance in; nothing here is a second
follower.

The three choices are three cuts of one line. From the start keeps the file's
geometry whole. Reversed reverses it, so the first point is the track's last and
the first bearing is the opposite one. From the nearest point projects the fix
onto the line, cuts the line there and keeps the file's direction, so the part
already behind the user is not guided again. A fix before the start clamps to
the first point; a fix after the end clamps to the last and leaves nothing to
walk, and the route keeps that last point twice so the session can start and
arrive at once instead of refusing a one-point line.

The route carries one line and no legs and no instructions, which is what a
track actually stores. `RouteProgress` therefore counts the duration down
uniformly over the line, and the navigation screen shows remaining distance,
time and ETA with no turn card: a track without turns still follows the line.
The duration is the track's own moving time from the analysis, or its wall-clock
span when no step counted as moving, scaled to the part followed. A track
without timestamps has no measured pace and falls back to 1.25 m/s, 4.5 km/h;
that figure is chosen, not derived, and reversing a track reuses the same pace
because the file carries one direction's timing.

The app wiring is `TrackFollow.kt`: `StoredTrack.followLine()` joins every
segment in file order, the position comes from `CameraModeController.position`,
and Start hands the route to `NavigationController.start` with the Walk profile
and the track's name as the destination label. A followed track starts with
reroute off, because its line is the guidance the user asked for: a road route
computed from the current position would abandon the track. While the user is
still walking to the join point they are off that line, so the session reports
off route instead of rerouting, and nothing leads them there yet except the
sheet's walk distance. The track store is not touched;
following reads the record the analysis card already loaded. A track with fewer
than two points, or one whose points all sit on one spot, has no plan, so the
card logs the refusal instead of opening a sheet with three dead choices.

`TrackFollowTest` in `navigation` covers the plan and the three cuts, the
position before the start and after the end, the two timing sources and the
fallback, and the empty, short and zero-length lines. It also replays a 600 m
dog-leg through the production `NavigationSession` with the geometry follower
and asserts the trip stays on route, the remaining distance only shrinks and the
run arrives. `FollowTrackContentTest` in `ui-shell` holds the prompt, the three
rows, their walk hints, the default and the Ukrainian copy; `TrackFollowWiringTest`
in `app` holds the segment join, the pace taken from the analysis and the
refusal of a one-point track. No device screenshot of the sheet exists yet, and
the light and dark palettes have content tests only.

## Slope-aware time models

`tracks` owns the slope-aware time models the route preview names (T-0407). A
model reads only `ElevationProfile.samples`. The profiler already measured the
distance, so a step is the distance between two consecutive samples and no
geometry is measured twice. A sample without a height never enters the profile,
so a gap is one longer step between the samples on either side.

Walk and hike use Tobler's hiker curve, re-anchored at the profile's flat speed:

    v(S) = vFlat * exp(-3.5 * (abs(S + 0.05) - 0.05))

S is rise over run. The constants 3.5 and 0.05 are Tobler's. vFlat is 5.0 km/h
for walk and 4.5 km/h for hike, the walking speeds the Valhalla costing sends.
The curve peaks at a 5 % descent, so a gentle descent is faster than flat and a
steep descent is slower again.

Bike uses a power balance:

    P = (Crr * m * g * cos(theta) + m * g * sin(theta) + 0.5 * rho * CdA * v^2) * v

The parameters are chosen, not measured: m = 95 kg, Crr = 0.010,
CdA = 0.45 m^2, rho = 1.225 kg/m^3 and g = 9.80665 m/s^2. The flat speed is
18 km/h, the cycling speed the Valhalla costing sends, so the power is 81.03 W.
A descent is capped at 50 km/h, a stated safety limit rather than a measured
one. Both models clamp the grade at ±50 % and treat a grade under 0.1 % as flat.

A missing, one-sample or flat profile falls back to the existing flat estimate
and is named Flat speed. The route preview passes the routing engine's duration
as that fallback. A track passes its distance divided by the profile's flat
speed. `SlopeTimeEstimator` chooses the model by `RouteProfile`. Car has no
model and keeps the engine's duration. The profile is not smoothed, so DEM
noise on a nearly flat route can add time. That is the profiler's no-smoothing
policy, not a separate model choice.

`app` maps the engine profile and the estimator's `TimeModelKind` to the
preview's `RoutePreviewTime`, and `ui-shell` appends the model's name to the
Time entry, for example `25 min · Tobler walk`. The arrival moves by the same
duration, so the preview's time and arrival stay one answer. The names Tobler
walk, Tobler hike, Bike power and Flat speed live in `app`'s strings in English
and Ukrainian.

`TimeModelTest` holds fixed expected times for flat, gentle and steep up and
down, walk, hike and bike, a profile with a gap, an empty and one-sample
profile, the flat fallback, a zero-distance step and the grade clamp.
`AnalysisChartsTest` covers the route mapping and the named fallback. The
constants come from the literature and the stated parameters, not from a fit to
real tracks. No real track or route has been compared with either model yet.

## Regions seam

`regions` reads the T-0015 `manifest.json` (region, version, package format,
OSM timestamp, pipeline bbox and source archives, and each component's kind,
format, format version, bytes and sha256). A manifest the app does not
understand is `Unsupported`; a malformed one, or one naming a different
region or version than the requested URL, is `CorruptData`.

`DownloadTransport` is the HTTP seam. `HttpDownloadTransport` performs one
authenticated GET over `HttpURLConnection` with bounded connect and read
timeouts. Redirects are handled explicitly: the policy caps their number, a
redirect to another host or port is refused unless the policy allows it, and
the bearer token is attached only while the request stays on the initial
origin. A non-2xx status is `Server(status, retryAfter)` and its body is never
read. The token is a `BearerToken` value type whose `toString` is redacted; a
request without one carries no `Authorization` header, and the server answers
401.

Every download runs through `NetworkAccess`. Production wires
`GateNetworkAccess(PrivacyNetworkGate(...))`, so a request names
`network.region_updates` and is refused while that switch is off. The debug
trigger can replace the gate with a logged bypass; nothing else may.

The store layout is:

```
<root>/<region>/<version>/     one verified package
<root>/<region>/current        the active version, replaced atomically
<root>/.incoming/              downloads, staging and quarantine
```

A download writes a zip under `.incoming`, extracts it to a staging directory
with a zip-slip guard, parses the manifest, verifies every declared file's size
and sha256, and rejects any undeclared regular file or symbolic link. The
version directory is then renamed into place and `current` is replaced by an
fsynced temporary file plus an atomic rename. The pointer is the commit point:
a crash before it leaves the previous version current and usable; a crash after
the version-directory rename but before the pointer rename leaves an unused new
directory, which a retry verifies and reuses. A failed verification or a crash
during extraction leaves only a `.incoming` directory that the next attempt
ignores.

`RegionMountResolver` is the read-only side of the store. It reads
`<region>/current`, parses `<version>/manifest.json` with the same strict
parser, and checks the declared map and contours files for size and a PMTiles
v3 header. Its result is `None`, `Mounted` or `Invalid` with a typed
`RegionMountProblem` (`MissingPointer`, `CorruptPointer`, `MissingVersion`,
`CorruptManifest`, `UnsupportedPackage`, `MissingMap`, `CorruptMap`,
`CorruptContours`, `MultipleRegions`). More than one installed region is
reported, not guessed; the two-oblast resolver is T-0503. It never hashes a
whole file, so it is safe to call while the map is starting.

The way-alert component is optional, so an older package still mounts and
opens. `RegionReader` (T-0319) reads `way-alerts.geojson` from the mounted
version on its first query, off Main, through the native reader (T-0321), and
answers the route corridor query the way-alert plugin uses. A manifest without the component is a success with no
features; a present but unreadable component is `CorruptData` and an unknown
format version is `Unsupported`. The mount does not hash the component; the
installer already verified every declared file, and the reader checks the
declared size before it reads.

The debug trigger is `core.region.download_test`, allowlisted for the control
channel only:

```sh
tools/control/grassctl.py invoke core.region.download_test \
  '{"url":"http://127.0.0.1:8765/package.zip","token":"<test-token>","region":"fixture-mini","version":"2026-11-03-1","bypass":true}'
```

`bypass` is logged on every call. It is refused with `PermissionDenied` unless
the running application reports `ApplicationInfo.FLAG_DEBUGGABLE`, so a release
build always uses the production `NetworkAccess`, where
`network.region_updates` still gates the request. The command bus can invoke
this command in any build, so `allowlistedForControl` alone is not the gate.
Cleartext is permitted only in a debuggable build, so the trigger can reach
`adb reverse`; the debug manifest allows it only for `127.0.0.1` and
`localhost` through `app/src/debug/res/xml/network_security_config.xml`, and
release keeps the platform default of no cleartext. No downloads UI exists yet;
T-0504 owns it.

## Follow-ups

`plugin-api` and `plugin-ui-api` are ordinary Android libraries in this
skeleton. Moving them to Kotlin Multiplatform source sets is a follow-up, so
their contracts stay free of Android types now.

CI, `docs/` and the benchmarks belong to the later milestone-0 tasks from
T-0012 onward. The release AI-control switch and its allowlist are T-0114.

## Licence

Apache License 2.0. See `LICENSE` and `NOTICE`. Planned asset licences and
attributions are in `ATTRIBUTION.md`.
