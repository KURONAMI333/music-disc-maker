package com.kuronami.musicdiscmaker.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.debug.MdmProbe;
import com.kuronami.musicdiscmaker.event.AlbumPlaybackMirror;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * (非公式) [Let's Do] Furniture の Gramophone (蓄音機) で MDM の custom disc をストリーミング再生する。
 *
 * <p>{@code GramophoneBlockEntity} は vanilla {@code JukeboxBlockEntity} を継承せず、自前の
 * {@code JukeboxSongPlayer} を合成して無音の {@code JukeboxSong} を鳴らすだけ。MDM の実音声トリガー
 * ({@code JukeboxBlockEntityMixin} → {@code JukeboxDiscController}、AA アルバム経路の
 * {@code AlbumJukeboxTickMixin} → {@link AlbumPlaybackMirror}) はどちらも vanilla
 * {@code JukeboxBlockEntity} だけを対象にしており、Gramophone はどちらも通らない。本 mixin が
 * その代わりの唯一の入口になる。
 *
 * <p>{@code tick(Level, BlockState)} を TAIL で捕まえる。{@code GramophoneBlockEntity.tick} は
 * 冒頭で {@code if (level.isClientSide) return;} しているため、TAIL への到達は常に server 側
 * (client 側では inject 本体が一切実行されない)。毎 tick、現在再生中の disc を
 * {@link AlbumPlaybackMirror#mirror} へポーリングする。
 *
 * <p><b>S1 (repeat ループの再送):</b> Gramophone は repeat オン時、曲が終わるたびに自前で
 * {@code JukeboxSongPlayer.stop→play} を張り直して同じ disc を鳴らし続けるが、
 * {@link AlbumPlaybackMirror#mirror} は「url が既存と同じなら何もしない」(= 毎 tick の
 * 再送を防ぐ dedup) ので、そのままではループ 2 周目以降の MDM ストリームが飛ばず無音になる。
 * {@code recordStartedTick == tickCount} (= このtickで (再) 生開始した) を検知した tick だけ、
 * mirror を stop→play の順で2回呼んで強制的に再送させる。{@link AlbumPlaybackMirror} 自体の
 * ロジックは一切変更しないので、既存の mirror 利用者 (AA のアルバム経路) の挙動は変わらない。
 *
 * <p>安全性: {@code @Pseudo} で Furniture 非導入環境では適用されない。inject 本体は try/catch で
 * 失敗を握りつぶす (無音再生に退化)。<b>ただし @Shadow の解決失敗は mixin 適用時のハードクラッシュで
 * 上記 try/catch では防げない</b> (Furniture が内部フィールド名を変えると追従が必要)。全 @Shadow は
 * {@code GramophoneBlockEntity} 直下に宣言されたフィールドで継承フィールドは無いので、
 * {@code UpgradeWrapperBaseAccessor} が警告する継承フィールド @Shadow のクラッシュ実績には該当しない。
 */
@Pseudo
@Mixin(targets = "com.berksire.furniture.core.block.entity.GramophoneBlockEntity", remap = false)
public abstract class FurnitureGramophoneMixin {

    @Shadow(remap = false)
    private ItemStack recordItem;

    @Shadow(remap = false)
    private boolean isPlaying;

    @Shadow(remap = false)
    private long tickCount;

    @Shadow(remap = false)
    private long recordStartedTick;

    @Inject(method = "tick", at = @At("TAIL"), require = 0, remap = false)
    private void musicdiscmaker$mirror(Level level, BlockState state, CallbackInfo ci) {
        try {
            final BlockEntity self = (BlockEntity) (Object) this;
            // 一時的な実機診断。この行が latest.log に 1 度も出なければ mixin が当たっていない。
            MdmProbe.gramophoneHook(level, self.getBlockPos(), this.isPlaying, this.recordItem);
            if (!(level instanceof ServerLevel serverLevel)) {
                return;
            }
            final ItemStack disc = this.isPlaying ? this.recordItem : null;

            // repeat ループの張り直しは url が変わらないので、通常の mirror 呼び出しだけでは
            // 再送 dedup に飲まれて無音のままになる。stop→play を明示的に2回呼んで再送を強制する。
            if (this.isPlaying && this.recordStartedTick == this.tickCount) {
                AlbumPlaybackMirror.mirror(serverLevel, self.getBlockPos(), null);
            }
            AlbumPlaybackMirror.mirror(serverLevel, self.getBlockPos(), disc);
        } catch (final Throwable t) {
            // fail-soft: Furniture の内部構造が変わってもクラッシュさせない (無音再生に退化)。
            MusicDiscMaker.LOGGER.debug("Furniture gramophone streaming hook skipped: {}", t.toString());
        }
    }
}
