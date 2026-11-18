import json
import re
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from update_website_manifest import (
    ManifestError,
    build_manifest,
    main,
    parse_gradle_version,
    sync_index_html,
    validate_manifest,
)

REPO_ROOT = Path(__file__).resolve().parents[2]
WEBSITE_DIR = REPO_ROOT / "website"


SAMPLE_INDEX_HTML = (
    "<h1>Dispatch revision website</h1>\n"
    '<a id="release-download-link" href="https://tubik-releases.23p.dev/tubik-v0.7.0.apk">Download Tubik v0.7.0</a>\n'
    '<a id="release-notes-link" href="https://github.com/bilalbox/tubik/releases/tag/v0.7.0">Release notes</a>\n'
    '<span id="release-version">v0.7.0</span>\n'
    '<span id="release-size">1.0 MB</span>\n'
    '<time id="release-date" datetime="2026-11-15">2026-11-15</time>\n'
    '<code id="release-sha256">0000</code>\n'
    '<code id="release-apk-filename">tubik-v0.7.0.apk</code>\n'
    '<code id="release-sha256-cmd">sha256sum tubik-v0.7.0.apk</code>\n'
)


def live_manifest(**overrides):
    manifest = build_manifest(
        tag="v1.2.1",
        version_code=85,
        version_name="1.2.1",
        min_sdk=26,
        sha256="d" * 64,
        size_bytes=52428800,
        changelog="Live release",
        published_at="2026-11-17T09:00:00Z",
    )
    manifest.update(overrides)
    return manifest


SAMPLE_GRADLE = """
android {
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.twenty3p.tubik"
        minSdk = 26
        targetSdk = 36
        versionCode = 81
        versionName = "0.7.1"
    }
}
"""


class UpdateWebsiteManifestTest(unittest.TestCase):
    def test_parse_gradle_version_extracts_codes_and_min_sdk(self):
        version_code, version_name, min_sdk = parse_gradle_version(SAMPLE_GRADLE)
        self.assertEqual(version_code, 81)
        self.assertEqual(version_name, "0.7.1")
        self.assertEqual(min_sdk, 26)

    def test_build_manifest_produces_spec_schema(self):
        manifest = build_manifest(
            tag="v0.7.1",
            version_code=81,
            version_name="0.7.1",
            min_sdk=26,
            sha256="A" * 64,
            size_bytes=48123456,
            changelog="Fix YouTube playback",
            published_at="2026-11-15T12:00:00Z",
        )
        self.assertEqual(
            manifest,
            {
                "schemaVersion": 1,
                "versionCode": 81,
                "versionName": "0.7.1",
                "minSdk": 26,
                "minSupportedVersionCode": 81,
                "publishedAt": "2026-11-15T12:00:00Z",
                "sha256": "a" * 64,
                "sizeBytes": 48123456,
                "apkUrl": "https://tubik-releases.23p.dev/tubik-v0.7.1.apk",
                "releaseNotesUrl": "https://github.com/bilalbox/tubik/releases/tag/v0.7.1",
                "changelog": "Fix YouTube playback",
            },
        )

    def test_build_manifest_accepts_explicit_min_supported_version_code(self):
        manifest = build_manifest(
            tag="v0.8.0",
            version_code=85,
            version_name="0.8.0",
            min_sdk=26,
            sha256="c" * 64,
            size_bytes=48123456,
            changelog="Upgrade",
            published_at="2026-11-16T12:00:00Z",
            min_supported_version_code=80,
        )
        self.assertEqual(manifest["minSupportedVersionCode"], 80)

    def test_cli_min_supported_version_code_defaults_to_version_code_and_overrides(self):
        sample_html = (
            '<a id="release-download-link" href="https://tubik-releases.23p.dev/tubik-v0.7.0.apk">Download Tubik v0.7.0</a>\n'
            '<a id="release-notes-link" href="https://github.com/bilalbox/tubik/releases/tag/v0.7.0">Release notes</a>\n'
            '<span id="release-version">v0.7.0</span>\n'
            '<span id="release-size">1.0 MB</span>\n'
            '<time id="release-date" datetime="2026-11-15">2026-11-15</time>\n'
            '<code id="release-sha256">0000</code>\n'
            '<code id="release-apk-filename">tubik-v0.7.0.apk</code>\n'
            '<code id="release-sha256-cmd">sha256sum tubik-v0.7.0.apk</code>\n'
        )
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            apk_path = tmp_dir / "tubik-v0.8.0.apk"
            apk_path.write_bytes(b"apk-bytes")
            gradle_path = tmp_dir / "build.gradle.kts"
            gradle_path.write_text(
                'android { defaultConfig { minSdk = 26\n versionCode = 85\n versionName = "0.8.0" } }',
                encoding="utf-8",
            )
            changelog_dir = tmp_dir / "changelogs"
            changelog_dir.mkdir()
            (changelog_dir / "85.txt").write_text("Release notes", encoding="utf-8")
            manifest_path = tmp_dir / "latest.json"
            index_path = tmp_dir / "index.html"
            index_path.write_text(sample_html, encoding="utf-8")

            base_args = [
                "update_website_manifest.py",
                "--tag",
                "v0.8.0",
                "--apk",
                str(apk_path),
                "--gradle",
                str(gradle_path),
                "--changelog-dir",
                str(changelog_dir),
                "--manifest",
                str(manifest_path),
                "--index-html",
                str(index_path),
            ]
            with patch.object(sys, "argv", base_args):
                main()
            defaulted = json.loads(manifest_path.read_text(encoding="utf-8"))
            self.assertEqual(defaulted["minSupportedVersionCode"], 85)

            with patch.object(sys, "argv", [*base_args, "--min-supported-version-code", "82"]):
                main()
            overridden = json.loads(manifest_path.read_text(encoding="utf-8"))
            self.assertEqual(overridden["minSupportedVersionCode"], 82)

    def test_sync_index_html_updates_release_metadata_idempotently(self):
        sample_html = (
            '<a class="btn btn-primary" id="release-download-link" '
            'href="https://tubik-releases.23p.dev/tubik-v0.7.0.apk">Download Tubik v0.7.0</a>\n'
            '<a class="btn btn-secondary" id="release-notes-link" '
            'href="https://github.com/bilalbox/tubik/releases/tag/v0.7.0">Release notes</a>\n'
            '<span id="release-version">v0.7.0</span>\n'
            '<span id="release-size">155.5 MB</span>\n'
            '<time id="release-date" datetime="2026-11-15">2026-11-15</time>\n'
            '<code id="release-sha256">0000</code>\n'
            '<code id="release-apk-filename">tubik-v0.7.0.apk</code>\n'
            '<code id="release-sha256-cmd">sha256sum tubik-v0.7.0.apk</code>\n'
        )
        manifest = build_manifest(
            tag="v0.7.1",
            version_code=81,
            version_name="0.7.1",
            min_sdk=26,
            sha256="b" * 64,
            size_bytes=52428800,
            changelog="Fix",
            published_at="2026-11-16T08:30:00Z",
        )
        updated = sync_index_html(sample_html, manifest)
        self.assertIn('href="https://tubik-releases.23p.dev/tubik-v0.7.1.apk">Download Tubik v0.7.1</a>', updated)
        self.assertIn('href="https://github.com/bilalbox/tubik/releases/tag/v0.7.1">Release notes</a>', updated)
        self.assertIn('<span id="release-version">v0.7.1</span>', updated)
        self.assertIn('<span id="release-size">50.0 MB</span>', updated)
        self.assertIn('<time id="release-date" datetime="2026-11-16">2026-11-16</time>', updated)
        self.assertIn(f'<code id="release-sha256">{"b" * 64}</code>', updated)
        self.assertIn('<code id="release-apk-filename">tubik-v0.7.1.apk</code>', updated)
        self.assertIn('<code id="release-sha256-cmd">sha256sum tubik-v0.7.1.apk</code>', updated)
        self.assertEqual(sync_index_html(updated, manifest), updated)

    def test_repo_website_matches_manifest_and_has_zero_tracking(self):
        manifest = json.loads((WEBSITE_DIR / "updates" / "latest.json").read_text(encoding="utf-8"))
        required_keys = {
            "schemaVersion",
            "versionCode",
            "versionName",
            "minSdk",
            "minSupportedVersionCode",
            "publishedAt",
            "sha256",
            "sizeBytes",
            "apkUrl",
            "releaseNotesUrl",
            "changelog",
        }
        self.assertEqual(set(manifest.keys()), required_keys)
        self.assertEqual(manifest["schemaVersion"], 1)
        self.assertRegex(manifest["sha256"], r"^[0-9a-f]{64}$")
        self.assertTrue(manifest["apkUrl"].startswith("https://tubik-releases.23p.dev/"))

        index_html = (WEBSITE_DIR / "index.html").read_text(encoding="utf-8")
        self.assertEqual(sync_index_html(index_html, manifest), index_html)

        for html_path in [WEBSITE_DIR / "index.html", WEBSITE_DIR / "privacy" / "index.html"]:
            text = html_path.read_text(encoding="utf-8")
            self.assertNotIn("<script", text.lower(), f"Unexpected <script> in {html_path}")
            self.assertIsNone(
                re.search(r'<(?:link|img|iframe)\b[^>]*\b(?:href|src)="https?://', text, re.I),
                f"Unexpected external subresource in {html_path}",
            )
            for asset_ref in re.findall(r'\b(?:href|src)="(/[^"#?]+)"', text):
                if asset_ref == "/" or asset_ref == "/privacy":
                    continue
                target = WEBSITE_DIR / asset_ref.lstrip("/")
                self.assertTrue(target.exists(), f"Missing local target {asset_ref} referenced in {html_path}")

        privacy_html = " ".join((WEBSITE_DIR / "privacy" / "index.html").read_text(encoding="utf-8").split())
        self.assertIn("https://tubik.23p.dev/updates/latest.json", privacy_html)
        self.assertIn("Cloudflare Web Analytics disabled", privacy_html)

    def run_cli(self, *args):
        with patch.object(sys, "argv", ["update_website_manifest.py", *map(str, args)]):
            main()

    def test_live_manifest_produces_matching_manifest_and_download_page(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            live_path = tmp_dir / "live.json"
            live_path.write_text(json.dumps(live_manifest()), encoding="utf-8")
            manifest_path = tmp_dir / "website" / "updates" / "latest.json"
            manifest_path.parent.mkdir(parents=True)
            manifest_path.write_text(json.dumps(live_manifest(versionName="0.7.0")), encoding="utf-8")
            index_path = tmp_dir / "website" / "index.html"
            index_path.write_text(SAMPLE_INDEX_HTML, encoding="utf-8")

            self.run_cli("--live-manifest", live_path, "--manifest", manifest_path, "--index-html", index_path)

            deployed = json.loads(manifest_path.read_text(encoding="utf-8"))
            self.assertEqual(deployed, live_manifest())
            index_html = index_path.read_text(encoding="utf-8")
            self.assertEqual(sync_index_html(index_html, deployed), index_html)
            self.assertIn('<span id="release-version">v1.2.1</span>', index_html)
            self.assertIn(f'<code id="release-sha256">{"d" * 64}</code>', index_html)
            self.assertIn("<h1>Dispatch revision website</h1>", index_html)

    def test_missing_partial_or_malformed_live_manifest_stops_without_touching_checked_in_files(self):
        partial = live_manifest()
        del partial["sha256"]
        cases = {
            "missing": None,
            "not json": "{",
            "partial": json.dumps(partial),
            "bad sha": json.dumps(live_manifest(sha256="xyz")),
            "bad size": json.dumps(live_manifest(sizeBytes=0)),
            "bad url": json.dumps(live_manifest(apkUrl="https://example.com/tubik.apk")),
            "bad schema": json.dumps(live_manifest(schemaVersion=2)),
            "bad date": json.dumps(live_manifest(publishedAt="yesterday")),
            "not object": json.dumps([live_manifest()]),
        }
        for name, live_text in cases.items():
            with self.subTest(name), tempfile.TemporaryDirectory() as tmp:
                tmp_dir = Path(tmp)
                live_path = tmp_dir / "live.json"
                if live_text is not None:
                    live_path.write_text(live_text, encoding="utf-8")
                checked_in = json.dumps(live_manifest(versionName="0.7.0", versionCode=80, minSupportedVersionCode=80))
                manifest_path = tmp_dir / "latest.json"
                manifest_path.write_text(checked_in, encoding="utf-8")
                index_path = tmp_dir / "index.html"
                index_path.write_text(SAMPLE_INDEX_HTML, encoding="utf-8")

                with self.assertRaises(SystemExit) as raised:
                    self.run_cli("--live-manifest", live_path, "--manifest", manifest_path, "--index-html", index_path)

                self.assertNotEqual(raised.exception.code, 0)
                self.assertEqual(manifest_path.read_text(encoding="utf-8"), checked_in)
                self.assertEqual(index_path.read_text(encoding="utf-8"), SAMPLE_INDEX_HTML)

    def test_download_page_that_cannot_be_rendered_stops_before_writing_manifest(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            live_path = tmp_dir / "live.json"
            live_path.write_text(json.dumps(live_manifest()), encoding="utf-8")
            manifest_path = tmp_dir / "latest.json"
            manifest_path.write_text("{}", encoding="utf-8")
            index_path = tmp_dir / "index.html"
            index_path.write_text("<h1>No release metadata</h1>", encoding="utf-8")

            with self.assertRaises(SystemExit) as raised:
                self.run_cli("--live-manifest", live_path, "--manifest", manifest_path, "--index-html", index_path)

            self.assertNotEqual(raised.exception.code, 0)
            self.assertEqual(manifest_path.read_text(encoding="utf-8"), "{}")

    def test_validate_manifest_rejects_min_supported_above_version_code(self):
        with self.assertRaises(ManifestError):
            validate_manifest(live_manifest(minSupportedVersionCode=86))
        self.assertEqual(validate_manifest(live_manifest()), live_manifest())

    def test_selected_tag_release_data_updates_dispatch_revision_website(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_dir = Path(tmp)
            tag_meta = tmp_dir / "tag"
            (tag_meta / "app").mkdir(parents=True)
            (tag_meta / "app" / "build.gradle.kts").write_text(
                'android { defaultConfig { minSdk = 26\n versionCode = 85\n versionName = "1.2.1" } }',
                encoding="utf-8",
            )
            changelog_dir = tag_meta / "changelogs"
            changelog_dir.mkdir()
            (changelog_dir / "85.txt").write_text("Tagged release notes", encoding="utf-8")
            apk_path = tmp_dir / "tubik-v1.2.1.apk"
            apk_path.write_bytes(b"released-apk")

            website = tmp_dir / "website"
            (website / "updates").mkdir(parents=True)
            manifest_path = website / "updates" / "latest.json"
            manifest_path.write_text(json.dumps(live_manifest(versionName="0.7.0")), encoding="utf-8")
            index_path = website / "index.html"
            index_path.write_text(SAMPLE_INDEX_HTML, encoding="utf-8")

            self.run_cli(
                "--tag", "v1.2.1",
                "--apk", apk_path,
                "--gradle", tag_meta / "app" / "build.gradle.kts",
                "--changelog-dir", changelog_dir,
                "--published-at", "2026-11-17T09:00:00Z",
                "--manifest", manifest_path,
                "--index-html", index_path,
            )

            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            self.assertEqual(manifest["versionName"], "1.2.1")
            self.assertEqual(manifest["versionCode"], 85)
            self.assertEqual(manifest["changelog"], "Tagged release notes")
            self.assertEqual(manifest["publishedAt"], "2026-11-17T09:00:00Z")
            self.assertEqual(manifest["apkUrl"], "https://tubik-releases.23p.dev/tubik-v1.2.1.apk")
            index_html = index_path.read_text(encoding="utf-8")
            self.assertEqual(sync_index_html(index_html, manifest), index_html)
            self.assertIn("<h1>Dispatch revision website</h1>", index_html)
            self.assertIn('<span id="release-version">v1.2.1</span>', index_html)


if __name__ == "__main__":
    unittest.main()
