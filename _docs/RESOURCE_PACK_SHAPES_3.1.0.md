# Resource-pack model guide for Music Disc Maker 3.1.0

Music Disc Maker 3.1.0 keeps its moving model parts in standard Minecraft model JSON and reads pivot settings from one resource. Copy any files you want to change from the mod's `assets/music_disc_maker/` resources into the same paths in your resource pack.

## Fixed model paths

- `models/block/speaker_horn_floor.json` — floor-mounted Speaker horn
- `models/block/speaker_horn_wall.json` — wall/ceiling-mounted Speaker horn
- `models/block/boombox_handle.json` — Boombox's animated handle

The part names and paths are fixed. Use regular block-model JSON for their geometry and textures. The renderer continues to own orientation and animation.

## Pivot example

Create `assets/music_disc_maker/device_model_pose.json` in your resource pack. This example moves the Boombox handle's pivot and changes its lowered angle; keep or adjust the Speaker values to match your replacement models.

```json
{
  "speaker": {
    "floor_pivot": [8.0, 0.0, 12.460918],
    "wall_pivot": [8.0, 0.0, 12.7654],
    "diagonal_shift": 3.75
  },
  "boombox": {
    "pivot": [1.0, 11.0, 9.0],
    "down_angle": 75.0
  }
}
```

Coordinates use model units (16 units per block); angles are degrees. Pivot components must be within `-64` to `64`, diagonal shift within `0` to `16`, and the Boombox lowered angle within `-180` to `180`. For Speaker floor and wall pivots, only X and Z are used; keep Y at `0`. Missing or invalid fields use that field’s built-in default. If a `speaker` or `boombox` section is not an object, that section uses its defaults. If the JSON document cannot be parsed as an object, all pose settings use built-in defaults.

A resource-pack model changes appearance only. It does not change collision bounds, selection behavior, or the Speaker's audio direction. Reload the pack after changing its models or pose file.
