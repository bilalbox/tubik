# Tubik is a hard fork of LibreTube, not a build flavor

Tubik (`dev.twenty3p.tubik`) changes LibreTube freely instead of living as a `guarded` product flavor or as an app that consumes LibreTube as a library. A flavor would keep rebasing on upstream cheap. But the Allowlist has to reach every content surface (feeds, search, related videos, comments, playlists, history, downloads, outside links), and Settings has to sit behind a Parent PIN. Keeping all of that behind flavor switches in shared code would cost more than occasionally merging upstream by hand.

## Consequences

- Upstream changes are cherry-picked or merged by hand, not rebased automatically. Most guarded behaviour still lives behind a few seams (`MediaServiceRepository`, `PlayerHelper.createPlayer`, the Settings entry point) to keep merge conflicts small.
- Tubik installs alongside upstream LibreTube, with its own name and icon.
