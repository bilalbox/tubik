# Tubik privacy policy

Tubik stores what it needs to play videos and to remember your setup. It has no account of its own, and it does not send usage analytics or build an advertising profile.

This policy describes Tubik (`dev.twenty3p.tubik`) and its website (`tubik.23p.dev`). Questions belong in [this repository's issues](https://github.com/bilalbox/tubik/issues).

## On the device

The app stores this on the phone:

- Subscriptions, playlists, followed Series, and downloads
- Watch history and search history, which both start on. Either can be turned off. Turning search history off clears it.
- Settings, including the Allowlist, Music Removal, and Screen Pin
- A Parent PIN, if you set one, stored as a salted hash. The PIN itself is not stored, and it is not sent off the device.

Music Removal runs on the device. The audio is not uploaded.

If the app crashes, it can keep a crash log on the device. That log is not uploaded.

## App updates

Automatic update checks start on and can be turned off in Settings (**Check for updates automatically**). While they are on, Tubik checks `https://tubik.23p.dev/updates/latest.json` about once every 24 hours (and whenever you manually check in Settings) to see if a newer release is available.

If you choose **Download & Install** in Settings, Tubik downloads the signed release APK from the URL listed in `latest.json` (hosted on `https://tubik-releases.23p.dev`) and verifies its SHA-256 checksum and signing certificate before handing it to Android's package installer.

`tubik.23p.dev` (Cloudflare Pages) and `tubik-releases.23p.dev` (Cloudflare R2) are hosted on Cloudflare. The website (`tubik.23p.dev`) uses Cloudflare Web Analytics — anonymous, cookie-free aggregate page-view analytics — and displays aggregate APK download counts. Update checks and APK downloads from the app do not send personal identifiers, device identifiers, or usage analytics. Standard web server connection logs (such as IP address and requested URL) are handled transiently by the hosting CDN without tracking profiles.

## While you watch

Playing, searching, and loading subscriptions and followed Series contact YouTube from this device. YouTube can see the device's IP address and which videos and channels are requested. Tubik does not ask you to sign in with a Google or YouTube account.

New-video notifications start on. While they are on, the app also checks subscriptions and followed Series in the background by contacting YouTube.

Two lookups run during playback unless you turn them off:

- **SponsorBlock** starts on. The video ID is sent to [sponsor.ajay.app](https://sponsor.ajay.app) to fetch skip segments.
- **Dislike counts** start on. The video ID is sent to `https://ryd-proxy.kavin.rocks` to fetch them.

DeArrow starts off. If you turn it on, the video ID is sent to SponsorBlock's branding service and to `https://dearrow-thumb.ajay.app`.

Submitting SponsorBlock segments or DeArrow titles starts off. Turning that on also sends a SponsorBlock user ID. Read SponsorBlock's [privacy policy](https://gist.github.com/ajayyy/aa9f8ded2b573d4f73a3ffa0ef74f796) and [terms of use](https://gist.github.com/ajayyy/9e8100f069348e0bc062641f34d6af12) before contributing.

## Local data & backups

Subscriptions, playlists, watch history, and settings stay strictly on the device. You can back up, import, or export your data locally as files you control.

## YouTube

Tubik is not affiliated with, authorized by, or endorsed by YouTube, Google LLC, or their affiliates. Names and content shown in the app belong to their owners.

## Changes

Using Tubik means you accept this policy. When it changes, the update is in this file and reflected on [tubik.23p.dev/privacy](https://tubik.23p.dev/privacy).
