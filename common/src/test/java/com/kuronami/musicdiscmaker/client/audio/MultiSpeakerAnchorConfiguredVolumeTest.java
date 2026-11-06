package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Constructor;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 固定speaker集合の anchor は、選択点がまだ決まっていなくても利用者設定の音量を保つ。
 *
 * <p>{@code MultiSpeakerAnchor.volumePercent()} は frame 未選択の間 0 を返す。それを
 * {@code mutedOut()} の根拠にすると、範囲外で意図的に黙っているだけの音を {@code [MUTED]} と
 * 誤表示する。{@code configuredVolumePercent()} は payload の音量を返す。
 *
 * <p>テストの compile classpath には Minecraft の型が無いので、payload と anchor は
 * 実行時 classpath から反射で組む (main 側のクラスはそのまま使う)。
 */
class MultiSpeakerAnchorConfiguredVolumeTest {

    @Test
    void configuredVolumeIsThePayloadVolumeWithoutASelectedFrame() throws Exception {
        final Class<?> blockPos = Class.forName("net.minecraft.core.BlockPos");
        final Object origin = blockPos.getField("ZERO").get(null);
        final Class<?> payloadType = Class.forName("com.kuronami.musicdiscmaker.network.SpeakerSetPayload");
        final Constructor<?> payloadCtor =
                payloadType.getConstructor(blockPos, int.class, int.class, boolean.class, List.class);
        final Object payload = payloadCtor.newInstance(origin, 16, 100, false, List.of());

        final Class<?> anchorType = Class.forName("com.kuronami.musicdiscmaker.client.audio.MultiSpeakerAnchor");
        final Object anchor = anchorType.getConstructor(payloadType).newInstance(payload);

        assertEquals(100, anchorType.getMethod("configuredVolumePercent").invoke(anchor));
    }
}
