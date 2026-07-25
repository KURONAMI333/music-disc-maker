package com.kuronami.musicdiscmaker.lavaplayer.api;

/**
 * PCM を Opus パケットへ圧縮する encoder。
 *
 * <p><b>なぜ橋渡し interface が要るのか</b>: Opus の実体 (native encoder) は同梱 LavaPlayer の中に
 * あり、隔離 classloader 側にしか存在しない。mod 側 (common) からは
 * {@code com.sedmelluq...natives.opus.OpusEncoder} を名指しできない
 * ({@code DependencyClassLoader} が mod へ委譲するのはこの api パッケージと slf4j だけ)。
 * そのため codec だけをこの境界に出し、コンテナ形式・置き場・上限といった方針は mod 側に置く。
 *
 * <p>スレッド安全ではない。1 スレッドから使うこと。
 */
public interface IOpusEncoder extends AutoCloseable {

    /** 1 フレームのサンプル数 (チャンネルあたり)。 */
    int frameSamples();

    /**
     * 1 フレームぶんの PCM を 1 つの Opus パケットへ圧縮する。
     *
     * @param pcm    interleaved S16 サンプル
     * @param offset {@code pcm} の読み出し開始位置
     * @param dst    パケットの書き込み先 (十分な長さが要る。20ms/48kHz なら 4096 で足りる)
     * @return パケットのバイト数。失敗時は {@code -1}
     */
    int encode(short[] pcm, int offset, byte[] dst);

    @Override
    void close();
}
