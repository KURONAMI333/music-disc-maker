package com.kuronami.musicdiscmaker.client.audio;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.kuronami.musicdiscmaker.MusicDiscMaker;

/**
 * 曲の切り替わり 1 回分の所要時間を集めて、鳴り始めた時に <b>INFO で 1 行だけ</b>出す。
 *
 * <h2>なぜ INFO なのか</h2>
 * 利用者の {@code debug.log} は {@code latest.log} と同一で DEBUG は無効になっている
 * (実測)。DEBUG へ出すと<b>作者の実機にも利用者のバグ報告にも一切現れない</b>ので、
 * 切り替わり 1 回につき 1 行だけという分量に抑えたうえで INFO へ出す。
 *
 * <h2>何を測っているか</h2>
 * <ul>
 *   <li>{@code prefetch} — {@link PlaybackPrefetch#claim} が当たったか (hit) 外れたか (miss)</li>
 *   <li>{@code resolve} — 再生要求から PCM ソースが手に入るまで (URL 解決 = ネットワーク)</li>
 *   <li>{@code buffer4s} — MC が最初に引く 4 秒分 ({@code Channel.attachBufferStream} の
 *       {@code pumpBuffers(4)}) を渡し切るまで。<b>これが "Sound engine" スレッドを占有する時間
 *       そのもの</b>で、その間 Render thread から出る全ての音が {@code createHandle().join()} で待つ</li>
 * </ul>
 *
 * <p>「先読みが効いていない」のか「効いているが別の場所で止まっている」のかは、この 3 つが
 * 揃わないとログから切り分けられない (MDM_DECISIONS D28)。
 */
public final class PlaybackTiming {

    /** 音源の識別子 (座標など)。ログに出すだけ。 */
    private final String label;
    private final String url;
    /** {@link PlaybackPrefetch#claim} が温めておいたソースを返したか。 */
    private final boolean prefetchHit;
    /**
     * 解決を始めた時刻。ラジオの再接続は {@code RECONNECT_DELAY_MS} 待ってから開くので、
     * 生成時刻のままにすると待ち時間が解決時間に化ける。{@link #beginResolve()} で打ち直す。
     */
    private volatile long startNanos = System.nanoTime();

    /**
     * ソースが手に入るまでに要した ms。まだなら {@code -1}。
     * 書き込み = ロードスレッド / 読み出し = MC の streaming スレッド。
     */
    private volatile long resolveMs = -1L;

    /** 1 回の切り替わりから 2 行出さないためのガード。 */
    private final AtomicBoolean emitted = new AtomicBoolean();

    /**
     * @param label       音源の識別子 (座標など)
     * @param url         鳴らそうとしている曲の URL
     * @param prefetchHit 先読みが当たったか
     */
    public PlaybackTiming(String label, String url, boolean prefetchHit) {
        this.label = label;
        this.url = url;
        this.prefetchHit = prefetchHit;
    }

    /** URL 解決をこれから始める。意図した待ち時間 (再接続の間隔) を解決時間に混ぜないための起点。 */
    public void beginResolve() {
        startNanos = System.nanoTime();
    }

    /** PCM ソースが手に入った。最初の 1 回だけを採る。 */
    public void resolved() {
        if (resolveMs < 0L) {
            resolveMs = msSince(startNanos);
        }
    }

    /**
     * MC が最初に引く 4 秒分を渡し切った (もしくはその前に曲が終わった)。ここで 1 行出す。
     *
     * @param bufferMs MC が最初に {@code read} を呼んでからの経過 ms
     * @param complete 4 秒分を渡し切ったなら {@code true}。曲が先に終わったなら {@code false}
     */
    public void buffered(long bufferMs, boolean complete) {
        if (!emitted.compareAndSet(false, true)) {
            return;
        }
        MusicDiscMaker.LOGGER.info("Track switch [{}] prefetch={} resolve={}ms buffer4s={}ms{} url={}",
                label,
                prefetchHit ? "hit" : "miss",
                Math.max(resolveMs, 0L),
                bufferMs,
                complete ? "" : " (track ended before the first 4s)",
                url);
    }

    /** {@code nanoTime} の基準点からの経過 ms。 */
    static long msSince(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nanos);
    }
}
