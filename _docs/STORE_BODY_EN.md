<!--
KURONAMI store description (Modrinth body / CurseForge description — shared, English).
Written to knowledge/STORE_BODY_FRAMEWORK.md (no version table, function-first hook, facts over adjectives).

== Store fields (set separately from the body) ==
SUMMARY: Craft playable music discs from any YouTube, Spotify, or SoundCloud link and play them in a vanilla jukebox.
MODRINTH categories: decoration, utility | loaders: neoforge | versions: 1.21.1 | env: client + server (required both)
MODRINTH slug: music-disc-maker
CURSEFORGE: main category = Miscellaneous; additional = Cosmetic, Server Utility | summary: Craft playable music discs from any YouTube/Spotify/SoundCloud link.
-->

# Music Disc Maker

Paste a track link, insert a blank disc, and get a custom music disc that plays in a vanilla jukebox.

Minecraft only gives you a fixed set of music discs and no way to play your own music. This adds a crafting block: paste a URL (YouTube, Spotify, SoundCloud, Bandcamp, or a direct stream), drop in a blank disc, and it makes a disc that streams that track from an ordinary jukebox — with the vanilla "Now Playing" overlay and positional audio.

<iframe width="560" height="315" src="https://www.youtube.com/embed/7sUJqdRrS3M" title="Music Disc Maker demo" frameborder="0" allowfullscreen></iframe>

**Features**

- Works with YouTube, Spotify, SoundCloud, Bandcamp, and direct stream URLs
- Spotify links resolve by song and artist, so you get the actual track instead of a video title
- Each song gets its own disc color, and the same song always looks the same
- The disc tooltip shows the title, artist, and length
- On a multiplayer server everyone hears it — including players who walk up mid-song, synced to the current position

**How to use**

1. Craft a Blank Disc (lapis lazuli + iron) and a Music Disc Maker block.
2. Open the block, paste a track URL, and insert the Blank Disc — a Custom Music Disc appears in the output.
3. Put the disc in a vanilla jukebox to play it.

**Notes**

- Audio is streamed from the source each time it plays — it isn't stored inside the disc, so it needs an internet connection. Some tracks may be region-locked or unavailable.
- Install it on the server and on every client; audio plays client-side. No other mods required.

Free to use in any modpack. Source and issues: https://github.com/KURONAMI333/music-disc-maker
