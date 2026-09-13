## Music Disc Maker 3.0.2

A bug-fix update for Albums, Boomboxes, Golden Jukeboxes, and Speakers.

- Fixed Albums and Boomboxes being incorrectly blocked from opening because of large item data in equipment slots outside their menus.
- Fixed volume, range, and directional sound settings not applying to vanilla music discs in a Golden Jukebox without linked Speakers.
- Reduced unnecessary Speaker reloads when briefly crossing the listening boundary, and restored smooth transitions for non-directional playback.
- Fixed delayed Speaker startup reading from an outdated position in the shared audio stream.
- Restored playback-start checks and timeout recovery on newer Minecraft versions so a failed audio start does not leave a stale playback session.

Use the same Music Disc Maker version on the server and all clients.

# Changelog

## 3.0.1

- Fixed a disconnect when joining a world caused by syncing the Disc Album dyeing recipe, including setups using JEI.
- Applies to Minecraft 26.1.2 (NeoForge) and 26.2 (NeoForge/Fabric); other versions remain on 3.0.0.

## 3.0.0

3.0.0 adds new ways to collect, carry, display, and share your music, along with a higher simultaneous playback limit and fixes for existing players.

### New items

- **Album:** Store up to nine custom discs, arrange their order, and see the contents in its tooltip. Dye the cover without changing the discs, their order, or the Album's name.
- **Boombox:** Play a custom disc or an Album while carried or placed. Includes play/pause, previous/next, shuffle, repeat, seek, and volume controls.
- **Speaker:** Link Speakers to a Golden Jukebox to play its music elsewhere in your build. Mount them on floors, walls, ceilings, or fence posts, and turn the horns straight, left, or right. Set each Speaker's volume separately, control their range from the jukebox, and mute individual Speakers with redstone.
- **Disc Dyeing Table:** Change a custom disc's surface and accent colors separately.
- **Disc Pedestal:** Display a custom disc with its track title and artist, and swap it in one interaction.

### Playback and appearance

- Raised the default simultaneous playback limit from 8 to **up to 64 custom tracks**, with no settings changes needed. Devices with fewer available audio channels use a lower limit automatically.
- Added previous, next, and shuffle controls for Albums in the Golden Jukebox.
- Updated the Music Disc Maker block textures and mod icon.
- Resource packs can now reskin the Golden Jukebox's sliders and playback bar.
- Added source names to custom disc tooltips on Minecraft 1.21.11, 26.1.2, and 26.2.

### Fixes and compatibility

- Fixed portable jukebox integrations starting an old track after playback was stopped or changed while the track was still loading.
- Finished tracks in the Golden Jukebox now restart from the beginning when Play is pressed.
- Clearing a Golden Jukebox's inventory now stops playback and clears its playback state.
- Fixed previous and next controls for Additional Additions Albums in the Golden Jukebox.
- Improved Golden Jukebox playback on Create contraptions on **Forge 1.20.1 and NeoForge 1.21.1**. Vanilla and compatible discs now move with the contraption, players who approach later receive the current playback position, and disassembly saves the latest position and stops the moving sound.
- Fixed normal playback stops being reported as failures on Minecraft 1.20.1, 1.21.11, 26.1.2, and 26.2.
- Stopped unnecessary audio preloading after a track had already ended.
