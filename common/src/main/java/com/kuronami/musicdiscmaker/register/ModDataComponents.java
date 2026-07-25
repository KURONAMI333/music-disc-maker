package com.kuronami.musicdiscmaker.register;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.platform.registry.RegistrationProvider;
import com.kuronami.musicdiscmaker.platform.registry.RegistryHolder;
import com.mojang.serialization.Codec;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;

/** custom music disc に載せる DataComponent。 */
public final class ModDataComponents {

    public static final RegistrationProvider<DataComponentType<?>> COMPONENTS =
            RegistrationProvider.get(Registries.DATA_COMPONENT_TYPE, MusicDiscMaker.MODID);

    public static final RegistryHolder<DataComponentType<CustomTrackData>> CUSTOM_TRACK =
            COMPONENTS.register("custom_track",
                    () -> DataComponentType.<CustomTrackData>builder()
                            .persistent(CustomTrackData.CODEC)
                            .networkSynchronized(CustomTrackData.STREAM_CODEC)
                            .build());

    /**
     * スピーカーのブロックアイテムが記憶した音源 (強化版ジュークボックス) の位置。
     * {@code GlobalPos} で dimension も持つので、別の次元へ持ち込んだアイテムのリンクは設置時に落ちる。
     * BE ⇔ item の往復は {@code SpeakerBlockEntity} の implicit component
     * ({@code applyImplicitComponents} / {@code collectImplicitComponents}) と loot table の
     * {@code copy_components} が担う。
     */
    public static final RegistryHolder<DataComponentType<GlobalPos>> SPEAKER_SOURCE =
            COMPONENTS.register("speaker_source",
                    () -> DataComponentType.<GlobalPos>builder()
                            .persistent(GlobalPos.CODEC)
                            .networkSynchronized(GlobalPos.STREAM_CODEC)
                            .build());

    /**
     * ブームボックスのブロックアイテムが持ち歩く中身 (ディスク + 可聴範囲/音量/指向性)。
     * BE ⇔ item の往復は {@code BoomboxBlockEntity} の implicit component と loot table の
     * {@code copy_components} が担う。
     */
    public static final RegistryHolder<DataComponentType<BoomboxContents>> BOOMBOX_CONTENTS =
            COMPONENTS.register("boombox_contents",
                    () -> DataComponentType.<BoomboxContents>builder()
                            .persistent(BoomboxContents.CODEC)
                            .networkSynchronized(BoomboxContents.STREAM_CODEC)
                            .build());

    /**
     * 手持ちブームボックスが再生中か。シフト右クリックのトグルで書き換わり、アイテムに残るので
     * ログアウトを跨いでも状態が保たれる。server の {@code BoomboxPlayback} はこれを毎 tick 読む。
     */
    public static final RegistryHolder<DataComponentType<Boolean>> BOOMBOX_PLAYING =
            COMPONENTS.register("boombox_playing",
                    () -> DataComponentType.<Boolean>builder()
                            .persistent(Codec.BOOL)
                            .networkSynchronized(ByteBufCodecs.BOOL)
                            .build());

    private ModDataComponents() {
    }

    public static void init() {
    }
}
