# Changelog

## 2.0.0

- **Gilded Jukebox** — a new craftable block (surround a jukebox with gold ingots) with a settings GUI: adjust the audible range (16-256 blocks), volume (0-200%), toggle repeat, and pause/resume. Holds any disc; the range and volume apply per block so you can make one jukebox fill a large area and another stay quiet
- **Album art on discs** — the track's thumbnail is downloaded and shown as a tooltip image on the disc and as a preview in the Music Disc Maker GUI (cached locally, https-only)
- **Radio & livestream discs** — infinite HTTP/icecast radio streams and YouTube live now play as endless discs, shown as "LIVE" instead of a length, with automatic reconnection on brief dropouts
- **Reason-specific failure messages** — the maker now tells you *why* a URL failed (unsupported, unavailable, geo-blocked, age-locked, offline, or blocked) instead of a generic error
- **Name your discs** — discs are automatically named after their track, and anvil renames are respected
- Added an IP-based SSRF guard on every URL entry point (blocks internal/reserved addresses and non-http(s) schemes)
- Fixed an audio-source leak when a disc was stopped before its stream opened

## 1.2.1

- Fixed YouTube links with playlist parameters (`&list=...`) failing to load — single-track links are now normalized to the bare video before streaming
- Music Disc Maker, Blank Disc, and disc-to-Blank recipes now appear in the vanilla recipe book once you have the ingredients (recipe unlock advancements added)

## 1.2.0

- Playback range is now configurable (`playbackRange` config option, default 64, range 16-256 blocks) — hear custom discs from farther away
- Fixed audio not resuming after reconnecting or joining late while a custom disc was already playing (jukeboxes are now rescanned on chunk load / relog)
