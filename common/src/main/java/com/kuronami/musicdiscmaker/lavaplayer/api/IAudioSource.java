package com.kuronami.musicdiscmaker.lavaplayer.api;

import java.util.function.Consumer;

/**
 * 再生中トラックの PCM を mod 側に引き渡す pull 型ソース。
 * LavaPlayer 型も javax.sound 型も露出させず、PCM bytes とフォーマット情報だけを渡す
 * (隔離 classloader 境界を越えるため)。
 */
public interface IAudioSource extends AutoCloseable {

    int sampleRate();

    /** 1 = mono (jukebox 位置音声向け) / 2 = stereo。 */
    int channels();

    int bitsPerSample();

    boolean bigEndian();

    /**
     * PCM を {@code dst[off .. off+len)} に詰める。<b>ブロッキングしない。</b>
     *
     * <p>データが無ければ待たずに短く (もしくは {@code 0} で) 返る。MC はこれを単一の
     * "Sound engine" スレッドから引き、同じスレッドが全ての効果音の再生も捌くので、
     * ここで待つとゲーム全体が止まる。<b>{@code len} 未満の戻り値は「今この瞬間に出せる分は
     * ここまで」であって終端ではない</b> — 呼び出し側は残りを無音で埋めるか、後で引き直す。
     *
     * @return 書き込んだバイト数。トラック終端なら {@code -1}。今は出せなければ {@code 0}。
     */
    int read(byte[] dst, int off, int len);

    /**
     * 再生中に壊れた理由。まだ壊れていなければ {@code null}。
     *
     * <p>{@link #read} が {@code -1} を返した (= ストリームが終わった) 時、それが
     * 「最後まで鳴った」のか「途中で落ちた」のかは戻り値では区別できない。区別できないと、
     * 再生スレッドの中で落ちた失敗は<b>利用者に何も出ないまま完全な無音</b>になる。
     * 終端を見た側がここを引いて、理由が付いていれば報告する。
     *
     * <p>{@code default} で {@code null} を返すのは、この口を持たない実装
     * (キャッシュ済み PCM を返すもの・複製して配るもの等) を壊さないため。持たない実装にとって
     * 「非同期に壊れる」という状態自体が存在しない。
     *
     * @return 失敗の分類と技術詳細。壊れていなければ {@code null}
     */
    default PlaybackFault playbackFault() {
        return null;
    }

    /**
     * 失敗の届け先を差す。理由が<b>確定した瞬間に</b>押し出してもらうための口。
     *
     * <p>{@link #playbackFault()} を終端で pull する形だけでは足りない。lavaplayer は再生が
     * 例外で落ちたとき「ストリームは終わった」を先に公開し、例外イベントを後から配るので
     * (根拠は {@link PlaybackFaultRelay} の javadoc に実バイトコードの順で書いてある)、
     * 終端を見た時点では理由がまだ存在しない。pull だけの経路はこの窓を毎回取り落とす。
     *
     * <p>差した時点で既に壊れていれば、その場で 1 回呼ばれる (順番に依存しない)。
     * 呼ばれるのは再生スレッドなので、実装は重い処理・MC の状態への書き込みを
     * main thread へ移すこと。
     *
     * <p>この口を持たない実装 (キャッシュ済み PCM を返すもの等) では既定の空実装が効く。
     * その場合は {@link #playbackFault()} を終端で引く従来の経路だけが残る。
     *
     * @param sink 失敗の届け先 ({@code null} = 解除)
     */
    default void onPlaybackFault(Consumer<PlaybackFault> sink) {
    }

    @Override
    void close();
}
