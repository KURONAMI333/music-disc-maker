package com.kuronami.musicdiscmaker.client.audio;

import java.nio.ByteBuffer;

import javax.sound.sampled.AudioFormat;

import org.lwjgl.BufferUtils;

import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;

import net.minecraft.client.sounds.AudioStream;

/**
 * {@link IAudioSource} (LavaPlayer PCM) を MC sound engine の {@link AudioStream} に橋渡しする。
 * MC の {@code Channel.pumpBuffers} がこの {@code read} を呼び、OpenAL バッファに流す。
 */
public class LavaPlayerAudioStream implements AudioStream {

    private final IAudioSource source;
    private final AudioFormat format;
    private final byte[] scratch = new byte[8192];

    public LavaPlayerAudioStream(IAudioSource source) {
        this.source = source;
        this.format = new AudioFormat(
                source.sampleRate(), source.bitsPerSample(), source.channels(), true, source.bigEndian());
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    @Override
    public ByteBuffer read(int size) {
        final ByteBuffer buffer = BufferUtils.createByteBuffer(size);
        while (buffer.hasRemaining()) {
            final int want = Math.min(scratch.length, buffer.remaining());
            final int read = source.read(scratch, 0, want);
            if (read < 0) {
                break; // トラック終端 → 空 (もしくは残り) を返すと MC が再生終了とみなす
            }
            if (read == 0) {
                break; // 取得できず (中断等) → 持ってる分を返す
            }
            buffer.put(scratch, 0, read);
        }
        buffer.flip();
        return buffer;
    }

    @Override
    public void close() {
        source.close();
    }
}
