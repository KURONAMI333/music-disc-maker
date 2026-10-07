package com.kuronami.musicdiscmaker.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.event.ExternalPlaybackMirror;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * (非公式) [Let's Do] Furniture 1.20.1 の Gramophone (蓄音機) で MDM の custom disc をストリーミング再生する。
 *
 * <p>1.20.1 の {@code GramophoneBlockEntity} (パッケージは 1.21.1 版と違い {@code com.berksire.furniture.block.entity})
 * は vanilla jukebox を継承せず、挿入時に level event 1010 で disc の SoundEvent を鳴らすだけ。MDM の disc は
 * 無音 SoundEvent なので、MDM の実音声はここから {@link ExternalPlaybackMirror} へ流す。
 *
 * <ul>
 *   <li>{@code tick(Level, BlockPos, BlockState)} は再生中だけ毎 tick 呼ばれる (server/client 両方。ticker は
 *       {@code playRecordTick})。TAIL で現在の disc を mirror する。MDM の disc は曲尺 2 時間の RecordItem なので
 *       蓄音機側の自動停止・repeat 張り直しは実質起きない。</li>
 *   <li>停止は取り出し ({@code popOutRecord})・破壊 ({@code stopPlayingOnRemove})・停止中の毎 tick
 *       ({@code playRecordTick}) のすべてが {@code stopPlaying()} を通るので、その TAIL で null を mirror する
 *       (mirror は鳴らしていない座標には何もしない)。</li>
 * </ul>
 *
 * <p>安全性: {@code @Pseudo} で Furniture 非導入環境では適用されない。inject 本体は失敗を握りつぶす
 * (無音再生に退化)。@Shadow は {@code GramophoneBlockEntity} 直下のフィールドだけ (Furniture 1.0.4 の
 * Forge/Fabric 両 jar で確認)。
 */
@Pseudo
@Mixin(targets = "com.berksire.furniture.block.entity.GramophoneBlockEntity", remap = false)
public abstract class LegacyFurnitureGramophoneMixin {

    @Shadow(remap = false)
    private ItemStack recordItem;

    @Shadow(remap = false)
    private boolean isPlaying;

    @Inject(method = "tick", at = @At("TAIL"), require = 0, remap = false)
    private void musicdiscmaker$mirror(Level level, BlockPos pos, BlockState state, CallbackInfo ci) {
        try {
            if (level instanceof ServerLevel serverLevel) {
                final BlockEntity self = (BlockEntity) (Object) this;
                ExternalPlaybackMirror.mirror(serverLevel, self.getBlockPos(), this.isPlaying ? this.recordItem : null);
            }
        } catch (final Throwable t) {
            // fail-soft: Furniture の内部構造が変わってもクラッシュさせない (無音再生に退化)。
            MusicDiscMaker.LOGGER.debug("Furniture gramophone streaming hook skipped", t);
        }
    }

    @Inject(method = "stopPlaying", at = @At("TAIL"), require = 0, remap = false)
    private void musicdiscmaker$stop(CallbackInfo ci) {
        try {
            final BlockEntity self = (BlockEntity) (Object) this;
            if (self.getLevel() instanceof ServerLevel serverLevel) {
                ExternalPlaybackMirror.mirror(serverLevel, self.getBlockPos(), null);
            }
        } catch (final Throwable t) {
            MusicDiscMaker.LOGGER.debug("Furniture gramophone stop hook skipped", t);
        }
    }
}
