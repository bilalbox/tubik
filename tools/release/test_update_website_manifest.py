import json
import re
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from update_website_manifest import build_manifest, main, parse_gradle_version, sync_index_html

REPO_ROOT = Path(__file__).resolve().parents[2]
WEBSITE_DIR = REPO_ROOT / "website"


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


if __name__ == "__main__":
    unittest.main()
