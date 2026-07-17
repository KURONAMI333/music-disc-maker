# Changelog

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
