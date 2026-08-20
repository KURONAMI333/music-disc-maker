package com.kuronami.musicdiscmaker.client.audio;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;

/**
 * 金ジュークボックスの<b>座標ごとの直近の失敗</b>を、その client の中だけで覚えておく入れ物。
 *
 * <h2>なぜ BlockEntity の同期に載せないか</h2>
 * ストリームは client ごとに開くので、同じ音源でも<b>片方の client だけ</b>失敗しうる
 * ({@link PlaybackFailure} の javadoc 参照 — DNS・回線・ガード判定・同時再生上限はすべてローカル)。
 * BE に載せて同期すると、<b>他人の失敗が自分の画面に出る</b>。だから client 側に持つ。
 *
 * <p>制作機は対象外 — あちらは server 側で URL を解決するので、失敗理由は既に BE の NBT に
 * 載っていて全員に同じものが出るのが正しい。
 *
 * <h2>消える点は 3 つだけ (時間では消さない)</h2>
 * <ol>
 * <li><b>同じ座標で再生が実際に受理された時</b> ({@code SoundEngine} が受理 =
 *     {@code PlaybackSessions#engineAccepted})。<b>「ロードが始まった」ではない</b> —
 *     {@code install()} は「ロードが間に合った」でしかなく、engine の受理はその後
 *     (MDM_DECISIONS D10)。ここを取り違えると、鳴っていないのにラベルだけ消える。</li>
 * <li>ディスクが抜かれた / ブロックが壊れた時 ({@code ClientPlaybackManager#stopPlayback})。</li>
 * <li>ワールドを抜けた時 ({@code ClientPlaybackManager#stopAll})。</li>
 * </ol>
 *
 * <p><b>時間で消さない。</b> 直っていない失敗が黙って消えると、利用者が見に行った時に画面に
 * 何も無い = 「鳴らないのに理由がどこにも無い」という元の状態へ戻る。
 *
 * <p>{@code Minecraft} を掴まないので headless テストに載る ({@link PlaybackFailureNotices} と
 * 同じ seam の切り方)。共有の入れ物は {@link #get()}、テストは独立の instance を作ってよい。
 */
public final class GoldenJukeboxFailures {

    /** 画面が読む共有の入れ物。 */
    private static final GoldenJukeboxFailures INSTANCE = new GoldenJukeboxFailures();

    /** jukebox の位置 → その座標で最後に起きた失敗。 */
    private final Map<BlockPos, PlaybackFailure> latest = new ConcurrentHashMap<>();

    /**
     * 共有の入れ物。実際の再生経路が書き、画面が読む。
     *
     * @return 共有 instance
     */
    public static GoldenJukeboxFailures get() {
        return INSTANCE;
    }

    /**
     * 失敗を覚える (同じ座標の古い失敗は上書きする)。
     *
     * @param pos     jukebox の位置
     * @param failure 分類済みの失敗 ({@code null} なら何もしない — 既に覚えているものを消さない)
     */
    public void record(@Nullable BlockPos pos, @Nullable PlaybackFailure failure) {
        if (pos == null || failure == null) {
            return;
        }
        latest.put(pos.immutable(), failure);
    }

    /**
     * その座標の直近の失敗。
     *
     * @param pos jukebox の位置
     * @return 覚えていれば失敗、無ければ {@code null}
     */
    @Nullable
    public PlaybackFailure latest(@Nullable BlockPos pos) {
        return pos == null ? null : latest.get(pos);
    }

    /**
     * その座標の記憶を捨てる。呼んでよいのは上の「消える点」の 1・2 だけ。
     *
     * @param pos jukebox の位置
     */
    public void clear(@Nullable BlockPos pos) {
        if (pos != null) {
            latest.remove(pos);
        }
    }

    /** 全部捨てる (ワールド離脱)。 */
    public void clearAll() {
        latest.clear();
    }
}
