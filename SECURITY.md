# Security Policy

## Supported Versions

Tubik follows a rolling release model from `master`. Security fixes are shipped in the latest release and are not backported to older versions.

| Version | Supported |
| ------- | --------- |
| Latest release (`1.3.x`) | :white_check_mark: |
| `< 1.3` | :x: |

The latest supported release and its SHA-256 checksum are always listed at [tubik.23p.dev](https://tubik.23p.dev) (`https://tubik.23p.dev/updates/latest.json`) and on [GitHub Releases](https://github.com/bilalbox/tubik/releases/latest).

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues.**

This repository has GitHub Private Vulnerability Reporting enabled. Submit reports privately via either:

- **[GitHub Security Advisories](https://github.com/bilalbox/tubik/security/advisories/new)** (preferred)
- **Email:** [security@23p.dev](mailto:security@23p.dev)

To help triage and reproduce the issue quickly, please include:

- Tubik version (`versionName` / `versionCode`) and where you obtained the APK
- Android version and device model (for app issues)
- Clear step-by-step reproduction instructions or a proof of concept
- Expected vs. actual behavior and potential impact

### What to Expect

Tubik is maintained as a free-software project on a best-effort basis:

- **Acknowledgment:** Within **7 days** of receiving your report.
- **Triage & assessment:** Within **14 days**, you will receive an update confirming whether the issue is accepted or declined, along with next steps.
- **Fix & disclosure:** For accepted vulnerabilities, we will work on a fix in a private branch/advisory and coordinate public disclosure once a patched release is published. If a report is declined, we will explain why.

## Scope

Examples of security-relevant issues for Tubik include:

- **Access control bypasses:** Circumventing the **Allowlist**, **Parent PIN** (Settings lock, subscription changes, **Channel Opt-Out**, backup restore, or update gating), or **Screen Pin** protections.
- **Fail-open audio leakage:** Bypassing **Music Removal** or **Overload Mute** so unprocessed audio plays while **Music Removal** is active (outside an explicit **Channel Opt-Out**).
- **Update & supply-chain integrity:** Bypassing SHA-256 verification, signing certificate checks, or release integrity for `dev.twenty3p.tubik` updates (`tubik.23p.dev/updates/latest.json` and `tubik-releases.23p.dev`).
- **Privacy & local data exposure:** Unintended off-device transmission of the **Parent PIN**, subscriptions, history, or crash logs, or unsafe handling of local backups/imports.
- **Website & release pipeline:** Vulnerabilities in `tubik.23p.dev`, `tubik-releases.23p.dev`, or the repository's GitHub Actions workflows.

### Out of Scope

- Vulnerabilities in third-party services Tubik connects to (YouTube, SponsorBlock, Return YouTube Dislike, DeArrow) — please report those to the respective service.
- Bypasses requiring root access, unlocked bootloader, ADB, or physical system-level overrides (such as OS-level exits from Android screen pinning that Android itself permits).
- Reports from automated scanners without a concrete reproduction or impact.

## Verifying Official Releases

Official Tubik release APKs (`dev.twenty3p.tubik`) are signed with APK Signature Scheme v2/v3, distributed via `tubik-releases.23p.dev` and GitHub Releases, and attested with GitHub Artifact Attestations. You can verify a downloaded APK's SHA-256 digest against `https://tubik.23p.dev/updates/latest.json` or verify its build provenance with:

```bash
gh attestation verify tubik-v<version>.apk --repo bilalbox/tubik
```
