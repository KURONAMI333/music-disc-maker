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
 *   <li>{@code prefill} — 4 秒分を裏で引いておくのに要した時間とバイト数
 *       ({@link LavaPlayerAudioStream#prefill})。これは専用スレッドの時間で、誰も待たない</li>
 *   <li>{@code firstpcm} — 開栓から<b>最初の実 PCM</b> が MC へ渡るまで。{@code none} は
 *       一度も実データが渡らないまま終わったという意味で、<b>登録の成否では区別が付かない</b>
 *       (「登録できた」= 鳴り始めた、ではない)</li>
 *   <li>{@code buffer4s} — MC が最初に引く 4 秒分 ({@code Channel.attachBufferStream} の
 *       {@code pumpBuffers(4)}) を渡し切るまで。<b>これが "Sound engine" スレッドを占有する時間
 *       そのもの</b>で、その間 Render thread から出る全ての音が {@code createHandle().join()} で待つ</li>
 * </ul>
 *
 * <p>{@code prefill} と {@code buffer4s} を両方出すのは、<b>費用が消えたのか見えなくなっただけ
 * なのかを 1 行で判定できるようにするため</b>。裏へ出せていれば {@code buffer4s} だけが 0 に近づき、
 * {@code prefill} がその値を引き取る。
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

    /**
     * ストリームを開栓してから<b>最初の実 PCM</b> が MC の buffer へ渡るまでの ms。
     * まだ渡っていないなら {@code -1}。書き込み = streaming スレッド。
     *
     * <p>「登録できた」と「鳴り始めた」を分けて見るための値。{@code SoundManager#play} は
     * インスタンスを登録するだけで、音声ストリームの future は未完了のまま返るので、
     * <b>登録の成否からは音が出たかどうか分からない</b>。無音パディングは数えない
     * ({@link LavaPlayerAudioStream#emit} だけが打つ)。
     */
    private volatile long firstAudioMs = -1L;

    /** 先読み充填に要した ms。走らせていないなら {@code -1}。書き込み = 充填スレッド。 */
    private volatile long prefillMs = -1L;
    /** 先読み充填で引けたバイト数。 */
    private volatile long prefillBytes;
    /** 4 秒分を引き切ったか (打ち切り・曲の終端だと {@code false})。 */
    private volatile boolean prefillComplete;

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
     * 最初の実 PCM が MC の buffer へ渡った (streaming スレッドから呼ばれる)。最初の 1 回だけを採る。
     *
     * @param ms ストリームを開栓してからの経過 ms
     */
    public void firstAudio(long ms) {
        if (firstAudioMs < 0L) {
            firstAudioMs = ms;
        }
    }

    /**
     * 先読み充填が終わった (専用スレッドから呼ばれる)。
     *
     * @param ms       充填に要した ms
     * @param bytes    引けたバイト数
     * @param complete 4 秒分を引き切ったなら {@code true}
     */
    public void prefilled(long ms, long bytes, boolean complete) {
        this.prefillMs = ms;
        this.prefillBytes = bytes;
        this.prefillComplete = complete;
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
        MusicDiscMaker.LOGGER.info(
                "Track switch [{}] prefetch={} resolve={}ms {} firstpcm={} buffer4s={}ms{} url={}",
                label,
                prefetchHit ? "hit" : "miss",
                Math.max(resolveMs, 0L),
                prefillText(),
                firstAudioText(),
                bufferMs,
                complete ? "" : " (track ended before the first 4s)",
                url);
    }

    /**
     * 最初の実 PCM までの時間を 1 語にする。{@code none} = 一度も実データが渡らないまま終わった
     * (登録は成功しているので、この行が無いと「鳴った」と区別が付かない)。
     */
    private String firstAudioText() {
        final long ms = firstAudioMs;
        return ms < 0L ? "none" : ms + "ms";
    }

    /** 先読み充填の結果を 1 語にする。 */
    private String prefillText() {
        final long ms = prefillMs;
        if (ms < 0L) {
            return "prefill=skipped";
        }
        return "prefill=" + ms + "ms/" + prefillBytes + "B" + (prefillComplete ? "" : "(short)");
    }

    /** {@code nanoTime} の基準点からの経過 ms。 */
    static long msSince(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nanos);
    }
}
