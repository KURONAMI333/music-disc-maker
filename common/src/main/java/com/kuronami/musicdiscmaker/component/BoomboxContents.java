package com.kuronami.musicdiscmaker.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * ブームボックスのブロックアイテムが持ち歩く中身。ディスク 1 枚と再生設定 (可聴範囲・音量・指向性)。
 *
 * <p>設置した BE ⇔ ブロックアイテムの往復はこの 1 個の component に集約する
 * ({@code applyImplicitComponents} / {@code collectImplicitComponents} と loot table の
 * {@code copy_components})。「設置して GUI で設定 → 壊して持ち歩く」で設定が巻き戻らないのが要点で、
 * 手持ち再生もここの値をそのまま使う。
 */
public record BoomboxContents(ItemStack disc, int rangeBlocks, int volumePercent, boolean directional) {

    /** 何も入っていないブームボックス。数値は {@code BoomboxBlockEntity} の既定と揃える。 */
    public static final BoomboxContents EMPTY = new BoomboxContents(ItemStack.EMPTY, 64, 100, false);

    public static final Codec<BoomboxContents> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("disc", ItemStack.EMPTY).forGetter(BoomboxContents::disc),
            Codec.INT.optionalFieldOf("range", EMPTY.rangeBlocks()).forGetter(BoomboxContents::rangeBlocks),
            Codec.INT.optionalFieldOf("volume", EMPTY.volumePercent()).forGetter(BoomboxContents::volumePercent),
            Codec.BOOL.optionalFieldOf("directional", EMPTY.directional()).forGetter(BoomboxContents::directional)
    ).apply(instance, BoomboxContents::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, BoomboxContents> STREAM_CODEC = StreamCodec.composite(
            ItemStack.OPTIONAL_STREAM_CODEC, BoomboxContents::disc,
            ByteBufCodecs.VAR_INT, BoomboxContents::rangeBlocks,
            ByteBufCodecs.VAR_INT, BoomboxContents::volumePercent,
            ByteBufCodecs.BOOL, BoomboxContents::directional,
            BoomboxContents::new);

    /** ディスクだけ差し替えた複製 (アイテム上での装填・取り出し)。 */
    public BoomboxContents withDisc(ItemStack value) {
        return new BoomboxContents(value.copy(), rangeBlocks, volumePercent, directional);
    }

    public boolean hasDisc() {
        return !disc.isEmpty();
    }
}
