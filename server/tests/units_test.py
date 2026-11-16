"""Unit tests: range parsing, limiter clock, usage persistence, token CLI, publisher."""

from __future__ import annotations

import io
import json
import os
import sys
import tempfile
import unittest
import zipfile
from unittest import mock
from contextlib import redirect_stdout
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from gm_server import token_cli  # noqa: E402
from gm_server.config import Config, ConfigError  # noqa: E402
from gm_server.limits import MAX_TRACKED_KEYS, Limiter, ip_key  # noqa: E402
from gm_server.ranges import RangeKind, parse_range  # noqa: E402
from gm_server.tokens import TokenFileError, TokenStore, hash_token, new_entry, read_entries, write_entries  # noqa: E402
from gm_server.usage import UsageStore  # noqa: E402
from support import load_publisher  # noqa: E402

SIZE = 100


class RangeTest(unittest.TestCase):
    def test_open_ended_bounded_and_suffix(self) -> None:
        self.assertEqual(parse_range("bytes=10-", SIZE).first, 10)
        bounded = parse_range("bytes=10-1000", SIZE)
        self.assertEqual((bounded.first, bounded.last, bounded.length), (10, 99, 90))
        suffix = parse_range("bytes=-30", SIZE)
        self.assertEqual((suffix.first, suffix.last), (70, 99))

    def test_missing_malformed_and_multiple_ranges_select_the_whole_file(self) -> None:
        for header in (None, "", "bytes=abc", "items=1-2", "bytes=1-2,5-6", "bytes=-", "bytes=9-3"):
            self.assertEqual(parse_range(header, SIZE).kind, RangeKind.FULL, header)

    def test_over_long_numbers_are_malformed_not_an_error(self) -> None:
        for header in ("bytes=" + "9" * 5000 + "-", "bytes=0-" + "9" * 5000, "bytes=-" + "9" * 5000):
            self.assertEqual(parse_range(header, SIZE).kind, RangeKind.FULL)

    def test_start_past_the_end_is_unsatisfiable(self) -> None:
        self.assertEqual(parse_range("bytes=100-", SIZE).kind, RangeKind.UNSATISFIABLE)
        self.assertEqual(parse_range("bytes=-0", SIZE).kind, RangeKind.UNSATISFIABLE)


class LimiterTest(unittest.TestCase):
    def test_window_slides_and_ban_expires(self) -> None:
        now = [0.0]
        limiter = Limiter(2, 1, 2, 600, 100, clock=lambda: now[0])
        self.assertEqual(limiter.rate_retry_after("k"), 0)
        self.assertEqual(limiter.rate_retry_after("k"), 0)
        self.assertGreater(limiter.rate_retry_after("k"), 0)
        now[0] = 61.0
        self.assertEqual(limiter.rate_retry_after("k"), 0)
        limiter.record_failure("ip")
        limiter.record_failure("ip")
        self.assertGreater(limiter.ban_remaining("ip"), 0)
        now[0] = 200.0
        self.assertEqual(limiter.ban_remaining("ip"), 0)

    def test_maps_stay_bounded_under_many_distinct_keys(self) -> None:
        now = [0.0]
        limiter = Limiter(5, 1, 3, 600, 100, clock=lambda: now[0])
        for index in range(MAX_TRACKED_KEYS * 2):
            limiter.record_failure(f"ip{index}")
            limiter.rate_retry_after(f"key{index}")
        self.assertLessEqual(len(limiter._failures), MAX_TRACKED_KEYS)
        self.assertLessEqual(len(limiter._hits), MAX_TRACKED_KEYS)
        now[0] = 10_000.0
        limiter.rate_retry_after("later")
        limiter.record_failure("later")
        self.assertEqual(len(limiter._hits), 1)
        self.assertEqual(len(limiter._failures), 1)

    def test_expired_bans_are_swept_without_a_lookup(self) -> None:
        now = [0.0]
        limiter = Limiter(5, 1, 1, 600, 100, clock=lambda: now[0])
        for index in range(50):
            limiter.record_failure(f"ip{index}")
        self.assertEqual(len(limiter._banned_until), 50)
        now[0] = 1_000.0
        limiter.record_failure("fresh")
        self.assertEqual(set(limiter._banned_until), {"fresh"})

    def test_ip_key_uses_the_64_prefix_for_ipv6_and_the_full_ipv4_address(self) -> None:
        self.assertEqual(ip_key("203.0.113.7"), "203.0.113.7")
        self.assertEqual(ip_key("2001:db8:1:2:aaaa:bbbb:cccc:dddd"), "2001:db8:1:2::/64")
        self.assertEqual(ip_key("::ffff:203.0.113.7"), "203.0.113.7")
        self.assertIsNone(ip_key("not-an-ip"))

    def test_concurrency_slots_are_returned(self) -> None:
        limiter = Limiter(0, 1, 0, 1, 1)
        self.assertTrue(limiter.acquire("k"))
        self.assertFalse(limiter.acquire("k"))
        limiter.release("k")
        self.assertTrue(limiter.acquire("k"))


class UsageTest(unittest.TestCase):
    def test_counts_survive_a_restart_and_reset_in_a_new_month(self) -> None:
        clock = [datetime(2026, 11, 20, tzinfo=timezone.utc)]
        with tempfile.TemporaryDirectory() as temp:
            usage = UsageStore(Path(temp), now=lambda: clock[0])
            usage.add("k", 500)
            usage.flush()
            self.assertEqual(UsageStore(Path(temp), now=lambda: clock[0]).used("k"), 500)
            self.assertEqual(usage.seconds_to_next_month(), 11 * 86400)
            clock[0] = datetime(2026, 12, 1, tzinfo=timezone.utc)
            self.assertEqual(UsageStore(Path(temp), now=lambda: clock[0]).used("k"), 0)


class UsageFileTest(unittest.TestCase):
    def test_a_corrupt_file_is_set_aside_and_counting_restarts(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "usage.json"
            path.write_bytes(b"\xff{broken")
            usage = UsageStore(Path(temp))
            self.assertEqual(usage.used("k"), 0)
            self.assertFalse(path.exists())
            self.assertEqual(len(list(Path(temp).glob("usage.json.corrupt-*"))), 1)

    def test_non_integer_entries_are_ignored_and_the_rest_kept(self) -> None:
        month = datetime.now(timezone.utc).strftime("%Y-%m")
        data = {"good": {month: 5}, "bad": {month: "lots"}, "flag": {month: True}, "odd": 3}
        with tempfile.TemporaryDirectory() as temp:
            (Path(temp) / "usage.json").write_text(json.dumps(data))
            usage = UsageStore(Path(temp))
            self.assertEqual(
                [usage.used(key) for key in ("good", "bad", "flag", "odd")], [5, 0, 0, 0]
            )

    def test_a_flush_syncs_the_file_and_the_directory(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            usage = UsageStore(Path(temp))
            with mock.patch("os.fsync") as fsync:
                usage.add("k", 7)
                usage.flush()
            self.assertGreaterEqual(fsync.call_count, 2)
            self.assertEqual(UsageStore(Path(temp)).used("k"), 7)


class ReservationTest(unittest.TestCase):
    def test_parallel_responses_cannot_all_start_near_the_quota(self) -> None:
        usage = UsageStore(None)
        self.assertTrue(usage.reserve("k", 60, 100))
        self.assertFalse(usage.reserve("k", 60, 100))
        self.assertTrue(usage.reserve("k", 40, 100))
        self.assertFalse(usage.reserve("k", 1, 100))

    def test_the_unsent_part_is_refunded_when_the_response_ends(self) -> None:
        usage = UsageStore(None)
        self.assertTrue(usage.reserve("k", 60, 100))
        usage.settle("k", 60, 10)
        self.assertEqual(usage.used("k"), 10)
        self.assertTrue(usage.reserve("k", 90, 100))
        self.assertFalse(usage.reserve("k", 1, 100))

    def test_a_limit_of_zero_means_no_quota(self) -> None:
        self.assertTrue(UsageStore(None).reserve("k", 10**12, 0))


class ConfigTest(unittest.TestCase):
    def test_defaults_require_tokens(self) -> None:
        config = Config.from_env({})
        self.assertTrue(config.require_tokens)
        self.assertEqual((config.rate_per_minute, config.max_concurrent), (60, 4))

    def test_bad_values_are_refused(self) -> None:
        for env in ({"GM_REQUIRE_TOKENS": "maybe"}, {"GM_PORT": "x"}, {"GM_ANON_KEY": "mac"}):
            with self.assertRaises(ConfigError):
                Config.from_env(env)


class TokenCliTest(unittest.TestCase):
    def test_create_list_disable_enable_delete_and_the_secret_is_not_stored(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "tokens.json"
            out = io.StringIO()
            with redirect_stdout(out):
                self.assertEqual(token_cli.main(["--file", str(path), "create", "--label", "phone"]), 0)
            token = out.getvalue().split("token (shown once): ")[1].strip()
            self.assertGreaterEqual(len(token), 43)
            self.assertNotIn(token, path.read_text())
            self.assertEqual(oct(path.stat().st_mode & 0o777), "0o600")
            entry = read_entries(path)[0]
            self.assertEqual(entry.sha256, hash_token(token))
            store = TokenStore(path)
            self.assertIsNotNone(store.lookup(token))
            with redirect_stdout(io.StringIO()):
                token_cli.main(["--file", str(path), "disable", entry.token_id])
            self.assertIsNone(store.lookup(token))
            token_cli.main(["--file", str(path), "enable", entry.token_id])
            self.assertIsNotNone(store.lookup(token))
            token_cli.main(["--file", str(path), "delete", entry.token_id])
            self.assertEqual(json.loads(path.read_text()), {"tokens": []})
            self.assertEqual(token_cli.main(["--file", str(path), "delete", "deadbeef"]), 1)


class TokenStoreTest(unittest.TestCase):
    def test_a_same_size_rewrite_with_the_same_mtime_is_seen(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "tokens.json"
            first, second = "a" * 40, "b" * 40
            write_entries(path, [new_entry(first, "x", None)])
            stat = path.stat()
            store = TokenStore(path)
            self.assertIsNotNone(store.lookup(first))
            write_entries(path, [new_entry(second, "x", None)])
            os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
            self.assertEqual(path.stat().st_size, stat.st_size)
            self.assertIsNone(store.lookup(first))
            self.assertIsNotNone(store.lookup(second))

    def test_bad_files_are_errors_not_exceptions(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "tokens.json"
            path.write_bytes(b"\xff\xfe")
            store = TokenStore(path)
            self.assertIsNotNone(store.error())
            self.assertIsNone(store.lookup("anything"))
            with self.assertRaises(TokenFileError):
                read_entries(path)
            entry = {"sha256": "a" * 64, "quota_bytes": True}
            path.write_text(json.dumps({"tokens": [entry]}))
            self.assertIsNotNone(store.error())


class PublishTest(unittest.TestCase):
    def test_zips_a_version_directory_with_nested_components_and_keeps_published_ones(self) -> None:
        publisher = load_publisher()
        with tempfile.TemporaryDirectory() as temp:
            source, dest = Path(temp) / "out", Path(temp) / "served"
            version = source / "ua-46" / "2026-11-03"
            (version / "routing").mkdir(parents=True)
            (version / "manifest.json").write_text("{}")
            (version / "map.pmtiles").write_bytes(b"tiles")
            (version / "routing" / "0.gph").write_bytes(b"graph")
            (source / "outlines").mkdir()
            (source / "outlines" / "ua-46.geojson").write_text("{}")
            (source / "boundaries").mkdir()
            (source / "catalog.json").write_text('{"format": 1}')

            self.assertEqual(publisher.publish(source, dest), 1)
            published = dest / "ua-46" / "2026-11-03"
            with zipfile.ZipFile(published / "package.zip") as archive:
                self.assertEqual(
                    sorted(archive.namelist()), ["manifest.json", "map.pmtiles", "routing/0.gph"]
                )
            self.assertEqual((published / "manifest.json").read_text(), "{}")
            self.assertTrue((dest / "outlines" / "ua-46.geojson").is_file())
            self.assertEqual((dest / "catalog.json").read_text(), '{"format": 1}')
            self.assertEqual(publisher.publish(source, dest), 0)

    def _pipeline_output(self, temp: str) -> tuple[Path, Path, Path]:
        source, dest = Path(temp) / "out", Path(temp) / "served"
        version = source / "ua-46" / "2026-11-03"
        version.mkdir(parents=True)
        (version / "manifest.json").write_text("{}")
        (version / "map.pmtiles").write_bytes(b"tiles")
        (source / "catalog.json").write_text("{}")
        return source, dest, version

    def test_an_elevation_addon_directory_is_published_as_a_package_of_its_own(self) -> None:
        publisher = load_publisher()
        with tempfile.TemporaryDirectory() as temp:
            source, dest, _ = self._pipeline_output(temp)
            addon = source / "ua-46-elevation-30m" / "2026-11-03"
            addon.mkdir(parents=True)
            (addon / "manifest.json").write_text("{}")
            (addon / "elevation.bin").write_bytes(b"GRME")

            self.assertEqual(publisher.publish(source, dest), 2)
            with zipfile.ZipFile(dest / "ua-46-elevation-30m" / "2026-11-03" / "package.zip") as archive:
                self.assertEqual(sorted(archive.namelist()), ["elevation.bin", "manifest.json"])

    def test_symlinks_are_refused_not_followed(self) -> None:
        publisher = load_publisher()
        secret = Path(tempfile.mkdtemp()) / "secret.txt"
        secret.write_text("secret")
        self.addCleanup(secret.unlink)
        for link_name in ("leak.txt", "manifest.json"):
            with tempfile.TemporaryDirectory() as temp:
                source, dest, version = self._pipeline_output(temp)
                link = version / link_name
                link.unlink(missing_ok=True)
                link.symlink_to(secret)
                with self.assertRaises(SystemExit) as raised:
                    publisher.publish(source, dest)
                self.assertIn(link_name, str(raised.exception))
                self.assertFalse((dest / "ua-46" / "2026-11-03" / "package.zip").exists())
        with tempfile.TemporaryDirectory() as temp:
            source, dest, _ = self._pipeline_output(temp)
            (source / "catalog.json").unlink()
            (source / "catalog.json").symlink_to(secret)
            with self.assertRaises(SystemExit):
                publisher.publish(source, dest)

    def test_names_the_server_would_404_are_rejected(self) -> None:
        publisher = load_publisher()
        with tempfile.TemporaryDirectory() as temp:
            source, dest, version = self._pipeline_output(temp)
            version.rename(version.with_name("Bad Version"))
            with self.assertRaises(SystemExit):
                publisher.publish(source, dest)

    def test_a_version_missing_its_manifest_is_published_again(self) -> None:
        publisher = load_publisher()
        with tempfile.TemporaryDirectory() as temp:
            source, dest, _ = self._pipeline_output(temp)
            self.assertEqual(publisher.publish(source, dest), 1)
            published = dest / "ua-46" / "2026-11-03"
            (published / "manifest.json").unlink()
            self.assertEqual(publisher.publish(source, dest), 1)
            self.assertTrue((published / "manifest.json").is_file())
            self.assertEqual(sorted(path.name for path in published.iterdir()), ["manifest.json", "package.zip"])


if __name__ == "__main__":
    unittest.main()
