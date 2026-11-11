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
defaults and scopes, dispatch policies, and stream serializers. Plugin modules
wire both checks into `check`.

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
OSM ids and tag maps instead of a stand-in marker. `plugin-api` publishes the
shared paged-provider contract suite as test fixtures; `PagedProviderContractSuite`
covers limits, paging, ordering, concurrency and cancellation. See
`tools/pipeline/README.md`.

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
| `routing` | route model, the `routing.engine` contract seam, its stable JSON form, the start/end planner state machine, and the reuse history store | `plugin-api` |
| `navigation` | route follower, off-route detection, reroute policy, guidance | `routing`, `plugin-api` |
| `alerts` | alert sources, scheduling, sound, vibration and banner output | `navigation`, `plugin-api` |
| `places` | place and search provider hosts, favourites store | `plugin-api` |
| `tracks` | GPX import and export, track store, analysis engine | `plugin-api` |
| `ui-shell` | screens, bottom bar and sheet, configurable button and widget grid, command palette, theme, strings | `plugin-api`, `plugin-ui-api` |
| `control` | JSON-RPC control channel and MCP server, peer auth, allowlist, privacy switch | `core`, `plugin-api` |
| `devtools` | debug harness: benchmark hooks, GPX replay, screenshot and state probes | `core`, `control`, `plugin-api` |
| `app` | composition root, Android entry points, foreground service, manifest, resources | every module |
| `plugins/example` | example of a first-party plugin module | `plugin-api` |
| `plugins/style-standard` | the Standard light and dark map styles as a `MapLayerProvider` | `plugin-api` |
| `plugins/synthetic-data` | the synthetic elevation `DataSource` and its real contour overlay | `plugin-api` |
| `plugins/gpx` | GPX 1.0/1.1 as a `FileFormat` provider | `plugin-api` |
| `plugins/info-widgets` | the altitude, coordinates and GPS info widget data providers | `plugin-api` |
| `plugins/dev-tools` | the release development-tools panel and its GPX replay start/stop commands | `plugin-api` |
| `plugins/map-controls` | the free, follow and heading camera mode, the compass reset, the position-marker offset seam and the scale-bar layout | `plugin-api` |
| `plugins/point-info` | the distance and azimuth from the fix or map centre to a long-pressed point | `plugin-api` |
| `plugins/osm-places` | the `places.card` provider that maps OSM tags to place-card rows and evaluates `opening_hours` | `plugin-api` |
| `plugins/online-search` | the opt-in `places.search` provider over the public Photon geocoder, behind the online-search switch | `plugin-api` |
| `plugins/favourites-ui` | the favourites lists state, the create, move, recolour, Home and Work commands, and the browsing search, sorts and description rendering | `plugin-api` |
| `plugins/links` | the pure `geo:` and osm.org link parsers, including the osm.org short-link decoder | `plugin-api` |
| `plugins/share` | the decimal, DMS and DDM coordinate notations, the osm.org and `geo:` link builders, and the copy, share and open-in commands | `plugin-api` |
| `plugins/coordinates` | the coordinate notation parser the search field reads, and the vectors it shares with the formatters | `plugin-api` |
| `plugins/valhalla` | the v1 `routing.engine` provider: profile mapping, the native Valhalla adapter, the async segment attributes | `plugin-api` |
| `plugins/direct-engine` | the second `routing.engine` provider: one geodesic per leg with the haversine length, its leg segments and its honest empty capabilities | `plugin-api` |
| `plugins/route-planner` | the start/end plan, its intermediate stops, its undo and redo history, its reuse commands and its state and history streams; the shell draws the preview and the turn stepper from it | `plugin-api`, `routing` |
| `plugins/guidance-ui` | the guidance panel's four facts and its Compose chrome, drawn from the navigation state the app holds | `plugin-api`, `plugin-ui-api` |

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

`PlatformActions` in `plugin-api` is the platform seam for sharing (T-0210).
It has three calls: copy text to the clipboard, share text, and open a URI. The
contract carries no Android type, so `plugins/share` asks for the effect and
`app` performs it. `AndroidPlatformActions` is the only file that names a
`ClipboardManager` or an `Intent`. It starts the system chooser for share and
`ACTION_VIEW` for a URI. A URI that no app handles is `Unsupported`. The text of
a share is never logged. `openUri` also parses the scheme itself and builds an
intent only for `http`, `https`, `tel`, `mailto` and `geo`; every other scheme
is `Unsupported` before an intent exists.

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

The Standard light and dark variants share one `BaseStyle.Json` document, so a
variant switch updates layers in place instead of reloading the style. A base
style carries sources and glyphs but no layers; the consumer applies the
provider's layers with `applyStyleDiff`, and only `setStyle` may reload.
`plugins/style-standard/src/main/styles/standard.json` holds one layer list and
a palette per variant; the provider resolves the palette tokens and returns
`standard` (light) and `standard-dark`. Symbol text and halo colours in that
palette are `rgb()` arrays: the current text path drops the green channel from
a hex text colour, so the style avoids that form until the render path is
fixed. The authored Touring style and the style switch are T-0109.

The map resolves its sources in a fixed order:

1. exactly one installed region package whose `current` pointer, manifest and
   declared map/contours files validate, read from the app's region store;
2. the whole-oblast archive `lviv-protomaps.pmtiles` (plus
   `lviv-contours-20m.pmtiles`) pushed to the app's external files directory;
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

One exception is `plugins/route-planner`: it is a first-party compiled-in
feature over `:routing`, the same domain edge `navigation` already has, because
the planning state machine is shared with future guidance work. It reaches no
core internals and still asks the host for the engine through
`RoutingEngineAccess`, so the plugin boundary that matters - the contract - is
unchanged.

`./gradlew checkPluginBoundaries` checks every module under `plugins/` and
fails with each violating module, configuration and dependency. Plugin modules
wire this task into `check`.

Feature modules never import `core` or `core-services`. They take the host and
service interfaces they need in their constructors, and `app` wires the
implementations. The only feature-to-feature edges are `navigation -> routing`
and `alerts -> navigation`, plus the compiled-in `plugins/route-planner ->
routing` exception above.

One test-only edge is declared separately in `moduleTestDependencyAllowlist`:
`navigation`'s tests depend on `plugins/direct-engine` (T-0305), because the
follower's acceptance test feeds the real engine's route through the Ferrostar
mapper. `plugins/guidance-ui`'s tests depend on `navigation` (T-0310), because
the panel's acceptance feeds the real session's published state into the panel.
Both reach no runtime classpath.

## Plugin manifests

Every module under `plugins/` carries `src/main/plugin.json`. At build time the
`generatePluginRegistry` task reads those manifests and writes
`GeneratedPlugins.kt` and `GeneratedStreams.kt` into `app`, so the host never
scans the classpath or uses reflection.

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
Each plugin module's `check` and `app:check` depend on it.

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

`RoutingEngine` in `plugin-api` is the engine contract (1.1.0). `plugins/valhalla`
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
`sac_scale` and `access`; `grade` is sampled from the mounted region DEM because
the phase-A pack has no elevation. The profile mapping, the seeded 100x3
validation and its derived length tolerance are in `test-data/routing/`.

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
`Routes` slot, using the same source pattern as the location puck. The app's
debug control channel exposes `core.route.test_walk`, which routes a short walk
in the fixture area or in Lviv and, with `{"modifiers": true}`, asks the
synthetic DataSource for cost modifiers and routes again. Valhalla takes a
multiplier as `linear_cost_factors`; when the synthetic line has no graph edges
near it, the adapter retries with `exclude_polygons` around the same line.

`routing.RoutePlanner` is the planning state (T-0302), with intermediate stops
(T-0303): a start, its vias in visit order and a destination go in as one
`RouteRequest`, the engine's own list comes back as selectable options, and a
swap routes the reversed journey with the via order reversed rather than
reversing a line. `add_via`, `remove_via` and `move_via` edit the chain and ask
the engine again, so a reorder changes the drawn line and not only the list.
`plugins/route-planner` owns the commands and the `route.planner.state` stream;
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

A route planned through `plugins/route-planner` carries the same model.
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
remembers it. `GuidancePanelContent` then only draws that model, so a camera or
gesture frame cannot recompute guidance and the panel is not on the map's draw
path. The app does not show the panel yet: no live navigation state reaches the
screen, and the wiring belongs to the task that owns the navigation screen.

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

## Navigation service and location lifecycle

`NavigationController` in `app` owns the one live session (T-0314). It starts
`NavigationService` before the session begins and stops it when the session
ends; no other component starts the service. The service declares
`foregroundServiceType="location"`, so the system shows it as a location
foreground service and `dumpsys activity services` reports `isForeground=true`
with type `0x8`.

The notification shows the next turn's text as its title and the distance to it
as its text. Its two actions pause or resume guidance and stop navigation. It is
public and ongoing, so it survives the lock screen. Pausing publishes
`NavigationPhase.Paused`; the session drops every fix before the follower and
before the reroute gate, so guidance stops and no reroute can start. Resuming
continues from the next fix; a fix that arrived during the pause is never
replayed. A paused session that is stopped ends in `Stopped`, never in
`Arrived`, because no arrival can be detected while fixes are dropped.

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
