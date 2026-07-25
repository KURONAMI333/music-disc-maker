package com.kuronami.musicdiscmaker.lavaplayer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

import com.kuronami.musicdiscmaker.lavaplayer.api.IOpusDecoder;
import com.sedmelluq.discord.lavaplayer.natives.opus.OpusDecoder;

/**
 * 同梱 LavaPlayer の native Opus decoder を {@link IOpusDecoder} として mod 側へ渡す。
 * direct buffer はインスタンスごとに 1 つずつ確保して使い回す ({@link LavaOpusEncoder} と同じ理由)。
 */
class LavaOpusDecoder implements IOpusDecoder {

    private final OpusDecoder decoder;
    private final int channels;
    private final ByteBuffer input;
    private final ShortBuffer output;
    private boolean closed;

    LavaOpusDecoder(int sampleRate, int channels, int maxPacketBytes, int maxFrameSamples) {
        this.decoder = new OpusDecoder(sampleRate, channels);
        this.channels = channels;
        this.input = ByteBuffer.allocateDirect(maxPacketBytes);
        this.output = ByteBuffer.allocateDirect(maxFrameSamples * channels * 2)
                .order(ByteOrder.nativeOrder()).asShortBuffer();
    }

    @Override
    public int decode(byte[] packet, int offset, int length, short[] dst) {
        if (closed || length <= 0 || length > input.capacity()) {
            return -1;
        }
        try {
            input.clear();
            input.put(packet, offset, length);
            input.flip();
            output.clear();
            // 戻り値は「チャンネルあたりの」サンプル数。buffer には samples * channels 個入っている
            // (mono 固定の今は同じ値だが、stereo にした瞬間に半分しか読まない静かなバグになる)。
            final int samples = decoder.decode(input, output);
            final int interleaved = samples * channels;
            if (samples <= 0 || interleaved > dst.length) {
                return -1;
            }
            output.position(0);
            output.get(dst, 0, interleaved);
            return samples;
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
            decoder.close();
        } catch (final Throwable ignored) {
            // close 時の例外は無視
        }
    }
}
