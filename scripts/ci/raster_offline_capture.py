#!/usr/bin/env python3
"""Capture an offline raster area draw on one emulator (T-0513).

The run downloads a small EOX 2016 area from the raster tile fixture served on
loopback, stops that server, turns the online-tiles switch off, and captures
the map. The area then draws only from its `pmtiles://file://` pack. A shot
taken before the download at the same camera shows the difference.

Usage:
    raster_offline_capture.py --serial emulator-5580 --apk app-debug.apk --output DIR
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import map_goldens as goldens

CENTER = (49.9500, 23.9500, 13.0, 0.0, 0.0)
HALF_LAT = 0.03
HALF_LON = 0.05
AREA_COMMAND = "core.map.download_raster_area"
BEFORE = "raster-offline-before"
AFTER = "raster-offline-after"


def download_area(client: object, template: str) -> str:
    """Ask the app to download the area, retrying while the channel is not ready."""
    lat, lon = CENTER[0], CENTER[1]
    params = {
        "tiles": template,
        "south": lat - HALF_LAT,
        "west": lon - HALF_LON,
        "north": lat + HALF_LAT,
        "east": lon + HALF_LON,
        "minZoom": goldens.RASTER_TILE_MIN_ZOOM,
        "maxZoom": goldens.RASTER_TILE_MAX_ZOOM,
    }
    return str(client.invoke(AREA_COMMAND, params))


def run(args: argparse.Namespace) -> int:
    adb = goldens.Adb(goldens.resolve_serial(args.serial))
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    goldens.clear_app_data(adb)
    adb.install(Path(args.apk))
    goldens.radios_on(adb)
    goldens.prepare_device(adb)
    goldens.launch(adb, False)
    client = goldens.wait_for_channel(adb)
    goldens.time.sleep(goldens.LAUNCH_SETTLE_S)
    goldens.wait_for_first_frame(adb)
    params = dict(zip(("lat", "lon", "zoom", "bearing", "tilt"), CENTER))
    goldens.capture_position(adb, client, output, BEFORE, params)
    server = goldens.RasterTileServer().start()
    adb.reverse(server.port)
    try:
        print(download_area(client, server.url), file=sys.stderr)
    finally:
        adb.reverse_remove(server.port)
        server.stop()
    print(goldens.invoke_raster(client, None, None), file=sys.stderr)
    goldens.capture_position(adb, client, output, AFTER, params)
    print(f"captured {output / (AFTER + '.png')}", file=sys.stderr)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--output", required=True)
    return run(parser.parse_args())


if __name__ == "__main__":
    sys.exit(main())
