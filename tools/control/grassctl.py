#!/usr/bin/env python3
"""Command-line client for the Grass Maps debug control channel.

Examples:
    tools/control/grassctl.py commands
    tools/control/grassctl.py invoke example.set_value 11
    tools/control/grassctl.py subscribe example.value --count 3
    tools/control/grassctl.py screenshot /tmp/opencode/grass.png

The app must be a debug build and running. The script sets the `adb forward`
rule itself and reads the bearer token with `adb run-as`. It never prints the
token.
"""

from __future__ import annotations

import argparse
import base64
import json
import sys
from pathlib import Path

import control_client

DEFAULT_STREAM_SECONDS = 5.0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Drive the Grass Maps debug build through its control channel.",
    )
    parser.add_argument("--port", type=int, default=control_client.DEFAULT_PORT)
    parser.add_argument("--abstract", default=control_client.DEFAULT_ABSTRACT)
    parser.add_argument("--serial", default=None, help="adb device serial")
    parser.add_argument("--token", default=None, help="bearer token (prefer --token-file)")
    parser.add_argument("--token-file", default=None)
    parser.add_argument(
        "--no-forward",
        action="store_true",
        help="use an existing adb forward instead of setting one",
    )
    parser.add_argument("--timeout", type=float, default=20.0, help="connect timeout, seconds")
    parser.add_argument("--json", action="store_true", help="print raw JSON results")

    commands = parser.add_subparsers(dest="command", required=True)

    commands.add_parser("commands", help="list every command and its control policy")

    invoke = commands.add_parser("invoke", help="invoke one allowlisted command")
    invoke.add_argument("name")
    invoke.add_argument(
        "params",
        nargs="?",
        default=None,
        help="JSON parameters, for example 11 or {\"key\": true}",
    )

    subscribe = commands.add_parser("subscribe", help="print values from a stream")
    subscribe.add_argument("stream")
    subscribe.add_argument("--count", type=int, default=0, help="stop after N values; 0 means unlimited")
    subscribe.add_argument(
        "--seconds",
        type=float,
        default=DEFAULT_STREAM_SECONDS,
        help="stop after this many seconds even if fewer values arrived",
    )
    subscribe.add_argument(
        "--invoke",
        metavar="COMMAND",
        default=None,
        help="invoke this command on the same connection after subscribing",
    )
    subscribe.add_argument("--params", default=None, help="JSON parameters for --invoke")

    screenshot = commands.add_parser("screenshot", help="capture the app window as PNG")
    screenshot.add_argument("path")

    return parser


def open_client(args: argparse.Namespace) -> control_client.ControlClient:
    token = control_client.read_token(
        serial=args.serial,
        token=args.token,
        token_file=args.token_file,
    )
    if args.no_forward:
        return control_client.connect(port=args.port, token=token, timeout=args.timeout)
    return control_client.wait_for_channel(
        port=args.port,
        token=token,
        serial=args.serial,
        abstract=args.abstract,
        timeout_s=args.timeout,
    )


def parse_params(text: str | None) -> object:
    if text is None:
        return None
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        # A bare word is a string parameter, which JSON needs quoted.
        return text


def print_commands(commands: list[dict]) -> None:
    width = max((len(str(command.get("id", ""))) for command in commands), default=10)
    for command in commands:
        policy = "allowlisted" if command.get("allowlisted") else "blocked"
        switch = command.get("requiresSwitch")
        if switch:
            policy += f", needs {switch}"
        print(f"{str(command.get('id', '')):<{width}}  {policy:<24} {command.get('dispatcher', '')}")
        doc = command.get("doc")
        if doc:
            print(f"{'':<{width}}  {doc}")
        print(f"{'':<{width}}  plugin: {command.get('plugin', '')}")


def print_notifications(
    client: control_client.ControlClient,
    stream: str,
    count: int,
    seconds: float,
    invoke: str | None,
    invoke_params: object,
) -> int:
    import time

    subscription = client.subscribe(stream)
    if invoke:
        print(f"invoked {invoke}: {client.invoke(invoke, invoke_params)}", file=sys.stderr)
    printed = 0
    deadline = time.monotonic() + seconds
    try:
        while time.monotonic() < deadline:
            if count and printed >= count:
                break
            client.set_timeout(max(0.1, deadline - time.monotonic()))
            try:
                frame = client.next_notification()
            except TimeoutError:
                break
            params = frame.get("params")
            if not isinstance(params, dict) or params.get("subscription") != subscription:
                continue
            print(json.dumps(params.get("value"), separators=(",", ":")))
            printed += 1
    finally:
        client.set_timeout(control_client.CONNECT_TIMEOUT_S)
        client.unsubscribe(subscription)
    return printed


def save_screenshot(client: control_client.ControlClient, path: str) -> int:
    result = client.screenshot()
    encoded = result.get("base64")
    if not isinstance(encoded, str) or not encoded:
        print("screenshot: the app returned no image bytes", file=sys.stderr)
        return 1
    data = base64.b64decode(encoded, validate=True)
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    print(f"{target}  {len(data)} bytes  source={result.get('source', 'unknown')}")
    return 0


def run(args: argparse.Namespace) -> int:
    with open_client(args) as client:
        if args.command == "commands":
            commands = client.list_commands()
            if args.json:
                print(json.dumps(commands, indent=2))
            else:
                print_commands(commands)
            return 0
        if args.command == "invoke":
            value = client.invoke(args.name, parse_params(args.params))
            print(json.dumps(value, separators=(",", ":")) if args.json else value)
            return 0
        if args.command == "subscribe":
            printed = print_notifications(
                client,
                args.stream,
                args.count,
                args.seconds,
                args.invoke,
                parse_params(args.params),
            )
            if printed == 0:
                print("subscribe: no values arrived", file=sys.stderr)
                return 1
            return 0
        if args.command == "screenshot":
            return save_screenshot(client, args.path)
    return 2


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return run(args)
    except control_client.ControlError as error:
        print(f"grassctl: {error}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
