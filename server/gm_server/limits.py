"""Rate limit, concurrency cap and ban list. All state is in memory."""

from __future__ import annotations

import threading
import time
from collections import deque
from collections.abc import Callable

RATE_WINDOW_S = 60
CONCURRENT_RETRY_S = 5


class Limiter:
    """Per-key request rate and concurrency, plus per-IP failure bans."""

    def __init__(
        self,
        rate_per_minute: int,
        max_concurrent: int,
        ban_failures: int,
        ban_window_s: int,
        ban_seconds: int,
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._rate = rate_per_minute
        self._concurrent_max = max_concurrent
        self._ban_failures = ban_failures
        self._ban_window_s = ban_window_s
        self._ban_seconds = ban_seconds
        self._clock = clock
        self._lock = threading.Lock()
        self._hits: dict[str, deque[float]] = {}
        self._active: dict[str, int] = {}
        self._failures: dict[str, deque[float]] = {}
        self._banned_until: dict[str, float] = {}

    def ban_remaining(self, ip: str) -> int:
        """Returns the seconds left of [ip]'s ban, 0 when it is not banned."""
        with self._lock:
            until = self._banned_until.get(ip)
            if until is None:
                return 0
            left = until - self._clock()
            if left <= 0:
                del self._banned_until[ip]
                return 0
            return int(left) + 1

    def record_failure(self, ip: str) -> None:
        """Counts one failed token check from [ip] and bans it past the limit."""
        if self._ban_failures == 0:
            return
        with self._lock:
            now = self._clock()
            window = self._failures.setdefault(ip, deque())
            window.append(now)
            while window and window[0] <= now - self._ban_window_s:
                window.popleft()
            if len(window) >= self._ban_failures:
                self._banned_until[ip] = now + self._ban_seconds
                window.clear()

    def rate_retry_after(self, key: str) -> int:
        """Counts a request for [key]. Returns 0 if allowed, else seconds to wait."""
        if self._rate == 0:
            return 0
        with self._lock:
            now = self._clock()
            window = self._hits.setdefault(key, deque())
            while window and window[0] <= now - RATE_WINDOW_S:
                window.popleft()
            if len(window) >= self._rate:
                return int(window[0] + RATE_WINDOW_S - now) + 1
            window.append(now)
            return 0

    def acquire(self, key: str) -> bool:
        """Takes one concurrency slot for [key]. False when all are taken."""
        if self._concurrent_max == 0:
            return True
        with self._lock:
            held = self._active.get(key, 0)
            if held >= self._concurrent_max:
                return False
            self._active[key] = held + 1
            return True

    def release(self, key: str) -> None:
        """Gives back a slot taken with [acquire]."""
        if self._concurrent_max == 0:
            return
        with self._lock:
            held = self._active.get(key, 0)
            if held <= 1:
                self._active.pop(key, None)
            else:
                self._active[key] = held - 1
