#!/usr/bin/env python3
"""Generates the T-0401 GPX fixtures and prints the oracle rows for TrackFixturesTest.

Run from anywhere:

    python3 test-data/tracks/generate-fixtures.py

It rewrites every fixture in this directory and prints the expected track,
segment, point, ascent, descent and elevation figures. Those figures are copied
into `TrackFixturesTest`; the generator is not part of the build.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from pathlib import Path

OUT = Path(__file__).resolve().parent
R = 6371008.8


@dataclass
class Pt:
    lat: float
    lon: float
    ele: float | None = None
    time: str | None = None


@dataclass
class Seg:
    points: list[Pt]


@dataclass
class Trk:
    name: str
    segments: list[Seg]


@dataclass
class Fixture:
    name: str
    creator: str
    version: str = "1.1"
    namespaces: dict[str, str] = field(default_factory=dict)
    metadata: bool = False
    tracks: list[Trk] = field(default_factory=list)
    routes: list[tuple[str, list[Pt]]] = field(default_factory=list)
    waypoints: list[tuple[str, Pt, str | None]] = field(default_factory=list)
    segments_note: str = ""


def esc(text: str) -> str:
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def p(
    lat: float,
    lon: float,
    ele: float | None,
    time: str | None,
) -> Pt:
    return Pt(lat=lat, lon=lon, ele=ele, time=time)


def pts(
    start_lat: float,
    start_lon: float,
    elevations: list[float | None],
    start_time: str = "2026-08-14T07:41:03Z",
    step_m: float = 0.0001,
) -> list[Pt]:
    out = []
    for i, ele in enumerate(elevations):
        hour = 7
        minute = 41 + i // 60
        second = 3 + i % 60
        time = (
            f"2026-08-14T{hour:02d}:{minute:02d}:{second:02d}Z"
            if start_time is not None
            else None
        )
        out.append(
            Pt(
                lat=round(start_lat + i * step_m, 6),
                lon=round(start_lon + i * step_m / 2, 6),
                ele=ele,
                time=time,
            )
        )
    return out


def point_extension(fixture: Fixture, index: int) -> list[str]:
    """The per-point extension block the real exporter writes."""
    if fixture.creator.startswith("OsmAnd"):
        return [
            "        <extensions>",
            f"          <osmand:speed>{1.2 + index * 0.1:.1f}</osmand:speed>",
            "        </extensions>",
        ]
    if fixture.creator.startswith("Garmin"):
        return [
            "        <extensions>",
            "          <gpxtpx:TrackPointExtension>",
            f"            <gpxtpx:hr>{120 + index}</gpxtpx:hr>",
            f"            <gpxtpx:cad>{80 + index}</gpxtpx:cad>",
            "          </gpxtpx:TrackPointExtension>",
            "        </extensions>",
        ]
    if fixture.creator.startswith("Strava"):
        return [
            "        <extensions>",
            "          <gpxtpx:TrackPointExtension>",
            f"            <gpxtpx:hr>{140 + index}</gpxtpx:hr>",
            "          </gpxtpx:TrackPointExtension>",
            f"          <power>{200 + index}</power>",
            "        </extensions>",
        ]
    return []


def gpx(fixture: Fixture) -> str:
    ns = {"xmlns": "http://www.topografix.com/GPX/1/1"}
    if fixture.version == "1.0":
        ns = {"xmlns": "http://www.topografix.com/GPX/1/0"}
    ns.update(fixture.namespaces)
    ns_attrs = " ".join(f'{key}="{value}"' for key, value in ns.items())
    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        f'<gpx version="{fixture.version}" creator="{fixture.creator}" {ns_attrs}>',
    ]
    if fixture.metadata:
        lines += [
            "  <metadata>",
            "    <name>Exported activity</name>",
            "    <time>2026-08-14T09:00:00Z</time>",
            "  </metadata>",
        ]
    for name, point, sym in fixture.waypoints:
        lines.append(f'  <wpt lat="{point.lat}" lon="{point.lon}">')
        if point.ele is not None:
            lines.append(f"    <ele>{point.ele}</ele>")
        if point.time is not None:
            lines.append(f"    <time>{point.time}</time>")
        lines.append(f"    <name>{esc(name)}</name>")
        if sym:
            lines.append(f"    <sym>{esc(sym)}</sym>")
        lines.append("  </wpt>")
    for name, route_points in fixture.routes:
        lines.append(f"  <rte><name>{esc(name)}</name>")
        for point in route_points:
            lines.append(f'    <rtept lat="{point.lat}" lon="{point.lon}">')
            if point.ele is not None:
                lines.append(f"      <ele>{point.ele}</ele>")
            lines.append("    </rtept>")
        lines.append("  </rte>")
    for track in fixture.tracks:
        lines.append(f"  <trk><name>{esc(track.name)}</name>")
        for segment in track.segments:
            lines.append("    <trkseg>")
            for index, point in enumerate(segment.points):
                lines.append(f'      <trkpt lat="{point.lat:.6f}" lon="{point.lon:.6f}">')
                if point.ele is not None:
                    lines.append(f"        <ele>{point.ele}</ele>")
                if point.time is not None:
                    lines.append(f"        <time>{point.time}</time>")
                lines.extend(point_extension(fixture, index))
                lines.append("      </trkpt>")
            lines.append("    </trkseg>")
        lines.append("  </trk>")
    lines.append("</gpx>")
    return "\n".join(lines) + "\n"


def haversine(a: Pt, b: Pt) -> float:
    lat1, lon1 = math.radians(a.lat), math.radians(a.lon)
    lat2, lon2 = math.radians(b.lat), math.radians(b.lon)
    h = (
        math.sin((lat2 - lat1) / 2) ** 2
        + math.cos(lat1) * math.cos(lat2) * math.sin((lon2 - lon1) / 2) ** 2
    )
    return 2 * R * math.asin(math.sqrt(min(1.0, max(0.0, h))))


def analysis(track: Trk) -> tuple[int, float, float, float, float | None, float | None]:
    count = 0
    distance = 0.0
    ascent = 0.0
    descent = 0.0
    eles: list[float] = []
    for segment in track.segments:
        previous = None
        for point in segment.points:
            count += 1
            if point.ele is not None:
                eles.append(point.ele)
            if previous is not None:
                distance += haversine(previous, point)
                if previous.ele is not None and point.ele is not None:
                    delta = point.ele - previous.ele
                    if delta > 0:
                        ascent += delta
                    else:
                        descent += -delta
            previous = point
    return (
        count,
        distance,
        ascent,
        descent,
        min(eles) if eles else None,
        max(eles) if eles else None,
    )


FIXTURES: list[Fixture] = []


def add(fixture: Fixture) -> None:
    FIXTURES.append(fixture)


T0 = "2026-08-14T07:41:03Z"

add(
    Fixture(
        name="osmand-walk-two-segments",
        creator="OsmAnd 4.9.10",
        namespaces={
            "xmlns:osmand": "https://osmand.net",
            "xmlns:xsi": "http://www.w3.org/2001/XMLSchema-instance",
        },
        metadata=True,
        tracks=[
            Trk(
                "Lviv centre walk",
                [
                    Seg(pts(49.84010, 24.03010, [300.0, 301.0, 302.0, 303.0, 304.0])),
                    Seg(pts(49.84110, 24.03100, [304.0, 303.0, 302.0, 300.0])),
                ],
            )
        ],
        segments_note="two segments with a pause between",
    )
)

add(
    Fixture(
        name="osmand-morning-run",
        creator="OsmAnd 4.9.10",
        namespaces={"xmlns:osmand": "https://osmand.net"},
        metadata=True,
        tracks=[
            Trk(
                "Ранкова пробіжка",
                [
                    Seg(
                        pts(
                            49.84200,
                            24.03200,
                            [310.0, 312.5, 311.0, 315.0, 314.0, 316.0],
                        )
                    )
                ],
            )
        ],
    )
)

add(
    Fixture(
        name="osmand-multi-track",
        creator="OsmAnd 4.9.10",
        namespaces={"xmlns:osmand": "https://osmand.net"},
        tracks=[
            Trk("Morning walk", [Seg(pts(49.84300, 24.03300, [320.0, 321.0, 322.0, 323.0]))]),
            Trk("Evening walk", [Seg(pts(49.84400, 24.03400, [323.0, 322.0, 320.0]))]),
        ],
    )
)

add(
    Fixture(
        name="osmand-waypoints",
        creator="OsmAnd 4.9.10",
        namespaces={"xmlns:osmand": "https://osmand.net"},
        tracks=[Trk("Walk to the market", [Seg(pts(49.84500, 24.03500, [330.0, 331.0, 332.0, 333.0]))])],
        waypoints=[
            ("Market", Pt(49.84550, 24.03550, 332.0, T0), "shop"),
            ("Fountain", Pt(49.84600, 24.03600, None, None), "drinking_water"),
            ("Viewpoint", Pt(49.84650, 24.03650, 335.0, None), "viewpoint"),
        ],
    )
)

add(
    Fixture(
        name="osmand-no-elevation",
        creator="OsmAnd 4.9.10",
        namespaces={"xmlns:osmand": "https://osmand.net"},
        tracks=[Trk("Car trip", [Seg(pts(49.84700, 24.03700, [None] * 5))])],
    )
)

add(
    Fixture(
        name="osmand-no-time",
        creator="OsmAnd 4.9.10",
        namespaces={"xmlns:osmand": "https://osmand.net"},
        tracks=[
            Trk(
                "Track without timestamps",
                [Seg([Pt(49.84800 + i * 0.0001, 24.03800, 340.0 + i, None) for i in range(4)])],
            )
        ],
    )
)

add(
    Fixture(
        name="osmand-single-point",
        creator="OsmAnd 4.9.10",
        namespaces={"xmlns:osmand": "https://osmand.net"},
        tracks=[Trk("Single point", [Seg([Pt(49.84900, 24.03900, 350.0, T0)])])],
    )
)

add(
    Fixture(
        name="osmand-planned-route",
        creator="OsmAnd 4.9.10",
        namespaces={"xmlns:osmand": "https://osmand.net"},
        tracks=[Trk("Planned walk", [Seg(pts(49.85000, 24.04000, [360.0, 361.0, 362.0]))])],
        routes=[
            (
                "Planned route",
                [
                    Pt(49.85000, 24.04000, 360.0, None),
                    Pt(49.85100, 24.04100, 365.0, None),
                    Pt(49.85200, 24.04200, 370.0, None),
                ],
            )
        ],
    )
)

GARMIN_NS = {
    "xmlns:gpxtpx": "http://www.garmin.com/xmlschemas/TrackPointExtension/v1",
    "xmlns:gpxx": "http://www.garmin.com/xmlschemas/GpxExtensions/v3",
    "xmlns:xsi": "http://www.w3.org/2001/XMLSchema-instance",
}

add(
    Fixture(
        name="garmin-connect-hike",
        creator="Garmin Connect",
        namespaces=GARMIN_NS,
        metadata=True,
        tracks=[
            Trk(
                "Lviv hills hike",
                [Seg(pts(49.85100, 24.04300, [370.0, 372.5, 371.0, 376.0, 374.0, 378.0]))],
            )
        ],
    )
)

add(
    Fixture(
        name="garmin-connect-autopause",
        creator="Garmin Connect",
        namespaces=GARMIN_NS,
        metadata=True,
        tracks=[
            Trk(
                "Autopause ride",
                [
                    Seg(pts(49.85200, 24.04400, [380.0, 381.0, 382.0, 383.0, 384.0])),
                    Seg(pts(49.85400, 24.04600, [384.0, 383.0, 382.0])),
                    Seg(pts(49.85500, 24.04700, [382.0, 384.0, 386.0, 388.0])),
                ],
            )
        ],
        segments_note="three auto-pause segments",
    )
)

add(
    Fixture(
        name="garmin-fenix-run",
        creator="Garmin Connect",
        namespaces=GARMIN_NS,
        tracks=[
            Trk(
                "Fenix interval run",
                [
                    Seg(pts(49.85600, 24.04800, [390.0, 391.0, 393.0, 395.0, 394.0, 396.0])),
                    Seg(pts(49.85800, 24.04900, [396.0, 395.0, 394.0, 392.0, 390.0])),
                ],
            )
        ],
        segments_note="two intervals with a recovery gap",
    )
)

add(
    Fixture(
        name="garmin-oregon-gpx10",
        creator="Oregon 700",
        version="1.0",
        tracks=[Trk("Oregon track", [Seg(pts(49.85900, 24.05000, [400.0, 402.0, 401.0, 403.0]))])],
    )
)

add(
    Fixture(
        name="garmin-waypoints-route",
        creator="Garmin Connect",
        namespaces=GARMIN_NS,
        tracks=[Trk("Garmin walk", [Seg(pts(49.86000, 24.05100, [410.0, 411.0, 412.0]))])],
        waypoints=[
            ("Trailhead", Pt(49.86000, 24.05100, 410.0, T0), "Trail Head"),
            ("Spring", Pt(49.86050, 24.05150, 412.0, None), "Drinking Water"),
        ],
        routes=[
            (
                "Garmin route",
                [
                    Pt(49.86000, 24.05100, 410.0, None),
                    Pt(49.86100, 24.05200, 415.0, None),
                ],
            )
        ],
    )
)

STRAVA_NS = {"xmlns:gpxtpx": "http://www.garmin.com/xmlschemas/TrackPointExtension/v1"}

add(
    Fixture(
        name="strava-run",
        creator="StravaGPX",
        namespaces=STRAVA_NS,
        metadata=True,
        tracks=[
            Trk(
                "Morning Run",
                [Seg(pts(49.86100, 24.05200, [420.0, 421.0, 423.0, 422.0, 425.0, 424.0, 426.0, 425.0]))],
            )
        ],
    )
)

add(
    Fixture(
        name="strava-ride-pauses",
        creator="StravaGPX",
        namespaces=STRAVA_NS,
        metadata=True,
        tracks=[
            Trk(
                "Lunch Ride",
                [
                    Seg(pts(49.86200, 24.05300, [430.0, 431.0, 432.0, 434.0, 435.0, 437.0])),
                    Seg(pts(49.86400, 24.05500, [437.0, 438.0])),
                    Seg(pts(49.86500, 24.05600, [438.0, 437.0, 436.0, 435.0, 434.0])),
                    Seg(pts(49.86600, 24.05700, [434.0, 433.0, 431.0])),
                ],
            )
        ],
        segments_note="four Strava auto-pause segments",
    )
)

add(
    Fixture(
        name="strava-no-elevation",
        creator="StravaGPX",
        namespaces=STRAVA_NS,
        tracks=[Trk("Evening Walk", [Seg(pts(49.86700, 24.05800, [None] * 5))])],
    )
)

add(
    Fixture(
        name="strava-route-and-track",
        creator="StravaGPX",
        namespaces=STRAVA_NS,
        tracks=[Trk("Saved Route", [Seg(pts(49.86800, 24.05900, [440.0, 441.0, 442.0, 444.0]))])],
        routes=[
            (
                "Saved Route Plan",
                [
                    Pt(49.86800, 24.05900, 440.0, None),
                    Pt(49.86900, 24.06000, 445.0, None),
                    Pt(49.87000, 24.06100, 450.0, None),
                ],
            )
        ],
    )
)

add(
    Fixture(
        name="strava-ukrainian-name",
        creator="StravaGPX",
        namespaces=STRAVA_NS,
        metadata=True,
        tracks=[Trk("Вечірня прогулянка Львовом", [Seg(pts(49.86900, 24.06000, [450.0, 451.0, 452.0, 451.0]))])],
    )
)


# The broken fixture is authored directly so the skipped parts are exact.
def broken_partial() -> str:
    return """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="OsmAnd 4.9.10" xmlns="http://www.topografix.com/GPX/1/1"
     xmlns:osmand="https://osmand.net">
  <metadata><name>Damaged export</name></metadata>
  <trk>
    <name>Partially damaged walk</name>
    <trkseg>
      <trkpt lat="49.87000" lon="24.06100"><ele>460.0</ele><time>2026-08-14T10:00:00Z</time></trkpt>
      <trkpt lat="49.87010" lon="24.06105"><ele>461.0</ele><time>2026-08-14T10:00:05Z</time></trkpt>
      <trkpt lon="24.06110"><ele>462.0</ele><time>2026-08-14T10:00:10Z</time></trkpt>
      <trkpt lat="not-a-number" lon="24.06115"><ele>463.0</ele></trkpt>
      <trkpt lat="95.50000" lon="200.00000"><ele>464.0</ele></trkpt>
      <trkpt lat="49.87040" lon="24.06120"><ele>oops</ele><time>yesterday</time></trkpt>
      <trkpt lat="49.87050" lon="24.06125"><ele>463.0</ele><time>2026-08-14T10:00:25Z</time></trkpt>
    </trkseg>
    <trkseg>
    </trkseg>
  </trk>
  <trk>
    <name>Empty second track</name>
    <trkseg></trkseg>
  </trk>
</gpx>
"""


def broken_empty_tracks() -> str:
    return """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Garmin Connect"
     xmlns="http://www.topografix.com/GPX/1/1"
     xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">
  <trk>
    <name>No segments at all</name>
  </trk>
  <trk>
    <name>One usable segment</name>
    <trkseg></trkseg>
    <trkseg>
      <trkpt lat="49.87100" lon="24.06200"><ele>470.0</ele></trkpt>
      <trkpt lat="49.87110" lon="24.06205"><ele>471.0</ele></trkpt>
      <trkpt lat="49.87120" lon="24.06210"><ele>472.0</ele></trkpt>
    </trkseg>
    <trkseg>
      <trkpt lon="24.06215"><ele>473.0</ele></trkpt>
      <trkpt lat="49.87130" lon="24.06220"><ele>474.0</ele></trkpt>
    </trkseg>
  </trk>
</gpx>
"""


def write(name: str, text: str) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / name).write_text(text, encoding="utf-8")


for fixture in FIXTURES:
    write(f"{fixture.name}.gpx", gpx(fixture))
write("broken-partial.gpx", broken_partial())
write("broken-empty-tracks.gpx", broken_empty_tracks())

print(f"{len(FIXTURES) + 2} fixtures written to {OUT}\n")
print("name | tracks | segs | points | ascent | descent | min | max | distance")
for fixture in FIXTURES:
    rows = [analysis(track) for track in fixture.tracks]
    total_points = sum(row[0] for row in rows)
    ascent = sum(row[2] for row in rows)
    descent = sum(row[3] for row in rows)
    distance = sum(row[1] for row in rows)
    mins = [row[4] for row in rows if row[4] is not None]
    maxs = [row[5] for row in rows if row[5] is not None]
    segs = sum(len(track.segments) for track in fixture.tracks)
    print(
        f"{fixture.name} | {len(rows)} | {segs} | {total_points} | {ascent:.1f} | "
        f"{descent:.1f} | {min(mins) if mins else None} | {max(maxs) if maxs else None} | {distance:.4f}"
    )

print("\nbroken-partial: 1 track, 1 segment, 4 kept points (7 trkpt, 3 dropped), "
      "warnings: missing-coordinates x2, out-of-range x1, invalid-ele x1, invalid-time x1, "
      "empty-segment x1, empty-track x1")
print("broken-empty-tracks: 1 track, 2 segments (2 + 2 points kept), "
      "warnings: empty-track trk[0], empty-segment trk[1]/trkseg[0], missing-coordinates trk[1]/trkseg[2]/trkpt[0]")
