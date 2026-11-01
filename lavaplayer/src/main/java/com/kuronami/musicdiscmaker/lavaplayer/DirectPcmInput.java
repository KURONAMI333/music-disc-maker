package com.kuronami.musicdiscmaker.lavaplayer;

import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;

/**
 * 通常の入力 probe が扱えない入力を、container を自前で読んで decode する {@link PcmInput}。
 *
 * <p>pass ごとに {@link #open()} が producer スレッドと queue を作り直すので、encode の
 * レートを下げて変換し直す時も入力の先頭から decode できる。全曲はメモリに溜めない
 * ({@link PcmQueue#MAX_BUFFERED_BYTES} で止まる)。
 */
final class DirectPcmInput implements PcmInput {

    private final PcmDecoder decoder;
    private final long durationMs;
    private final Origin origin;
    private final String name;

    DirectPcmInput(PcmDecoder decoder, long durationMs, Origin origin, String name) {
        this.decoder = decoder;
        this.durationMs = durationMs;
        this.origin = origin;
        this.name = name;
    }

    @Override
    public long durationMs() {
        return durationMs;
    }

    @Override
    public Origin origin() {
        return origin;
    }

    @Override
    public IAudioSource open() {
        final PcmQueue queue = new PcmQueue();
        final MonoPcmConverter converter =
                new MonoPcmConverter(decoder.sampleRate(), decoder.channels(), queue);
        return new DirectPcmSource(name, decoder, converter, queue);
    }
}
