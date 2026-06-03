<!--
KURONAMI store description (Modrinth body / CurseForge description — shared, English).
Guide: knowledge/STORE_DESCRIPTION_GUIDE.md

== Store fields (set separately from the body) ==
SUMMARY: Paste a YouTube, Spotify, or SoundCloud link to craft a playable record for any vanilla jukebox.
MODRINTH categories: decoration, utility  | loaders: neoforge | versions: 1.21.1 | env: client + server (required both)
MODRINTH slug: music-disc-maker
CURSEFORGE: main category = Miscellaneous; additional = Cosmetic, Server Utility | summary: Craft playable records from any YouTube/Spotify/SoundCloud link.
-->

# Music Disc Maker

> Vanilla gives you a fixed handful of music discs from chests and creepers. This makes your *own* music playable in-game — paste a link, get a disc, drop it in a jukebox.

Want to hear your favorite song in your base? There's no built-in way to bring outside music into Minecraft. Music Disc Maker adds a crafting block: paste a track URL, insert a blank disc, and it streams that song from a regular vanilla jukebox — with the normal "Now Playing" overlay and positional audio.

## Demo

<iframe width="560" height="315" src="https://www.youtube.com/embed/7sUJqdRrS3M" title="Music Disc Maker demo" frameborder="0" allowfullscreen></iframe>

- 🎵 Paste a link → get a custom disc that plays in any **vanilla jukebox**
- Works with **YouTube, Spotify, SoundCloud, Bandcamp**, and direct stream URLs
- **Spotify links are matched by song + artist** — clean titles, not raw video names
- Each track gets its **own disc color**, and the same song always looks the same
- Tooltip shows the **song title (highlighted), artist, and length**

## What it does / Usage

1. Craft a **Blank Disc** (lapis lazuli + iron).
2. Craft and place a **Music Disc Maker** block.
3. Open it, paste a track URL, and insert a Blank Disc. A **Custom Music Disc** appears in the output — one disc per link.
4. Put the disc in a **vanilla jukebox** to play it. Pick it back up to stop.

The block recognizes plain YouTube/SoundCloud/Bandcamp links and Spotify track links (it reads the song and artist from Spotify, then finds the audio). If a link can't be resolved, the GUI shows a short error instead of making a disc.

## Supported loaders / versions

| Minecraft | NeoForge | Forge | Fabric |
|---|:---:|:---:|:---:|
| 1.21.1 | ✅ | — | — |

NeoForge 1.21.1 only for now. Audio plays on the **client**; on a server, both the server and connecting clients need the mod installed.

## Dependencies

None. The audio engine is bundled — no extra mods required.

## Compatibility & scope

Uses the **vanilla jukebox** directly, so it fits into normal redstone/jukebox setups. The Music Disc Maker is a standalone block and does not change any vanilla items or recipes.

## Known limitations

- **Requires an internet connection.** Audio is streamed from the source on playback — it is not stored inside the disc, so the song re-streams each time it plays.
- Availability depends on the source service; some tracks may be region-restricted, private, or removed.
- On servers, a player who arrives **after** a disc has started may not hear it until the disc is taken out and put back (playback is event-based, not re-synced on join).

## Install

1. Install **NeoForge 21.1.x** for Minecraft **1.21.1**.
2. Drop `musicdiscmaker-1.0.0-neoforge-1.21.1.jar` into `mods/`.
3. For multiplayer, install it on the server **and** every client.

- Minecraft 1.21.1 · NeoForge · JDK 21

## Languages

In-game text is available in 14 languages (en, ja, zh-CN, zh-TW, ko, ru, de, fr, es, pt-BR, uk, pl, it, nl). Native-speaker corrections are welcome.

## License

MIT — modpack inclusion welcome, no credit required.

Author: KURONAMI
