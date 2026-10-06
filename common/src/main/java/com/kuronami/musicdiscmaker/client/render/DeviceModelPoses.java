package com.kuronami.musicdiscmaker.client.render;

import java.io.IOException;
import java.io.Reader;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kuronami.musicdiscmaker.MusicDiscMaker;

//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;

/**
 * Resource-pack configurable pivots for the animated device parts.
 *
 * <p>The model locations themselves are deliberately fixed. Keeping this file limited to pivots,
 * the diagonal speaker offset and the handle's down angle prevents a pack from changing gameplay
 * direction or animation ownership while still allowing a replacement model to line up cleanly.
 */
public final class DeviceModelPoses implements ResourceManagerReloadListener {

    public static final DeviceModelPoses INSTANCE = new DeviceModelPoses();

    //? if >=1.21.11 {
    private static final Identifier RESOURCE =
            Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "device_model_pose.json");
    //?} elif >=1.21 {
    /*private static final ResourceLocation RESOURCE =
            ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "device_model_pose.json");
    *///?} else {
    /*private static final ResourceLocation RESOURCE =
            new ResourceLocation(MusicDiscMaker.MODID, "device_model_pose.json");
    *///?}

    private static final Pose DEFAULT = new Pose(
            8.0F, 12.460918F,
            8.0F, 12.7654F,
            3.75F,
            0.0F, 11.0F, 9.0F,
            90.0F);

    private static volatile Pose current = DEFAULT;

    private DeviceModelPoses() {
    }

    public static Pose current() {
        return current;
    }

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        final var resource = resourceManager.getResource(RESOURCE);
        if (resource.isEmpty()) {
            current = DEFAULT;
            MusicDiscMaker.LOGGER.warn("Device model pose resource {} is missing; using built-in defaults", RESOURCE);
            return;
        }

        try (Reader reader = resource.get().openAsReader()) {
            final JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("root must be a JSON object");
            }
            current = parse(parsed.getAsJsonObject());
        } catch (IOException | RuntimeException exception) {
            current = DEFAULT;
            MusicDiscMaker.LOGGER.warn("Invalid device model pose resource {}; using built-in defaults: {}",
                    RESOURCE, exception.getMessage());
        }
    }

    private static Pose parse(JsonObject root) {
        float floorX = DEFAULT.speakerFloorPivotX();
        float floorZ = DEFAULT.speakerFloorPivotZ();
        float wallX = DEFAULT.speakerWallPivotX();
        float wallZ = DEFAULT.speakerWallPivotZ();
        float shift = DEFAULT.speakerDiagonalShift();
        float handleX = DEFAULT.boomboxPivotX();
        float handleY = DEFAULT.boomboxPivotY();
        float handleZ = DEFAULT.boomboxPivotZ();
        float downAngle = DEFAULT.boomboxDownAngle();

        final JsonObject speaker = object(root, "speaker");
        if (speaker != null) {
            final float[] floor = vector(speaker, "floor_pivot", floorX, 0.0F, floorZ);
            floorX = floor[0];
            floorZ = floor[2];
            final float[] wall = vector(speaker, "wall_pivot", wallX, 0.0F, wallZ);
            wallX = wall[0];
            wallZ = wall[2];
            shift = number(speaker, "diagonal_shift", shift, 0.0F, 16.0F);
        }

        final JsonObject boombox = object(root, "boombox");
        if (boombox != null) {
            final float[] pivot = vector(boombox, "pivot", handleX, handleY, handleZ);
            handleX = pivot[0];
            handleY = pivot[1];
            handleZ = pivot[2];
            downAngle = number(boombox, "down_angle", downAngle, -180.0F, 180.0F);
        }

        return new Pose(floorX, floorZ, wallX, wallZ, shift,
                handleX, handleY, handleZ, downAngle);
    }

    private static JsonObject object(JsonObject root, String name) {
        final JsonElement element = root.get(name);
        if (element == null) {
            return null;
        }
        if (element.isJsonObject()) {
            return element.getAsJsonObject();
        }
        MusicDiscMaker.LOGGER.warn("Invalid {} section in {}; using defaults for that section", name, RESOURCE);
        return null;
    }

    private static float[] vector(JsonObject object, String name, float x, float y, float z) {
        final JsonElement element = object.get(name);
        if (element == null) {
            return new float[] {x, y, z};
        }
        if (!element.isJsonArray() || element.getAsJsonArray().size() != 3) {
            MusicDiscMaker.LOGGER.warn("Invalid {} in {}; expected three model-unit numbers, using default",
                    name, RESOURCE);
            return new float[] {x, y, z};
        }
        try {
            final float a = element.getAsJsonArray().get(0).getAsFloat();
            final float b = element.getAsJsonArray().get(1).getAsFloat();
            final float c = element.getAsJsonArray().get(2).getAsFloat();
            if (!finiteInRange(a, -64.0F, 64.0F) || !finiteInRange(b, -64.0F, 64.0F)
                    || !finiteInRange(c, -64.0F, 64.0F)) {
                throw new IllegalArgumentException("component outside -64..64");
            }
            return new float[] {a, b, c};
        } catch (RuntimeException exception) {
            MusicDiscMaker.LOGGER.warn("Invalid {} in {}; using default: {}", name, RESOURCE,
                    exception.getMessage());
            return new float[] {x, y, z};
        }
    }

    private static float number(JsonObject object, String name, float fallback, float min, float max) {
        final JsonElement element = object.get(name);
        if (element == null) {
            return fallback;
        }
        try {
            final float value = element.getAsFloat();
            if (!finiteInRange(value, min, max)) {
                throw new IllegalArgumentException("value outside " + min + ".." + max);
            }
            return value;
        } catch (RuntimeException exception) {
            MusicDiscMaker.LOGGER.warn("Invalid {} in {}; using default {}: {}", name, RESOURCE,
                    fallback, exception.getMessage());
            return fallback;
        }
    }

    private static boolean finiteInRange(float value, float min, float max) {
        return Float.isFinite(value) && value >= min && value <= max;
    }

    /** Coordinates are model units (16 units per block); angles are degrees. */
    public record Pose(
            float speakerFloorPivotX,
            float speakerFloorPivotZ,
            float speakerWallPivotX,
            float speakerWallPivotZ,
            float speakerDiagonalShift,
            float boomboxPivotX,
            float boomboxPivotY,
            float boomboxPivotZ,
            float boomboxDownAngle) {
    }
}
