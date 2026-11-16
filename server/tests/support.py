"""Shared helpers for the server tests: a running server and a raw client."""

from __future__ import annotations

import http.client
import importlib.util
import tempfile
import threading
from dataclasses import dataclass, replace
from pathlib import Path

from gm_server.app import create_server
from gm_server.config import Config
from gm_server.tokens import new_entry, new_token, write_entries

SERVER_DIR = Path(__file__).resolve().parents[1]
CATALOG = b'{"format": 1, "regions": []}'
PACKAGE = bytes(range(256)) * 40
MANIFEST = b'{"region": "ua-46"}'
OUTLINE = b'{"type": "MultiPolygon"}'
REGION = "ua-46"
VERSION = "2026-11-03"


@dataclass
class Response:
    """One HTTP response, fully read."""

    status: int
    headers: dict[str, str]
    body: bytes


def load_publisher():
    """Imports ``publish-packs.py``, whose file name is not a module name."""
    spec = importlib.util.spec_from_file_location("publish_packs", SERVER_DIR / "publish-packs.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def write_packs(root: Path) -> None:
    """Writes the served layout with one region version."""
    version = root / REGION / VERSION
    version.mkdir(parents=True)
    (root / "catalog.json").write_bytes(CATALOG)
    (root / "outlines").mkdir()
    (root / "outlines" / "ua-46.geojson").write_bytes(OUTLINE)
    (version / "package.zip").write_bytes(PACKAGE)
    (version / "manifest.json").write_bytes(MANIFEST)


class RunningServer:
    """A server on a free loopback port with its own packs and state."""

    def __init__(self, require_tokens: bool = True, **overrides: object) -> None:
        self._temp = tempfile.TemporaryDirectory()
        base = Path(self._temp.name)
        write_packs(base / "packs")
        self.token = new_token()
        self.tokens_file = base / "config" / "tokens.json"
        write_entries(self.tokens_file, [new_entry(self.token, "test", None)])
        config = Config.from_env(
            {
                "GM_PACKS_DIR": str(base / "packs"),
                "GM_STATE_DIR": str(base / "state"),
                "GM_TOKENS_FILE": str(self.tokens_file),
                "GM_REQUIRE_TOKENS": "1" if require_tokens else "0",
                "GM_HOST": "127.0.0.1",
                "GM_PORT": "0",
                "GM_SOCKET_TIMEOUT_S": "5",
            }
        )
        self.config = replace(config, **overrides)
        self.server = create_server(self.config)
        self.service = self.server.service
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.02}, daemon=True).start()

    def request(
        self,
        path: str,
        method: str = "GET",
        headers: dict[str, str] | None = None,
        token: str | None = None,
    ) -> Response:
        """Sends one request. A [token] is sent as a bearer credential."""
        sent = dict(headers or {})
        if token is not None:
            sent["Authorization"] = f"Bearer {token}"
        connection = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        try:
            connection.request(method, path, headers=sent)
            response = connection.getresponse()
            return Response(
                response.status,
                {name.lower(): value for name, value in response.getheaders()},
                response.read(),
            )
        finally:
            connection.close()

    def close(self) -> None:
        """Stops the server and removes its files."""
        self.server.shutdown()
        self.server.server_close()
        self._temp.cleanup()
