#!/usr/bin/env python3
"""Minimal MCP server that bridges stdio JSON-RPC to the Grass Maps control channel.

The channel stays local: every tool call opens a connection over the host's
`adb forward` rule, so the app never listens on a network interface. Run it from
an MCP client configuration as:

    python3 tools/control/mcp_server.py

Configuration comes from the environment: GRASSMAPS_CONTROL_PORT,
GRASSMAPS_CONTROL_ABSTRACT, GRASSMAPS_CONTROL_SERIAL, GRASSMAPS_CONTROL_TOKEN_FILE.
"""

from __future__ import annotations

import base64
import json
import os
import sys
from typing import Any

import control_client

PROTOCOL_VERSION = "2024-11-05"
SERVER_NAME = "grassmaps-control"
SERVER_VERSION = "0.1.0"
MAX_STREAM_VALUES = 20
STREAM_TIMEOUT_S = 5.0

TOOLS: list[dict[str, Any]] = [
    {
        "name": "grassmaps_list_commands",
        "description": "List every command the app declares, with plugin, doc and control policy.",
        "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
    {
        "name": "grassmaps_invoke_command",
        "description": "Invoke one command. Only commands allowlisted for control run; others are refused.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "command": {"type": "string"},
                "params": {"description": "JSON parameters for the command"},
            },
            "required": ["command"],
            "additionalProperties": False,
        },
    },
    {
        "name": "grassmaps_screenshot",
        "description": "Capture the current app window as a PNG image.",
        "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
    {
        "name": "grassmaps_read_stream",
        "description": "Subscribe to a stream and return a bounded number of values.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "stream": {"type": "string"},
                "count": {"type": "integer", "minimum": 1, "maximum": MAX_STREAM_VALUES},
            },
            "required": ["stream"],
            "additionalProperties": False,
        },
    },
]


def settings() -> dict[str, Any]:
    return {
        "port": int(os.environ.get("GRASSMAPS_CONTROL_PORT", control_client.DEFAULT_PORT)),
        "abstract": os.environ.get("GRASSMAPS_CONTROL_ABSTRACT", control_client.DEFAULT_ABSTRACT),
        "serial": os.environ.get("GRASSMAPS_CONTROL_SERIAL") or None,
        "token_file": os.environ.get("GRASSMAPS_CONTROL_TOKEN_FILE") or None,
    }


def open_client() -> control_client.ControlClient:
    config = settings()
    token = control_client.read_token(serial=config["serial"], token_file=config["token_file"])
    return control_client.wait_for_channel(
        port=config["port"],
        token=token,
        serial=config["serial"],
        abstract=config["abstract"],
    )


def text_result(text: str) -> dict[str, Any]:
    return {"content": [{"type": "text", "text": text}]}


def call_tool(name: str, arguments: dict[str, Any]) -> dict[str, Any]:
    with open_client() as client:
        if name == "grassmaps_list_commands":
            return text_result(json.dumps(client.list_commands(), indent=2))
        if name == "grassmaps_invoke_command":
            command = arguments.get("command")
            if not isinstance(command, str) or not command:
                raise control_client.ControlError(-32602, "command is required")
            value = client.invoke(command, arguments.get("params"))
            return text_result(json.dumps(value, separators=(",", ":")))
        if name == "grassmaps_screenshot":
            result = client.screenshot()
            encoded = result.get("base64")
            if not isinstance(encoded, str) or not encoded:
                raise control_client.ControlError(-32603, "the app returned no image bytes")
            base64.b64decode(encoded, validate=True)
            return {
                "content": [
                    {"type": "image", "data": encoded, "mimeType": "image/png"},
                    {
                        "type": "text",
                        "text": f"capture source: {result.get('source', 'unknown')}",
                    },
                ]
            }
        if name == "grassmaps_read_stream":
            stream = arguments.get("stream")
            if not isinstance(stream, str) or not stream:
                raise control_client.ControlError(-32602, "stream is required")
            count = min(int(arguments.get("count", 1)), MAX_STREAM_VALUES)
            values = read_stream(client, stream, count)
            return text_result(json.dumps(values, separators=(",", ":")))
        raise control_client.ControlError(-32601, f"unknown tool: {name}")


def read_stream(
    client: control_client.ControlClient,
    stream: str,
    count: int,
) -> list[Any]:
    import time

    subscription = client.subscribe(stream)
    values: list[Any] = []
    deadline = time.monotonic() + STREAM_TIMEOUT_S
    try:
        while len(values) < count and time.monotonic() < deadline:
            client.set_timeout(max(0.1, deadline - time.monotonic()))
            try:
                frame = client.next_notification()
            except TimeoutError:
                break
            params = frame.get("params")
            if isinstance(params, dict) and params.get("subscription") == subscription:
                values.append(params.get("value"))
    finally:
        client.set_timeout(control_client.CONNECT_TIMEOUT_S)
        client.unsubscribe(subscription)
    return values


def handle(request: dict[str, Any]) -> dict[str, Any] | None:
    method = request.get("method")
    request_id = request.get("id")
    if request_id is None and method != "initialize":
        return None
    try:
        if method == "initialize":
            result: Any = {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {"tools": {}},
                "serverInfo": {"name": SERVER_NAME, "version": SERVER_VERSION},
            }
        elif method == "ping":
            result = {}
        elif method == "tools/list":
            result = {"tools": TOOLS}
        elif method == "tools/call":
            params = request.get("params") or {}
            result = call_tool(params.get("name", ""), params.get("arguments") or {})
        else:
            raise control_client.ControlError(-32601, f"unknown method: {method}")
        return {"jsonrpc": "2.0", "id": request_id, "result": result}
    except control_client.ControlError as error:
        return {
            "jsonrpc": "2.0",
            "id": request_id,
            "error": {
                "code": error.code,
                "message": error.message,
                "data": error.data,
            },
        }


def main() -> int:
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            request = json.loads(line)
        except json.JSONDecodeError:
            response: dict[str, Any] = {
                "jsonrpc": "2.0",
                "id": None,
                "error": {"code": -32700, "message": "malformed JSON"},
            }
        else:
            response = handle(request) if isinstance(request, dict) else {
                "jsonrpc": "2.0",
                "id": None,
                "error": {"code": -32600, "message": "invalid request"},
            }
            if response is None:
                continue
        sys.stdout.write(json.dumps(response, separators=(",", ":")) + "\n")
        sys.stdout.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
