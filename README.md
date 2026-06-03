# Music Disc Maker

> Paste a URL, turn a blank disc into a custom music disc, and play it in any vanilla jukebox.
> **NeoForge 1.21.1.**

Music Disc Maker adds a crafting block that takes a **Blank Disc** + a **URL** and produces a
**Custom Music Disc** playable in the ordinary Minecraft jukebox — bringing the "paste a link, hear it instantly"
experience to Minecraft's disc culture.

## Supported services

YouTube · Spotify · SoundCloud · Bandcamp · Vimeo · Twitch · direct HTTP audio streams

Spotify track links are matched by song + artist (the audio is then found automatically). Audio is
streamed and decoded with [LavaPlayer](https://github.com/lavalink-devs/lavaplayer),
**bundled inside the mod jar** — no extra install, no external programs (no ffmpeg / yt-dlp).

## How to use

1. Craft a **Music Disc Maker** block and a **Blank Disc** (lapis lazuli + iron).
2. Right-click the block to open its GUI.
3. Paste a track URL into the field and insert a Blank Disc in the input slot. As soon as both are
   present, a **Custom Music Disc** is created in the output — one disc per link (the field then clears).
4. Take the **Custom Music Disc** and put it in a vanilla **Jukebox**. It plays as positional audio
   (fades with distance, follows the Records volume slider). Right-click the jukebox again to eject and stop.

## Multiplayer

Server-authoritative: the URL is stored on the block / disc, and on playback the server broadcasts it to
nearby players who each decode independently (≈1–2 s sync tolerance). No voice-chat mod required.

## Config (client)

- `volumeMultiplier` (default `0.5`) — playback volume relative to the Records slider.
- `maxConcurrent` (default `16`) — max custom discs playing at once.

## Disclaimer

Streaming audio from third-party services is **the user's responsibility**. Please respect the Terms of
Service of YouTube, SoundCloud, and any other service you use. This mod is a tool; how you use it is up to you.

## Requirements

- Minecraft 1.21.1
- NeoForge 21.1.x

## Building

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew build
```

The output jar (in `build/libs/`) bundles LavaPlayer and its dependencies in a `dependencies/` folder,
loaded at runtime by an isolated class loader so they don't clash with the game's own libraries.

## License & credits

- Mod code: [MIT](LICENSE).
- Bundled audio libraries (LavaPlayer etc.): see `META-INF/NOTICE` and `META-INF/LICENSE-DEPENDENCIES`.
- LavaPlayer-on-NeoForge integration approach inspired by
  [MC-U-Team/Music-Player](https://github.com/MC-U-Team/Music-Player).
