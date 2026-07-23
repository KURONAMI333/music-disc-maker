# Changelog

## 2.1.0

- **Custom discs now play correctly on Valkyrien Skies 2 ships.** When a jukebox (vanilla or Golden) rides a VS2 physics ship, its music now follows the ship instead of staying stuck at the ship's origin (the "invisible speaker" problem). Works with Eureka! and Clockwork too, since they run on the VS2 backend. No setup needed — it just works when Valkyrien Skies is installed.
- **Custom discs now keep playing on moving Create contraptions (Forge).** A Golden Jukebox assembled into a Create contraption keeps its music playing and following the contraption, instead of going silent when the blocks are picked up for assembly. Forge only for now.
- **Golden Jukebox range changes now apply instantly while playing.** Dragging the range slider updates the audible radius live with no momentary silence, matching the instant response the volume slider already had.
- Fixed **YouTube playback failing with "Must find sig function".** Updated the YouTube source to a build that tracks YouTube's latest player/signature changes, restoring YouTube streaming.

## 2.0.1

- Fixed **YouTube bot-check failures showing as "Offline"**. When YouTube flags the server's IP as a bot and demands sign-in (common on hosted or rented servers), the maker now shows a distinct "Login wall" status with a hover tooltip explaining it isn't a connection problem.
- Fixed **SoundCloud tracks playing at half speed and an octave too low**. SoundCloud audio was reaching the game as stereo but labelled mono, so it played back at the wrong rate. Audio is now reliably downmixed to mono for all sources; SoundCloud, YouTube, and radio all play at the correct speed and pitch.
- Fixed the **Golden Jukebox playing silently** — the progress bar advanced but no sound came out. The disc's sound instance was being stopped on the first client tick because the Golden Jukebox wasn't recognised as a valid playback block.
- Fixed the **Golden Jukebox GUI** — inventory slots and items were misaligned with their slot frames. Slot coordinates now come from a single source shared with the texture generator so they can't drift apart.
- Removed the album-art preview from the Music Disc Maker block GUI (it overlapped the UI). Album art still shows as a tooltip image on discs.

## 2.0.0

- **Golden Jukebox** — a new craftable block (surround a jukebox with gold ingots) with a settings GUI: adjust the audible range (16-256 blocks), volume (0-200%), toggle repeat, and play/stop. Range and volume apply per block, so one jukebox can fill a large area while another stays quiet. Comparator output and redstone behave like a vanilla jukebox.
- **Radio & livestream discs** — infinite HTTP/icecast radio streams and YouTube live now play as endless discs, shown as "LIVE" instead of a length, with automatic reconnection on brief dropouts.
- **Album art on discs** — the track's thumbnail is downloaded and shown as a tooltip image on the disc and as a preview in the Music Disc Maker GUI (cached locally, https-only).
- **Reason-specific failure messages** — the maker now tells you *why* a URL failed (unsupported, unavailable/private, geo-blocked, age-restricted, offline, or blocked) instead of a generic error.
- **Name your discs** — discs are automatically named after their track, and anvil renames are respected.
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
