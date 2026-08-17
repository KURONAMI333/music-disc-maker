package com.kuronami.musicdiscmaker.compat.pocketjukebox;

import java.util.function.LongSupplier;

import org.jetbrains.annotations.Nullable;

/**
 * 携帯ジュークボックスの「曲送りを待たせるか」を決める状態機械。MC の型を掴まないので headless に固定できる。
 *
 * <h2>これが要る理由</h2>
 * Additional Additions の {@code PocketJukeboxPlayer.tick()} は
 * {@code SoundManager.isActive(currentSong)} が偽になった tick で次のトラックへ送る。MDM のカスタム
 * ディスクが持たせているのは<b>無音ディスク</b>で、その実体は 1.0 秒の {@code silence.ogg} なので、
 * 実際の曲が何分あろうと約 1 秒で「曲が終わった」と判定される (単曲なら 1 秒で停止、アルバムなら
 * 数秒で最後まで飛ぶ)。実音声は MDM が LavaPlayer で別に鳴らしているため、AA から見えていない。
 *
 * <p>そこで MDM が張っているストリームが生きている間だけ {@code startNextTrack()} を保留する。
 * <b>無音ディスクの尺には一切依存しない</b> (バケットを読む実装にすると、バケットは実尺以上に切り上げた
 * 値なので今度は曲が終わってからも待たされる)。
 *
 * <h2>保留を打ち切る条件</h2>
 * 保留の解除は本来「ストリーム終端 ({@code read()} が -1) のコールバック」と「音源の自己停止」の
 * 2 つで足りるが、どちらも起きずに音源だけが失われる経路が残る (resource reload や
 * {@code SoundEngine#stopAll} は音源を捨てるだけで、こちらのフラグを立てない)。その場合
 * 保留が永久に続いて<b>ディスクを抜くまで曲送りが二度と起きない</b>ので、壁時計の締切を belt として置く。
 * 締切は「曲の尺 + {@link #OVERRUN_GRACE_MS}」で、バッファリングで多少伸びても切らない幅にしてある。
 *
 * <p>全てのメソッドは client tick スレッドからのみ呼ぶこと (唯一の例外は {@link Voice#isPlaying()}
 * の実装が読む終端フラグで、そちらは実装側が atomic に持つ)。
 */
public final class PocketJukeboxAdvance {

    /** 鳴っている音源の生存判定と停止。client の {@code DiscSoundInstance} を包む seam。 */
    public interface Voice {

        /** まだ鳴っているか。偽になった時点で AA の曲送りを通す。 */
        boolean isPlaying();

        /** 音源を止める (担当の張り替え・停止)。 */
        void stop();
    }

    /** 締切なし。曲の尺が不明なディスク (durationMs &lt;= 0) はこれになり、音源の生存だけで判定する。 */
    private static final long NO_DEADLINE = Long.MIN_VALUE;

    /** ロード中に保留してよい上限 (ms)。URL 解決が固まっても曲送りが止まったままにならないようにする。 */
    public static final long LOAD_TIMEOUT_MS = 60_000L;

    /** 曲の尺に対して締切へ上乗せする余裕 (ms)。バッファリングで実再生が伸びる分。 */
    public static final long OVERRUN_GRACE_MS = 30_000L;

    private final LongSupplier clock;

    /** MDM が担当しているトラック番号。-1 = 担当なし。 */
    private int index = -1;
    /** 担当トラックの url。index の使い回し (停止 → 同じ番号で再生) を弾くための照合鍵。 */
    private String url = "";
    /** この担当が MDM ストリームを持つか。偽 = バニラ / 他 MOD のディスク・ラジオ (保留しない)。 */
    private boolean streaming;
    /** 保留の打ち切り時刻。{@link #NO_DEADLINE} なら音源の生存だけで判定する。 */
    private long deadline = NO_DEADLINE;
    @Nullable
    private Voice voice;

    /**
     * @param clock 壁時計 (ms)。本番は {@code System::currentTimeMillis}、テストは差し替える
     */
    public PocketJukeboxAdvance(LongSupplier clock) {
        this.clock = clock;
    }

    /** 担当が無い (= 何も鳴らしていないし待ってもいない)。 */
    public boolean isIdle() {
        return index < 0;
    }

    /**
     * いまこの (トラック番号, url) を担当しているか。真なら呼び出し側は何もしなくてよい。
     *
     * <p>url も見るのは、停止 → 同じトラック番号で再生し直した時に「担当済み」と誤読しないため。
     */
    public boolean handles(int trackIndex, String trackUrl) {
        return trackIndex >= 0 && this.index == trackIndex && this.url.equals(trackUrl);
    }

    /**
     * 担当を張り替える。
     *
     * @param trackIndex 新しい担当トラック番号
     * @param trackUrl   そのトラックの url (MDM ディスクでなければ空文字)
     * @param stream     MDM ストリームを張るか。偽なら保留せず AA の従来の尺に任せる
     * @return 前任の音源 (呼び出し側が {@link Voice#stop()} すること)。無ければ {@code null}
     */
    @Nullable
    public Voice assign(int trackIndex, String trackUrl, boolean stream) {
        final Voice previous = this.voice;
        this.index = trackIndex;
        this.url = trackUrl;
        this.streaming = stream;
        this.voice = null;
        this.deadline = stream ? clock.getAsLong() + LOAD_TIMEOUT_MS : NO_DEADLINE;
        return previous;
    }

    /**
     * ロードが終わって音源が鳴り始めた。
     *
     * @param trackIndex ロードを始めた時点のトラック番号
     * @param trackUrl   ロードを始めた時点の url
     * @param started    鳴り始めた音源
     * @param durationMs 曲の尺 (ms)。0 以下 = 不明 (締切を置かない)
     * @return 担当が変わっていなければ真。偽なら呼び出し側がその音源を捨てること
     */
    public boolean attach(int trackIndex, String trackUrl, Voice started, long durationMs) {
        if (!handles(trackIndex, trackUrl) || !this.streaming) {
            return false;
        }
        this.voice = started;
        this.deadline = durationMs > 0L
                ? clock.getAsLong() + durationMs + OVERRUN_GRACE_MS
                : NO_DEADLINE;
        return true;
    }

    /**
     * ストリームを張れなかった (URL 解決の失敗等)。以後この担当は保留しない = AA が普段どおり曲送りする。
     *
     * @param trackIndex ロードを始めた時点のトラック番号
     * @param trackUrl   ロードを始めた時点の url
     */
    public void giveUp(int trackIndex, String trackUrl) {
        if (!handles(trackIndex, trackUrl)) {
            return;
        }
        this.streaming = false;
        this.voice = null;
        this.deadline = NO_DEADLINE;
    }

    /**
     * AA の {@code startNextTrack()} を保留すべきか。
     *
     * @param currentTrack AA がいま鳴らしているつもりのトラック番号
     */
    public boolean holdsAdvance(int currentTrack) {
        if (currentTrack < 0 || this.index != currentTrack || !this.streaming) {
            return false;
        }
        if (this.deadline != NO_DEADLINE && clock.getAsLong() >= this.deadline) {
            return false;
        }
        // 音源がまだ無い = ロード中。実音声が始まる前に AA に送らせない。
        return this.voice == null || this.voice.isPlaying();
    }

    /**
     * 担当を解除する。
     *
     * @return 止めるべき音源 (呼び出し側が {@link Voice#stop()} すること)。無ければ {@code null}
     */
    @Nullable
    public Voice release() {
        final Voice previous = this.voice;
        this.index = -1;
        this.url = "";
        this.streaming = false;
        this.voice = null;
        this.deadline = NO_DEADLINE;
        return previous;
    }
}
