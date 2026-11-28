#!/usr/bin/env python3
"""Aggregate Tubik APK download stats from Cloudflare HTTP Analytics and GitHub Releases.

Maintains a persistent daily ledger (`stats-ledger.json`) so all-time totals survive
Cloudflare's ~31-day analytics retention window, and emits a compact public `stats.json`
for the website's release strip.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from typing import Any

CLOUDFLARE_GRAPHQL_URL = "https://api.cloudflare.com/client/v4/graphql"
GITHUB_API_BASE = "https://api.github.com"
DEFAULT_RELEASES_HOST = "tubik-releases.23p.dev"
DEFAULT_GITHUB_REPO = "bilalbox/tubik"
SCHEMA_VERSION = 1
# Cloudflare caps `httpRequestsAdaptiveGroups` at 30 days (4w2d) from `startDate 00:00Z` to `now`,
# so 29 days prior to `today` keeps the inclusive span strictly under 30 days at any UTC hour.
MAX_CLOUDFLARE_LOOKBACK_DAYS = 29
WEEK_WINDOW_DAYS = 7

CLOUDFLARE_DOWNLOADS_QUERY = """
query TubikApkDownloads(
  $zoneTag: string!
  $startDate: Date!
  $endDate: Date!
  $host: string!
) {
  viewer {
    zones(filter: { zoneTag: $zoneTag }) {
      httpRequestsAdaptiveGroups(
        limit: 10000
        filter: {
          date_geq: $startDate
          date_leq: $endDate
          clientRequestHTTPHost: $host
          clientRequestHTTPMethodName: "GET"
          edgeResponseStatus: 200
        }
        orderBy: [date_ASC]
      ) {
        count
        dimensions {
          date
          clientRequestPath
        }
      }
    }
  }
}
""".strip()


class StatsError(RuntimeError):
    """Raised when fetching or validating download statistics fails."""


def is_apk_download_path(path: str) -> bool:
    """Return True if [path] is a Tubik release APK download path."""
    normalized = path.split("?", 1)[0]
    return normalized.startswith("/tubik-") and normalized.endswith(".apk")


def _http_json(
    url: str,
    *,
    method: str = "GET",
    headers: dict[str, str] | None = None,
    payload: dict[str, Any] | None = None,
    timeout: int = 30,
) -> Any:
    body = json.dumps(payload).encode("utf-8") if payload is not None else None
    req_headers = {"User-Agent": "tubik-download-stats/1.0"}
    if headers:
        req_headers.update(headers)
    if body is not None:
        req_headers.setdefault("Content-Type", "application/json")
    request = urllib.request.Request(url, data=body, headers=req_headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")[:500]
        raise StatsError(f"HTTP {error.code} from {url}: {detail}") from error
    except (urllib.error.URLError, json.JSONDecodeError, OSError) as error:
        raise StatsError(f"Request failed for {url}: {error}") from error


def fetch_cloudflare_daily_downloads(
    *,
    zone_id: str,
    api_token: str,
    host: str,
    start_date: date,
    end_date: date,
) -> dict[str, int]:
    """Query Cloudflare GraphQL HTTP Analytics for daily APK downloads."""
    if end_date < start_date:
        raise StatsError(f"end_date ({end_date}) cannot be before start_date ({start_date})")
    if (end_date - start_date).days > MAX_CLOUDFLARE_LOOKBACK_DAYS:
        raise StatsError(f"Cloudflare lookback window cannot exceed {MAX_CLOUDFLARE_LOOKBACK_DAYS} days")

    response = _http_json(
        CLOUDFLARE_GRAPHQL_URL,
        method="POST",
        headers={"Authorization": f"Bearer {api_token}"},
        payload={
            "query": CLOUDFLARE_DOWNLOADS_QUERY,
            "variables": {
                "zoneTag": zone_id,
                "startDate": start_date.isoformat(),
                "endDate": end_date.isoformat(),
                "host": host,
            },
        },
    )

    errors = response.get("errors")
    if errors:
        raise StatsError(f"Cloudflare GraphQL returned errors: {errors}")

    zones = ((response.get("data") or {}).get("viewer") or {}).get("zones") or []
    if not zones:
        raise StatsError(f"Cloudflare GraphQL returned no zone data for zone_id={zone_id!r}")

    groups = zones[0].get("httpRequestsAdaptiveGroups") or []
    daily: dict[str, int] = {}
    for row in groups:
        dimensions = row.get("dimensions") or {}
        day = dimensions.get("date")
        path = dimensions.get("clientRequestPath")
        count = row.get("count")
        if not isinstance(day, str) or not isinstance(path, str) or not isinstance(count, int):
            continue
        if is_apk_download_path(path) and count > 0:
            daily[day] = daily.get(day, 0) + count

    # Ensure every queried day up to end_date has an explicit integer entry so zero-download
    # days are recorded cleanly and re-runs remain idempotent.
    cursor = start_date
    while cursor <= end_date:
        daily.setdefault(cursor.isoformat(), 0)
        cursor += timedelta(days=1)

    return dict(sorted(daily.items()))


def fetch_github_apk_downloads(*, repo: str, github_token: str | None = None) -> int:
    """Sum cumulative `download_count` across all `.apk` assets on GitHub Releases."""
    headers = {"Accept": "application/vnd.github+json"}
    if github_token:
        headers["Authorization"] = f"Bearer {github_token}"

    total = 0
    page = 1
    while True:
        url = f"{GITHUB_API_BASE}/repos/{repo}/releases?per_page=100&page={page}"
        releases = _http_json(url, headers=headers)
        if not isinstance(releases, list):
            raise StatsError(f"Unexpected GitHub Releases response shape on page {page}")
        if not releases:
            break
        for release in releases:
            if not isinstance(release, dict) or release.get("draft"):
                continue
            for asset in release.get("assets") or []:
                if not isinstance(asset, dict):
                    continue
                name = asset.get("name")
                count = asset.get("download_count")
                if isinstance(name, str) and name.endswith(".apk") and isinstance(count, int) and count > 0:
                    total += count
        if len(releases) < 100:
            break
        page += 1
    return total


def empty_ledger() -> dict[str, Any]:
    return {
        "schemaVersion": SCHEMA_VERSION,
        "updatedAt": None,
        "cloudflareDaily": {},
        "githubSnapshots": {},
    }


def load_ledger(path: Path) -> dict[str, Any]:
    """Load an existing ledger file, or return an empty ledger if it does not exist."""
    if not path.is_file():
        return empty_ledger()
    try:
        raw = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise StatsError(f"Cannot read ledger {path}: {error}") from error
    if not isinstance(raw, dict):
        raise StatsError(f"Ledger {path} must be a JSON object")

    cf_daily: dict[str, int] = {}
    for k, v in (raw.get("cloudflareDaily") or {}).items():
        if isinstance(k, str) and isinstance(v, int) and not isinstance(v, bool) and v >= 0:
            cf_daily[k] = v

    gh_snapshots: dict[str, int] = {}
    for k, v in (raw.get("githubSnapshots") or {}).items():
        if isinstance(k, str) and isinstance(v, int) and not isinstance(v, bool) and v >= 0:
            gh_snapshots[k] = v

    return {
        "schemaVersion": SCHEMA_VERSION,
        "updatedAt": raw.get("updatedAt"),
        "cloudflareDaily": dict(sorted(cf_daily.items())),
        "githubSnapshots": dict(sorted(gh_snapshots.items())),
    }


def _compute_github_week_delta(snapshots: dict[str, int], today: date) -> int:
    """Compute rolling 7-day GitHub downloads from cumulative daily snapshots.

    Uses the snapshot closest to (and on or before) `today - 7 days` as the baseline.
    If the ledger has less than 7 days of history, falls back to the earliest snapshot
    so pre-ledger historical downloads are never counted as "this week".
    """
    today_str = today.isoformat()
    if today_str not in snapshots or len(snapshots) < 2:
        return 0
    current = snapshots[today_str]
    target = (today - timedelta(days=WEEK_WINDOW_DAYS)).isoformat()
    earlier_dates = sorted(d for d in snapshots if d < today_str)
    if not earlier_dates:
        return 0

    on_or_before_target = [d for d in earlier_dates if d <= target]
    baseline_date = on_or_before_target[-1] if on_or_before_target else earlier_dates[0]
    return max(0, current - snapshots[baseline_date])


def merge_and_compute_stats(
    ledger: dict[str, Any],
    *,
    cloudflare_daily: dict[str, int],
    github_total: int,
    now: datetime,
) -> tuple[dict[str, Any], dict[str, Any]]:
    """Return `(updated_ledger, public_stats)` without mutating the input ledger."""
    today = now.astimezone(timezone.utc).date()
    today_str = today.isoformat()
    timestamp = now.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    merged_cf = dict(ledger.get("cloudflareDaily") or {})
    for day, count in cloudflare_daily.items():
        if count < 0:
            raise StatsError(f"Negative Cloudflare download count for {day}: {count}")
        # Keep the larger value for today/recent days in case of minor sampling jitter,
        # while still letting finalized days update accurately.
        merged_cf[day] = max(merged_cf.get(day, 0), count) if day == today_str else count
    merged_cf = dict(sorted(merged_cf.items()))

    merged_gh = dict(ledger.get("githubSnapshots") or {})
    merged_gh[today_str] = max(merged_gh.get(today_str, 0), github_total)
    merged_gh = dict(sorted(merged_gh.items()))

    week_start = (today - timedelta(days=WEEK_WINDOW_DAYS - 1)).isoformat()
    cf_week = sum(count for day, count in merged_cf.items() if week_start <= day <= today_str)
    cf_total = sum(merged_cf.values())

    gh_total = merged_gh[today_str]
    gh_week = _compute_github_week_delta(merged_gh, today)

    updated_ledger = {
        "schemaVersion": SCHEMA_VERSION,
        "updatedAt": timestamp,
        "cloudflareDaily": merged_cf,
        "githubSnapshots": merged_gh,
    }
    public_stats = {
        "schemaVersion": SCHEMA_VERSION,
        "updatedAt": timestamp,
        "downloadsThisWeek": cf_week + gh_week,
        "downloadsTotal": cf_total + gh_total,
        "sources": {
            "cloudflare": {
                "thisWeek": cf_week,
                "total": cf_total,
            },
            "github": {
                "thisWeek": gh_week,
                "total": gh_total,
            },
        },
    }
    return updated_ledger, public_stats


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ledger", required=True, type=Path, help="Path to stats-ledger.json (read & updated)")
    parser.add_argument("--output", required=True, type=Path, help="Path to write public stats.json")
    parser.add_argument("--zone-id", default=os.environ.get("CLOUDFLARE_ZONE_ID", ""))
    parser.add_argument(
        "--cloudflare-token",
        default=os.environ.get("CLOUDFLARE_ANALYTICS_TOKEN") or os.environ.get("CLOUDFLARE_API_TOKEN", ""),
    )
    parser.add_argument("--releases-host", default=DEFAULT_RELEASES_HOST)
    parser.add_argument("--github-repo", default=DEFAULT_GITHUB_REPO)
    parser.add_argument("--github-token", default=os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN"))
    parser.add_argument(
        "--lookback-days",
        type=int,
        default=14,
        help="Days of Cloudflare analytics to query (max 29; defaults to 29 on first run with empty ledger)",
    )
    args = parser.parse_args()

    if not args.zone_id:
        sys.exit("error: --zone-id (or CLOUDFLARE_ZONE_ID) is required")
    if not args.cloudflare_token:
        sys.exit("error: --cloudflare-token (or CLOUDFLARE_ANALYTICS_TOKEN / CLOUDFLARE_API_TOKEN) is required")
    if not (1 <= args.lookback_days <= MAX_CLOUDFLARE_LOOKBACK_DAYS):
        sys.exit(f"error: --lookback-days must be between 1 and {MAX_CLOUDFLARE_LOOKBACK_DAYS}")

    try:
        ledger = load_ledger(args.ledger)
        now = datetime.now(timezone.utc)
        today = now.date()
        effective_lookback = MAX_CLOUDFLARE_LOOKBACK_DAYS if not ledger["cloudflareDaily"] else args.lookback_days
        start_date = today - timedelta(days=effective_lookback)

        cf_daily = fetch_cloudflare_daily_downloads(
            zone_id=args.zone_id,
            api_token=args.cloudflare_token,
            host=args.releases_host,
            start_date=start_date,
            end_date=today,
        )
        gh_total = fetch_github_apk_downloads(
            repo=args.github_repo,
            github_token=args.github_token,
        )
        updated_ledger, public_stats = merge_and_compute_stats(
            ledger,
            cloudflare_daily=cf_daily,
            github_total=gh_total,
            now=now,
        )
    except StatsError as error:
        sys.exit(f"error: {error}")

    args.ledger.parent.mkdir(parents=True, exist_ok=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.ledger.write_text(json.dumps(updated_ledger, indent=2) + "\n", encoding="utf-8")
    args.output.write_text(json.dumps(public_stats, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
