# Changelog

## 3.1.0

### New features

- Albums now hold up to 18 discs in two rows.
- Add the Golden Album: surround an Album with 8 gold ingots to get 54 slots in six rows, like a large chest. Its discs, their order, its color and its name carry over, and it plays in the Boombox, the Golden Jukebox and the Minecart with Golden Jukebox like an Album.
- Boomboxes and Albums accept any music disc that plays in a jukebox, including vanilla discs and discs from other mods.
- Import an MP3 from the local computer and share its audio through the server for multiplayer playback.
- Equip a Boombox through an optional Curios slot on NeoForge 1.21.1, 1.21.11, 26.1.2, and 26.2, and Forge 1.20.1.
- Replace the Speaker horn and Boombox handle models with resource-pack JSON and adjust their animation pivots. See the [resource-pack model guide](./_docs/RESOURCE_PACK_SHAPES_3.1.0.md).
- Add the Minecart with Golden Jukebox for music playback and controls on rails.
- Custom music discs can be placed on BiblioCraft Legacy disc racks.
- Custom music discs play in the [Let's Do] Furniture Gramophone on Minecraft 1.20.1 (Forge and Fabric), as they already do on 1.21.1.
- Custom music discs play in the Create: Steam 'n' Rails Minecart with Jukebox on Minecraft 1.20.1 (Forge and Fabric) and NeoForge 1.21.1.
- Rename a new disc in the Music Disc Maker before taking it out: the URL field shows the song title and can be edited at no cost.
- Song titles and artists read from video titles are cleaner: headings in 【】 and anime or drama tie-ins are left out, the song and the singer are separated, and the song is read from the video description when the title does not name it.
- Album art keeps its shape: square covers no longer show the bars of a 16:9 thumbnail, and the art in tooltips is larger.
- Vanilla discs can be seeked on the Golden Jukebox, the Minecart with Golden Jukebox and the Boombox, and their screens show the song and the artist on separate lines. Album tooltips list vanilla discs by song title, like custom discs.
- The Golden Jukebox range now starts at 4 blocks instead of 16.
- Redstone now switches the Golden Jukebox each time the signal turns on: it starts, pauses, or resumes the music. Turning the signal off does nothing, so buttons and pressure plates work as well as levers.
- New client setting "Show album art", on by default. Each player can turn off the album art in custom disc tooltips; nothing needs to be set on the server.
- Every client setting now has a translated name and description in all 14 languages (most were shown as their internal names before).
- New client setting "Show Now Playing", off by default. While it is off, no "Now Playing" message appears for any disc, including vanilla discs and discs from other mods.
- The Music Disc Maker screen is narrower, the same width as a furnace.
- The Curios Boombox slot shows a Boombox outline when empty.
- Clear button in the Music Disc Maker cancels a new disc that has not been taken out yet and returns the blank disc.
- More of the look can be changed with resource packs: the Boombox screen's keys, volume slider and playback bar come from a texture, a placed Boombox has a `playing` block state for its model, and the Golden Jukebox's inventory area follows the vanilla container texture like the other screens.

### Fixes

- Fixed the track number of an Album in the Boombox staying at 1 (for example 1/5) as the songs advanced. The Golden Jukebox and the Minecart with Golden Jukebox now show the same number for Albums.
- Fixed Albums and Boomboxes rejecting every disc as too large on dedicated servers when a large item, such as a filled Shulker Box, was elsewhere in the inventory.
- Fixed a carried Boombox stopping after about 10 seconds while the server was running slowly.
- A stopped Boombox held in the hand now shows its handle in first person instead of disappearing from view.
- Fixed Forge 1.20.1 failing to start with older Sophisticated Core versions, such as the one in DeceasedCraft.
- Fixed [Let's Do] Furniture Gramophone and Additional Additions Album playback restarting from the beginning for everyone when another player came near.
- Fixed players outside a Golden Jukebox's range hearing about a second of each track start, and of each repeat, when directional audio was off.
- Fixed the last seconds of a track being cut off on Golden Jukeboxes and Speakers when the track took a while to load.
- With "Show Now Playing" on, the message appears only for players who can hear the music, and only once while the same track repeats or resumes from a pause.
- Fixed a vanilla disc on a Golden Jukebox starting over from the beginning after a pause.
- Fixed a vanilla disc that kept playing after its Golden Jukebox was broken, until leaving the world.
- Fixed album art in disc tooltips appearing between the lines of a long song title.
- Dyed discs: each dye now gives the same edge colour on every disc face, so a chosen colour no longer turns black, salmon or swaps with a neighbouring colour (such as pink and magenta). Choosing the same dye for both parts gives a highlighted edge, and a pink face no longer shows white spots.

Use the same Music Disc Maker version on the server and all clients.

## 3.0.5

A bug-fix update for disc colors, playback recovery, and saved device contents.

- Fixed a rare Minecraft 1.21.1 case where an invalid third-party disc could prevent the other contents of a Golden Jukebox, Boombox, Disc Pedestal, Disc Dyeing Table, or Music Disc Maker from being saved.
- Fixed custom disc accent colors turning pale gray after an explicit dye choice.
- Fixed playback failing to resume after leaving and returning to a loaded chunk on Fabric 1.20.1.
- Fixed a Golden Jukebox staying silent for a player who walked back into range after the world had unloaded and reloaded its chunk; playback could stay silent until the disc was paused and resumed.
- Fixed a client staying silent after a track's audio stream ended or its sound channel was dropped mid-play; playback could fail to restart on that client while others kept hearing the song.
- Fixed the placed Boombox model using a missing texture on some versions.
- Fixed the Music Disc Maker creative tab listing no items on Minecraft 1.20.1 on Fabric, and removed duplicate entries on Forge 1.20.1.

Use the same Music Disc Maker version on the server and all clients.

## 3.0.4

A bug-fix update for Albums, Boomboxes, Golden Jukeboxes, Speakers, and Disc Pedestals.

- Fixed Albums and Boomboxes being blocked by large items elsewhere in the inventory, including when their menus resynchronize.
- Fixed contradictory recovery messages for oversized media and preserved the contents when recovering them.
- Fixed Boomboxes dropping an extra body when broken in Creative mode. Survival drops and Creative copies retain their contents and settings.
- Fixed newly connected Speakers starting ahead of their Golden Jukebox or failing to start after a slow initial load.
- Fixed MDM playback staying silent after reloading resource packs, including custom discs in Jukeboxes, Golden Jukeboxes with Speakers, and Boomboxes.
- Fixed carried Boombox playback ending before the track's final audio had finished.
- Fixed Album insertion into Golden Jukeboxes on Forge 1.20.1.
- Fixed disc dye combinations turning pure black or losing distinct neighboring shades.
- Disc Pedestals now accept vanilla and compatible music discs and display their track names, while retaining support for existing custom discs.
- Boomboxes now break faster, with the same speed regardless of tool.
- Added a guard against missing world-outline render state on Fabric 1.21.11.

Use the same Music Disc Maker version on the server and all clients.

## 3.0.3

- Fixed a client crash when joining a world on Fabric 1.21.11.
- Applies to Fabric 1.21.11 only; other versions and loaders remain on 3.0.2.

## 3.0.2

A bug-fix update for Albums, Boomboxes, Golden Jukeboxes, and Speakers.

- Fixed Albums and Boomboxes being incorrectly blocked from opening because of large item data in equipment slots outside their menus.
- Fixed volume, range, and directional sound settings not applying to vanilla music discs in a Golden Jukebox without linked Speakers.
- Reduced unnecessary Speaker reloads when briefly crossing the listening boundary, and restored smooth transitions for non-directional playback.
- Fixed delayed Speaker startup reading from an outdated position in the shared audio stream.
- Restored playback-start checks and timeout recovery on newer Minecraft versions so a failed audio start does not leave a stale playback session.

Use the same Music Disc Maker version on the server and all clients.

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
