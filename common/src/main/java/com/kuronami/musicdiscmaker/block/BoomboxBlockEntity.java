package com.kuronami.musicdiscmaker.block;

import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.register.ModBlockEntities;
import com.kuronami.musicdiscmaker.register.ModDataComponents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * ブームボックス (携帯プレイヤー) の BlockEntity。
 *
 * <p>再生機構は強化版ジュークボックスをそのまま継ぐ (再生権威・シーク・リピート・late-join・
 * 移動構造物アダプタ・設定 GUI が全部そのまま効く)。違うのは 2 点だけ:
 * ① 指向性の既定が OFF (携帯 BGM は範囲内フラットが自然)
 * ② ディスクと再生設定がブロックアイテムの {@link BoomboxContents} と往復する (持ち歩ける)。
 */
public class BoomboxBlockEntity extends GoldenJukeboxBlockEntity {

    public BoomboxBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.BOOMBOX.get(), pos, state, false);
    }

    /**
     * ブームボックスはレッドストーン出力を持たない ({@code BoomboxBlock} が
     * {@code hasAnalogOutputSignal} / {@code isSignalSource} を false にしている) ので、
     * ビート解析も出力更新も走らせない。解析用ストリームを無駄に 1 本開かないためでもある。
     */
    @Override
    protected boolean emitsBeatSignal() {
        return false;
    }

    /** 現在の中身をブロックアイテム用の component へ写す。 */
    public BoomboxContents toContents() {
        return new BoomboxContents(getDisc().copy(), getRangeBlocks(), getVolumePercent(), isDirectional());
    }

    /**
     * ブロックアイテムから中身を取り込む。{@code BlockItem#place} はこれを {@code setPlacedBy} より
     * 前に呼ぶので、ここで {@link #setItem} まで済ませておけば {@code HAS_RECORD} が立ち、
     * ticker が付いて再生が始まる (component 適用より前に BE が生えるため、初回 tick 頼みにはできない)。
     */
    @Override
    protected void applyImplicitComponents(BlockEntity.DataComponentInput input) {
        super.applyImplicitComponents(input);
        final BoomboxContents contents = input.get(ModDataComponents.BOOMBOX_CONTENTS.get());
        if (contents == null) {
            return;
        }
        setRangeBlocks(contents.rangeBlocks());
        setVolumePercent(contents.volumePercent());
        setDirectional(contents.directional());
        if (contents.hasDisc()) {
            setItem(SLOT_DISC, contents.disc().copy());
        }
    }

    /**
     * 破壊ドロップ (loot table の {@code copy_components}) に中身を載せる。
     * pick-block でも載るのはクリエイティブで Ctrl を押した時だけ (vanilla の {@code Minecraft#pickBlock})。
     */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        components.set(ModDataComponents.BOOMBOX_CONTENTS.get(), toContents());
    }
}
