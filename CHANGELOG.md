# Changelog

## 3.1.3

- Choosing a local audio file now starts the import right after the file check, without pressing the same button a second time.
- A disc made in a different world, such as one carried over from a single-player world to a server, now plays from the client's verified audio cache instead of staying silent.
- Fixed a disc that was out of range, or playing with directional audio off, showing [MUTED] when no volume setting was actually muted.
- Playback failures no longer appear in chat. The Golden Jukebox and Boombox screens show the reason where available, and the details stay in the log.
- The Music Disc Maker recipe now appears in the recipe book once you have iron ingots or lapis lazuli, not only a Note Block.
- On Linux without a graphical file dialog, you can now import a local audio file by dropping it onto the Music Disc Maker screen, which works without any dialog program.
- With Sound Physics Remastered installed, custom discs playing at a position now go through its reverb and occlusion. The Records volume slider is unchanged.
- Custom discs displayed on shelves no longer recalculate their color every frame, fixing FPS drops when many discs are in view.
- SoundCloud links, and the search for another copy when YouTube blocks the server, play again. When SoundCloud only offers a preview of a track, or only a DRM-protected copy, the disc is made from the same song on YouTube or another SoundCloud upload instead.
- A disc whose link stops working later (made private, region-locked, age-restricted, or blocked for one player) now plays the same song from another source instead of failing.
- When YouTube briefly refuses a player, the disc keeps trying for up to 30 seconds and then plays, instead of failing on the first refusal.
- When YouTube blocks the server and no other copy of the song is found, the disc is now made from the link as entered, using the details the player's own game reads from YouTube.
- Local audio files can now be up to 2 hours long. Files over about 77 minutes are stored at a lower sample rate so they still fit in 32 MiB.
- A search whose first result is a SoundCloud preview, and a SoundCloud track with no artist or title, now look for the same song elsewhere. A Spotify page that fails to load is tried again.
- Apple Music and Deezer song links now make a disc from the same song on YouTube, SoundCloud or Bandcamp, keeping the original title and artist. A copy whose length does not match the song is not used.
- Local M4A files encoded with Apple Lossless (ALAC), and MKV files with FLAC audio, can now be imported. Previously they were rejected as unreadable files.
- Choosing a local audio file before inserting a Blank Disc no longer makes you choose it again: the import starts as soon as the disc is in. When the server is busy with other transfers, the import waits and tries again instead of stopping, and a transfer the server drops no longer leaves the screen stuck at "Sending audio".
- A disc made from a local file in another world now plays for everyone: a player who still has the original audio sends it to the server, and players who could not play it yet start hearing it once it arrives.

Use the same Music Disc Maker version on the server and all clients.

## 3.1.2

- Speakers no longer darken the blocks around them.
- The Boombox's previous, next, repeat and shuffle buttons have their own textures, so a resource pack can change them separately from the Golden Jukebox.
- Fixed players who were out of range when a song started hearing nothing after walking closer, when directional audio was off. This also affected the Boombox and the Minecart with Golden Jukebox.
- Fixed [Let's Do] Furniture Gramophones staying silent with Furniture 1.1.7 and later.
- Fixed the end of a song being cut off when a hopper swaps the disc in a Golden Jukebox.
- Fixed the end of a song being cut off for a player whose loading paused partway through, in the Golden Jukebox and the Boombox. That player hears the next song a few seconds after everyone else.
- Fixed a Golden Jukebox with Speakers stopping its song when shuffle was turned on or off.
- Fixed discs made from local audio files sometimes failing to load when played from a disc the player carries or has open in a container.
- The Boombox no longer leaves about 8 seconds of silence between songs.
- Fixed the "could not be opened" message appearing for every song when an Album's discs cannot be played. The same music source now shows it once, until a song actually starts.

Use the same Music Disc Maker version on the server and all clients.

## 3.1.1

- Fixed the game crashing on startup with Music Disc Maker 3.1.0 on Minecraft 1.21.11, 26.1.2 and 26.2.
- Added builds for Minecraft 26.3 (NeoForge and Fabric). Minecraft 1.21.4 (NeoForge and Fabric) is available as 3.1.0.

Use the same Music Disc Maker version on the server and all clients.

## 3.1.0

### Fixes

- Fixed the track number of an Album in the Boombox staying at 1 (for example 1/5) as the songs advanced. The Golden Jukebox and the Minecart with Golden Jukebox now show the same number for Albums.
- Fixed Albums and Boomboxes rejecting every disc as too large on dedicated servers when a large item, such as a filled Shulker Box, was elsewhere in the inventory.
- Fixed a carried Boombox stopping after about 10 seconds while the server was running slowly.
- A stopped Boombox held in the hand now shows its handle in first person instead of disappearing from view.
- Fixed Forge 1.20.1 failing to start with older Sophisticated Core versions, such as the one in DeceasedCraft.
- Fixed [Let's Do] Furniture Gramophone and Additional Additions Album playback restarting from the beginning for everyone when another player came near.
- Fixed players outside a Golden Jukebox's range hearing about a second of each track start, and of each repeat, when directional audio was off.
- Fixed the last seconds of a track being cut off on Golden Jukeboxes, Speakers and the Minecart with Golden Jukebox when the track took a while to load, including when an Album moves on to its next disc or a disc repeats.
- Players who arrive while a vanilla disc is playing in a Golden Jukebox now hear it from the current position.
- If a track's connection drops partway through, playback now picks up again from the same spot (up to twice).
- Fixed song details with emoji or other rare characters making a disc count as too large on servers.
- Fixed a Speaker staying silent after a sound channel became free again.
- Each release file now loads only on the Minecraft version it is made for.
- With "Show Now Playing" on, the message appears only for players who can hear the music, and only once while the same track repeats or resumes from a pause.
- Fixed a vanilla disc on a Golden Jukebox starting over from the beginning after a pause.
- Fixed a vanilla disc that kept playing after its Golden Jukebox was broken, until leaving the world.
- Fixed album art in disc tooltips appearing between the lines of a long song title.
- Dyed discs: each dye now gives the same edge colour on every disc face, so a chosen colour no longer turns black, salmon or swaps with a neighbouring colour (such as pink and magenta). Choosing the same dye for both parts gives a highlighted edge, and a pink face no longer shows white spots.

### New features

- Added Fabric builds for Minecraft 26.1.2.
- Albums now hold up to 18 discs in two rows.
- Add the Golden Album: surround an Album with 8 gold ingots to get 54 slots in six rows, like a large chest. Its discs, their order, its color and its name carry over, and it plays in the Boombox, the Golden Jukebox and the Minecart with Golden Jukebox like an Album.
- Boomboxes and Albums accept any music disc that plays in a jukebox, including vanilla discs and discs from other mods.
- Import an audio or video file from your computer (MP3, WAV, FLAC, OGG, M4A, MP4, WebM and more) and share its sound through the server for multiplayer playback. A file can be up to 32 MiB and 60 minutes long, and the server keeps imported audio in the world folder.
- Equip a Boombox through an optional Curios slot on NeoForge 1.21.1, 1.21.11, 26.1.2, and 26.2, and Forge 1.20.1, and open it with a key (B by default, changeable in Controls).
- Replace the Speaker horn and Boombox handle models with resource-pack JSON and adjust their animation pivots. See the [resource-pack model guide](https://github.com/KURONAMI333/music-disc-maker/blob/master/_docs/RESOURCE_PACK_SHAPES_3.1.0.md).
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
- On NeoForge, every setting in the config screen now has a translated name and description in all 14 languages (most were shown as their internal names before). On Forge the client settings are in `config/music_disc_maker-client.toml`, and on Fabric in `config/music_disc_maker-client.properties`.
- New client setting "Show Now Playing", off by default. While it is off, no "Now Playing" message appears for any disc, including vanilla discs and discs from other mods.
- The Music Disc Maker screen is narrower, the same width as a furnace.
- The Curios Boombox slot shows a Boombox outline when empty.
- Clear button in the Music Disc Maker cancels a new disc that has not been taken out yet and returns the blank disc.
- More of the look can be changed with resource packs: the Boombox screen's keys, volume slider and playback bar come from a texture, a placed Boombox has a `playing` block state for its model, the Golden Jukebox's inventory area follows the vanilla container texture like the other screens, and the Speaker and Boombox screens, their play and pause marks and the Music Disc Maker buttons use textures.

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
