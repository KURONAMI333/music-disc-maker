package com.kuronami.musicdiscmaker.lavaplayer.api;

/**
 * Opus パケットを PCM へ戻す decoder。{@link IOpusEncoder} と対で、隔離 classloader 側の
 * native 実体をこの境界越しに使うためにある。
 *
 * <p>スレッド安全ではない。1 スレッドから使うこと。
 */
public interface IOpusDecoder extends AutoCloseable {

    /**
     * 1 つの Opus パケットを PCM へ戻す。
     *
     * @param packet パケットのバイト列
     * @param offset 読み出し開始位置
     * @param length パケットのバイト数
     * @param dst    interleaved S16 サンプルの書き込み先
     * @return 書き込んだサンプル数 (チャンネルあたり)。失敗時は {@code -1}
     */
    int decode(byte[] packet, int offset, int length, short[] dst);

    @Override
    void close();
}
