# Changelog

## 2.0.1

- Fixed YouTube bot-check failures being mislabeled as "Offline". When YouTube flags the server's IP as a bot and demands sign-in (common on hosted or rented servers), the maker now shows a distinct "Login wall" status with a hover tooltip explaining it isn't a connection problem, instead of a misleading "Offline".
- Removed the cover art preview from the Music Disc Maker screen (it overlapped the UI). The album art still shows as a tooltip image on the disc itself.

## 2.0.0

- **Golden Jukebox** — a new craftable block (surround a jukebox with gold ingots) with a settings GUI: adjust the audible range (16-256 blocks), volume (0-200%), toggle repeat, and play/stop. Range and volume apply per block, so one jukebox can fill a large area while another stays quiet. Comparator output and redstone behave like a vanilla jukebox.
- **Radio & livestream discs** — infinite HTTP/icecast radio streams and YouTube live now play as endless discs, shown as "LIVE" instead of a length, with automatic reconnection on brief dropouts.
- **Album art on discs** — the track's thumbnail is downloaded and shown as a tooltip image on the disc and as a preview in the Music Disc Maker GUI (cached locally, https-only).
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

## 1.0.0

Initial release for Minecraft 1.21.1 (NeoForge).

- Music Disc Maker block: paste a track URL + insert a Blank Disc to craft a Custom Music Disc.
- Streams audio from YouTube, Spotify, SoundCloud, Bandcamp, and direct stream URLs (Spotify matched by song + artist).
- Custom discs play in a vanilla jukebox with positional audio and the "Now Playing" overlay.
- Per-track disc color; tooltip shows song title, artist, and length.
- Recipes: Blank Disc (lapis lazuli + iron), Music Disc Maker block, and converting existing discs back into Blank Discs.
- In-game text localized in 14 languages.
