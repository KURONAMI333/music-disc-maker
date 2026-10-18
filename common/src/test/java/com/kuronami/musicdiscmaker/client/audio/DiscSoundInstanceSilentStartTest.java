package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

/**
 * A flat (non-directional) voice that is out of range when it is submitted starts at volume 0.
 * Vanilla only starts a zero-volume sound when {@code canStartSilent()} is true; otherwise the
 * listener never hears it, even after walking into range.
 *
 * <p>The instance is allocated without its constructor (which needs the sound registry), and only
 * the two gate fields are set.
 */
class DiscSoundInstanceSilentStartTest {

    // The test compile classpath has no Minecraft classes (the runtime one does), so the instance is
    // reached through reflection only.
    private static final String VOICE = "com.kuronami.musicdiscmaker.client.audio.DiscSoundInstance";

    private static boolean canStartSilent(boolean directional, boolean flatAudible) throws Exception {
        final Class<?> type = Class.forName(VOICE);
        final Field unsafeField = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        final Object unsafe = unsafeField.get(null);
        final Object instance = unsafe.getClass().getMethod("allocateInstance", Class.class).invoke(unsafe, type);
        set(type, instance, "directional", directional);
        set(type, instance, "flatAudible", flatAudible);
        return (Boolean) type.getMethod("canStartSilent").invoke(instance);
    }

    private static void set(Class<?> type, Object instance, String name, boolean value) throws Exception {
        final Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(instance, value);
    }

    @Test
    void flatVoiceOutOfRangeStartsSilentlySoItCanFadeInLater() throws Exception {
        assertTrue(canStartSilent(false, false));
    }

    @Test
    void flatVoiceInRangeKeepsTheZeroVolumeRefusal() throws Exception {
        assertFalse(canStartSilent(false, true));
    }

    @Test
    void directionalVoiceKeepsTheZeroVolumeRefusal() throws Exception {
        assertFalse(canStartSilent(true, true));
        assertFalse(canStartSilent(true, false));
    }
}
