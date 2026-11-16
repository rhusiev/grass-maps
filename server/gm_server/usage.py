"""Monthly byte counters per key, persisted to one JSON file."""

from __future__ import annotations

import calendar
import json
import os
import threading
import time
from collections.abc import Callable
from datetime import datetime, timezone
from pathlib import Path

FLUSH_INTERVAL_S = 2.0
USAGE_FILE = "usage.json"


def _utc_now() -> datetime:
    return datetime.now(timezone.utc)


class UsageStore:
    """Counts bytes sent per key and UTC month.

    The file is rewritten at most every ``FLUSH_INTERVAL_S`` and on [flush], so
    a crash loses at most that many seconds of counting.
    """

    def __init__(
        self,
        state_dir: Path | None,
        now: Callable[[], datetime] = _utc_now,
    ) -> None:
        self._path = None if state_dir is None else state_dir / USAGE_FILE
        self._now = now
        self._lock = threading.Lock()
        self._used: dict[str, dict[str, int]] = {}
        self._reserved: dict[str, int] = {}
        self._dirty = False
        self._last_flush = 0.0
        self._load()

    def used(self, key: str) -> int:
        """Returns the bytes [key] has downloaded this month."""
        with self._lock:
            return self._used.get(key, {}).get(self._month(), 0)

    def reserve(self, key: str, count: int, limit: int) -> bool:
        """Sets [count] bytes aside for a response about to start.

        Returns False, reserving nothing, when the month's bytes plus the bytes
        other responses hold plus [count] would pass [limit]. A [limit] of 0
        means no quota.
        """
        with self._lock:
            held = self._reserved.get(key, 0)
            used = self._used.get(key, {}).get(self._month(), 0)
            if limit and used + held + count > limit:
                return False
            self._reserved[key] = held + count
            return True

    def settle(self, key: str, reserved: int, sent: int) -> None:
        """Ends a reservation: frees [reserved] bytes and counts the [sent] ones."""
        with self._lock:
            left = self._reserved.get(key, 0) - reserved
            if left > 0:
                self._reserved[key] = left
            else:
                self._reserved.pop(key, None)
        self.add(key, sent)

    def add(self, key: str, count: int) -> None:
        """Adds [count] bytes to [key]'s total for this month."""
        if count <= 0:
            return
        with self._lock:
            months = self._used.setdefault(key, {})
            month = self._month()
            months[month] = months.get(month, 0) + count
            self._dirty = True
            if time.monotonic() - self._last_flush >= FLUSH_INTERVAL_S:
                self._write_locked()

    def seconds_to_next_month(self) -> int:
        """Returns the seconds until the counters of every key restart."""
        now = self._now()
        days = calendar.monthrange(now.year, now.month)[1]
        end = now.replace(day=days, hour=23, minute=59, second=59, microsecond=0)
        return int((end - now).total_seconds()) + 1

    def flush(self) -> None:
        """Writes the counters now."""
        with self._lock:
            self._write_locked()

    def _month(self) -> str:
        return self._now().strftime("%Y-%m")

    def _load(self) -> None:
        if self._path is None:
            return
        try:
            data = json.loads(self._path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return
        if isinstance(data, dict):
            self._used = {
                str(key): {str(m): int(b) for m, b in months.items()}
                for key, months in data.items()
                if isinstance(months, dict)
            }

    def _write_locked(self) -> None:
        self._last_flush = time.monotonic()
        if self._path is None or not self._dirty:
            return
        month = self._month()
        current = {
            key: {m: b for m, b in months.items() if m == month}
            for key, months in self._used.items()
        }
        self._used = {key: months for key, months in current.items() if months}
        temp = self._path.with_suffix(".tmp")
        try:
            self._path.parent.mkdir(parents=True, exist_ok=True)
            temp.write_text(json.dumps(self._used), encoding="utf-8")
            os.replace(temp, self._path)
            self._dirty = False
        except OSError:
            pass
