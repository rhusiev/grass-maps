#!/usr/bin/env python3
"""Host-side client for the Grass Maps debug control channel.

The channel is a Linux abstract socket inside the app process. `adb forward`
maps a host TCP port onto it, so nothing leaves the machine. The token is read
from the app's private file with `adb run-as` or from `--token-file`; it is
never printed.
"""

from __future__ import annotations

import json
import shutil
import socket
import subprocess
import time

DEFAULT_PORT = 4599
DEFAULT_ABSTRACT = "nl.r1a.grassmap.control"
PACKAGE = "nl.r1a.grassmap"
TOKEN_FILE_IN_APP = "files/control-token"
MAX_FRAME_BYTES = 1 << 20
ADB_TIMEOUT_S = 30
CONNECT_TIMEOUT_S = 10


class ControlError(RuntimeError):
    """A refusal or transport failure, carrying the typed RPC kind when present."""

    def __init__(self, code: int, message: str, data: dict | None = None) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.data = data or {}

    @property
    def kind(self) -> str:
        return str(self.data.get("kind", "transport"))

    def __str__(self) -> str:
        detail = self.data.get("kind")
        suffix = f" [{detail}]" if detail else ""
        return f"{self.message} (code {self.code}){suffix}"


def adb(args: list[str], serial: str | None = None) -> subprocess.CompletedProcess[str]:
    if shutil.which("adb") is None:
        raise ControlError(-1, "adb is not on PATH")
    command = ["adb"] + (["-s", serial] if serial else []) + args
    try:
        return subprocess.run(
            command,
            capture_output=True,
            text=True,
            timeout=ADB_TIMEOUT_S,
            check=False,
        )
    except subprocess.TimeoutExpired as error:
        raise ControlError(-1, f"adb timed out: {' '.join(command)}") from error


def ensure_forward(
    port: int = DEFAULT_PORT,
    abstract: str = DEFAULT_ABSTRACT,
    serial: str | None = None,
) -> None:
    """Point host port `port` at the app's abstract socket. Safe to repeat."""
    adb(["forward", "--remove", f"tcp:{port}"], serial)
    result = adb(["forward", f"tcp:{port}", f"localabstract:{abstract}"], serial)
    if result.returncode != 0:
        raise ControlError(-1, f"adb forward failed: {result.stderr.strip()}")


def read_token(
    serial: str | None = None,
    token: str | None = None,
    token_file: str | None = None,
) -> str:
    """Read the bearer token. The value is never logged."""
    if token:
        return token.strip()
    if token_file:
        try:
            with open(token_file, encoding="utf-8") as handle:
                return handle.read().strip()
        except OSError as error:
            raise ControlError(-1, f"cannot read token file: {error.strerror}") from error
    result = adb(["exec-out", "run-as", PACKAGE, "cat", TOKEN_FILE_IN_APP], serial)
    value = result.stdout.strip()
    if result.returncode != 0 or not value:
        raise ControlError(
            -1,
            "cannot read the token: the app must be a debug build and running, "
            "or pass --token-file",
        )
    return value


class ControlClient:
    """One authenticated JSON-RPC 2.0 connection, newline-framed."""

    def __init__(self, sock: socket.socket) -> None:
        self._socket = sock
        self._buffer = b""
        self._queued: list[dict] = []
        self._next_id = 1

    def close(self) -> None:
        self._socket.close()

    def __enter__(self) -> "ControlClient":
        return self

    def __exit__(self, *_: object) -> None:
        self.close()

    def set_timeout(self, seconds: float) -> None:
        self._socket.settimeout(seconds)

    def _send(self, payload: dict) -> None:
        frame = json.dumps(payload, separators=(",", ":")).encode("utf-8") + b"\n"
        if len(frame) > MAX_FRAME_BYTES:
            raise ControlError(-1, "request frame exceeds the size limit")
        self._socket.sendall(frame)

    def read_frame(self) -> dict:
        """Read one frame. A socket timeout leaves the connection usable."""
        while b"\n" not in self._buffer:
            chunk = self._socket.recv(65536)
            if not chunk:
                raise ControlError(-1, "the app closed the connection")
            self._buffer += chunk
            if len(self._buffer) > MAX_FRAME_BYTES:
                raise ControlError(-1, "frame exceeds the size limit")
        line, self._buffer = self._buffer.split(b"\n", 1)
        try:
            frame = json.loads(line)
        except json.JSONDecodeError as error:
            raise ControlError(-32700, f"malformed frame from the app: {error}") from error
        if not isinstance(frame, dict):
            raise ControlError(-32600, "the app sent a non-object frame")
        return frame

    def next_notification(self) -> dict:
        """Return the next frame, including notifications queued during a request."""
        if self._queued:
            return self._queued.pop(0)
        return self.read_frame()

    def request(self, method: str, params: dict | None = None) -> object:
        request_id = self._next_id
        self._next_id += 1
        self._send(
            {
                "jsonrpc": "2.0",
                "id": request_id,
                "method": method,
                "params": params or {},
            }
        )
        while True:
            frame = self.read_frame()
            if frame.get("id") != request_id:
                # A stream notification may arrive while a request is in flight.
                self._queued.append(frame)
                continue
            error = frame.get("error")
            if error:
                raise ControlError(
                    int(error.get("code", -32603)),
                    str(error.get("message", "request failed")),
                    error.get("data") if isinstance(error.get("data"), dict) else None,
                )
            return frame.get("result")

    def authenticate(self, token: str) -> None:
        self.request("auth", {"token": token})

    def list_commands(self) -> list[dict]:
        result = self.request("control.commands.list")
        commands = result.get("commands", []) if isinstance(result, dict) else []
        return [command for command in commands if isinstance(command, dict)]

    def invoke(self, command: str, params: object = None) -> object:
        return self.request("control.command.invoke", {"command": command, "params": params})

    def subscribe(self, stream: str) -> int:
        result = self.request("control.stream.subscribe", {"stream": stream})
        return int(result["subscription"]) if isinstance(result, dict) else 0

    def unsubscribe(self, subscription: int) -> None:
        self.request("control.stream.unsubscribe", {"subscription": subscription})

    def screenshot(self) -> dict:
        result = self.request("devtools.screenshot")
        return result if isinstance(result, dict) else {}


def connect(
    port: int = DEFAULT_PORT,
    token: str = "",
    host: str = "127.0.0.1",
    timeout: float = CONNECT_TIMEOUT_S,
) -> ControlClient:
    """Open one connection and authenticate it."""
    try:
        sock = socket.create_connection((host, port), timeout=timeout)
    except OSError as error:
        raise ControlError(
            -1,
            f"cannot reach 127.0.0.1:{port}: {error.strerror}. "
            "Is `adb forward` set and the app running?",
        ) from error
    sock.settimeout(timeout)
    client = ControlClient(sock)
    try:
        client.authenticate(token)
    except ControlError:
        client.close()
        raise
    return client


def wait_for_channel(
    port: int = DEFAULT_PORT,
    token: str = "",
    serial: str | None = None,
    abstract: str = DEFAULT_ABSTRACT,
    timeout_s: float = 20.0,
) -> ControlClient:
    """Forward and connect, retrying while the app finishes starting."""
    ensure_forward(port=port, abstract=abstract, serial=serial)
    deadline = time.monotonic() + timeout_s
    last: ControlError | None = None
    while time.monotonic() < deadline:
        try:
            return connect(port=port, token=token)
        except ControlError as error:
            if error.kind == "unauthorized":
                # A wrong token will not fix itself; report it at once.
                raise
            last = error
            time.sleep(0.5)
    raise last or ControlError(-1, "the control channel did not answer")
