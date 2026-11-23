# F-Droid submission material

This directory holds the app metadata and its changelog. Grass Maps is not in
F-Droid yet.

A source build resolves the three native dependencies from Maven as FOSS
packages: Valhalla is MIT, Ferrostar core is BSD-3-Clause and MapLibre is
BSD-2-Clause. They are recorded with the other dependencies in `ATTRIBUTION.md`
and `NOTICE`, so F-Droid needs no allowed exception for them.

The app also has a stable published signing key. The metadata points `Binaries:`
at the signed `0.1.0-alpha.2` release APK and pins its certificate SHA-256 in
`AllowedAPKSigningKeys:`. F-Droid can ship that APK instead of rebuilding it,
and updates keep the same signature. The repository README's Release signing
section holds the fingerprint and the key custody rules.

`scripts/ci/fdroid_metadata_test.py` checks the fields, the binary URL, the
changelog file and the signing key line on every gate run.

Submitting the app to F-Droid is the user's action. Nothing here publishes it.
