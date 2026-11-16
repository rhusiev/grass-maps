"""The token file: hashed bearer tokens with a label and an enabled flag."""

from __future__ import annotations

import hashlib
import json
import logging
import os
import secrets
import tempfile
import threading
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

TOKEN_BYTES = 32
ID_LENGTH = 8
FILE_MODE = 0o600
LOG = logging.getLogger("gm_server")


class TokenFileError(ValueError):
    """The token file cannot be read or holds an invalid entry."""


@dataclass(frozen=True)
class TokenEntry:
    """One stored token. The secret itself is never stored, only its hash."""

    token_id: str
    label: str
    sha256: str
    created: str
    enabled: bool
    quota_bytes: int | None


def hash_token(token: str) -> str:
    """Returns the hex SHA-256 of [token]."""
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def _entry_from_json(raw: object) -> TokenEntry:
    if not isinstance(raw, dict):
        raise TokenFileError("a token entry must be an object")
    digest = raw.get("sha256")
    if not isinstance(digest, str) or len(digest) != 64:
        raise TokenFileError("a token entry needs a 64 character sha256")
    quota = raw.get("quota_bytes")
    if quota is not None and (isinstance(quota, bool) or not isinstance(quota, int) or quota < 0):
        raise TokenFileError("quota_bytes must be a non-negative integer")
    return TokenEntry(
        token_id=digest[:ID_LENGTH],
        label=str(raw.get("label", "")),
        sha256=digest,
        created=str(raw.get("created", "")),
        enabled=raw.get("enabled", True) is True,
        quota_bytes=quota,
    )


def _entry_to_json(entry: TokenEntry) -> dict[str, object]:
    return {
        "label": entry.label,
        "sha256": entry.sha256,
        "created": entry.created,
        "enabled": entry.enabled,
        "quota_bytes": entry.quota_bytes,
    }


class TokenStore:
    """Reads the token file again on every lookup, keyed on its content hash.

    A disabled token therefore stops working on the next request, with no
    restart. The file is tiny, so reading it each time is cheap and cannot miss
    a rewrite that keeps the same size and modification time. A file that cannot
    be read or parsed leaves [error] set until it is fixed.
    """

    def __init__(self, path: Path) -> None:
        self._path = path
        self._lock = threading.Lock()
        self._digest: str | None = None
        self._by_hash: dict[str, TokenEntry] = {}
        self._error: str | None = None

    def lookup(self, token: str) -> TokenEntry | None:
        """Returns the enabled entry for [token], or None."""
        self._refresh()
        entry = self._by_hash.get(hash_token(token))
        return entry if entry is not None and entry.enabled else None

    def error(self) -> str | None:
        """Returns why the token file is unusable, or None when it is fine."""
        self._refresh()
        return self._error

    def count_enabled(self) -> int:
        """Returns how many enabled tokens the file holds."""
        self._refresh()
        return sum(1 for entry in self._by_hash.values() if entry.enabled)

    def _refresh(self) -> None:
        try:
            raw = self._path.read_bytes()
        except FileNotFoundError:
            raw = b""
            digest = None
        except OSError as exc:
            self._fail(f"cannot read {self._path}: {exc}", f"unreadable:{exc}")
            return
        else:
            digest = hashlib.sha256(raw).hexdigest()
        with self._lock:
            if digest == self._digest:
                return
        try:
            entries = parse_entries(raw, self._path) if digest is not None else []
        except TokenFileError as exc:
            self._fail(str(exc), digest)
            return
        with self._lock:
            self._by_hash = {entry.sha256: entry for entry in entries}
            self._digest = digest
            self._error = None

    def _fail(self, message: str, version: str | None) -> None:
        with self._lock:
            if version != self._digest:
                LOG.error("token file unusable, answering 503 until fixed: %s", message)
            self._digest = version
            self._error = message
            self._by_hash = {}


def parse_entries(raw: bytes, path: Path) -> list[TokenEntry]:
    """Parses the bytes of a token file read from [path]."""
    try:
        document = json.loads(raw.decode("utf-8"))
    except UnicodeDecodeError as exc:
        raise TokenFileError(f"{path} is not valid UTF-8: {exc}") from exc
    except json.JSONDecodeError as exc:
        raise TokenFileError(f"{path} is not valid JSON: {exc}") from exc
    items = document.get("tokens") if isinstance(document, dict) else None
    if not isinstance(items, list):
        raise TokenFileError(f"{path} needs a top-level \"tokens\" list")
    return [_entry_from_json(item) for item in items]


def read_entries(path: Path) -> list[TokenEntry]:
    """Parses the token file at [path]. A missing file is an empty list."""
    try:
        raw = path.read_bytes()
    except FileNotFoundError:
        return []
    except OSError as exc:
        raise TokenFileError(f"cannot read {path}: {exc}") from exc
    return parse_entries(raw, path)


def write_entries(path: Path, entries: list[TokenEntry]) -> None:
    """Writes [entries] to [path] atomically with mode 0600."""
    document = {"tokens": [_entry_to_json(entry) for entry in entries]}
    path.parent.mkdir(parents=True, exist_ok=True)
    handle, temp_name = tempfile.mkstemp(dir=path.parent, prefix=".tokens-")
    try:
        with os.fdopen(handle, "w", encoding="utf-8") as out:
            json.dump(document, out, indent=2)
            out.write("\n")
        os.chmod(temp_name, FILE_MODE)
        os.replace(temp_name, path)
    except BaseException:
        Path(temp_name).unlink(missing_ok=True)
        raise


def new_token() -> str:
    """Returns a random 256-bit token, URL-safe."""
    return secrets.token_urlsafe(TOKEN_BYTES)


def new_entry(token: str, label: str, quota_bytes: int | None) -> TokenEntry:
    """Builds the stored entry for a freshly made [token]."""
    digest = hash_token(token)
    created = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    return TokenEntry(digest[:ID_LENGTH], label, digest, created, True, quota_bytes)
