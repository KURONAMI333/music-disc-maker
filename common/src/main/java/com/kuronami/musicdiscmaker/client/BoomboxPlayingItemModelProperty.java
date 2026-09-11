package com.kuronami.musicdiscmaker.client;

//? if >=1.21.2 {
import com.mojang.serialization.MapCodec;

import com.kuronami.musicdiscmaker.client.audio.BoomboxClientPlayback;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemStack;

/**
 * ブームボックスが鳴っているかを 0 / 1 で返す range_dispatch プロパティ。
 * {@code items/boombox.json} の threshold 0.5 が「鳴っている側」の境目になる。
 *
 * <p>ON / OFF で入れ替わるのは<b>取っ手の姿勢と手の持ち方の 2 つだけ</b>で、
 * アイコンのカメラ ({@code display.gui}) は両方の模型で同じ値を持つ。
 */
public record BoomboxPlayingItemModelProperty() implements RangeSelectItemModelProperty {

    public static final MapCodec<BoomboxPlayingItemModelProperty> MAP_CODEC =
            MapCodec.unit(new BoomboxPlayingItemModelProperty());

    @Override
    public float get(ItemStack stack, ClientLevel level, ItemOwner owner, int seed) {
        return BoomboxClientPlayback.isPlaying(stack) ? 1.0F : 0.0F;
    }

    @Override
    public MapCodec<BoomboxPlayingItemModelProperty> type() {
        return MAP_CODEC;
    }
}

//?} else {
//?}
