# Navigation acceptance fixture (T-0314)

`nav-route.json` is a route in the stable JSON form of `RouteJson`
(`routing/RouteJson.kt`), used to start live guidance on an emulator that has
no routing pack. The control command `core.nav.start` takes it as
`{"route": <object>, "profile": "walk"}`.

The geometry is the committed `test-data/location/mock-walk.gpx` track followed
by a 300 m straight tail along the last bearing. The debug build replays that
track as fixes and loops at its end; without the tail, the route would end where
the replay starts and every lap would arrive. The route is 382.6 m, with
instructions at 0 m, 126.3 m and 252.5 m. Its duration is the distance at the
walking gate speed, 1.1 m/s, and the instruction durations add up to the route
duration, so `RouteProgress` reads the instruction span model instead of falling
back to a uniform one.

The fixture is test data, not a product route. It exists so `dumpsys` and the
notification can be read on the emulator without a Valhalla pack. The
`navigation-notification` lines in `adb logcat` are the same content the
notification shows.
