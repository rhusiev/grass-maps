"""Tests for the device smoke's pack install and post-frame assertions.

The fake `adb` answers each command from a scenario file, so these tests run
without a device and prove the script's control flow: the pack is installed
before the launch, a dead or restarted process after the first frame fails, a
captured composition error fails, and a fallback to the bundled asset fails.
"""

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "android-smoke.sh"
ROOT = SCRIPT.parent.parent.parent
REGION = "region:fixture-mini/2026-11-03-1"
STRICT_MODE = "I/GrassMapsStrictMode: StrictMode thread policy enabled"
FRAME = "I/T0020: first-full-frame-ms=2000"

FAKE_ADB = '''#!/usr/bin/env python3
import json
import os
import sys
from pathlib import Path

scenario_path = Path(os.environ["FAKE_ADB_SCENARIO"])
scenario = json.loads(scenario_path.read_text())
state_path = scenario_path.with_suffix(".state.json")
state = (
    json.loads(state_path.read_text())
    if state_path.exists()
    else {"pidof": 0, "samples": 0}
)
args = sys.argv[1:]
with open(os.environ["FAKE_ADB_CALLS"], "a", encoding="utf-8") as calls:
    calls.write(" ".join(args) + "\\n")
if args[:1] == ["-s"]:
    args = args[2:]
command = args[0] if args else ""

if command == "devices":
    print("List of devices attached")
    print("emulator-5554\\tdevice")
    sys.exit(0)
if command == "install":
    print("Success")
    sys.exit(0)
if command == "push":
    sys.exit(0)
if command == "logcat":
    if "-c" in args:
        sys.exit(0)
    if "-s" in args:
        print(scenario.get("map_log", ""), end="")
        sys.exit(0)
    if any(arg.startswith("--pid=") for arg in args):
        state["samples"] += 1
        state_path.write_text(json.dumps(state))
        key = "log_first" if state["samples"] == 1 else "log_after"
        print(scenario.get(key, ""), end="")
        sys.exit(0)
    print(scenario.get("full_log", ""), end="")
    sys.exit(0)
if command == "shell":
    if args[1:3] == ["getprop", "sys.boot_completed"]:
        print("1")
        sys.exit(0)
    if args[1:3] == ["getprop", "ro.build.version.sdk"]:
        print("29")
        sys.exit(0)
    if args[1:3] == ["pm", "path"]:
        print("package:/system/framework/framework-res.apk")
        sys.exit(0)
    if args[1:4] == ["pm", "list", "packages"]:
        print("package:nl.r1a.grassmap")
        sys.exit(0)
    if args[1:2] == ["pidof"]:
        state["pidof"] += 1
        pid = scenario.get("pid", "4242")
        if state["pidof"] > 1:
            pid = scenario.get("pid_after", pid)
        print(pid)
        state_path.write_text(json.dumps(state))
        sys.exit(0)
    sys.exit(0)
sys.exit(0)
'''


class SmokeTest(unittest.TestCase):
    def run_smoke(
        self, scenario: dict[str, str]
    ) -> tuple[subprocess.CompletedProcess[str], list[str]]:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            bin_dir = base / "bin"
            bin_dir.mkdir()
            fake = bin_dir / "adb"
            fake.write_text(FAKE_ADB, encoding="utf-8")
            fake.chmod(0o755)
            scenario_path = base / "scenario.json"
            scenario_path.write_text(json.dumps(scenario), encoding="utf-8")
            calls_path = base / "calls.txt"
            apk = base / "app-debug.apk"
            apk.write_bytes(b"apk")
            env = os.environ.copy()
            env["PATH"] = f"{bin_dir}:{env['PATH']}"
            env["FAKE_ADB_SCENARIO"] = str(scenario_path)
            env["FAKE_ADB_CALLS"] = str(calls_path)
            env["SMOKE_SETTLE_SECONDS"] = "0"
            env["SMOKE_FRAME_SETTLE_SECONDS"] = "0"
            env["SMOKE_MAP_READY_SECONDS"] = "5"
            env["SMOKE_FIRST_FRAME_SECONDS"] = "5"
            env["SMOKE_DEVICE_DEADLINE_SECONDS"] = "5"
            env["SMOKE_LOGCAT_CLEAR_ATTEMPTS"] = "1"
            env["SMOKE_LOGCAT_CLEAR_RETRY_SECONDS"] = "0"
            result = subprocess.run(
                ["bash", str(SCRIPT), str(apk)],
                cwd=ROOT,
                env=env,
                capture_output=True,
                text=True,
                timeout=120,
                check=False,
            )
            calls = calls_path.read_text(encoding="utf-8").splitlines()
        return result, calls

    def test_pack_is_installed_before_the_launch_and_the_map_passes(self) -> None:
        result, calls = self.run_smoke(
            {
                "map_log": f"I/GrassMapsMap: map-ready region={REGION} contours=true\n",
                "full_log": FRAME + "\n",
                "log_first": STRICT_MODE + "\n",
                "log_after": "",
            }
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(f"OK: the region pack is installed as {REGION}", result.stdout)
        self.assertIn("OK: the map reported its first full frame", result.stdout)
        pushed = next(
            index for index, call in enumerate(calls) if " push " in f" {call} "
        )
        launched = next(
            index
            for index, call in enumerate(calls)
            if call.startswith("shell am start")
        )
        self.assertLess(pushed, launched)

    def test_a_dead_process_after_the_first_frame_fails(self) -> None:
        result, _ = self.run_smoke(
            {
                "map_log": f"I/GrassMapsMap: map-ready region={REGION} contours=true\n",
                "full_log": FRAME + "\n",
                "log_first": STRICT_MODE + "\n",
                "pid_after": "",
            }
        )
        self.assertEqual(result.returncode, 1)
        self.assertIn("died within", result.stdout + result.stderr)

    def test_a_death_before_the_first_frame_is_named_without_waiting(self) -> None:
        # T-0512's shape: the renderer drew, but the applier crash killed the
        # process before the harness could report a full frame.
        result, _ = self.run_smoke(
            {
                "map_log": f"I/GrassMapsMap: map-ready region={REGION} contours=true\n",
                "full_log": "",
                "log_first": STRICT_MODE + "\n",
                "pid_after": "",
            }
        )
        self.assertEqual(result.returncode, 1)
        output = result.stdout + result.stderr
        self.assertIn("died before reporting a first full frame", output)

    def test_a_restart_after_the_first_frame_fails(self) -> None:
        result, _ = self.run_smoke(
            {
                "map_log": f"I/GrassMapsMap: map-ready region={REGION} contours=true\n",
                "full_log": FRAME + "\n",
                "log_first": STRICT_MODE + "\n",
                "pid_after": "9999",
            }
        )
        self.assertEqual(result.returncode, 1)
        output = result.stdout + result.stderr
        self.assertIn("restarted after its first full frame", output)

    def test_a_composition_error_after_the_first_frame_fails(self) -> None:
        result, _ = self.run_smoke(
            {
                "map_log": f"I/GrassMapsMap: map-ready region={REGION} contours=true\n",
                "full_log": FRAME + "\n",
                "log_first": STRICT_MODE + "\n",
                "log_after": (
                    "E ComposeInternal: Error was captured in composition.\n"
                    "E ComposeInternal: java.lang.ClassCastException: "
                    "androidx.compose.ui.node.LayoutNode cannot be cast to "
                    "org.maplibre.compose.style.MapNode\n"
                ),
            }
        )
        self.assertEqual(result.returncode, 1)
        self.assertIn("composition error", result.stdout + result.stderr)

    def test_a_fallback_to_the_bundled_asset_fails(self) -> None:
        result, _ = self.run_smoke(
            {
                "map_log": (
                    "I/GrassMapsMap: map-ready region=fixture-mini-map.pmtiles "
                    "contours=true\n"
                ),
                "full_log": FRAME + "\n",
                "log_first": STRICT_MODE + "\n",
            }
        )
        self.assertEqual(result.returncode, 1)
        output = result.stdout + result.stderr
        self.assertIn("did not open on the installed region pack", output)


if __name__ == "__main__":
    unittest.main()
