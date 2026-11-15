# Tubik

A YouTube client for children's devices: only subscribed channels are visible when **Allowlist** is on, music is removed from audio as it plays, and the device can be pinned to the app.

## Language

### Access Control

**Allowed Channel**:
A channel the user is subscribed to while the **Allowlist** is on; subscriptions are the single source of truth.
_Avoid_: whitelisted channel, approved channel

**Allowlist**:
A switch that hides all content not from an **Allowed Channel** across every surface while freezing subscriptions until a parent turns it off.
_Avoid_: whitelist, filter, fence

**Parent PIN**:
The parent code that gates Settings and any action that could weaken the **Allowlist**, **Music Removal**, or **Screen Pin**.
_Avoid_: password, passcode, lock

**Screen Pin**:
A switch offering one-touch locking of the device to the app via Android's screen pinning.
_Avoid_: kiosk mode, lock mode, app lock

### Music Removal & Playback

**Music Removal**:
A switch that removes music from all playback audio in realtime, keeping speech only in mono and muting on failure.
_Avoid_: noise suppression, enhancement, filtering, vocal isolation

**Channel Opt-Out**:
A per-channel override that turns off **Music Removal** for one channel while the global **Music Removal** switch stays on.
_Avoid_: channel whitelist, music bypass

**Overload Mute**:
Silencing playback audio whenever **Music Removal** cannot load its speech model or falls below the required realtime throughput, rather than playing unprocessed audio.
_Avoid_: fallback passthrough, audio dropout

**Preferred Speed**:
The user's chosen playback speed before **Music Removal** caps effective playback speed, preserved across channels that temporarily restrict speed.
_Avoid_: clamped speed, temporary speed
