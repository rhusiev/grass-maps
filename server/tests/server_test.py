"""The server over real HTTP: protocol paths, tokens, ranges and limits."""

from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from support import CATALOG, MANIFEST, OUTLINE, PACKAGE, REGION, VERSION, RunningServer  # noqa: E402

PACKAGE_PATH = f"/{REGION}/{VERSION}/package.zip"


class ServingTest(unittest.TestCase):
    def setUp(self) -> None:
        self.server = RunningServer()
        self.addCleanup(self.server.close)

    def test_serves_catalog_manifest_outline_and_package(self) -> None:
        token = self.server.token
        self.assertEqual(self.server.request("/catalog.json", token=token).body, CATALOG)
        self.assertEqual(self.server.request("/outlines/ua-46.geojson", token=token).body, OUTLINE)
        manifest = self.server.request(f"/{REGION}/{VERSION}/manifest.json", token=token)
        self.assertEqual(manifest.body, MANIFEST)
        package = self.server.request(PACKAGE_PATH, token=token)
        self.assertEqual((package.status, package.body), (200, PACKAGE))
        self.assertEqual(package.headers["accept-ranges"], "bytes")
        self.assertEqual(package.headers["content-type"], "application/zip")

    def test_range_from_offset_answers_206_with_content_range(self) -> None:
        size = len(PACKAGE)
        response = self.server.request(
            PACKAGE_PATH, headers={"Range": "bytes=1000-"}, token=self.server.token
        )
        self.assertEqual(response.status, 206)
        self.assertEqual(response.headers["content-range"], f"bytes 1000-{size - 1}/{size}")
        self.assertEqual(response.body, PACKAGE[1000:])

    def test_bounded_and_suffix_ranges(self) -> None:
        size = len(PACKAGE)
        bounded = self.server.request(PACKAGE_PATH, headers={"Range": "bytes=10-19"}, token=self.server.token)
        self.assertEqual(bounded.headers["content-range"], f"bytes 10-19/{size}")
        self.assertEqual(bounded.body, PACKAGE[10:20])
        suffix = self.server.request(PACKAGE_PATH, headers={"Range": "bytes=-5"}, token=self.server.token)
        self.assertEqual(suffix.body, PACKAGE[-5:])

    def test_range_past_the_end_is_416_with_the_size(self) -> None:
        size = len(PACKAGE)
        response = self.server.request(
            PACKAGE_PATH, headers={"Range": f"bytes={size}-"}, token=self.server.token
        )
        self.assertEqual(response.status, 416)
        self.assertEqual(response.headers["content-range"], f"bytes */{size}")

    def test_head_has_length_and_no_body(self) -> None:
        response = self.server.request(PACKAGE_PATH, method="HEAD", token=self.server.token)
        self.assertEqual(response.status, 200)
        self.assertEqual(response.headers["content-length"], str(len(PACKAGE)))
        self.assertEqual(response.body, b"")

    def test_unknown_and_unsafe_paths_are_404_and_other_methods_405(self) -> None:
        token = self.server.token
        for path in ("/nope", "/ua-46/2026-11-03/secret.bin", "/../etc/passwd", "/ua-46/../catalog.json"):
            self.assertEqual(self.server.request(path, token=token).status, 404, path)
        self.assertEqual(self.server.request("/catalog.json", method="POST", token=token).status, 405)

    def test_health_needs_no_token(self) -> None:
        self.assertEqual(self.server.request("/healthz").status, 200)


class TokenTest(unittest.TestCase):
    def setUp(self) -> None:
        self.server = RunningServer(require_tokens=True)
        self.addCleanup(self.server.close)

    def test_missing_wrong_and_malformed_tokens_get_401(self) -> None:
        self.assertEqual(self.server.request(PACKAGE_PATH).status, 401)
        self.assertEqual(self.server.request(PACKAGE_PATH, token="wrong-token-0123456789").status, 401)
        basic = self.server.request(PACKAGE_PATH, headers={"Authorization": "Basic abc"})
        self.assertEqual(basic.status, 401)
        self.assertEqual(basic.headers["www-authenticate"], 'Bearer realm="grassmaps"')

    def test_401_comes_before_404_so_nothing_leaks(self) -> None:
        self.assertEqual(self.server.request("/ua-99/x/package.zip").status, 401)

    def test_a_valid_token_works_and_a_wrong_one_changes_nothing(self) -> None:
        self.server.request(PACKAGE_PATH, token="wrong-token-0123456789")
        self.assertEqual(self.server.request(PACKAGE_PATH, token=self.server.token).status, 200)

    def test_a_disabled_token_stops_on_the_next_request(self) -> None:
        from dataclasses import replace

        from gm_server.tokens import read_entries, write_entries

        self.assertEqual(self.server.request("/catalog.json", token=self.server.token).status, 200)
        entries = read_entries(self.server.tokens_file)
        write_entries(self.server.tokens_file, [replace(entry, enabled=False) for entry in entries])
        self.assertEqual(self.server.request("/catalog.json", token=self.server.token).status, 401)

    def test_repeated_failures_ban_the_ip_for_everything(self) -> None:
        server = RunningServer(ban_failures=3, ban_seconds=3600)
        self.addCleanup(server.close)
        for _ in range(3):
            self.assertEqual(server.request("/catalog.json", token="wrong-token-0123456789").status, 401)
        banned = server.request("/catalog.json", token=server.token)
        self.assertEqual(banned.status, 429)
        self.assertGreater(int(banned.headers["retry-after"]), 3000)


class TokensOffTest(unittest.TestCase):
    def setUp(self) -> None:
        self.server = RunningServer(require_tokens=False)
        self.addCleanup(self.server.close)

    def test_downloads_without_a_token(self) -> None:
        response = self.server.request(PACKAGE_PATH)
        self.assertEqual((response.status, response.body), (200, PACKAGE))

    def test_a_wrong_token_changes_nothing(self) -> None:
        response = self.server.request(PACKAGE_PATH, token="wrong-token-0123456789")
        self.assertEqual((response.status, response.body), (200, PACKAGE))


class LimitTest(unittest.TestCase):
    def test_request_rate_limit_answers_429_with_retry_after(self) -> None:
        server = RunningServer(rate_per_minute=3)
        self.addCleanup(server.close)
        statuses = [server.request("/catalog.json", token=server.token).status for _ in range(4)]
        self.assertEqual(statuses, [200, 200, 200, 429])
        limited = server.request("/catalog.json", token=server.token)
        self.assertTrue(1 <= int(limited.headers["retry-after"]) <= 60)

    def test_rate_limit_is_per_token_so_another_key_is_not_affected(self) -> None:
        from gm_server.tokens import new_entry, new_token, read_entries, write_entries

        server = RunningServer(rate_per_minute=1)
        self.addCleanup(server.close)
        other = new_token()
        write_entries(
            server.tokens_file,
            [*read_entries(server.tokens_file), new_entry(other, "other", None)],
        )
        self.assertEqual(server.request("/catalog.json", token=server.token).status, 200)
        self.assertEqual(server.request("/catalog.json", token=server.token).status, 429)
        self.assertEqual(server.request("/catalog.json", token=other).status, 200)

    def test_quota_blocks_after_the_bytes_are_used(self) -> None:
        server = RunningServer(quota_bytes=len(PACKAGE) + 10)
        self.addCleanup(server.close)
        self.assertEqual(server.request(PACKAGE_PATH, token=server.token).status, 200)
        self.assertEqual(server.request("/catalog.json", token=server.token).status, 200)
        blocked = server.request(PACKAGE_PATH, token=server.token)
        self.assertEqual(blocked.status, 429)
        self.assertGreater(int(blocked.headers["retry-after"]), 0)

    def test_a_ranged_download_counts_only_the_bytes_sent(self) -> None:
        server = RunningServer(quota_bytes=len(PACKAGE))
        self.addCleanup(server.close)
        half = len(PACKAGE) // 2
        self.assertEqual(
            server.request(PACKAGE_PATH, headers={"Range": f"bytes={half}-"}, token=server.token).status,
            206,
        )
        self.assertEqual(server.request(PACKAGE_PATH, token=server.token).status, 200)
        self.assertEqual(server.request(PACKAGE_PATH, token=server.token).status, 429)

    def test_tokens_off_quota_and_rate_apply_per_ip(self) -> None:
        server = RunningServer(require_tokens=False, rate_per_minute=2, quota_bytes=len(PACKAGE))
        self.addCleanup(server.close)
        self.assertEqual(server.request(PACKAGE_PATH).status, 200)
        self.assertEqual(server.request(PACKAGE_PATH).status, 429)

    def test_concurrency_cap_refuses_the_extra_download(self) -> None:
        server = RunningServer(max_concurrent=1)
        self.addCleanup(server.close)
        self.assertTrue(server.service.limiter.acquire("token:" + server.service.tokens.lookup(server.token).token_id))
        refused = server.request("/catalog.json", token=server.token)
        self.assertEqual(refused.status, 429)
        self.assertEqual(refused.headers["retry-after"], "5")

    def test_forwarded_ip_is_the_limit_key_when_trusted(self) -> None:
        server = RunningServer(require_tokens=False, rate_per_minute=1, trust_forwarded=True)
        self.addCleanup(server.close)
        first = {"X-Forwarded-For": "203.0.113.1"}
        second = {"X-Forwarded-For": "203.0.113.2"}
        self.assertEqual(server.request("/catalog.json", headers=first).status, 200)
        self.assertEqual(server.request("/catalog.json", headers=first).status, 429)
        self.assertEqual(server.request("/catalog.json", headers=second).status, 200)

    def test_global_key_shares_one_bucket_between_ips(self) -> None:
        server = RunningServer(require_tokens=False, rate_per_minute=1, anon_key="global", trust_forwarded=True)
        self.addCleanup(server.close)
        self.assertEqual(server.request("/catalog.json", headers={"X-Forwarded-For": "203.0.113.1"}).status, 200)
        self.assertEqual(server.request("/catalog.json", headers={"X-Forwarded-For": "203.0.113.2"}).status, 429)


if __name__ == "__main__":
    unittest.main()
