package com.kuronami.musicdiscmaker.lavaplayer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

import com.kuronami.musicdiscmaker.lavaplayer.api.IOpusEncoder;
import com.sedmelluq.discord.lavaplayer.natives.opus.OpusEncoder;

/**
 * 同梱 LavaPlayer の native Opus encoder を {@link IOpusEncoder} として mod 側へ渡す。
 *
 * <p>native は direct buffer しか受け取らないので、入出力の direct buffer は
 * インスタンスごとに 1 つずつ確保して使い回す (1 曲で 12,000 フレーム = 毎フレーム
 * {@code allocateDirect} すると GC の病巣になる)。
 */
class LavaOpusEncoder implements IOpusEncoder {

    /** Opus の圧縮品質 (complexity 0..10)。曲ごとに 1 回・別スレッドなので最上位で構わない。 */
    private static final int QUALITY = 10;

    private final OpusEncoder encoder;
    private final int frameSamples;
    private final ShortBuffer input;
    private final ByteBuffer output;
    private boolean closed;

    LavaOpusEncoder(int sampleRate, int channels, int frameSamples, int maxPacketBytes) {
        this.encoder = new OpusEncoder(sampleRate, channels, QUALITY);
        this.frameSamples = frameSamples;
        this.input = ByteBuffer.allocateDirect(frameSamples * channels * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer();
        this.output = ByteBuffer.allocateDirect(maxPacketBytes);
    }

    @Override
    public int frameSamples() {
        return frameSamples;
    }

    @Override
    public int encode(short[] pcm, int offset, byte[] dst) {
        if (closed) {
            return -1;
        }
        try {
            input.clear();
            input.put(pcm, offset, input.capacity());
            input.flip();
            output.clear();
            final int written = encoder.encode(input, frameSamples, output);
            if (written <= 0 || written > dst.length) {
                return -1;
            }
            output.get(dst, 0, written);
            return written;
        } catch (final Throwable t) {
            return -1;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            encoder.close();
        } catch (final Throwable ignored) {
            // close 時の例外は無視 (native の解放失敗で再生を壊さない)
        }
    }
}
