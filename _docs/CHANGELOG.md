# Changelog

## 2.0.0

- **Gilded Jukebox** — a new block that plays custom discs with per-block controls: audible range, volume, repeat, and play/stop, all in a settings screen. Crafted by surrounding a jukebox with gold ingots. Comparator output and redstone behave like a vanilla jukebox.
- **Radio discs** — HTTP/icecast radio and live streams now play as endless streams (shown as "LIVE" instead of a length), with automatic reconnection on brief dropouts.
- **Track artwork** — disc tooltips and the maker screen now show the track's cover image (downloaded and cached client-side).
- **Failure reasons** — when a URL can't be resolved, the maker now says why (unsupported link, unavailable/private, region-locked, age-restricted, offline, or blocked).
- **Anvil naming** — custom discs are named after their track and honor anvil renames.
- Security: added an SSRF guard on every URL entry point (blocks internal/reserved IPs and non-http(s) schemes), image-download hardening, and a `maxPlaybackRange` config cap. Fixed an audio-source leak when playback was stopped before the stream opened.

## 1.2.1

- Fixed YouTube links with playlist parameters (`&list=...`) failing to load — single-track links are now normalized to the bare video before streaming
- Music Disc Maker, Blank Disc, and disc-to-Blank recipes now appear in the vanilla recipe book once you have the ingredients (recipe unlock advancements added)

## 1.2.0

- Playback range is now configurable (`playbackRange` config option, default 64, range 16-256 blocks) — hear custom discs from farther away
- Fixed audio not resuming after reconnecting or joining late while a custom disc was already playing (jukeboxes are now rescanned on chunk load / relog)

## 1.0.0

Initial release for Minecraft 1.21.1 (NeoForge).

- Music Disc Maker block: paste a track URL + insert a Blank Disc to craft a Custom Music Disc.
- Streams audio from YouTube, Spotify, SoundCloud, Bandcamp, and direct stream URLs (Spotify matched by song + artist).
- Custom discs play in a vanilla jukebox with positional audio and the "Now Playing" overlay.
- Per-track disc color; tooltip shows song title, artist, and length.
- Recipes: Blank Disc (lapis lazuli + iron), Music Disc Maker block, and converting existing discs back into Blank Discs.
- In-game text localized in 14 languages.
