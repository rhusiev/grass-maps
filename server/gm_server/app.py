"""The HTTP handler: authorization, limits, quota and file serving."""

from __future__ import annotations

import logging
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from gm_server.config import Config
from gm_server.files import content_type, resolve
from gm_server.limits import CONCURRENT_RETRY_S, Limiter, ip_key
from gm_server.ranges import RangeKind, parse_range
from gm_server.tokens import TokenStore
from gm_server.usage import UsageStore

LOG = logging.getLogger("gm_server")
CHUNK_BYTES = 256 * 1024
BEARER_PREFIX = "bearer "
HEALTH_PATH = "/healthz"
ANONYMOUS_KEY = "anonymous"


class Service:
    """The shared state of one server: settings, tokens, limits and counters."""

    def __init__(self, config: Config) -> None:
        self.config = config
        self.tokens = TokenStore(config.tokens_file)
        self.usage = UsageStore(config.state_dir)
        self.limiter = Limiter(
            rate_per_minute=config.rate_per_minute,
            max_concurrent=config.max_concurrent,
            ban_failures=config.ban_failures,
            ban_window_s=config.ban_window_s,
            ban_seconds=config.ban_seconds,
        )


class Identity:
    """Who a request counts against: a limit key, a quota and a log name."""

    def __init__(self, key: str, name: str, quota_bytes: int) -> None:
        self.key = key
        self.name = name
        self.quota_bytes = quota_bytes


class Handler(BaseHTTPRequestHandler):
    """Serves GET and HEAD for the three path shapes of the protocol."""

    server_version = "gm-server"
    sys_version = ""
    service: Service

    def setup(self) -> None:
        self.timeout = self.service.config.socket_timeout_s
        super().setup()

    def do_GET(self) -> None:  # noqa: N802 - the name http.server dispatches on
        self._handle(send_body=True)

    def do_HEAD(self) -> None:  # noqa: N802
        self._handle(send_body=False)

    def do_POST(self) -> None:  # noqa: N802
        self._refuse_method()

    do_PUT = do_DELETE = do_PATCH = do_OPTIONS = do_POST

    def log_message(self, format: str, *args: object) -> None:
        """Silences the default access log, which would print raw request lines."""

    def _refuse_method(self) -> None:
        self._reply(405, headers={"Allow": "GET, HEAD"})

    def _handle(self, send_body: bool) -> None:
        service = self.service
        path = self.path.split("?", 1)[0]
        if path == HEALTH_PATH:
            self._reply(200, b"ok\n", send_body=send_body)
            return
        ip = self._client_ip()
        banned = service.limiter.ban_remaining(ip)
        if banned:
            self._reply(429, headers={"Retry-After": str(banned)})
            return
        identity = self._identify(ip)
        if identity is None:
            self._reply(401, headers={"WWW-Authenticate": 'Bearer realm="grassmaps"'})
            return
        wait = service.limiter.rate_retry_after(identity.key)
        if wait:
            self._reply(429, headers={"Retry-After": str(wait)}, who=identity.name)
            return
        self._serve(path, identity, send_body)

    def _serve(self, path: str, identity: Identity, send_body: bool) -> None:
        file = resolve(self.service.config.packs_dir, path)
        if file is None:
            self._reply(404, who=identity.name)
            return
        if not self.service.limiter.acquire(identity.key):
            self._reply(
                429, headers={"Retry-After": str(CONCURRENT_RETRY_S)}, who=identity.name
            )
            return
        try:
            self._send_file(file, identity, send_body)
        finally:
            self.service.limiter.release(identity.key)

    def _identify(self, ip: str) -> Identity | None:
        config = self.service.config
        if not config.require_tokens:
            key = ANONYMOUS_KEY if config.anon_key == "global" else f"ip:{ip}"
            return Identity(key, key, config.quota_bytes)
        header = self.headers.get("Authorization", "")
        if header[: len(BEARER_PREFIX)].lower() == BEARER_PREFIX:
            entry = self.service.tokens.lookup(header[len(BEARER_PREFIX) :].strip())
            if entry is not None:
                quota = entry.quota_bytes
                limit = config.quota_bytes if quota is None else quota
                return Identity(f"token:{entry.token_id}", entry.label, limit)
        self.service.limiter.record_failure(ip)
        return None

    def _client_ip(self) -> str:
        if self.service.config.trust_forwarded:
            forwarded = self.headers.get("X-Forwarded-For", "")
            key = ip_key(forwarded.split(",")[-1].strip())
            if key is not None:
                return key
        return ip_key(self.client_address[0]) or self.client_address[0]

    def _send_file(self, file: Path, identity: Identity, send_body: bool) -> None:
        try:
            handle = file.open("rb")
        except OSError:
            self._reply(404, who=identity.name)
            return
        with handle:
            size = file.stat().st_size
            selected = parse_range(self.headers.get("Range"), size)
            headers = {
                "Content-Type": content_type(file),
                "Accept-Ranges": "bytes",
            }
            if selected.kind is RangeKind.UNSATISFIABLE:
                headers["Content-Range"] = f"bytes */{size}"
                self._reply(416, headers=headers, who=identity.name)
                return
            status = 200
            if selected.kind is RangeKind.PARTIAL:
                status = 206
                headers["Content-Range"] = f"bytes {selected.first}-{selected.last}/{size}"
            length = selected.length
            headers["Content-Length"] = str(length)
            reserved = length if send_body else 0
            usage = self.service.usage
            if not usage.reserve(identity.key, reserved, identity.quota_bytes):
                retry = str(usage.seconds_to_next_month())
                self._reply(429, headers={"Retry-After": retry}, who=identity.name)
                return
            sent = 0
            try:
                self._start(status, headers)
                sent = self._stream(handle, selected.first, length) if send_body else 0
            finally:
                usage.settle(identity.key, reserved, sent)
            LOG.info("%s %s %s %s %d", identity.name, self.command, self.path, status, sent)

    def _stream(self, handle, first: int, length: int) -> int:  # noqa: ANN001
        handle.seek(first)
        sent = 0
        try:
            while sent < length:
                chunk = handle.read(min(CHUNK_BYTES, length - sent))
                if not chunk:
                    break
                self.wfile.write(chunk)
                sent += len(chunk)
        except (OSError, TimeoutError):
            LOG.info("connection dropped after %d of %d bytes", sent, length)
        return sent

    def _start(self, status: int, headers: dict[str, str]) -> None:
        self.send_response(status)
        for name, value in headers.items():
            self.send_header(name, value)
        self.send_header("Connection", "close")
        self.end_headers()

    def _reply(
        self,
        status: int,
        body: bytes = b"",
        headers: dict[str, str] | None = None,
        send_body: bool = True,
        who: str = "-",
    ) -> None:
        sent_headers = {"Content-Length": str(len(body)), **(headers or {})}
        self._start(status, sent_headers)
        if body and send_body:
            self.wfile.write(body)
        if status != 200:
            LOG.info("%s %s %s %d", who, self.command, self.path, status)


def create_server(config: Config) -> ThreadingHTTPServer:
    """Builds the HTTP server for [config]. The caller runs ``serve_forever``."""
    service = Service(config)
    handler = type("BoundHandler", (Handler,), {"service": service})
    server = ThreadingHTTPServer((config.host, config.port), handler)
    server.daemon_threads = True
    server.request_queue_size = 128
    server.service = service  # type: ignore[attr-defined]
    return server
