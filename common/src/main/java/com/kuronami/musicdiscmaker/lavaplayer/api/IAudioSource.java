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

    @Override
    void close();
}
