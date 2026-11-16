"""Server settings, read once from environment variables."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

GIB = 1024**3
ANON_KEY_MODES = ("ip", "global")


class ConfigError(ValueError):
    """An environment variable holds a value the server cannot use."""


@dataclass(frozen=True)
class Config:
    """Every setting of the server.

    Attributes:
        packs_dir: Root of the served layout (read only).
        state_dir: Directory for the usage counters.
        tokens_file: JSON file written by ``gm-token``.
        require_tokens: When true, a request needs a valid bearer token.
        anon_key: With tokens off, the limit key: ``ip`` or ``global``.
        trust_forwarded: Take the client IP from the last ``X-Forwarded-For``.
        rate_per_minute: Requests a key may make in a 60 s window.
        max_concurrent: Simultaneous responses a key may hold.
        quota_bytes: Bytes a key may download per UTC month, 0 for no quota.
        ban_failures: Failed token checks from one IP that trigger a ban.
        ban_window_s: Window in which those failures are counted.
        ban_seconds: Length of a ban.
        host: Listen address.
        port: Listen port.
        socket_timeout_s: Idle time after which a connection is dropped.
    """

    packs_dir: Path
    state_dir: Path
    tokens_file: Path
    require_tokens: bool
    anon_key: str
    trust_forwarded: bool
    rate_per_minute: int
    max_concurrent: int
    quota_bytes: int
    ban_failures: int
    ban_window_s: int
    ban_seconds: int
    host: str
    port: int
    socket_timeout_s: int

    @classmethod
    def from_env(cls, env: dict[str, str] | None = None) -> Config:
        """Builds the settings from [env], default ``os.environ``."""
        values = dict(os.environ if env is None else env)

        def text(name: str, default: str) -> str:
            return values.get(name, default).strip()

        def number(name: str, default: int) -> int:
            raw = text(name, str(default))
            try:
                parsed = int(raw)
            except ValueError as exc:
                raise ConfigError(f"{name} must be a whole number, got {raw!r}") from exc
            if parsed < 0:
                raise ConfigError(f"{name} must not be negative")
            return parsed

        def flag(name: str, default: bool) -> bool:
            raw = text(name, "1" if default else "0").lower()
            if raw in ("1", "true", "yes", "on"):
                return True
            if raw in ("0", "false", "no", "off"):
                return False
            raise ConfigError(f"{name} must be 1 or 0, got {raw!r}")

        anon_key = text("GM_ANON_KEY", "ip")
        if anon_key not in ANON_KEY_MODES:
            raise ConfigError(f"GM_ANON_KEY must be one of {ANON_KEY_MODES}")
        return cls(
            packs_dir=Path(text("GM_PACKS_DIR", "/packs")),
            state_dir=Path(text("GM_STATE_DIR", "/state")),
            tokens_file=Path(text("GM_TOKENS_FILE", "/config/tokens.json")),
            require_tokens=flag("GM_REQUIRE_TOKENS", True),
            anon_key=anon_key,
            trust_forwarded=flag("GM_TRUST_FORWARDED", False),
            rate_per_minute=number("GM_RATE_PER_MINUTE", 60),
            max_concurrent=number("GM_MAX_CONCURRENT", 4),
            quota_bytes=number("GM_QUOTA_GIB", 20) * GIB,
            ban_failures=number("GM_BAN_FAILURES", 20),
            ban_window_s=number("GM_BAN_WINDOW_S", 600),
            ban_seconds=number("GM_BAN_SECONDS", 3600),
            host=text("GM_HOST", "0.0.0.0"),
            port=number("GM_PORT", 8080),
            socket_timeout_s=number("GM_SOCKET_TIMEOUT_S", 30),
        )
