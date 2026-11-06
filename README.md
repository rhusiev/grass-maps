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
targetSdk 36 and minSdk 29.

## Checks

```sh
./gradlew checkPluginBoundaries
./gradlew checkPluginManifests
./gradlew :plugins:example:check
```

`checkPluginBoundaries` enforces the plugin module dependency rule.
`checkPluginManifests` validates every `src/main/plugin.json`: unique plugin
ids, namespaced command and stream ids, command implementations, setting
defaults and scopes, and stream serializers. Plugin modules wire the boundary
task into `check`.

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
| `routing` | engine host, request builder, route model assembly | `plugin-api` |
| `navigation` | route follower, off-route detection, reroute policy, guidance | `routing`, `plugin-api` |
| `alerts` | alert sources, scheduling, sound, vibration and banner output | `navigation`, `plugin-api` |
| `places` | place and search provider hosts, favourites store | `plugin-api` |
| `tracks` | GPX import and export, track store, analysis engine | `plugin-api` |
| `ui-shell` | screens, bottom bar and sheet, configurable button and widget grid, command palette, theme, strings | `plugin-api`, `plugin-ui-api` |
| `control` | JSON-RPC control channel and MCP server, peer auth, allowlist, privacy switch | `core`, `plugin-api` |
| `devtools` | debug harness: benchmark hooks, GPX replay, screenshot and state probes | `core`, `control`, `plugin-api` |
| `app` | composition root, Android entry points, foreground service, manifest, resources | every module |
| `plugins/example` | example of a first-party plugin module | `plugin-api` |

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
schemas, providers with contract versions and static capabilities, streams with
a value type and serializer, and settings with defaults and scopes. The example
plugin declares one command, one stream and one provider.

`checkPluginManifests` fails the build when a manifest breaks the rules above,
including a declared command whose implementation class is missing from the
plugin's source.

## Follow-ups

`plugin-api` and `plugin-ui-api` are ordinary Android libraries in this
skeleton. Moving them to Kotlin Multiplatform source sets is a follow-up, so
their contracts stay free of Android types now.

CI, `docs/`, the control channel and the benchmarks belong to the later
milestone-0 tasks from T-0011 onward.

## Licence

Apache License 2.0. See `LICENSE` and `NOTICE`. Planned asset licences and
attributions are in `ATTRIBUTION.md`.
