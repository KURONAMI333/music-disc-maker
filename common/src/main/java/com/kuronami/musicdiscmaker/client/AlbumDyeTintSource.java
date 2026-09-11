package com.kuronami.musicdiscmaker.client;

//? if >=1.21.2 {
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;

import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * アルバムのカバーの tint 源。{@code items} モデル JSON の {@code tints} 配列に
 * {@code {"type": "music_disc_maker:album_dye", "index": N}} で並べ、N が model の
 * {@code tintindex} に対応する。
 *
 * <p>1.21.2 で {@code ItemColor} は廃止され、色は model 側が宣言する形になった。
 * 1.21.1 以下の口は {@code ItemColor} で、どちらも {@link AlbumDyeTint} を呼ぶだけの殻。
 */
public record AlbumDyeTintSource(int index) implements ItemTintSource {

    public static final MapCodec<AlbumDyeTintSource> MAP_CODEC =
            Codec.intRange(0, AlbumDyeTint.LAYERS - 1)
                    .xmap(AlbumDyeTintSource::new, AlbumDyeTintSource::index)
                    .fieldOf("index");

    @Override
    public int calculate(ItemStack stack, ClientLevel level, LivingEntity owner) {
        return AlbumDyeTint.color(stack, index);
    }

    @Override
    public MapCodec<? extends ItemTintSource> type() {
        return MAP_CODEC;
    }
}

//?} else {
//?}
