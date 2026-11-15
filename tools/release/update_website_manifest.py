#!/usr/bin/env python3
"""Generate website/updates/latest.json and sync download metadata in website/index.html."""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import re
from datetime import datetime, timezone
from pathlib import Path

RE_VERSION_CODE = re.compile(r"\bversionCode\s*=\s*(\d+)")
RE_VERSION_NAME = re.compile(r'\bversionName\s*=\s*"([^"]+)"')
RE_MIN_SDK = re.compile(r"\bminSdk\s*=\s*(\d+)")
RE_SHA256 = re.compile(r"^[0-9a-fA-F]{64}$")

RELEASES_CDN_BASE = "https://tubik-releases.23p.dev"
GITHUB_REPO_URL = "https://github.com/bilalbox/tubik"


def parse_gradle_version(gradle_text: str) -> tuple[int, str, int]:
    """Extract (versionCode, versionName, minSdk) from app/build.gradle.kts text."""
    code_match = RE_VERSION_CODE.search(gradle_text)
    name_match = RE_VERSION_NAME.search(gradle_text)
    min_sdk_match = RE_MIN_SDK.search(gradle_text)
    if not code_match or not name_match or not min_sdk_match:
        raise ValueError("Could not extract versionCode, versionName, and minSdk from build.gradle.kts")
    return int(code_match.group(1)), name_match.group(1), int(min_sdk_match.group(1))


def build_manifest(
    *,
    tag: str,
    version_code: int,
    version_name: str,
    min_sdk: int,
    sha256: str,
    size_bytes: int,
    changelog: str,
    published_at: str | None = None,
    min_supported_version_code: int | None = None,
) -> dict[str, object]:
    """Build the /updates/latest.json payload matching Spec #11 schema."""
    normalized_sha = sha256.strip().lower()
    if not RE_SHA256.match(normalized_sha):
        raise ValueError(f"Invalid SHA-256 hex digest: {sha256!r}")
    if size_bytes <= 0:
        raise ValueError(f"APK sizeBytes must be positive, got {size_bytes}")
    resolved_min_supported = (
        version_code if min_supported_version_code is None else min_supported_version_code
    )
    if resolved_min_supported <= 0 or resolved_min_supported > version_code:
        raise ValueError(
            f"minSupportedVersionCode must be between 1 and {version_code}, got {resolved_min_supported}"
        )
    timestamp = published_at or datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    apk_filename = f"tubik-{tag}.apk"
    return {
        "schemaVersion": 1,
        "versionCode": version_code,
        "versionName": version_name,
        "minSdk": min_sdk,
        "minSupportedVersionCode": resolved_min_supported,
        "publishedAt": timestamp,
        "sha256": normalized_sha,
        "sizeBytes": size_bytes,
        "apkUrl": f"{RELEASES_CDN_BASE}/{apk_filename}",
        "releaseNotesUrl": f"{GITHUB_REPO_URL}/releases/tag/{tag}",
        "changelog": changelog.strip(),
    }


def _replace_anchor(html_text: str, element_id: str, href: str, inner_text: str | None = None) -> str:
    pattern = re.compile(
        rf'(<a\b[^>]*\bid="{re.escape(element_id)}"[^>]*\bhref=")[^"]*("[^>]*>)(.*?)(</a>)',
        re.DOTALL,
    )

    def _sub(match: re.Match[str]) -> str:
        text = html.escape(inner_text) if inner_text is not None else match.group(3)
        return f"{match.group(1)}{html.escape(href, quote=True)}{match.group(2)}{text}{match.group(4)}"

    updated, count = pattern.subn(_sub, html_text, count=1)
    if count != 1:
        raise ValueError(f"Expected exactly 1 anchor with id={element_id!r}, found {count}")
    return updated


def _replace_element_text(html_text: str, tag: str, element_id: str, inner_text: str) -> str:
    pattern = re.compile(
        rf'(<{tag}\b[^>]*\bid="{re.escape(element_id)}"[^>]*>)(.*?)(</{tag}>)',
        re.DOTALL,
    )
    updated, count = pattern.subn(
        lambda m: f"{m.group(1)}{html.escape(inner_text)}{m.group(3)}",
        html_text,
        count=1,
    )
    if count != 1:
        raise ValueError(f"Expected exactly 1 <{tag}> with id={element_id!r}, found {count}")
    return updated


def _replace_time(html_text: str, element_id: str, date_str: str) -> str:
    pattern = re.compile(
        rf'(<time\b[^>]*\bid="{re.escape(element_id)}"[^>]*\bdatetime=")[^"]*("[^>]*>)(.*?)(</time>)',
        re.DOTALL,
    )
    escaped_date = html.escape(date_str, quote=True)
    updated, count = pattern.subn(
        lambda m: f"{m.group(1)}{escaped_date}{m.group(2)}{html.escape(date_str)}{m.group(4)}",
        html_text,
        count=1,
    )
    if count != 1:
        raise ValueError(f"Expected exactly 1 <time> with id={element_id!r}, found {count}")
    return updated


def sync_index_html(html_text: str, manifest: dict[str, object]) -> str:
    """Update release download metadata elements in website/index.html from manifest."""
    version_name = str(manifest["versionName"])
    apk_url = str(manifest["apkUrl"])
    apk_filename = apk_url.rsplit("/", 1)[-1]
    release_notes_url = str(manifest["releaseNotesUrl"])
    size_mb = f"{int(manifest['sizeBytes']) / 1_048_576:.1f} MB"
    published_date = str(manifest["publishedAt"])[:10]
    sha256 = str(manifest["sha256"])

    result = _replace_anchor(html_text, "release-download-link", apk_url, f"Download Tubik v{version_name}")
    result = _replace_anchor(result, "release-notes-link", release_notes_url)
    result = _replace_element_text(result, "span", "release-version", f"v{version_name}")
    result = _replace_element_text(result, "span", "release-size", size_mb)
    result = _replace_time(result, "release-date", published_date)
    result = _replace_element_text(result, "code", "release-sha256", sha256)
    result = _replace_element_text(result, "code", "release-apk-filename", apk_filename)
    result = _replace_element_text(result, "code", "release-sha256-cmd", f"sha256sum {apk_filename}")
    return result


def _file_sha256_and_size(apk_path: Path) -> tuple[str, int]:
    digest = hashlib.sha256()
    size = 0
    with apk_path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
            size += len(chunk)
    return digest.hexdigest(), size


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", default="website/updates/latest.json", type=Path)
    parser.add_argument("--index-html", default="website/index.html", type=Path)
    parser.add_argument("--sync-html-from-manifest", action="store_true")
    parser.add_argument("--tag")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--gradle", default="app/build.gradle.kts", type=Path)
    parser.add_argument("--changelog-dir", default="fastlane/metadata/android/en-US/changelogs", type=Path)
    parser.add_argument("--published-at")
    parser.add_argument("--min-supported-version-code", type=int)
    args = parser.parse_args()

    if args.sync_html_from_manifest:
        manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    else:
        if not args.tag or not args.apk:
            parser.error("--tag and --apk are required unless --sync-html-from-manifest is passed")
        version_code, version_name, min_sdk = parse_gradle_version(args.gradle.read_text(encoding="utf-8"))
        sha256, size_bytes = _file_sha256_and_size(args.apk)
        changelog_file = args.changelog_dir / f"{version_code}.txt"
        changelog = (
            changelog_file.read_text(encoding="utf-8").strip()
            if changelog_file.is_file()
            else f"Tubik {args.tag}"
        )
        manifest = build_manifest(
            tag=args.tag,
            version_code=version_code,
            version_name=version_name,
            min_sdk=min_sdk,
            sha256=sha256,
            size_bytes=size_bytes,
            changelog=changelog,
            published_at=args.published_at,
            min_supported_version_code=args.min_supported_version_code,
        )
        args.manifest.parent.mkdir(parents=True, exist_ok=True)
        args.manifest.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")

    updated_html = sync_index_html(args.index_html.read_text(encoding="utf-8"), manifest)
    args.index_html.write_text(updated_html, encoding="utf-8")


if __name__ == "__main__":
    main()
