package com.kuronami.musicdiscmaker.client;

//? if >=1.21.2 {
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;

import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 染色ディスクの tint 源。{@code items} モデル JSON の {@code tints} 配列に
 * {@code {"type": "music_disc_maker:disc_dye", "index": N}} で並べ、N が model の
 * {@code tintindex} に対応する。
 *
 * <p>1.21.2 で {@code ItemColor} は廃止され、色は model 側が宣言する形になった。
 * 1.21.1 以下の口は {@code ItemColor} で、どちらも {@link DiscDyeTint} を呼ぶだけの殻。
 */
public record DiscDyeTintSource(int index) implements ItemTintSource {

    public static final MapCodec<DiscDyeTintSource> MAP_CODEC =
            Codec.intRange(0, DiscDyeTint.LAYERS - 1)
                    .xmap(DiscDyeTintSource::new, DiscDyeTintSource::index)
                    .fieldOf("index");

    @Override
    public int calculate(ItemStack stack, ClientLevel level, LivingEntity owner) {
        return DiscDyeTint.color(stack, index);
    }

    @Override
    public MapCodec<? extends ItemTintSource> type() {
        return MAP_CODEC;
    }
}

//?} else {
//?}
