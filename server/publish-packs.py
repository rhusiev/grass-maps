#!/usr/bin/env python3
"""Turns a pipeline output directory into the layout the server serves.

Input (``tools/pipeline/build-oblasts.sh --out``):
    <out>/catalog.json, <out>/outlines/*.geojson,
    <out>/<region>/<version>/<component files> + manifest.json

Output (``--dest``, the directory mounted at /packs):
    <dest>/catalog.json, <dest>/outlines/*.geojson,
    <dest>/<region>/<version>/package.zip + manifest.json

``package.zip`` holds the version directory's files with their relative paths
(a directory component such as routing tiles keeps its folder), which is the archive
the app downloads, extracts and verifies against the manifest. A published
version directory is immutable: an existing ``package.zip`` is kept, so a
re-run only adds what is new. The catalog goes last and atomically, so a client
never sees a version whose archive is missing.
"""

from __future__ import annotations

import argparse
import os
import shutil
import sys
import zipfile
from pathlib import Path

MANIFEST = "manifest.json"
PACKAGE = "package.zip"
CATALOG = "catalog.json"
OUTLINES = "outlines"
STORED_SUFFIXES = {".pmtiles", ".tar", ".zst", ".gz"}


def build_archive(source: Path, target: Path) -> None:
    """Zips the files under [source] into [target], written atomically."""
    temp = target.with_name(f".{target.name}.tmp")
    try:
        with zipfile.ZipFile(temp, "w") as archive:
            for file in sorted(source.rglob("*")):
                if not file.is_file() or file.name.startswith("."):
                    continue
                method = zipfile.ZIP_STORED if file.suffix in STORED_SUFFIXES else zipfile.ZIP_DEFLATED
                archive.write(file, file.relative_to(source).as_posix(), compress_type=method)
        os.replace(temp, target)
    finally:
        temp.unlink(missing_ok=True)


def copy_atomic(source: Path, target: Path) -> None:
    """Copies [source] to [target] so a reader never sees half a file."""
    target.parent.mkdir(parents=True, exist_ok=True)
    temp = target.with_name(f".{target.name}.tmp")
    shutil.copyfile(source, temp)
    os.replace(temp, target)


def publish_version(source: Path, dest: Path) -> bool:
    """Publishes one version directory. Returns False when it was already there."""
    if not (source / MANIFEST).is_file():
        raise SystemExit(f"publish-packs: {source} has no {MANIFEST}")
    dest.mkdir(parents=True, exist_ok=True)
    if (dest / PACKAGE).exists():
        return False
    build_archive(source, dest / PACKAGE)
    copy_atomic(source / MANIFEST, dest / MANIFEST)
    return True


def publish(source: Path, dest: Path) -> int:
    """Publishes every version under [source]. Returns how many were new."""
    if not (source / CATALOG).is_file():
        raise SystemExit(f"publish-packs: {source} has no {CATALOG}")
    added = 0
    for region in sorted(path for path in source.iterdir() if path.is_dir()):
        for version in sorted(path for path in region.iterdir() if (path / MANIFEST).is_file()):
            if publish_version(version, dest / region.name / version.name):
                added += 1
                print(f"published {region.name}/{version.name}")
    for outline in sorted((source / OUTLINES).glob("*.geojson")):
        copy_atomic(outline, dest / OUTLINES / outline.name)
    copy_atomic(source / CATALOG, dest / CATALOG)
    return added


def main(argv: list[str] | None = None) -> int:
    """Parses [argv] and publishes. Returns the process exit code."""
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("source", type=Path, help="pipeline output directory")
    parser.add_argument("--dest", type=Path, required=True, help="served directory")
    args = parser.parse_args(argv)
    added = publish(args.source.resolve(), args.dest.resolve())
    print(f"{added} new version(s) published to {args.dest}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
