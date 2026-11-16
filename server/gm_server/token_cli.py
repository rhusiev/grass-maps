"""Command line for the token file: ``gm-token create|list|disable|enable|delete``."""

from __future__ import annotations

import argparse
import os
import sys
from dataclasses import replace
from pathlib import Path

from gm_server.config import GIB
from gm_server.tokens import (
    TokenEntry,
    TokenFileError,
    new_entry,
    new_token,
    read_entries,
    write_entries,
)

DEFAULT_TOKENS_FILE = "/config/tokens.json"


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="gm-token", description=__doc__)
    parser.add_argument(
        "--file",
        type=Path,
        default=Path(os.environ.get("GM_TOKENS_FILE", DEFAULT_TOKENS_FILE)),
        help="token file (default $GM_TOKENS_FILE or %(default)s)",
    )
    commands = parser.add_subparsers(dest="command", required=True)
    create = commands.add_parser("create", help="make a token and print it once")
    create.add_argument("--label", required=True, help='who it is for, e.g. "my phone"')
    create.add_argument("--quota-gib", type=int, help="monthly quota for this token")
    commands.add_parser("list", help="list tokens without their secrets")
    for name in ("disable", "enable", "delete"):
        commands.add_parser(name, help=f"{name} a token by its id").add_argument("token_id")
    return parser


def _print_table(entries: list[TokenEntry]) -> None:
    print(f"{'ID':<10}{'ENABLED':<9}{'QUOTA GiB':<11}{'CREATED':<22}LABEL")
    for entry in entries:
        quota = "default" if entry.quota_bytes is None else str(entry.quota_bytes // GIB)
        print(f"{entry.token_id:<10}{str(entry.enabled).lower():<9}{quota:<11}{entry.created:<22}{entry.label}")


def _change(entries: list[TokenEntry], token_id: str, command: str) -> list[TokenEntry] | None:
    if not any(entry.token_id == token_id for entry in entries):
        return None
    if command == "delete":
        return [entry for entry in entries if entry.token_id != token_id]
    enabled = command == "enable"
    return [replace(entry, enabled=enabled) if entry.token_id == token_id else entry for entry in entries]


def main(argv: list[str] | None = None) -> int:
    """Runs one command. Returns the process exit code."""
    args = _parser().parse_args(argv)
    try:
        entries = read_entries(args.file)
    except TokenFileError as exc:
        print(f"gm-token: {exc}", file=sys.stderr)
        return 1
    if args.command == "list":
        _print_table(entries)
        return 0
    if args.command == "create":
        token = new_token()
        quota = None if args.quota_gib is None else args.quota_gib * GIB
        entry = new_entry(token, args.label, quota)
        write_entries(args.file, [*entries, entry])
        print(f"id: {entry.token_id}\nlabel: {entry.label}\ntoken (shown once): {token}")
        return 0
    changed = _change(entries, args.token_id, args.command)
    if changed is None:
        print(f"gm-token: no token with id {args.token_id}", file=sys.stderr)
        return 1
    write_entries(args.file, changed)
    return 0


if __name__ == "__main__":
    sys.exit(main())
