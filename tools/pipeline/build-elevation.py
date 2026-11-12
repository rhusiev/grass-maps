#!/usr/bin/env python3
"""Build elevation products from Copernicus GLO-30.

Two outputs come from the same source tiles:

* ``grid`` writes the region package's ``elevation.bin``: int16 metres,
  3 arcsec cells in 1-degree tiles. Each cell is the mean of the 3x3 GLO-30
  samples it covers. The container is documented in ``tools/pipeline/README.md``.
* ``hgt`` writes the 1 arcsec ``.hgt`` tiles Valhalla's Skadi sampler reads:
  3601x3601 int16 big-endian nodes per 1-degree cell. GLO-30 is already a
  1 arcsec grid, so each output node is the bilinear sample at that node,
  which is an edge-clamped mean of the two neighbouring source samples.

Source: Copernicus DEM GLO-30, the 1-degree COG tiles named
``Copernicus_DSM_COG_10_N{lat}_00_E{lon}_00_DEM.tif`` from
``https://copernicus-dem-30m.s3.amazonaws.com`` (the bench ``fetch-dem.sh``
downloads them). GLO-30 measures the surface, including tree canopy and
buildings; ascent figures inherit that bias.

Usage:
  build-elevation.py grid --source DIR --bbox W,S,E,N --out elevation.bin
  build-elevation.py hgt --source DIR --bbox W,S,E,N --out DIR
"""

import argparse
import pathlib
import struct
import sys
import zlib

import numpy as np
from PIL import Image

ARCSEC_PER_DEGREE = 3600
GRID_ARCSEC = 3
GRID_CELLS_PER_DEGREE = ARCSEC_PER_DEGREE // GRID_ARCSEC
SOURCE_TILE_SAMPLES = 3600
HGT_NODES = 3601
NO_DATA = -32768
SOURCE_NO_DATA_LIMIT = -1000
GRID_MAGIC = b"GRME"
GRID_VERSION = 1
GRID_HEADER_BYTES = 32
GRID_INDEX_ENTRY_BYTES = 32
GRID_ENCODING_DEFLATE_PREDICTOR = 1


def source_tile(source, latitude, longitude):
    """The GLO-30 tile covering the north-west corner of a 1-degree cell."""
    name = (
        f"Copernicus_DSM_COG_10_{latitude_name(latitude)}_00_"
        f"{longitude_name(longitude)}_00_DEM.tif"
    )
    path = pathlib.Path(source) / name
    if not path.is_file():
        raise SystemExit(f"missing GLO-30 tile: {path}")
    return path


def latitude_name(latitude):
    return f"{'N' if latitude >= 0 else 'S'}{abs(latitude):02d}"


def longitude_name(longitude):
    return f"{'E' if longitude >= 0 else 'W'}{abs(longitude):03d}"


def cell_name(latitude, longitude):
    """The `N49E023` cell name used by both GLO-30 and Skadi."""
    return latitude_name(latitude) + longitude_name(longitude)


def load_tile(source, latitude, longitude):
    """One GLO-30 tile as float32 metres, with its north-west corner."""
    path = source_tile(source, latitude, longitude)
    image = Image.open(path)
    values = np.array(image, dtype=np.float32, copy=True)
    scale = image.tag_v2[33550]
    tiepoint = image.tag_v2[33922]
    west = tiepoint[3]
    north = tiepoint[4]
    rows, columns = values.shape
    if rows != SOURCE_TILE_SAMPLES:
        raise SystemExit(f"{path}: expected 3600 rows, got {rows}")
    if abs(scale[1] - 1.0 / SOURCE_TILE_SAMPLES) > 1e-12:
        raise SystemExit(f"{path}: expected 1 arcsec rows, got {scale[1]}")
    if abs(west - longitude) > 1e-9 or abs(north - (latitude + 1)) > 1e-9:
        raise SystemExit(f"{path}: unexpected origin {west},{north}")
    values[values < SOURCE_NO_DATA_LIMIT] = np.nan
    return values, west, north, float(scale[0]), float(scale[1])


def degrees_to_cell_index(degrees):
    return degrees * GRID_CELLS_PER_DEGREE


def round_cell_index(value):
    rounded = round(value)
    if abs(value - rounded) > 1e-6:
        raise SystemExit(f"bbox edge {value} is not aligned to a 3 arcsec cell")
    return int(rounded)


def grid_tiles(bbox):
    """Yield (latitude, longitude, west, south, east, north) per 1-degree cell."""
    min_lon, min_south, max_lon, max_north = bbox
    first_lon_cell = int(np.floor(min_lon))
    first_lat_cell = int(np.floor(min_south))
    last_lon_cell = int(np.ceil(max_lon)) - 1
    last_lat_cell = int(np.ceil(max_north)) - 1
    for latitude in range(first_lat_cell, last_lat_cell + 1):
        for longitude in range(first_lon_cell, last_lon_cell + 1):
            west = max(min_lon, float(longitude))
            south = max(min_south, float(latitude))
            east = min(max_lon, float(longitude + 1))
            north = min(max_north, float(latitude + 1))
            if west >= east or south >= north:
                continue
            yield latitude, longitude, west, south, east, north


def source_pixel_range(cell_start, cell_end, source_start, pixel_size, count):
    """Source pixels whose centres fall inside [cell_start, cell_end)."""
    first = int(np.ceil((cell_start - source_start) / pixel_size - 0.5))
    last = int(np.ceil((cell_end - source_start) / pixel_size - 0.5)) - 1
    first = max(0, min(count - 1, first))
    last = max(0, min(count - 1, last))
    return first, last + 1


def block_mean(values, west, north, pixel_width, pixel_height, cell_west, cell_south, cell_east, cell_north):
    """Mean of the source pixels whose centres cover one 3 arcsec cell."""
    first_row, last_row = source_pixel_range(cell_north, cell_south, north, -pixel_height, values.shape[0])
    first_column, last_column = source_pixel_range(cell_west, cell_east, west, pixel_width, values.shape[1])
    block = values[first_row:last_row, first_column:last_column]
    valid = ~np.isnan(block)
    count = int(valid.sum())
    if count == 0:
        return NO_DATA
    return int(round(float(np.where(valid, block, 0.0).sum()) / count))


def cells_for_tile(values, west, north, pixel_width, pixel_height, west_out, south_out, east_out, north_out):
    """One grid tile's 3 arcsec cells, as a rows x columns int16 array."""
    rows = round_cell_index(degrees_to_cell_index(north_out - south_out))
    columns = round_cell_index(degrees_to_cell_index(east_out - west_out))
    pixels_y = int(round((north_out - south_out) / rows / pixel_height))
    pixels_x = int(round((east_out - west_out) / columns / pixel_width))
    first_row = int(round((north - north_out) / pixel_height))
    first_column = int(round((west_out - west) / pixel_width))
    block = values[
        first_row : first_row + rows * pixels_y,
        first_column : first_column + columns * pixels_x,
    ]
    if block.shape == (rows * pixels_y, columns * pixels_x):
        block = block.reshape(rows, pixels_y, columns, pixels_x)
        valid = ~np.isnan(block)
        count = valid.sum(axis=(1, 3))
        total = np.where(valid, block, 0.0).sum(axis=(1, 3))
        result = np.full((rows, columns), NO_DATA, dtype=np.int16)
        has_data = count > 0
        result[has_data] = np.rint(total[has_data] / count[has_data]).astype(np.int16)
        return result
    result = np.full((rows, columns), NO_DATA, dtype=np.int16)
    for row in range(rows):
        for column in range(columns):
            result[row, column] = block_mean(
                values,
                west,
                north,
                pixel_width,
                pixel_height,
                west_out + column / GRID_CELLS_PER_DEGREE,
                north_out - (row + 1) / GRID_CELLS_PER_DEGREE,
                west_out + (column + 1) / GRID_CELLS_PER_DEGREE,
                north_out - row / GRID_CELLS_PER_DEGREE,
            )
    return result


def encode_grid_tile(values):
    """Horizontal predictor over int16 cells, then zlib deflate."""
    rows, columns = values.shape
    unsigned = values.astype(np.uint16)
    predicted = np.empty_like(unsigned)
    predicted[:, 0] = unsigned[:, 0]
    if columns > 1:
        predicted[:, 1:] = unsigned[:, 1:] - unsigned[:, :-1]
    raw = predicted.astype("<u2").tobytes()
    return zlib.compress(raw, 9)


def build_grid(source, bbox, output):
    entries = []
    payload = bytearray()
    tile_count = sum(1 for _ in grid_tiles(bbox))
    data_offset = GRID_HEADER_BYTES + tile_count * GRID_INDEX_ENTRY_BYTES
    print(f"grid: {tile_count} tile(s), 3 arcsec cells")
    for latitude, longitude, west, south, east, north in grid_tiles(bbox):
        values, source_west, source_north, pixel_width, pixel_height = load_tile(source, latitude, longitude)
        cells = cells_for_tile(
            values,
            source_west,
            source_north,
            pixel_width,
            pixel_height,
            west,
            south,
            east,
            north,
        )
        compressed = encode_grid_tile(cells)
        rows, columns = cells.shape
        entries.append(
            (
                round(west * 1_000_000),
                round(south * 1_000_000),
                round(east * 1_000_000),
                round(north * 1_000_000),
                rows,
                columns,
                data_offset,
                len(compressed),
            )
        )
        payload += compressed
        data_offset += len(compressed)
        valid = cells[cells != NO_DATA]
        print(
            f"  N{latitude:02d}E{longitude:03d}: {rows}x{columns} cells, "
            f"{len(compressed)} B, height {valid.min()}..{valid.max()} m"
        )
    header = struct.pack(
        "<4sHHIIIIII",
        GRID_MAGIC,
        GRID_VERSION,
        GRID_HEADER_BYTES,
        len(entries),
        GRID_HEADER_BYTES,
        GRID_INDEX_ENTRY_BYTES,
        0,
        0,
        0,
    )
    index = bytearray()
    for west, south, east, north, rows, columns, offset, length in entries:
        index += struct.pack("<iiiihhIIhh", west, south, east, north, rows, columns, offset, length, GRID_ENCODING_DEFLATE_PREDICTOR, NO_DATA)
    pathlib.Path(output).write_bytes(header + index + payload)
    print(f"wrote {pathlib.Path(output).stat().st_size} B to {output}")


def bilinear_nodes(values, west, north, pixel_width, pixel_height, west_out, north_out):
    """Resample a source grid to 3601x3601 1 arcsec nodes, edges clamped."""
    xs = (west_out + np.arange(HGT_NODES) / ARCSEC_PER_DEGREE - west) / pixel_width - 0.5
    ys = (north - (north_out - np.arange(HGT_NODES) / ARCSEC_PER_DEGREE)) / pixel_height - 0.5
    xs = np.clip(xs, 0.0, values.shape[1] - 1)
    ys = np.clip(ys, 0.0, values.shape[0] - 1)
    x0 = np.floor(xs).astype(np.int64)
    y0 = np.floor(ys).astype(np.int64)
    x1 = np.clip(x0 + 1, 0, values.shape[1] - 1)
    y1 = np.clip(y0 + 1, 0, values.shape[0] - 1)
    wx = (xs - x0).astype(np.float32)
    wy = (ys - y0).astype(np.float32)
    row0 = values[y0, :]
    row1 = values[y1, :]
    top = row0[:, x0] * (1.0 - wx) + row0[:, x1] * wx
    bottom = row1[:, x0] * (1.0 - wx) + row1[:, x1] * wx
    return top * (1.0 - wy[:, None]) + bottom * wy[:, None]


def build_hgt(source, bbox, output_directory):
    output = pathlib.Path(output_directory)
    output.mkdir(parents=True, exist_ok=True)
    for latitude, longitude, _west, _south, _east, _north in grid_tiles(bbox):
        values, source_west, source_north, pixel_width, pixel_height = load_tile(source, latitude, longitude)
        nodes = bilinear_nodes(
            values,
            source_west,
            source_north,
            pixel_width,
            pixel_height,
            float(longitude),
            float(latitude + 1),
        )
        missing = np.isnan(nodes)
        integers = np.full(nodes.shape, NO_DATA, dtype=np.int16)
        integers[~missing] = np.rint(nodes[~missing]).astype(np.int16)
        directory = output / latitude_name(latitude)
        directory.mkdir(exist_ok=True)
        path = directory / f"{cell_name(latitude, longitude)}.hgt"
        path.write_bytes(integers.astype(">i2").tobytes())
        valid = integers[integers != NO_DATA]
        print(
            f"  {path}: {path.stat().st_size} B, height {valid.min()}..{valid.max()} m"
        )


def parse_bbox(text):
    try:
        west, south, east, north = (float(value) for value in text.split(","))
    except ValueError:
        raise SystemExit(f"bbox must be W,S,E,N: {text}") from None
    if not (west < east and south < north):
        raise SystemExit(f"bbox has no area: {text}")
    return west, south, east, north


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    for name in ("grid", "hgt"):
        command = subparsers.add_parser(name)
        command.add_argument("--source", required=True, help="directory with the GLO-30 COG tiles")
        command.add_argument("--bbox", required=True, help="W,S,E,N in degrees")
        command.add_argument("--out", required=True, help="output file or directory")
    args = parser.parse_args()
    bbox = parse_bbox(args.bbox)
    if args.command == "grid":
        build_grid(args.source, bbox, args.out)
    else:
        build_hgt(args.source, bbox, args.out)


if __name__ == "__main__":
    sys.exit(main())
