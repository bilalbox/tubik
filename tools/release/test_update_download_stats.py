import json
import sys
import tempfile
import unittest
from datetime import date, datetime, timezone
from pathlib import Path
from unittest.mock import patch

from update_download_stats import (
    StatsError,
    empty_ledger,
    fetch_cloudflare_daily_downloads,
    fetch_github_apk_downloads,
    is_apk_download_path,
    load_ledger,
    main,
    merge_and_compute_stats,
)


class UpdateDownloadStatsTest(unittest.TestCase):
    def test_is_apk_download_path_filters_non_apks(self):
        self.assertTrue(is_apk_download_path("/tubik-v0.7.0.apk"))
        self.assertTrue(is_apk_download_path("/tubik-v1.2.3.apk?download=1"))
        self.assertFalse(is_apk_download_path("/stats.json"))
        self.assertFalse(is_apk_download_path("/stats-ledger.json"))
        self.assertFalse(is_apk_download_path("/favicon.ico"))
        self.assertFalse(is_apk_download_path("/other-app.apk"))

    def test_fetch_cloudflare_daily_downloads_aggregates_apk_paths_only(self):
        mock_response = {
            "data": {
                "viewer": {
                    "zones": [
                        {
                            "httpRequestsAdaptiveGroups": [
                                {
                                    "count": 12,
                                    "dimensions": {"date": "2026-11-26", "clientRequestPath": "/tubik-v0.7.0.apk"},
                                },
                                {
                                    "count": 3,
                                    "dimensions": {"date": "2026-11-26", "clientRequestPath": "/tubik-v0.6.0.apk"},
                                },
                                {
                                    "count": 500,
                                    "dimensions": {"date": "2026-11-26", "clientRequestPath": "/stats.json"},
                                },
                                {
                                    "count": 8,
                                    "dimensions": {"date": "2026-11-28", "clientRequestPath": "/tubik-v0.7.0.apk"},
                                },
                            ]
                        }
                    ]
                }
            }
        }
        with patch("update_download_stats._http_json", return_value=mock_response):
            daily = fetch_cloudflare_daily_downloads(
                zone_id="zone-123",
                api_token="tok",
                host="tubik-releases.23p.dev",
                start_date=date(2026, 11, 26),
                end_date=date(2026, 11, 28),
            )
        self.assertEqual(
            daily,
            {
                "2026-11-26": 15,
                "2026-11-27": 0,
                "2026-11-28": 8,
            },
        )

    def test_fetch_cloudflare_raises_on_graphql_errors(self):
        with patch("update_download_stats._http_json", return_value={"errors": [{"message": "unauthorized"}]}):
            with self.assertRaises(StatsError):
                fetch_cloudflare_daily_downloads(
                    zone_id="zone-123",
                    api_token="bad",
                    host="tubik-releases.23p.dev",
                    start_date=date(2026, 11, 27),
                    end_date=date(2026, 11, 28),
                )

    def test_fetch_github_apk_downloads_sums_published_apk_assets(self):
        releases = [
            {
                "draft": False,
                "assets": [
                    {"name": "tubik-v0.7.0.apk", "download_count": 40},
                    {"name": "SHA256SUMS", "download_count": 100},
                ],
            },
            {
                "draft": True,
                "assets": [{"name": "tubik-v0.8.0.apk", "download_count": 99}],
            },
            {
                "draft": False,
                "assets": [{"name": "tubik-v0.6.0.apk", "download_count": 25}],
            },
        ]
        with patch("update_download_stats._http_json", return_value=releases):
            self.assertEqual(fetch_github_apk_downloads(repo="bilalbox/tubik"), 65)

    def test_merge_and_compute_stats_preserves_pre_31_day_history_and_is_idempotent(self):
        ledger = {
            "schemaVersion": 1,
            "updatedAt": "2026-11-27T01:00:00Z",
            "cloudflareDaily": {
                "2026-10-01": 100,  # Older than 31-day Cloudflare retention
                "2026-11-20": 10,   # Outside rolling 7-day window (2026-11-22..2026-11-28)
                "2026-11-22": 5,
                "2026-11-27": 7,
            },
            "githubSnapshots": {
                "2026-11-20": 50,
                "2026-11-21": 55,   # Exact 7-day baseline for 2026-11-28
                "2026-11-27": 62,
            },
        }
        now = datetime(2026, 11, 28, 12, 0, tzinfo=timezone.utc)
        cf_fresh = {"2026-11-27": 9, "2026-11-28": 4}

        updated, stats = merge_and_compute_stats(
            ledger,
            cloudflare_daily=cf_fresh,
            github_total=67,
            now=now,
        )

        # Cloudflare: total = 100 + 10 + 5 + 9 + 4 = 128; week (11-22..11-28) = 5 + 9 + 4 = 18
        # GitHub: total = 67; week = 67 - 55 (2026-11-21 baseline) = 12
        self.assertEqual(stats["sources"]["cloudflare"], {"thisWeek": 18, "total": 128})
        self.assertEqual(stats["sources"]["github"], {"thisWeek": 12, "total": 67})
        self.assertEqual(stats["downloadsThisWeek"], 30)
        self.assertEqual(stats["downloadsTotal"], 195)
        self.assertEqual(updated["cloudflareDaily"]["2026-10-01"], 100)

        # Running again with identical data produces identical totals (idempotent)
        updated2, stats2 = merge_and_compute_stats(
            updated,
            cloudflare_daily=cf_fresh,
            github_total=67,
            now=now,
        )
        self.assertEqual(updated2, updated)
        self.assertEqual(stats2, stats)

    def test_first_run_does_not_inflate_weekly_github_downloads(self):
        now = datetime(2026, 11, 28, 12, 0, tzinfo=timezone.utc)
        _, stats = merge_and_compute_stats(
            empty_ledger(),
            cloudflare_daily={"2026-11-28": 6},
            github_total=200,
            now=now,
        )
        self.assertEqual(stats["sources"]["github"], {"thisWeek": 0, "total": 200})
        self.assertEqual(stats["downloadsThisWeek"], 6)
        self.assertEqual(stats["downloadsTotal"], 206)

    def test_cli_writes_ledger_and_public_stats(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            ledger_path = tmp_dir / "stats-ledger.json"
            output_path = tmp_dir / "stats.json"

            argv = [
                "update_download_stats.py",
                "--ledger",
                str(ledger_path),
                "--output",
                str(output_path),
                "--zone-id",
                "zone-123",
                "--cloudflare-token",
                "tok",
            ]
            with (
                patch.object(sys, "argv", argv),
                patch("update_download_stats.fetch_cloudflare_daily_downloads", return_value={"2026-11-28": 11}),
                patch("update_download_stats.fetch_github_apk_downloads", return_value=30),
            ):
                main()

            self.assertTrue(ledger_path.is_file())
            self.assertTrue(output_path.is_file())
            stats = json.loads(output_path.read_text(encoding="utf-8"))
            self.assertEqual(stats["downloadsTotal"], 41)
            self.assertEqual(
                load_ledger(ledger_path)["githubSnapshots"],
                {datetime.now(timezone.utc).date().isoformat(): 30},
            )


if __name__ == "__main__":
    unittest.main()
