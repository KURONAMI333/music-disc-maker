package com.kuronami.musicdiscmaker.lavaplayer;

import java.io.IOException;

/**
 * 自前で container を読む decoder (ALAC / MKV 内 FLAC)。48kHz mono への変換は持たず、
 * native rate の PCM を {@link PcmFrameSink} へ押し出すだけにする。
 *
 * <p>押し出しは呼び出し側 ({@link DirectPcmSource}) の producer スレッド 1 本で走る。
 * 全曲をメモリに溜めず、{@link PcmFrameSink} の先で queue に詰まらせて背圧をかける。
 */
interface PcmDecoder {

    /** 入力の sample rate (Hz)。 */
    int sampleRate();

    /** 入力の channel 数。 */
    int channels();

    /**
     * 先頭から最後まで decode して {@code sink} へ流す。失敗は {@link IOException} で返す
     * (利用者に見せる理由は呼び出し側が短い詳細に畳む)。
     */
    void decode(PcmFrameSink sink) throws IOException, InterruptedException;
}
