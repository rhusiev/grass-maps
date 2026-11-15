#!/usr/bin/env python3
"""Fixture tests for the per-module unit test floor in test-count.sh.

The script reads Gradle's XML reports, so each test builds a throwaway tree with
the report counts it wants and runs the real script against it.
"""

import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "test-count.sh"
BASELINE = "test-count-baseline.txt"


class TestCountScriptTest(unittest.TestCase):
    def setUp(self) -> None:
        self._temp = tempfile.TemporaryDirectory()
        self.addCleanup(self._temp.cleanup)
        self.root = Path(self._temp.name)
        self.baseline = self.root / BASELINE

    def add_module(self, name: str, tests: int, sources: int = 1) -> None:
        test_dir = self.root / name / "src" / "test" / "kotlin"
        test_dir.mkdir(parents=True, exist_ok=True)
        for index in range(sources):
            (test_dir / f"Case{index}.kt").write_text("class Case\n", encoding="utf-8")
        if tests == 0:
            return
        results = self.root / name / "build" / "test-results" / "testDebugUnitTest"
        results.mkdir(parents=True, exist_ok=True)
        (results / "TEST-Case.xml").write_text(
            f'<testsuite name="Case" tests="{tests}" skipped="0" failures="0"/>\n',
            encoding="utf-8",
        )

    def write_baseline(self, rows: dict[str, int]) -> None:
        body = "".join(f"{module}\t{count}\n" for module, count in sorted(rows.items()))
        self.baseline.write_text(body, encoding="utf-8")

    def run_script(self, *args: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                "bash",
                str(SCRIPT),
                "--root",
                str(self.root),
                "--baseline",
                str(self.baseline),
                *args,
            ],
            capture_output=True,
            text=True,
            check=False,
        )

    def test_module_dropping_from_120_tests_to_3_fails(self) -> None:
        self.add_module("module-a", tests=3)
        self.write_baseline({"module-a": 120})

        result = self.run_script()

        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("module-a ran 3 tests, below the recorded 120", result.stderr)

    def test_module_keeping_its_recorded_count_passes(self) -> None:
        self.add_module("module-a", tests=120)
        self.add_module("module-b", tests=3)
        self.write_baseline({"module-a": 120, "module-b": 3})

        result = self.run_script()

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("UNIT TESTS: 123 executed in 2 report files.", result.stdout)

    def test_growth_above_the_recorded_count_passes_and_is_noted(self) -> None:
        self.add_module("module-a", tests=121)
        self.write_baseline({"module-a": 120})

        result = self.run_script()

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("module-a ran 121 tests, above the recorded 120", result.stdout)

    def test_module_with_tests_but_no_baseline_row_fails(self) -> None:
        self.add_module("module-a", tests=4)
        self.write_baseline({"module-b": 4})
        self.add_module("module-b", tests=4)

        result = self.run_script()

        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("module-a has test sources but no test count baseline row", result.stderr)

    def test_baseline_row_without_test_sources_fails(self) -> None:
        self.add_module("module-a", tests=4)
        self.write_baseline({"module-a": 4, "module-gone": 9})

        result = self.run_script()

        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("module-gone is in the test count baseline", result.stderr)

    def test_module_with_test_sources_and_no_tests_fails(self) -> None:
        self.add_module("module-a", tests=4)
        self.add_module("module-b", tests=0)
        self.write_baseline({"module-a": 4, "module-b": 1})

        result = self.run_script()

        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("module-b has test sources but ran no tests", result.stderr)

    def test_update_rewrites_the_baseline_from_the_reports(self) -> None:
        self.add_module("module-a", tests=7)
        self.add_module("module-b", tests=2)
        self.write_baseline({"module-a": 120})

        result = self.run_script("--update")

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(
            self.baseline.read_text(encoding="utf-8"),
            "module-a\t7\nmodule-b\t2\n",
        )

    def test_missing_baseline_file_fails_with_the_update_command(self) -> None:
        self.add_module("module-a", tests=7)

        result = self.run_script()

        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("test-count.sh --update", result.stderr)


if __name__ == "__main__":
    sys.exit(unittest.main())
