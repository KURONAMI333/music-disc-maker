package com.kuronami.musicdiscmaker.lavaplayer.api;

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
     * PCM を {@code dst[off .. off+len)} に詰める (ブロッキング)。
     *
     * @return 書き込んだバイト数。トラック終端なら {@code -1}。取得できなければ {@code 0}。
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

    @Override
    void close();
}
