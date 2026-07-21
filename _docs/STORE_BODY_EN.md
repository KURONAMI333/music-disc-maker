<!--
KURONAMI store description (Modrinth body / CurseForge description — shared, English).
Written to knowledge/STORE_BODY_FRAMEWORK.md (no version table, function-first hook, facts over adjectives).

DRAFT for v2.0.0 — publish at release, not before.

== Reconcile against the shipped build before publishing ==
- Enhanced-jukebox display name is not final. Candidates: "Tuned Jukebox" (current en_us.json) / "Gilded Jukebox". This body uses "Tuned Jukebox" to match the shipped lang. If the final name differs, swap it here and in the changelog.
- Gallery screenshots (jacket display, Tuned Jukebox GUI) are a separate asset step — add captioned images before publish.

== Store fields (set separately from the body) ==
SUMMARY: Craft playable music discs from any YouTube, Spotify, or SoundCloud link and play them in a vanilla jukebox.
MODRINTH categories: decoration, utility | loaders: fabric, neoforge | versions: 1.20.1, 1.21.1, 26.1.2 | env: client + server (required both)
MODRINTH slug: music-disc-maker
CURSEFORGE: main category = Miscellaneous; additional = Cosmetic, Server Utility | summary: Craft playable music discs from any YouTube/Spotify/SoundCloud link.
-->

# Music Disc Maker

Paste a track link, insert a blank disc, and get a custom music disc that plays in a vanilla jukebox.

Minecraft only gives you a fixed set of music discs and no way to play your own music. This adds a crafting block: paste a URL (YouTube, Spotify, SoundCloud, Bandcamp, or a direct stream), drop in a blank disc, and it makes a disc that streams that track from an ordinary jukebox, with the vanilla "Now Playing" overlay and positional audio.

Demo: https://youtu.be/7sUJqdRrS3M

**Features**

- Works with YouTube, Spotify, SoundCloud, Bandcamp, and direct stream URLs
- Spotify links resolve by song and artist, so you get the actual track instead of a video title
- The disc shows the track's cover art in its tooltip and in the maker's preview
- The tooltip shows the title, artist, and length
- Rename a disc on an anvil to whatever you want
- On a multiplayer server everyone hears it, including players who walk up mid-song, synced to the current position
- If a link can't be used, the maker tells you why (unavailable, geo-blocked, age-locked, offline, or unsupported) instead of a generic failure

**Tuned Jukebox**

A jukebox crafted with gold that adds playback controls. Open it to set:

- Audible range from 16 to 256 blocks (a vanilla jukebox reaches about 64)
- Volume from 0 to 200%
- Repeat on or off
- Play and stop from the screen

Craft it by surrounding a vanilla jukebox with gold ingots.

**Radio discs**

Paste an internet radio stream (Icecast/SHOUTcast) or a YouTube live URL and you get a disc that never ends. It shows LIVE instead of a length and reconnects on its own if the stream drops. Twitch is not supported.

**How to use**

1. Craft a Blank Disc (lapis lazuli + iron) and a Music Disc Maker block. Both recipes show up in the recipe book.
2. Open the block, paste a track URL, and insert the Blank Disc. A Custom Music Disc appears in the output.
3. Put the disc in a vanilla jukebox, or a Tuned Jukebox if you want range, volume, and repeat control.

**Compatibility**

- Additional Additions: drop custom discs into an Additional Additions album and they play their real audio.
- Sophisticated Backpacks: custom discs play from a backpack's jukebox upgrade.

**Notes**

- Audio is streamed from the source each time it plays. It isn't stored inside the disc, so it needs an internet connection. Some tracks may be region-locked or unavailable.
- Install it on the server and on every client. Audio plays client-side. No other mods required.

Free to use in any modpack. Source and issues: https://github.com/KURONAMI333/music-disc-maker

---

## Changelog

### 2.0.0

- New **Tuned Jukebox**: a gold-crafted jukebox with in-game controls for audible range (16–256 blocks), volume, repeat, and play/stop.
- **Radio discs**: internet radio (Icecast/SHOUTcast) and YouTube live URLs now make endless discs that show LIVE and reconnect automatically. Twitch is not supported.
- Discs now show the track's **cover art** in the tooltip and in the maker's preview.
- **Failed links report a reason** (unavailable, geo-blocked, age-locked, offline, or unsupported) instead of a generic failure.
- **Rename discs on an anvil.**
- **Additional Additions** albums now play the real audio of custom discs.
- **Sophisticated Backpacks** jukebox-upgrade support.
- Recipes now appear in the **recipe book**.
- Stability and security improvements, including audio resuming after you rejoin a world.
