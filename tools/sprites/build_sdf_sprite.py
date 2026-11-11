#!/usr/bin/env python3
"""Build the Standard style single-colour SDF icon sprite.

The source SVGs are vendored next to this script.  `sources.json` records, for
every icon, the upstream repository, the path inside it, the commit the file
was taken from and the licence.  Every glyph is a Google Material Symbols
Rounded SVG:

  * Material Symbols Rounded, https://github.com/google/material-design-icons,
    Apache-2.0, the `fill1` weight-400 variant at 24 px, except `electronics`,
    which uses the outlined `tv` because the filled one collapses at map size

The 15 road-shield frames (`US:I-<n>char`, `NL:S-road-<n>char`,
`generic_shield-<n>char`) are authored in this repository under `shields/`,
because the open icon sets have no shield outline for these networks.  They
are Apache-2.0, like the rest of this repository.

The output is one sheet used by both the light and dark Standard variants.  A
pixel's red, green and blue channels are white; the alpha channel carries the
signed distance.  MapLibre decodes it with the constants of its SDF shader
(`SDF_PX = 8`, cutoff 0.25): alpha 255 * (1 - 0.25 - d / 8), where d is the
distance in texture pixels, positive outside the glyph.  The glyph edge lands
at alpha 0.75 * 255 and the field reaches zero 6 px outside the shape, which
is also the padding around every glyph.

Usage:
  python3 tools/sprites/build_sdf_sprite.py [--check] [--supersample N]

`--check` rebuilds the sheet in memory and compares the bytes with the
committed sprite, so a stale sheet is reported instead of silently accepted.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import struct
import subprocess
import sys
import xml.etree.ElementTree as ElementTree
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SPRITE_DIR = ROOT / "app" / "src" / "main" / "assets" / "sprites"
SOURCES = HERE / "sources.json"

SDF_RADIUS_PX = 8.0
SDF_CUTOFF = 0.25
SDF_PADDING_PX = 6
SHEET_NAME = "standard"
SHEET_1X_WIDTH = 512
SHEET_GAP_PX = 1
# Four samples per output pixel keep the edge position within a quarter pixel
# while the pure-Python distance transform stays fast.
DEFAULT_SUPERSAMPLE = 4
LARGE_DISTANCE = 1.0e10


class SpriteError(RuntimeError):
    pass


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def load_sources() -> dict:
    return json.loads(SOURCES.read_text())


def check_sources(sources: dict) -> None:
    for family in ("icons", "shields"):
        for name, entry in sources[family].items():
            path = HERE / family / f"{name}.svg"
            if not path.exists():
                raise SpriteError(f"{name}: missing source {path}")
            actual = sha256(path.read_bytes())
            if actual != entry["sha256"]:
                raise SpriteError(
                    f"{name}: {path.name} changed (sha256 {actual}, "
                    f"sources.json says {entry['sha256']})"
                )


def svg_view_box(data: bytes) -> tuple[float, float]:
    root = ElementTree.fromstring(data)
    view_box = root.get("viewBox")
    if view_box:
        _, _, width, height = (float(part) for part in view_box.replace(",", " ").split())
        return width, height
    width = float(str(root.get("width", "0")).rstrip("px"))
    height = float(str(root.get("height", "0")).rstrip("px"))
    if width <= 0 or height <= 0:
        raise SpriteError("SVG has neither a viewBox nor a numeric width and height")
    return width, height


def render_alpha(svg: Path, width: int, height: int) -> list[bytes]:
    """Rasterise an SVG and return its alpha channel rows."""
    result = subprocess.run(
        ["rsvg-convert", "-w", str(width), "-h", str(height), str(svg)],
        check=False,
        capture_output=True,
    )
    if result.returncode != 0:
        raise SpriteError(
            f"rsvg-convert failed for {svg.name}: "
            f"{result.stderr.decode(errors='replace').strip()}"
        )
    return decode_png_alpha(result.stdout)


def decode_png_alpha(data: bytes) -> list[bytes]:
    if data[:8] != b"\x89PNG\r\n\x1a\n":
        raise SpriteError("rsvg-convert did not return a PNG")
    position = 8
    header = None
    compressed = bytearray()
    while position < len(data):
        length = struct.unpack(">I", data[position : position + 4])[0]
        kind = data[position + 4 : position + 8]
        body = data[position + 8 : position + 8 + length]
        position += 12 + length
        if kind == b"IHDR":
            header = struct.unpack(">IIBBBBB", body)
        elif kind == b"IDAT":
            compressed += body
        elif kind == b"IEND":
            break
    if header is None:
        raise SpriteError("PNG has no IHDR")
    width, height, depth, colour, compression, filter_method, interlace = header
    if depth != 8 or compression != 0 or filter_method != 0 or interlace != 0:
        raise SpriteError(f"unsupported PNG format {header}")
    if colour == 6:
        channels = 4
    elif colour == 2:
        channels = 3
    elif colour == 0:
        channels = 1
    else:
        raise SpriteError(f"unsupported PNG colour type {colour}")
    raw = zlib.decompress(bytes(compressed))
    stride = width * channels
    rows = []
    previous = bytearray(stride)
    offset = 0
    for _ in range(height):
        method = raw[offset]
        line = bytearray(raw[offset + 1 : offset + 1 + stride])
        offset += 1 + stride
        if method == 1:
            for index in range(channels, stride):
                line[index] = (line[index] + line[index - channels]) & 0xFF
        elif method == 2:
            for index in range(stride):
                line[index] = (line[index] + previous[index]) & 0xFF
        elif method == 3:
            for index in range(stride):
                left = line[index - channels] if index >= channels else 0
                line[index] = (line[index] + ((left + previous[index]) >> 1)) & 0xFF
        elif method == 4:
            for index in range(stride):
                left = line[index - channels] if index >= channels else 0
                up = previous[index]
                upper_left = previous[index - channels] if index >= channels else 0
                estimate = left + up - upper_left
                left_delta = abs(estimate - left)
                up_delta = abs(estimate - up)
                upper_left_delta = abs(estimate - upper_left)
                if left_delta <= up_delta and left_delta <= upper_left_delta:
                    predictor = left
                elif up_delta <= upper_left_delta:
                    predictor = up
                else:
                    predictor = upper_left
                line[index] = (line[index] + predictor) & 0xFF
        elif method != 0:
            raise SpriteError(f"unsupported PNG filter {method}")
        previous = line
        if colour == 6:
            rows.append(bytes(line[3::4]))
        elif colour == 0:
            rows.append(bytes(line))
        else:
            rows.append(b"\xff" * width)
    return rows


def distance_transform_1d(values: list[float]) -> list[float]:
    """Squared Euclidean distance transform of one line (Felzenszwalb)."""
    count = len(values)
    result = [0.0] * count
    sites = [0] * count
    boundaries = [0.0] * (count + 1)
    envelope = 0
    sites[0] = 0
    boundaries[0] = -math.inf
    boundaries[1] = math.inf
    for point in range(1, count):
        intersection = (
            (values[point] + point * point) - (values[sites[envelope]] + sites[envelope] ** 2)
        ) / (2.0 * point - 2.0 * sites[envelope])
        while intersection <= boundaries[envelope]:
            envelope -= 1
            intersection = (
                (values[point] + point * point)
                - (values[sites[envelope]] + sites[envelope] ** 2)
            ) / (2.0 * point - 2.0 * sites[envelope])
        envelope += 1
        sites[envelope] = point
        boundaries[envelope] = intersection
        boundaries[envelope + 1] = math.inf
    envelope = 0
    for point in range(count):
        while boundaries[envelope + 1] < point:
            envelope += 1
        delta = point - sites[envelope]
        result[point] = delta * delta + values[sites[envelope]]
    return result


def distance_transform(mask: list[list[bool]], want_inside: bool) -> list[list[float]]:
    """Distance in pixels from every cell to the nearest cell matching `want_inside`."""
    height = len(mask)
    width = len(mask[0])
    rows = [
        [0.0 if mask[y][x] == want_inside else LARGE_DISTANCE for x in range(width)]
        for y in range(height)
    ]
    columns = [distance_transform_1d([rows[y][x] for y in range(height)]) for x in range(width)]
    return [
        distance_transform_1d([columns[x][y] for x in range(width)]) for y in range(height)
    ]


def sdf_alpha(
    svg: Path, view_width: float, view_height: float, box_width: int, box_height: int, scale: int, supersample: int
) -> tuple[int, int, bytearray]:
    """Encode one glyph as a MapLibre SDF alpha field at `scale` x."""
    canvas_width = (box_width + 2 * SDF_PADDING_PX) * scale
    canvas_height = (box_height + 2 * SDF_PADDING_PX) * scale
    aspect = view_width / view_height
    box_w = float(box_width * scale)
    box_h = float(box_height * scale)
    if box_w / box_h > aspect:
        draw_h = box_h
        draw_w = box_h * aspect
    else:
        draw_w = box_w
        draw_h = box_w / aspect
    render_w = max(1, round(draw_w * supersample))
    render_h = max(1, round(draw_h * supersample))
    glyph = render_alpha(svg, render_w, render_h)
    high_width = canvas_width * supersample
    high_height = canvas_height * supersample
    mask = [[False] * high_width for _ in range(high_height)]
    offset_x = (high_width - render_w) // 2
    offset_y = (high_height - render_h) // 2
    for y, row in enumerate(glyph):
        target_y = offset_y + y
        if target_y < 0 or target_y >= high_height:
            continue
        target_row = mask[target_y]
        for x, value in enumerate(row):
            target_x = offset_x + x
            if 0 <= target_x < high_width and value >= 128:
                target_row[target_x] = True
    to_inside = distance_transform(mask, True)
    to_outside = distance_transform(mask, False)
    radius = SDF_RADIUS_PX * scale
    alpha = bytearray(canvas_width * canvas_height)
    samples = supersample * supersample
    for y in range(canvas_height):
        for x in range(canvas_width):
            total = 0.0
            for high_y in range(y * supersample, (y + 1) * supersample):
                inside_row = to_inside[high_y]
                outside_row = to_outside[high_y]
                for high_x in range(x * supersample, (x + 1) * supersample):
                    total += math.sqrt(inside_row[high_x]) - math.sqrt(outside_row[high_x])
            signed = total / samples
            if signed > 0.0:
                signed -= 0.5
            elif signed < 0.0:
                signed += 0.5
            distance = signed / supersample
            value = 1.0 - SDF_CUTOFF - distance / radius
            alpha[y * canvas_width + x] = max(0, min(255, round(value * 255)))
    return canvas_width, canvas_height, alpha


def glyph_box(entry: dict) -> tuple[int, int]:
    if "width" in entry:
        return entry["width"], entry["height"]
    size = entry.get("size", 19)
    return size, size


def build_glyph(name: str, entry: dict, family: str, supersample: int) -> dict:
    svg = HERE / family / f"{name}.svg"
    view_width, view_height = svg_view_box(svg.read_bytes())
    box_width, box_height = glyph_box(entry)
    glyph = {}
    for scale in (1, 2):
        width, height, alpha = sdf_alpha(
            svg, view_width, view_height, box_width, box_height, scale, supersample
        )
        glyph[scale] = {"width": width, "height": height, "alpha": alpha}
    return glyph


def pack(glyphs: dict, scale: int) -> tuple[dict, int, int]:
    items = sorted(
        glyphs.items(),
        key=lambda item: (-item[1][scale]["height"], item[0]),
    )
    positions = {}
    x = 0
    y = 0
    shelf_height = 0
    sheet_width = 0
    for name, glyph in items:
        width = glyph[scale]["width"]
        height = glyph[scale]["height"]
        if x > 0 and x + width > SHEET_1X_WIDTH * scale:
            x = 0
            y += shelf_height + SHEET_GAP_PX * scale
            shelf_height = 0
        positions[name] = (x, y, width, height)
        x += width + SHEET_GAP_PX * scale
        sheet_width = max(sheet_width, x - SHEET_GAP_PX * scale)
        shelf_height = max(shelf_height, height)
    return positions, sheet_width, y + shelf_height


def png_chunk(kind: bytes, body: bytes) -> bytes:
    return (
        struct.pack(">I", len(body))
        + kind
        + body
        + struct.pack(">I", zlib.crc32(kind + body) & 0xFFFFFFFF)
    )


def encode_png(width: int, height: int, pixels: bytearray) -> bytes:
    header = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    stride = width * 4
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        raw += pixels[y * stride : (y + 1) * stride]
    return (
        b"\x89PNG\r\n\x1a\n"
        + png_chunk(b"IHDR", header)
        + png_chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + png_chunk(b"IEND", b"")
    )


def build(supersample: int) -> dict[str, bytes]:
    sources = load_sources()
    check_sources(sources)
    glyphs = {}
    for family in ("icons", "shields"):
        for name, entry in sources[family].items():
            glyphs[name] = build_glyph(name, entry, family, supersample)
    output = {}
    for scale in (1, 2):
        positions, width, height = pack(glyphs, scale)
        pixels = bytearray(b"\xff" * (width * height * 4))
        for name, (x, y, glyph_width, glyph_height) in positions.items():
            alpha = glyphs[name][scale]["alpha"]
            for row in range(glyph_height):
                for column in range(glyph_width):
                    source = row * glyph_width + column
                    target = ((y + row) * width + x + column) * 4
                    pixels[target + 3] = alpha[source]
        suffix = "" if scale == 1 else "@2x"
        png = SPRITE_DIR / f"{SHEET_NAME}{suffix}.png"
        output[png.name] = encode_png(width, height, pixels)
        data = {}
        for name, (x, y, glyph_width, glyph_height) in positions.items():
            data[name] = {
                "height": glyph_height,
                "pixelRatio": scale,
                "sdf": True,
                "width": glyph_width,
                "x": x,
                "y": y,
            }
        index = SPRITE_DIR / f"{SHEET_NAME}{suffix}.json"
        output[index.name] = (
            json.dumps(data, separators=(",", ":"), sort_keys=True) + "\n"
        ).encode()
    return output


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="build into a temporary directory and compare with the committed sprite",
    )
    parser.add_argument(
        "--supersample",
        type=int,
        default=DEFAULT_SUPERSAMPLE,
        help=f"samples per output pixel (default {DEFAULT_SUPERSAMPLE})",
    )
    arguments = parser.parse_args()
    if arguments.supersample < 1:
        parser.error("--supersample must be at least 1")
    try:
        output = build(arguments.supersample)
    except SpriteError as error:
        print(f"build_sdf_sprite: {error}", file=sys.stderr)
        return 1
    if arguments.check:
        stale = []
        for name, content in sorted(output.items()):
            path = SPRITE_DIR / name
            if not path.exists() or path.read_bytes() != content:
                stale.append(name)
        if stale:
            print(
                "build_sdf_sprite: stale sprite files: " + ", ".join(stale),
                file=sys.stderr,
            )
            return 1
        sources = load_sources()
        icon_count = len(sources["icons"]) + len(sources["shields"])
        print(f"build_sdf_sprite: {icon_count} icons, sprite is current")
        return 0
    for name, content in sorted(output.items()):
        (SPRITE_DIR / name).write_bytes(content)
    icon_count = len(load_sources()["icons"]) + len(load_sources()["shields"])
    print(
        f"build_sdf_sprite: wrote {icon_count} icons to {SPRITE_DIR.relative_to(ROOT)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
