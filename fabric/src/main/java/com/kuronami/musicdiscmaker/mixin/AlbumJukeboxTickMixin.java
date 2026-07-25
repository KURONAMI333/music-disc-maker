package com.kuronami.musicdiscmaker.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.platform.Services;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Additional Additions のアルバム再生を MDM のストリーミングにミラーするための vanilla tick フック。
 *
 * <p>AA の album 機能自体が {@code JukeboxBlockEntity.tick} (static ticker) への TAIL inject で
 * トラックを送っているので、同じ tick を TAIL で捕まえ、AA の再生状態を毎 tick ポーリングして
 * {@code ActiveDiscRegistry} との差分だけを client へ送る (実装は {@code AdditionalAdditionsCompat})。
 *
 * <p>本 mixin は vanilla {@code JukeboxBlockEntity} が対象なので通常 mixin (非 @Pseudo) でよい。
 * AA 不在耐性は「AA の型に触れる前に isModLoaded ゲートを通す」ことで担保する:
 * このクラスは AA 型を一切 import せず、ゲート通過後に初めて {@code AdditionalAdditionsCompat} を
 * 呼ぶ。AA 不在時はそのクラスが load されず AA 型解決も走らない。try-catch は最終防波堤。
 */
@Mixin(JukeboxBlockEntity.class)
public class AlbumJukeboxTickMixin {

    @Unique
    private static Boolean musicdiscmaker$aaEnabled = null;

    @Unique
    private static boolean musicdiscmaker$disabled = false;

    @Inject(method = "tick", at = @At("TAIL"))
    private static void musicdiscmaker$mirrorAlbum(Level level, BlockPos pos, BlockState state,
            JukeboxBlockEntity jukebox, CallbackInfo ci) {
        if (musicdiscmaker$disabled) {
            return;
        }
        if (musicdiscmaker$aaEnabled == null) {
            musicdiscmaker$aaEnabled = Services.PLATFORM.isModLoaded("additionaladditions");
        }
        if (!musicdiscmaker$aaEnabled) {
            return;
        }
        try {
            /* SPIKE: AA compat removed (A7 scope) */
        } catch (final Throwable t) {
            // AA の API 不一致等 → 永久サスペンドで毎 tick のログ汚染を防ぐ。
            musicdiscmaker$disabled = true;
            MusicDiscMaker.LOGGER.error(
                    "[MDM] Additional Additions album compat disabled due to an error", t);
        }
    }
}
