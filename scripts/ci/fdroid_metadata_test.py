#!/usr/bin/env python3
"""Fixture tests for the F-Droid app metadata in fdroid/metadata.

The metadata is read as text so a field with the wrong key, a split signing key
or a missing changelog file fails here before it reaches the submission.
"""

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
METADATA = ROOT / "fdroid" / "metadata" / "nl.r1a.grassmaps.yml"

SOURCE_URL = "https://github.com/rhusiev/grass-maps"
APK_NAME = "grassmaps-0.1.0-alpha.2.apk"
BINARY_URL = f"{SOURCE_URL}/releases/download/0.1.0-alpha.2/{APK_NAME}"
SIGNING_KEY = "3fc003c4efbfb379b9c67b593dd9f9ab797f395473a0a2b64fc2425431f51ff0"
REQUIRED_FIELDS = (
    "Summary",
    "Description",
    "License",
    "AuthorName",
    "SourceCode",
    "IssueTracker",
    "Categories",
    "Changelog",
    "Binaries",
    "AllowedAPKSigningKeys",
)


def field_block(text: str, field: str) -> list[str] | None:
    """Return the value lines under a top-level field, or None if absent."""
    lines = text.splitlines()
    for index, line in enumerate(lines):
        if not line.startswith(f"{field}:"):
            continue
        remainder = line[len(field) + 1 :].strip()
        if remainder and not remainder.startswith("|"):
            return [remainder]
        block: list[str] = []
        for continuation in lines[index + 1 :]:
            if continuation and not continuation.startswith((" ", "\t")):
                break
            block.append(continuation.strip())
        return block
    return None


class FdroidMetadataTest(unittest.TestCase):
    def setUp(self) -> None:
        if not METADATA.is_file():
            self.fail(f"missing F-Droid metadata: {METADATA.relative_to(ROOT)}")
        self.text = METADATA.read_text(encoding="utf-8")

    def require_block(self, field: str) -> list[str]:
        block = field_block(self.text, field)
        if block is None:
            self.fail(f"missing {field} field")
        return [line.removeprefix("- ") for line in block if line]

    def test_all_required_fields_are_present(self) -> None:
        for field in REQUIRED_FIELDS:
            with self.subTest(field=field):
                self.assertRegex(self.text, rf"(?m)^{field}:", f"missing {field}")

    def test_license_and_author(self) -> None:
        self.assertEqual(self.require_block("License"), ["Apache-2.0"])
        self.assertEqual(self.require_block("AuthorName"), ["rhusiev"])

    def test_project_urls(self) -> None:
        self.assertEqual(self.require_block("SourceCode"), [SOURCE_URL])
        self.assertEqual(self.require_block("IssueTracker"), [f"{SOURCE_URL}/issues"])

    def test_summary_is_short(self) -> None:
        summary = " ".join(self.require_block("Summary"))
        self.assertGreater(len(summary), 0)
        self.assertLessEqual(len(summary), 80)

    def test_description_has_several_paragraphs(self) -> None:
        paragraphs = [
            line
            for line in (field_block(self.text, "Description") or [])
            if line.strip()
        ]
        self.assertGreaterEqual(len(paragraphs), 3)

    def test_categories_are_listed(self) -> None:
        self.assertTrue(self.require_block("Categories"))

    def test_changelog_is_a_committed_file_for_the_alpha(self) -> None:
        changelog = self.require_block("Changelog")
        self.assertEqual(len(changelog), 1)
        self.assertEqual(changelog[0], "changelog/1.txt")
        path = METADATA.parent / changelog[0]
        self.assertTrue(path.is_file(), f"missing changelog file: {path}")
        self.assertIn("0.1.0-alpha.2", path.read_text(encoding="utf-8"))

    def test_binaries_point_at_the_published_apk(self) -> None:
        self.assertEqual(self.require_block("Binaries"), [BINARY_URL])

    def test_signing_key_is_one_complete_line(self) -> None:
        self.assertEqual(self.require_block("AllowedAPKSigningKeys"), [SIGNING_KEY])
        self.assertEqual(self.text.count(SIGNING_KEY), 1)


if __name__ == "__main__":
    unittest.main()
