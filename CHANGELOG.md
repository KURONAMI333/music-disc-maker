# Changelog

## 2.3.0
- **Custom discs now play in Additional Additions' Pocket Jukebox.** Putting one in produced no sound at all, and the pocket jukebox gave up on the track after about a second. Both the audio and the track timing now follow the real track, so albums play through properly, and it keeps playing while you carry it. Radio discs are not supported here yet — inserting one stops shortly after, the same as in the gramophone.
- **Albums no longer fall silent between tracks.** Playing an album in the Golden Jukebox left a five to ten second gap while the next track was looked up and buffered. The next track is now fetched and buffered in the background before the current one finishes, so it starts as soon as the previous one ends. This covers custom discs inside an album, including the wrap-around when repeat is on.

## 2.2.1
- **Fixed the Golden Jukebox showing no track length and an empty progress bar for vanilla and other mods' discs.** The length was there all along, the jukebox just wasn't reading it. Scrubbing still only works on custom discs.
- **Fixed a hopper under the Golden Jukebox not pulling the disc out when the track ended.** Like a vanilla jukebox, the Golden Jukebox powers the block below it while a record plays, which switches a hopper off. It was measuring "still playing" against an internal placeholder rounded up to the next ten seconds — up to an hour for a disc of unknown length, and forever on a radio stream — so the hopper stayed switched off long after the music stopped. It now goes by the track's real length. Repeat and albums still hold the hopper off while they are actually playing.
- **Fixed the elapsed time and progress bar drifting behind the music.** The readout was counting game ticks, so it fell behind whenever the server dipped below 20 ticks per second and then jumped to catch up (about four seconds adrift over a four-minute track). It now follows the same clock as the audio.

- **Fixed custom discs not playing in a normal jukebox when Additional Additions is installed.** The disc showed as playing and stayed silent, with no error. This has been broken since 2.0.0 for anyone running Additional Additions.
- **Fixed crackling and jumping stereo with directional audio turned off.** Moving around while looking about made the sound break up and flick between left and right. It now plays as a flat, non-positional sound the way vanilla background music does, and fades in and out at the edge of the audible range instead of cutting.
- **Additional Additions albums now work in the Golden Jukebox.** Albums could already hold custom discs and play them in a normal jukebox, but the Golden Jukebox refused to take them at all. It now plays through an album track by track, shows which track is playing, and repeat loops the whole album. Comparator output follows the current track.
- **Custom discs now play in [Let's Do] Furniture's gramophone.** They could be inserted before, and the gramophone acted like they were playing, but no audio came out. Repeat works too, and breaking the gramophone stops the music for anyone still listening.

## 2.2.0

- **Fixed YouTube playback that could stop working entirely until you restarted the game.** Occasionally YouTube would reject the connection Music Disc Maker was using to resolve a link; once that happened, every YouTube link — new and old — kept failing, and the only way back was restarting the game (which also restarts the mod). Failed connections are now swapped out and retried automatically instead of getting stuck, and playback now only uses YouTube backends that can actually stream audio: most of the previous defaults couldn't play anything at all, and one of them would resolve a link successfully and then fail partway into playback, which made the underlying problem easy to miss.
- **Playback failures now tell you why.** When a disc won't play, the reason stays in chat and in the log instead of flashing past on the action bar: YouTube asking this connection to sign in (bot check), age-restricted or region-locked videos, private or deleted videos, unsupported links, network/DNS trouble, or hitting the concurrent-playback limit. This covers playback from backpacks and from Create contraptions too, which previously failed silently or with no reason given.
- **Directional audio toggle**, on the Golden Jukebox. Off plays the track at a flat volume anywhere inside the audible range instead of changing with distance and direction, like background music, and still cuts out once you leave the range. On (the default) is the usual positional sound.
- **Custom discs are much louder.** They were quieter than vanilla records at every setting, and pushing the Golden Jukebox volume slider past 100% didn't help. Playback now has its own gain stage with a soft limiter, so custom discs sit at vanilla level or above out of the box — nothing to configure.
- **Fixed discs restarting from the beginning.** A disc left in a jukebox after its track had finished would start over from the top for anyone entering the chunk — after dying and teleporting back, relogging, or a chunk reload — and could leave the same track playing twice at once.
- **Traveler's Backpack support.** Custom discs now play from Traveler's Backpack's jukebox upgrade slot, the same as Sophisticated Backpacks.
- Updated the YouTube resolver to youtube-source 1.18.2.
- Fixed the backpack Stop button doing nothing while a track was still loading, which could leave music playing after you stopped it, or start two tracks at once if you pressed Play twice.
- Fixed playback bookkeeping not being released when a dimension unloads.
- **Server and client now need matching mod versions.** A 2.1.0 client can no longer connect to a 2.2.0 server (or the reverse) — the mismatch is now rejected outright instead of causing unpredictable behavior, so make sure both sides are updated together.

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
