package com.kuronami.musicdiscmaker.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.event.AlbumPlaybackMirror;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * (非公式) [Let's Do] Furniture の Gramophone (蓄音機) が破壊等でブロックごと消えるとき、
 * {@link FurnitureGramophoneMixin} の tick ポーリングに代わって MDM ストリーミングの停止を送る。
 *
 * <p>{@code GramophoneBlock.onRemove} は {@code GramophoneBlockEntity} を {@code stopPlayingOnRemove}
 * → {@code popOutRecord} (内部で {@code recordItem} を直接 {@code ItemStack.EMPTY} 代入。setter 経由
 * ではないのでフック不可) → {@code removeBlockEntity} の順で片付ける。ブロック entity が消えた後は
 * {@link FurnitureGramophoneMixin} の tick フックは二度と呼ばれないので、再生中に破壊されると
 * (爆発・ピストン・pickaxe を問わず) MDM の server 側 registry は孤立し、<b>既に聴いている client には
 * 二度と Stop が届かない</b> (次に chunk を見る player への late-join 掃除はあるが、既存の listener には
 * 何も送らない)。vanilla jukebox はブロック除去時に {@code setTheItem(EMPTY)} を内部で呼ぶので
 * 既存の {@code JukeboxBlockEntityMixin} がそのまま拾えるが、Gramophone にはその等価物が無い。
 *
 * <p>{@code onRemove(BlockState, Level, BlockPos, BlockState, boolean)} を HEAD で捕まえ、
 * ブロック自体が入れ替わる遷移 (同一ブロックのプロパティ変化 = repeat/facing/hasRecord トグルは除外)
 * でだけ {@link AlbumPlaybackMirror#mirror} を null で呼んで停止させる。mirror は非アクティブな pos に
 * 対しては何もしないので、対象外の呼び出しでも無害 (idempotent)。
 *
 * <p>安全性: {@code @Pseudo} で Furniture 非導入環境では適用されない。inject 本体は try/catch で
 * 失敗を握りつぶす。@Shadow は使わない (state/level/pos は inject の引数からそのまま読める)。
 */
@Pseudo
@Mixin(targets = "com.berksire.furniture.core.block.GramophoneBlock", remap = false)
public abstract class FurnitureGramophoneBlockMixin {

    @Inject(method = "onRemove", at = @At("HEAD"), require = 0, remap = false)
    private void musicdiscmaker$stopOnRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
            boolean isMoving, CallbackInfo ci) {
        try {
            if (state.is(newState.getBlock())) {
                return; // 同ブロック内のプロパティ変化 (repeat/facing/hasRecord トグル等) は対象外
            }
            if (!(level instanceof ServerLevel serverLevel)) {
                return;
            }
            final DoubleBlockHalf half = state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF);
            final BlockPos basePos = half == DoubleBlockHalf.UPPER ? pos.below() : pos;
            AlbumPlaybackMirror.mirror(serverLevel, basePos, null);
        } catch (final Throwable t) {
            // fail-soft: Furniture の内部構造が変わってもクラッシュさせない。
            MusicDiscMaker.LOGGER.debug("Furniture gramophone remove-stop hook skipped", t);
        }
    }
}
