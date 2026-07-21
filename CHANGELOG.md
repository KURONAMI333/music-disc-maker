# Changelog

## 2.0.1

- Removed the cover art preview from the Music Disc Maker screen (it overlapped the UI). The album art still shows as a tooltip image on the disc itself.

## 2.0.0

- **Golden Jukebox** — a new craftable block (surround a jukebox with gold ingots) with a music-player-style GUI: a seek bar to scrub through the track, play/stop and repeat controls, and per-block sliders for audible range (16-256 blocks) and volume (0-200%), so one jukebox can fill a large area while another stays quiet. The GUI shows the current track's title and album art — for vanilla and other mods' discs as well as custom ones. Comparator output and redstone behave like a vanilla jukebox.
- **Radio & livestream discs** — infinite HTTP/icecast radio streams and YouTube live now play as endless discs, shown as "LIVE" instead of a length, with automatic reconnection on brief dropouts.
- **Album art on discs** — the track's thumbnail (PNG or JPEG) is downloaded and shown as a tooltip image on the disc and as a preview in the Music Disc Maker GUI (cached locally, https-only).
- **Reason-specific failure messages** — the maker now tells you *why* a URL failed (unsupported, unavailable/private, geo-blocked, age-restricted, offline, or blocked) instead of a generic error.
- **Name your discs** — discs are automatically named after their track, and anvil renames are respected.
- **Additional Additions albums** — custom discs placed in an Additional Additions album now play their real audio through the album's jukebox.
- **Sophisticated Backpacks / Storage** — custom discs now play from the Jukebox Upgrade in Sophisticated Backpacks and Storage.
- Playback range is now configurable (`playbackRange`, default 64, 16-256 blocks) so you can hear custom discs from farther away, with a server-side `maxPlaybackRange` cap.
- Music Disc Maker, Blank Disc, and disc-to-Blank recipes now appear in the vanilla recipe book once you have the ingredients (recipe unlock advancements).
- Fixed audio not resuming after reconnecting or joining late while a custom disc was already playing (jukeboxes are rescanned on chunk load / relog).
- Fixed YouTube links with playlist parameters (`&list=...`) failing to load — single-track links are normalized to the bare video before streaming.
- Security: added an SSRF guard on every URL entry point (blocks internal/reserved IPs and non-http(s) schemes) plus image-download hardening. Fixed an audio-source leak when playback was stopped before its stream opened.

## 1.2.1

- Fixed YouTube links with playlist parameters (`&list=...`) failing to load — single-track links are now normalized to the bare video before streaming
- Music Disc Maker, Blank Disc, and disc-to-Blank recipes now appear in the vanilla recipe book once you have the ingredients (recipe unlock advancements added)

## 1.2.0

- Playback range is now configurable (`playbackRange` config option, default 64, range 16-256 blocks) — hear custom discs from farther away
- Fixed audio not resuming after reconnecting or joining late while a custom disc was already playing (jukeboxes are now rescanned on chunk load / relog)
