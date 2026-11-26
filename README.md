# Tubik

Tubik is an Android YouTube client for all ages — built for anyone who wants to cut out music and YouTube's frenzied trending feeds, whether for yourself or a child's device. **Trending is gone.** Watch only your **Allowed Channels** with the **Allowlist**, strip music from audio in realtime with **Music Removal**, and optionally lock Settings behind a **Parent PIN** and **Screen Pin**.

Signed APKs (Android 8.0+ / API 26) are published at [tubik.23p.dev](https://tubik.23p.dev) and on [GitHub Releases](https://github.com/bilalbox/tubik/releases). The app installs beside other YouTube clients as `dev.twenty3p.tubik`.

## No trending

The trending tab is gone, and so is the home screen that led with it: both are removed from the app entirely, launcher shortcuts included, and Tubik never fetches trending feeds. Tubik does not open on a page of whatever is popular today. The bottom bar is Search, Subscriptions, and Library, with Downloads available as an optional tab.

## Allowlist

The **Allowlist** limits the app to **Allowed Channels**: the channels you are subscribed to. Subscriptions are the single source of truth.

While the Allowlist is on:

- Feeds, search, playlists, history, downloads, Shorts, and live streams include only Allowed Channels. Other channels are left out of those results.
- Related videos and comments stay hidden.
- Playback of a video from any other channel is refused.
- Subscribe and unsubscribe are unavailable, so the set of channels stays fixed.

To add or remove a channel, turn the Allowlist off, change subscriptions, and turn it back on.

The Allowlist starts off. Until you turn it on, search can still reach any channel. Trending stays gone either way.

## Music Removal

**Music Removal** takes the music out of the audio as it plays on the device: in the player, in the background, in picture-in-picture, and in downloads. Speech remains, in mono. Sound effects go with the music.

- **Overload Mute:** If the speech model cannot load or the device falls behind realtime throughput, Tubik first switches to a lighter speech model. If that also fails, it silences playback audio rather than playing unprocessed audio.
- **Channel Opt-Out:** You can turn off Music Removal for a specific channel on its channel page or subscription row while the global Music Removal switch stays on.
- **Preferred Speed:** Playback speed is capped at 1.5× while Music Removal is active, preserving your chosen speed across channels.

Music Removal starts on.

## Parent PIN

You can set a **Parent PIN** (stored only on the device as a salted hash) and turn on **Lock Settings**. With the lock on, opening Settings asks for the PIN. The Allowlist, Music Removal, and Screen Pin switches and in-app updates live in Settings, so the lock covers them.

Once a Parent PIN is set, Tubik asks for it before any change that loosens protection or moves data in or out: turning off the Allowlist, Music Removal, Screen Pin, or Lock Settings, opting a channel out of Music Removal, unsubscribing, importing or exporting subscriptions or playlists, importing watch history, and creating or restoring a backup. Changes that tighten protection, such as turning a switch back on or subscribing to a channel, need no PIN.

Settings open without a PIN until you set one and turn the lock on.

## Screen Pin

**Screen Pin** adds a one-touch button in the toolbar and player controls that locks the device to Tubik with Android's system screen pinning. Unpinning follows the system gesture: hold Back and Overview, or swipe up and hold. In-app updates are not installed while the screen is pinned.

## Watching & local data

Tubik plays YouTube videos without ads and without a Google account.

- Subscriptions, subscription groups, playlists, and playlist bookmarks
- Watch history, search history, downloads, and crash logs kept strictly on the device
- Default video (1080p) and audio (best) download qualities, set under Settings > General > Downloads
- Local backup and import/export support for your data (no cloud or sync servers)
- [SponsorBlock](https://sponsor.ajay.app/), [Return YouTube Dislike](https://www.returnyoutubedislike.com/), and optional [DeArrow](https://dearrow.ajay.app/)
- Optional automatic in-app update checks against [tubik.23p.dev/updates/latest.json](https://tubik.23p.dev/updates/latest.json) with SHA-256 and signing-certificate verification

Details on what stays on the device and what network requests the app makes are in the [privacy policy](PRIVACY_POLICY.md).

## Security & Verification

Official Tubik APKs are built and signed exclusively by GitHub Actions using APK Signature Scheme v2 and v3 (with v1 JAR signing disabled).

### Signing certificate fingerprint

- **Package ID:** `dev.twenty3p.tubik`
- **Certificate DN:** `CN=Tubik, O=twenty3p.dev`
- **SHA-256 (colon-separated):** `E7:88:0A:AE:08:10:29:E2:DF:33:6F:86:54:21:7F:72:E8:84:BF:BC:C9:9A:C5:A2:5E:EF:FE:03:38:A8:84:08`
- **SHA-256 (hex):** `e7880aae081029e2df336f8654217f72e884bfbcc99ac5a25eeffe0338a88408`

You can verify a downloaded APK on-device with [AppVerifier](https://github.com/soupslurpr/AppVerifier) or on a desktop using `apksigner` and GitHub CLI build provenance attestations:

```bash
apksigner verify --print-certs tubik-v1.2.5.apk
gh attestation verify tubik-v1.2.5.apk --owner bilalbox
```

### Permissions

Tubik does not use Google accounts, cookies, or usage analytics, and requests only the permissions needed for playback, downloads, notifications, and verified self-updates:

| Permission | Purpose |
| --- | --- |
| `INTERNET` | Stream YouTube videos and fetch feeds, SponsorBlock, Return YouTube Dislike, DeArrow, and update manifests |
| `ACCESS_NETWORK_STATE` | Check connectivity before playback, downloads, and background checks |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `FOREGROUND_SERVICE_DATA_SYNC` | Keep background/PiP audio playback and offline video downloads running reliably |
| `WAKE_LOCK` | Prevent CPU sleep while audio playback or downloads are active |
| `POST_NOTIFICATIONS` | Display media playback controls, download progress, new-video alerts, and update notifications |
| `RECEIVE_BOOT_COMPLETED` | Reschedule periodic new-video and update checks after device reboot |
| `REQUEST_INSTALL_PACKAGES` | Hand verified in-app updates (after SHA-256 and signing-certificate checks) to Android's package installer |

Tubik requests **no** access to contacts, location, camera, microphone, accounts, or shared device storage.

## A separate project

Tubik is disconnected from [LibreTube](https://github.com/libre-tube/LibreTube). This repository, these releases, and this issue tracker are Tubik's. LibreTube's maintainers, releases, and community channels belong to that project.

The code began there. LibreTube's contributors published a working private YouTube client, and Tubik is built on that work. Thank you for the foundation. From here the app is its own: its own name, its own direction, and its own changes.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Local quality checks run via `./tools/quality/gate.sh fast` and `./tools/quality/gate.sh full` (or `make gate-fast` / `make gate`).

## License

Tubik is free software under the [GNU General Public License v3 or later](https://www.gnu.org/licenses/gpl-3.0.en.html). The LibreTube code it is built from is under the same license.
