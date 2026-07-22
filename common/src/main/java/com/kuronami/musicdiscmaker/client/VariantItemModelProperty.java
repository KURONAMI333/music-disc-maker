package com.kuronami.musicdiscmaker.client;

import com.mojang.serialization.MapCodec;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.TrackKey;
import com.kuronami.musicdiscmaker.register.ModDataComponents;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemStack;

/**
 * custom disc の 12 色 variant を曲名+アーティストから決定的に選ぶ range_dispatch プロパティ。
 * 返り値 (index+0.5)/VARIANTS が items モデルの range_dispatch threshold (i/VARIANTS) に対応する。
 */
public record VariantItemModelProperty() implements RangeSelectItemModelProperty {

    public static final MapCodec<VariantItemModelProperty> MAP_CODEC = MapCodec.unit(new VariantItemModelProperty());

    @Override
    public float get(ItemStack stack, ClientLevel level, ItemOwner owner, int seed) {
        final CustomTrackData track = stack.get(ModDataComponents.CUSTOM_TRACK.get());
        return (TrackKey.variantIndex(track) + 0.5F) / TrackKey.VARIANTS;
    }

    @Override
    public MapCodec<VariantItemModelProperty> type() {
        return MAP_CODEC;
    }
}
