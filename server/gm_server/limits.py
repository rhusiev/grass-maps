"""Rate limit, concurrency cap and ban list. All state is in memory."""

from __future__ import annotations

import ipaddress
import threading
import time
from collections import deque
from collections.abc import Callable

RATE_WINDOW_S = 60
CONCURRENT_RETRY_S = 5
MAX_TRACKED_KEYS = 10_000
SWEEP_INTERVAL_S = 30
IPV6_PREFIX_BITS = 64


def ip_key(address: str) -> str | None:
    """Returns the key an IP counts under, or None when [address] is no IP.

    IPv4 keeps the full address. IPv6 collapses to its /64 prefix, because a
    client owns a whole /64 and could otherwise rotate inside it.
    """
    try:
        parsed = ipaddress.ip_address(address)
    except ValueError:
        return None
    if isinstance(parsed, ipaddress.IPv6Address):
        if parsed.ipv4_mapped is not None:
            return str(parsed.ipv4_mapped)
        network = ipaddress.ip_network((parsed, IPV6_PREFIX_BITS), strict=False)
        return str(network)
    return str(parsed)


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
        self._last_sweep = clock()

    def _sweep_locked(self, now: float) -> None:
        """Drops expired entries now and then, and caps every map's size."""
        if now - self._last_sweep >= SWEEP_INTERVAL_S:
            self._last_sweep = now
            for key in [k for k, w in self._hits.items() if not w or w[-1] <= now - RATE_WINDOW_S]:
                del self._hits[key]
            for key in [
                k for k, w in self._failures.items() if not w or w[-1] <= now - self._ban_window_s
            ]:
                del self._failures[key]
            for key in [k for k, until in self._banned_until.items() if until <= now]:
                del self._banned_until[key]
        for tracked in (self._hits, self._failures, self._banned_until):
            while len(tracked) >= MAX_TRACKED_KEYS:
                del tracked[next(iter(tracked))]

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
            self._sweep_locked(now)
            window = self._failures.setdefault(ip, deque())
            window.append(now)
            while window and window[0] <= now - self._ban_window_s:
                window.popleft()
            if len(window) >= self._ban_failures:
                self._banned_until[ip] = now + self._ban_seconds
                del self._failures[ip]

    def rate_retry_after(self, key: str) -> int:
        """Counts a request for [key]. Returns 0 if allowed, else seconds to wait."""
        if self._rate == 0:
            return 0
        with self._lock:
            now = self._clock()
            self._sweep_locked(now)
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
