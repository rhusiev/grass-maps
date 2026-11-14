# Navigation screen goldens (T-0320)

`light/` and `dark/` hold the navigation screen in the following, expanded and
alert states. Each image is the whole 1080x1920 frame, captured on the API 29
AVD, because the card and the bar are the subject; the map goldens' centre crop
would remove them.

The state is deterministic: `core.map.set_camera` fixes the map, and the debug
command `core.nav.show_screen` fills the production screen with
`test-data/navigation/screen-route.json`. The command supplies a published
navigation state, not a picture.

The comparator is the map goldens' 108x192 mean-RGB block grid with the same
32-per-channel delta and 5 % mismatch tolerance, applied to the full frame. The
status-bar clock and icons are inside the tolerance. A recapture on the same
AVD and renderer still compares at about 0.01 %, so the gate is that the
expected positions move and every position passes with a wide margin, not
0.0 %.

```sh
./scripts/ci/android-navigation-goldens.sh --update
./scripts/ci/android-navigation-goldens.sh
```

Recapture on the same API 29 renderer as the reference set. The goldens CI job
runs the compare after the map sets and uploads `build/map-goldens/navigation`
with the other golden artifacts.
