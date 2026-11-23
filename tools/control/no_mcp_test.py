#!/usr/bin/env python3
"""Guard that the MCP bridge stays out of the control tooling and its docs.

The JSON-RPC channel, `adb forward`, `control_client.py`, the command bus and
the auth and allowlist code stay; only the MCP bridge and its documentation
are removed.
"""

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MCP_SERVER = ROOT / "tools" / "control" / "mcp_server.py"
ROOT_README = ROOT / "README.md"
CONTROL_README = ROOT / "tools" / "control" / "README.md"
KEPT_MECHANICS = (
    "tools/control/control_client.py",
    "tools/control/grassctl.py",
    "control/src/main/java/nl/r1a/grassmaps/control/Frames.kt",
)


class NoMcpTest(unittest.TestCase):
    def assert_no_mcp(self, path: Path) -> None:
        lines = path.read_text(encoding="utf-8").splitlines()
        matches = [line.strip() for line in lines if "mcp" in line.lower()]
        self.assertEqual(matches, [], f"{path.name} still mentions MCP: {matches}")

    def test_mcp_server_file_is_absent(self) -> None:
        self.assertFalse(MCP_SERVER.exists(), f"{MCP_SERVER} still exists")

    def test_root_readme_has_no_mcp_reference(self) -> None:
        self.assert_no_mcp(ROOT_README)

    def test_control_readme_has_no_mcp_reference(self) -> None:
        self.assert_no_mcp(CONTROL_README)

    def test_programmatic_control_mechanics_are_kept(self) -> None:
        for relative in KEPT_MECHANICS:
            self.assertTrue((ROOT / relative).is_file(), f"{relative} is missing")


if __name__ == "__main__":
    sys.exit(unittest.main())
