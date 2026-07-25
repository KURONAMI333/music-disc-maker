# Changelog

## 3.0.0 (unreleased)

- **Beat-reactive redstone** — the Golden Jukebox's comparator now outputs the *beat strength* of whatever is playing, 0-15, instead of a fixed per-song value. The track is analysed once on the server and cached, so every player sees the same signal, it works on an empty server, and it comes out identical every time you replay it — good for builds and recordings. Server config exposes the tuning: band (kick / snare / hats / full mix), envelope or onset mode, sensitivity, floor, attack/release, and an output offset that can be **negative** so the redstone fires *ahead* of the music to cancel out mechanical delay in Create contraptions.
- **Breaking (redstone):** the Golden Jukebox comparator no longer reports the song's identity value. If you were reading which disc was inserted, read it from a **vanilla** jukebox instead. Direct redstone from the block (full signal while playing) is unchanged, so read the beat through a comparator, not through dust touching the jukebox.
- **Breaking (redstone):** a custom disc in a **vanilla** jukebox now always gives comparator output **15**, instead of a 1-15 value scaled by track length. Circuits that sorted custom discs by length will need rewiring.
- Radio and livestream discs do not drive the beat output (their length is open-ended, so there is nothing to analyse ahead of time) — the comparator stays at 0 while they play.
- **Boombox** — a portable player you carry, not a block you place. Right-click anywhere to start or stop it, shift + right-click for its own small screen (disc slot, volume, directional audio). It keeps playing while it is anywhere in your inventory, so switching to your pickaxe, moving it between slots or holding it on the cursor does not cut the music. Dropping it, putting it in a chest or dying stops it. Two Boomboxes loaded with the same link play and stop independently.
  - Because it is no longer a block, holding a Boombox makes right-click always control the music: you cannot open a chest or a furnace with a Boombox in your main hand. Put it in your off-hand or another slot to interact with blocks normally.
  - Volume and directional audio apply to the track already playing, with no gap.
- **Local audio cache (off by default)** — with `audioCacheEnabled` turned on in the client config, a track you have played all the way through is stored on your PC as a compressed local copy, and every later play of that disc comes from that copy instead of the internet. This is a guard against the sources themselves breaking: when YouTube changed its signature scheme in 2026, every disc in every published version stopped playing for weeks. With the cache on, anything you had already listened to keeps playing on that PC regardless. Once a track is cached it also starts instantly and uses no bandwidth at all.
  - It is **off by default** — turning it on means audio files accumulate on your disk, so that is your call to make. NeoForge exposes it in the in-game config screen (Mods → Music Disc Maker → Client).
  - Only a play that starts at the beginning **and reaches the end** is stored. A track you seek into, stop halfway, or that gets cut off by a bad connection is never written, so a truncated copy can never get baked in.
  - **SoundCloud is never cached** — their terms explicitly forbid storing content persistently. Radio and livestream discs are never cached either (they have no end).
  - Budget defaults to 1024 MB (`audioCacheMaxMB`), roughly 700 four-minute tracks at about 1.5 MB each; the least recently played are dropped first. Files live in `music_disc_maker/cache/*.audio` and can be deleted by hand at any time. Turning the setting back off stops all reading and writing but leaves your files alone.
  - Fabric has no config mechanism, so the cache is unavailable there for now.

## 2.1.0

- **Create & Create Aeronautics support** — a custom disc playing in a Golden Jukebox now keeps playing and follows the jukebox when it is assembled onto a moving Create contraption (cart, train, piston, bearing) or a Create Aeronautics physics airship, instead of going silent when the block leaves its spot. Requires Create (Aeronautics optional).
- **Live audible-range changes** — dragging the Golden Jukebox's range slider now updates the audible radius instantly on the currently playing track, with no momentary silence (it now works exactly like the volume slider).
- Fixed YouTube playback failing with "Must find sig function" after YouTube changed its signature scheme. Updated the YouTube resolver so links load and play again.

## 2.0.1

- Fixed YouTube bot-check failures being mislabeled as "Offline". When YouTube flags the server's IP as a bot and demands sign-in (common on hosted or rented servers), the maker now shows a distinct "Login wall" status with a hover tooltip explaining it isn't a connection problem, instead of a misleading "Offline".
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
