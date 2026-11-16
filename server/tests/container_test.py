"""Builds the image and runs the packs through a real container.

Opt-in: set GM_CONTAINER_TEST=1. It needs podman or docker and network access
to pull the Python base image once. The container gets the same mounts and
hardening flags as docker-compose.yml, minus the external ``caddy`` network.
"""

from __future__ import annotations

import http.client
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from gm_server.tokens import new_entry, new_token, write_entries  # noqa: E402
from support import PACKAGE, REGION, SERVER_DIR, VERSION, write_packs  # noqa: E402

ENGINE = shutil.which("podman") or shutil.which("docker")
START_TIMEOUT_S = 30


def free_port() -> int:
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def get(port: int, path: str, headers: dict[str, str]) -> tuple[int, dict[str, str], bytes]:
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
    try:
        connection.request("GET", path, headers=headers)
        response = connection.getresponse()
        return response.status, dict(response.getheaders()), response.read()
    finally:
        connection.close()


@unittest.skipUnless(ENGINE and os.environ.get("GM_CONTAINER_TEST") == "1", "set GM_CONTAINER_TEST=1")
class ContainerTest(unittest.TestCase):
    def run_engine(self, *args: str) -> str:
        result = subprocess.run([ENGINE, *args], capture_output=True, text=True, check=False)
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout

    def test_image_serves_packs_read_only_with_and_without_tokens(self) -> None:
        image = f"grassmaps-server-test:{uuid.uuid4().hex[:8]}"
        self.run_engine("build", "-q", "-t", image, str(SERVER_DIR))
        self.addCleanup(subprocess.run, [ENGINE, "rmi", "-f", image], capture_output=True)
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            write_packs(base / "packs")
            (base / "state").mkdir()
            for name in ("state", "config", "packs"):
                (base / name).mkdir(exist_ok=True)
                os.chmod(base / name, 0o777 if name != "packs" else 0o755)
            token = new_token()
            write_entries(base / "config" / "tokens.json", [new_entry(token, "t", None)])
            os.chmod(base / "config" / "tokens.json", 0o644)
            self.check_mode(image, base, "1", token)
            self.check_mode(image, base, "0", token)

    def check_mode(self, image: str, base: Path, require_tokens: str, token: str) -> None:
        port = free_port()
        name = f"gm-test-{uuid.uuid4().hex[:8]}"
        self.run_engine(
            "run", "-d", "--name", name, "-p", f"127.0.0.1:{port}:8080",
            "--read-only", "--tmpfs", "/tmp", "--cap-drop", "ALL",
            "--security-opt", "no-new-privileges",
            "-e", f"GM_REQUIRE_TOKENS={require_tokens}",
            "-v", f"{base / 'packs'}:/packs:ro,z", "-v", f"{base / 'config'}:/config:z",
            "-v", f"{base / 'state'}:/state:z", image,
        )
        self.addCleanup(subprocess.run, [ENGINE, "rm", "-f", name], capture_output=True)
        self.wait_ready(port)
        path = f"/{REGION}/{VERSION}/package.zip"
        bearer = {"Authorization": f"Bearer {token}"}
        wrong = {"Authorization": "Bearer wrong-token-0123456789"}
        if require_tokens == "1":
            self.assertEqual(get(port, path, {})[0], 401)
            self.assertEqual(get(port, path, wrong)[0], 401)
        else:
            self.assertEqual(get(port, path, wrong)[0], 200)
        status, _, body = get(port, path, bearer)
        self.assertEqual((status, body), (200, PACKAGE))
        status, headers, body = get(port, path, {**bearer, "Range": "bytes=100-"})
        self.assertEqual(status, 206)
        self.assertEqual(headers["Content-Range"], f"bytes 100-{len(PACKAGE) - 1}/{len(PACKAGE)}")
        listing = self.run_engine("exec", name, "gm-token", "list")
        self.assertIn("t", listing)
        self.run_engine("stop", "-t", "5", name)
        self.assertTrue((base / "state" / "usage.json").is_file(), "usage flushed on SIGTERM")
        (base / "state" / "usage.json").unlink()

    def wait_ready(self, port: int) -> None:
        deadline = time.monotonic() + START_TIMEOUT_S
        while time.monotonic() < deadline:
            try:
                if get(port, "/healthz", {})[0] == 200:
                    return
            except OSError:
                time.sleep(0.2)
        self.fail("container did not become ready")


if __name__ == "__main__":
    unittest.main()
