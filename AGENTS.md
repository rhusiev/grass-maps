# Agent rules

Every agent reads this file first. These rules come from the project process
document, section 3, plus the build and module rules of this repository.

## Robustness

- The app never crashes on bad input, bad data or a missing file. Errors are
  values: Kotlin uses sealed results and no `!!`; Rust uses `Result` and no
  `unwrap`, `expect` or `panic!` outside tests. A crash fix comes with a
  regression test
- Region data is untrusted input: every reader validates sizes, offsets and
  versions before use

## Modularity

- Built-in features go through the public plugin API. Plugin modules cannot
  import core internals. The build enforces the module dependency rules, not
  convention
- A feature that needs something the API lacks extends the API first, with a
  test of the new contract, then uses it

## Performance

- The performance budgets are benchmarks in CI. A change that regresses one
  past its tolerance fails, like a failing test
- No work on the UI thread beyond layout and drawing; no blocking I/O there,
  checked by a strict-mode run in CI

## Privacy

- No ads or tracking libraries, ever. No analytics or crash reporters in v1.
  Anything like it later is opt-in and a user decision. No Google Play Services
  dependency (F-Droid)
- Network access only through the core network capability, which refuses every
  request whose privacy switch is off. A new kind of network contact is a user
  decision
- Location never leaves the device unless the user shares it themselves
  (one-off share). Live location sharing and tracking are not built

## Licences and assets

- Code dependency allowlist: Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause, ISC,
  Zlib, Unicode, CC0, MPL-2.0 (file-level copyleft, kept unmodified). Assets:
  CC0, CC-BY, OFL for fonts. Data: ODbL for OSM-derived files. Anything else,
  including any GPL variant, is a user decision
- Every asset (icon, font, style, sample data) is attributed in the same
  change, in `ATTRIBUTION.md`

## Product

- No wanted feature is ever lost: anything moved out of a milestone, or asked
  for without a milestone, gets a backlog entry in the same change
- Every user-visible string goes through i18n; English and Ukrainian land in
  the same change
- Simple by default: a new option starts hidden in advanced settings unless the
  task says otherwise
- Light and dark themes both checked for every UI change

## Git

- One worktree per task, branched from `dev`
- Commit subjects: conventional, imperative, one line, task ID at the end, no
  body, no trailers - for example `feat: add north-up button (T-0042)`

## Build

- JDK: `$HOME/dotfiles/local/share/jdk`. Android SDK:
  `$HOME/.local/share/android-sdk`, written as `sdk.dir` into the untracked
  `local.properties`
- Build the app:
  `JAVA_HOME=$HOME/dotfiles/local/share/jdk ./gradlew :app:assembleDebug`
- Iterate with `./check.sh --fast`: it runs the same steps minus the two
  reproducible release builds, which are the slow part. Gradle re-runs only
  tasks whose inputs changed, so do not pass `--rerun-tasks` to the iteration
  gate; that forces a full rebuild and belongs to the full gate
- Run the full gate `./check.sh` before merging. It takes the `gm-build` slot
  itself and runs format, lint, the build, unit tests, the plugin boundary and
  manifest checks, the example plugin, the privacy guards and the
  reproducibility check
- CI tiers the same way: the checks job runs `./check.sh --fast`, while
  reproducibility, the API 29 smoke, the goldens and the benchmark are
  separate jobs. A `--fast` run is not evidence for a merge on its own; the
  reproducibility job is
- Check the plugin boundary rule: `./gradlew checkPluginBoundaries`
- Check plugin manifests: `./gradlew checkPluginManifests`
- Check the privacy guards (dependency denylist and merged manifests):
  `./gradlew checkPrivacyGuards`
- The build uses Gradle 9.3.1, AGP 9.1.0 with built-in Kotlin, compileSdk 37,
  targetSdk 36 and minSdk 29. Do not apply the standalone Kotlin Android
  plugin; AGP 9 compiles Kotlin itself. The root buildscript upgrades the
  built-in Kotlin Gradle plugin to 2.4.21, because maplibre-compose 0.20 is
  compiled with Kotlin 2.4 metadata

## Modules

- The module list and every allowed dependency edge are in `README.md`. Do not
  add an edge without a design decision
- A module under `plugins/` may declare `plugin-api`, plus `plugin-ui-api` only
  when it contributes Compose UI. `./gradlew checkPluginBoundaries` fails
  otherwise
- Feature modules never import `core` or `core-services`. They take the service
  and host interfaces they need in their constructors, and `app` wires the
  implementations
- The only feature-to-feature edges are `navigation -> routing` and
  `alerts -> navigation`. Every other call goes through `plugin-api` contracts
- Package layout: `nl.r1a.grassmap.<module>`, with dots in place of hyphens:
  `core-services` is `nl.r1a.grassmap.core.services`
- `plugin-api` and `plugin-ui-api` are ordinary Android libraries until they
  move to Kotlin Multiplatform source sets. Keep Android types out of their
  contracts
