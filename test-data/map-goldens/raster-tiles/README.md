# Raster tile fixture

The tiles the `raster` map-golden profile draws (T-0514). They are eight solid
colours, 256x256, committed so the capture needs no network: the fixture map
shows the tile lattice at 40 % and a shifted, missing or wrong-zoom tile
changes whole comparison blocks.

`scripts/ci/map_goldens.py` serves this directory from a loopback HTTP server,
forwards it to the device with `adb reverse`, and draws it through the app's own
custom-source path (`core.map.show_raster`). The server answers
`/{z}/{x}/{y}.png` for z12 and z13 with `tile-{(x + 2*y) % 4}.png`:

| zoom | tile-0 | tile-1 | tile-2 | tile-3 |
|---|---|---|---|---|
| z12 | `#B41E1E` | `#1E4DB4` | `#1E8C1E` | `#9C8C1E` |
| z13 | `#B41E8C` | `#1E8CB4` | `#8C4D1E` | `#4D1E8C` |

The two zooms have different palettes, so a capture at the wrong zoom is
visible. z11 and below, and z14 and above, answer 404 and the base map shows
through there.

Regenerate by writing solid 256x256 RGB PNGs of the colours in the table; the
files are interchangeable as long as the rule and the two palettes stay.
