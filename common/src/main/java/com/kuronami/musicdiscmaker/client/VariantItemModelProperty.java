package com.kuronami.musicdiscmaker.client;

//? if >=1.21.2 {
import com.mojang.serialization.MapCodec;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.TrackKey;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.register.ModDataComponents;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemStack;

/**
 * custom disc の 12 色 variant を曲名+アーティストから決定的に選ぶ range_dispatch プロパティ。
 * 返り値 (index+0.5)/VARIANTS が items モデルの range_dispatch threshold (i/VARIANTS) に対応する。
 *
 * <p>染色されたディスクだけは 1.0 を返し、threshold 1.0 の枝 (tint を持つモデル) へ落とす。
 * 自動割り当ての最大値は (11+0.5)/12 = 0.958 なので、未染色がこの枝へ入ることはない
 * (自動 = 既定値・染色 = 上書き。2026-09-06 設計上の決定)。
 */
public record VariantItemModelProperty() implements RangeSelectItemModelProperty {

    /** 染色ディスクを振り分ける枝の threshold。自動割り当ての上限 0.958 より上にある。 */
    public static final float DYED = 1.0F;

    public static final MapCodec<VariantItemModelProperty> MAP_CODEC = MapCodec.unit(new VariantItemModelProperty());

    @Override
    public float get(ItemStack stack, ClientLevel level, ItemOwner owner, int seed) {
        if (CustomMusicDiscItem.isDyed(stack)) {
            return DYED;
        }
        final CustomTrackData track = stack.get(ModDataComponents.CUSTOM_TRACK.get());
        return (TrackKey.variantIndex(track) + 0.5F) / TrackKey.VARIANTS;
    }

    @Override
    public MapCodec<VariantItemModelProperty> type() {
        return MAP_CODEC;
    }
}

//?} else {
//?}
