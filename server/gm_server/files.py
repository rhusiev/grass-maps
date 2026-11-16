"""Maps a request path to a file under the packs directory."""

from __future__ import annotations

import re
from pathlib import Path

SEGMENT = r"[a-z0-9][a-z0-9._-]*"
_PACK_FILE = re.compile(rf"^/({SEGMENT})/({SEGMENT})/(package\.zip|manifest\.json)$")
_OUTLINE = re.compile(rf"^/outlines/({SEGMENT}\.geojson)$")
CATALOG_PATH = "/catalog.json"
_SEGMENT_ONLY = re.compile(rf"^{SEGMENT}$")
_OUTLINE_NAME = re.compile(rf"^{SEGMENT}\.geojson$")


def is_served_segment(name: str) -> bool:
    """True when the server would serve a region or version directory [name]."""
    return _SEGMENT_ONLY.match(name) is not None


def is_served_outline(name: str) -> bool:
    """True when the server would serve an outline file called [name]."""
    return _OUTLINE_NAME.match(name) is not None

CONTENT_TYPES = {
    ".json": "application/json",
    ".geojson": "application/geo+json",
    ".zip": "application/zip",
}
DEFAULT_CONTENT_TYPE = "application/octet-stream"


def resolve(packs_dir: Path, path: str) -> Path | None:
    """Returns the file a served [path] names, or None when the path is not served.

    Only three shapes exist: the catalog, an outline and a version's
    ``package.zip`` or ``manifest.json``. Every segment is validated, and the
    result must stay inside [packs_dir] after symbolic links are resolved.
    """
    if path == CATALOG_PATH:
        relative = "catalog.json"
    elif (outline := _OUTLINE.match(path)) is not None:
        relative = f"outlines/{outline.group(1)}"
    elif (pack := _PACK_FILE.match(path)) is not None:
        relative = "/".join(pack.groups())
    else:
        return None
    root = packs_dir.resolve()
    candidate = (root / relative).resolve()
    if root not in candidate.parents or not candidate.is_file():
        return None
    return candidate


def content_type(file: Path) -> str:
    """Returns the media type for [file]."""
    return CONTENT_TYPES.get(file.suffix, DEFAULT_CONTENT_TYPE)
