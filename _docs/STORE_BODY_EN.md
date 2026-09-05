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
- If a disc fails while playing, the reason stays in chat and in the log instead of going silent

**Golden Jukebox**

A jukebox crafted with gold that adds playback controls. Open it to set:

- Audible range from 16 to 256 blocks (a vanilla jukebox reaches about 64)
- Volume from 0 to 200%
- Directional audio on or off — off plays the track at a flat volume anywhere inside the range, like background music
- Repeat on or off
- A seek bar to scrub through the track, and play/stop from the screen

Range and volume changes apply instantly to the track that is already playing. Craft it by surrounding a vanilla jukebox with gold ingots.

**Radio discs**

Paste an internet radio stream (Icecast/SHOUTcast) or a YouTube live URL and you get a disc that never ends. It shows LIVE instead of a length and reconnects on its own if the stream drops. Twitch is not supported.

**How to use**

1. Craft a Blank Disc (lapis lazuli + iron) and a Music Disc Maker block. Both recipes show up in the recipe book.
2. Open the block, paste a track URL, and insert the Blank Disc. A Custom Music Disc appears in the output.
3. Put the disc in a vanilla jukebox, or a Golden Jukebox if you want range, volume, and repeat control.

**Compatibility**

- Additional Additions: drop custom discs into an Additional Additions album and they play their real audio.
- Sophisticated Backpacks: custom discs play from a backpack's jukebox upgrade.
- Traveler's Backpack: the same, from its jukebox upgrade slot. (NeoForge only on 1.21.11.)
- Create: a Golden Jukebox assembled onto a moving contraption keeps playing and follows it (1.21.1, and Forge on 1.20.1). Create Aeronautics physics airships too, on 1.21.1.
- Valkyrien Skies 2, Eureka! and Clockwork (1.20.1): music follows the ship instead of staying at the ship's origin.

**Notes**

- Audio is streamed from the source each time it plays. It isn't stored inside the disc, so it needs an internet connection. Some tracks may be region-locked or unavailable.
- Not all sources are equally reliable. Direct audio-file links, SoundCloud, and Bandcamp don't do bot checks. YouTube (and Spotify, which resolves through a YouTube search) can occasionally hit a login/bot-check wall on some server IPs — most common on hosted or rented servers. The maker tells you when that happens; a direct link or one of the other sources is the fix.
- No other mods are required.
- **The server and every client must run the same version of this mod.** From 2.2.0 on, NeoForge and Forge reject a mismatch when you connect. Fabric has no version handshake, so a mismatch there is not caught and will misbehave instead — update both sides together.

Bugs and questions: comment on the CurseForge page, or DM @kuronami333 on X.

All Rights Reserved. Free to put in any modpack, on any platform, monetised or not - no permission needed, no credit required. Source is published so you can read exactly what it does: https://github.com/KURONAMI333/music-disc-maker
