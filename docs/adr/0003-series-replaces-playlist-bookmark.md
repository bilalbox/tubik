# Series replaces Playlist Bookmark

Following a playlist for new episodes (#17) uses the existing Playlist Bookmark instead of adding a second concept. Every bookmark becomes a **Series**: it is checked for new videos on the daily feed refresh and shown under "Series" in Library. The UI says Follow instead of Bookmark. Existing bookmarks are migrated in place. A passive saved playlist does little in an app for following lectures and audiobooks, and two near-identical lists in Library (bookmarks and followed playlists) would be the duplication the glossary exists to prevent.

## Considered options

- **A Follow flag on bookmarks.** Rejected: Library ends up with two lists that differ only by whether new episodes are checked.
- **A separate followed-playlist table.** Rejected: it would duplicate everything bookmarks already do (Allowlist filtering by owner, backup, the Library row, toggles in the playlist screen and long-press sheet).
- **Calling them Podcasts.** Rejected as a term. Tying the name to the channel's audio-only setting would make a Series change name when that setting is changed. "Podcast" also suggests RSS and adding a show by URL, which Tubik doesn't do. It is used only in descriptions (README, landing page, empty states).

## Consequences

- Every bookmark costs at least one request per daily refresh. Pages are read only when a playlist's video count changes, and at most 3 Series are checked at a time.
- The first check of a Series records its current videos as known without marking them new. That includes migrated bookmarks, so an upgrade doesn't flood the feed with false "new" episodes.
- Series videos have their own storage and are kept for as long as the Series is followed. The feed cache's one-year and unsubscribed-channel pruning must not remove them.
- The Allowlist rules for bookmarks carry over unchanged. A Series whose owner isn't an Allowed Channel is hidden, not deleted. Follow is hidden for such playlists, and following never adds an Allowed Channel.
- With the Allowlist on, a Series can only come from a subscribed channel. **Feed Scope** (All uploads / Series only) keeps that channel's other uploads from flooding the feed.
- Old backups restore their bookmarks as Series.
