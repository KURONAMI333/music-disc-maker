package com.kuronami.musicdiscmaker.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.compat.pocketjukebox.PocketJukeboxClient;

import net.minecraft.world.item.ItemStack;

/**
 * (非公式) Additional Additions の携帯ジュークボックス (Pocket Jukebox) で MDM の custom disc を
 * ストリーミング再生する。
 *
 * <h2>AA 側の構造 (10.0.5 を javap で読んだ結果)</h2>
 * <ul>
 *   <li>携帯ジュークボックスは<b>ブロックではなくアイテム</b> ({@code PocketJukeboxItem})。ディスクは
 *       vanilla の {@code DataComponents.CONTAINER} に 1 件だけ入る。挿入・取り出しは
 *       {@code overrideOtherStackedOnMe} (インベントリで右クリック) のみ。</li>
 *   <li>再生状態は client 専用 singleton {@code PocketJukeboxPlayer.INSTANCE} が全部持つ。server は
 *       advancement を撃つだけで、状態も packet も持たない。</li>
 *   <li>{@code tick()} は loader の client tick イベントから毎 tick 呼ばれ、
 *       {@code canContinuePlaying()} (プレイヤーの生存・携帯ジュークボックスがインベントリか手にあるか・
 *       level の有無・config) が偽なら {@code stop()}、そうでなければ
 *       <b>{@code SoundManager.isActive(currentSong)} が偽になった tick で {@code startNextTrack()}</b>。</li>
 *   <li>{@code startNextTrack()} はトラック番号を進め、末尾を超えたら {@code stop()}。</li>
 * </ul>
 *
 * <h2>何が壊れているか</h2>
 * 曲送りの判定に使われる {@code currentSong} は MDM のカスタムディスクが持たせている<b>無音ディスク</b>
 * で、その実体は 1.0 秒の {@code silence.ogg}。実音声は MDM が別に鳴らす前提なので AA からは見えず、
 * MDM 側にこの経路のコードは 1 行も無かった = 実音声が出ないまま約 1 秒で「曲が終わった」と判定される。
 *
 * <h2>介入</h2>
 * <ul>
 *   <li>{@code tick} の RETURN — 現在トラックを {@link PocketJukeboxClient#reconcile} に渡し、
 *       MDM ディスクなら実音声を張る。早期 return も含めた全ての出口に入れてある
 *       ({@code TAIL} だと「再生していない」「停止した」出口を取り落とす)。</li>
 *   <li>{@code startNextTrack} の HEAD — MDM のストリームがまだ生きている間は取り消して曲送りを待たせる。
 *       {@code play()} からの初回呼び出しはトラック番号が -1 なので取り消し対象にならない。</li>
 *   <li>{@code stop} の HEAD — 音源を落とす。AA 側の寿命判定は全部ここに合流する。</li>
 * </ul>
 *
 * <h2>3 つの inject の関係 (写経するとき落とさないこと)</h2>
 * {@code stop} と {@code tick} は<b>停止について冗長</b>にしてある。AA の {@code stop()} は
 * {@code isPlaying} を倒すので、次の client tick で {@code tick()} が冒頭の
 * {@code if (!isPlaying) return;} に落ち、その RETURN からも {@link PocketJukeboxClient#reconcile}
 * が「再生していない」を受け取って音源を落とす。{@code stop} の inject が解決できなくても
 * 最大 1 tick 遅れで止まる (これが {@code require = 0} を全部に付けられる根拠)。
 * <b>{@code tick} の inject を {@code TAIL} に変えるとこの退路が消える</b> — TAIL は最後の RETURN
 * 1 つだけなので、早期 return の出口を拾わなくなる。
 *
 * <p>解決できなかった時の症状: {@code tick} = 実音声が出ない (従来どおり) /
 * {@code startNextTrack} = 約 1 秒で曲送りされる (従来どおり) / {@code stop} = 停止が 1 tick 遅れる。
 * いずれも「この compat が無かった状態」以下には落ちない。
 *
 * <h2>安全性</h2>
 * {@code @Pseudo} で AA 非導入環境では適用されない。inject 本体は try/catch で失敗を握りつぶす
 * (無音再生に退化)。<b>この mixin と呼び出し先は AA の型を一切参照しない</b> — トラック列は vanilla の
 * {@code CONTAINER} component と既存の {@code AlbumSupport} アダプタから復元する
 * ({@code PocketJukeboxTracks} の javadoc) ので、common に置いても AA 依存は増えない。
 *
 * <p><b>ただし @Shadow の解決失敗は mixin 適用時のハードクラッシュで上記 try/catch では防げない。</b>
 * 面を減らすため、公開メソッドがあるものはフィールドではなくメソッドを shadow してある
 * ({@code getCurrentTrack})。残る 2 つは {@code PocketJukeboxPlayer} 直下に宣言されたフィールドで、
 * 継承フィールドの @Shadow (ハードクラッシュ実績あり) には該当しない。
 */
@Pseudo
@Mixin(targets = "one.dqu.additionaladditions.feature.PocketJukeboxPlayer", remap = false)
public abstract class PocketJukeboxPlayerMixin {

    @Shadow(remap = false)
    private boolean isPlaying;

    @Shadow(remap = false)
    private ItemStack jukeboxStack;

    @Shadow(remap = false)
    public abstract int getCurrentTrack();

    @Inject(method = "tick", at = @At("RETURN"), require = 0, remap = false)
    private void musicdiscmaker$reconcile(CallbackInfo ci) {
        try {
            PocketJukeboxClient.reconcile(this.isPlaying, getCurrentTrack(), this.jukeboxStack);
        } catch (final Throwable t) {
            // fail-soft: AA の内部構造が変わってもクラッシュさせない (無音再生に退化)。
            MusicDiscMaker.LOGGER.debug("Pocket jukebox streaming hook skipped", t);
        }
    }

    @Inject(method = "startNextTrack", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void musicdiscmaker$holdAdvance(CallbackInfo ci) {
        try {
            if (PocketJukeboxClient.holdsAdvance(getCurrentTrack())) {
                ci.cancel();
            }
        } catch (final Throwable t) {
            MusicDiscMaker.LOGGER.debug("Pocket jukebox advance hook skipped", t);
        }
    }

    @Inject(method = "stop", at = @At("HEAD"), require = 0, remap = false)
    private void musicdiscmaker$stop(CallbackInfo ci) {
        try {
            PocketJukeboxClient.stop();
        } catch (final Throwable t) {
            MusicDiscMaker.LOGGER.debug("Pocket jukebox stop hook skipped", t);
        }
    }
}
