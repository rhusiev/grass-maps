"""The single-range subset of RFC 9110 range requests."""

from __future__ import annotations

import re
from dataclasses import dataclass
from enum import Enum

MAX_RANGE_DIGITS = 18
_RANGE = re.compile(rf"^bytes=(\d{{0,{MAX_RANGE_DIGITS}}})-(\d{{0,{MAX_RANGE_DIGITS}}})$")


class RangeKind(Enum):
    """How a ``Range`` header applies to a file."""

    FULL = "full"
    PARTIAL = "partial"
    UNSATISFIABLE = "unsatisfiable"


@dataclass(frozen=True)
class ByteRange:
    """The outcome of [parse_range]: first and last byte, both inclusive."""

    kind: RangeKind
    first: int = 0
    last: int = 0

    @property
    def length(self) -> int:
        """Number of bytes in the range."""
        return self.last - self.first + 1


def parse_range(header: str | None, size: int) -> ByteRange:
    """Applies a ``Range`` header to a file of [size] bytes.

    A missing, malformed or multi-range header selects the whole file, which
    RFC 9110 allows. A range that starts past the end is unsatisfiable.
    """
    if not header:
        return ByteRange(RangeKind.FULL, 0, size - 1)
    match = _RANGE.match(header.strip())
    if match is None or (not match.group(1) and not match.group(2)):
        return ByteRange(RangeKind.FULL, 0, size - 1)
    start_text, end_text = match.groups()
    if not start_text:
        suffix = int(end_text)
        if suffix == 0 or size == 0:
            return ByteRange(RangeKind.UNSATISFIABLE)
        return ByteRange(RangeKind.PARTIAL, max(size - suffix, 0), size - 1)
    start = int(start_text)
    if start >= size:
        return ByteRange(RangeKind.UNSATISFIABLE)
    end = min(int(end_text), size - 1) if end_text else size - 1
    if end < start:
        return ByteRange(RangeKind.FULL, 0, size - 1)
    return ByteRange(RangeKind.PARTIAL, start, end)
