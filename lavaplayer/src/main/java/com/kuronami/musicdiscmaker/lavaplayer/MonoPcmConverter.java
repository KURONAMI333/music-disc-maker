package com.kuronami.musicdiscmaker.lavaplayer;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ShortBuffer;

import com.sedmelluq.discord.lavaplayer.filter.ChannelCountPcmAudioFilter;
import com.sedmelluq.discord.lavaplayer.filter.FloatPcmAudioFilter;
import com.sedmelluq.discord.lavaplayer.filter.ResamplingPcmAudioFilter;
import com.sedmelluq.discord.lavaplayer.filter.UniversalPcmAudioFilter;
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration;

/**
 * 直 decode の PCM を normalize の入力形式 (48kHz / mono / S16LE) へ直して queue に積む。
 *
 * <p>変換は既存の再生経路と同じ LavaPlayer の filter を使う:
 * 入力 rate が 48kHz でなければ {@link ResamplingPcmAudioFilter} (品質 HIGH)、
 * 3ch 以上は {@link ChannelCountPcmAudioFilter} で 2ch にしてから、末尾で L/R を平均して
 * mono にする (2ch は平均・1ch はそのまま)。
 *
 * <p>resampler は 44.1kHz → 48kHz で末尾 51 sample (約 1.06ms) を返さない (実測。
 * libsamplerate の遅延分で、{@code flush()} も空実装)。normalize の「曲が途中で終わった」
 * 判定は 1 秒単位なので、この差は判定に影響しない。
 */
final class MonoPcmConverter implements PcmFrameSink {

    private final int channels;
    private final PcmQueue queue;
    private final FloatPcmAudioFilter chain;
    private float[][] samples = new float[0][];

    MonoPcmConverter(int inputRate, int channels, PcmQueue queue) {
        if (channels <= 0) {
            throw new IllegalArgumentException("channels must be positive: " + channels);
        }
        this.channels = channels;
        this.queue = queue;
        final ShortSink sink = new ShortSink(queue);
        FloatPcmAudioFilter tail = sink;
        if (channels > 2) {
            tail = new ChannelCountPcmAudioFilter(channels, 2, sink);
        }
        if (inputRate != MusicLoaderImpl.SAMPLE_RATE) {
            final AudioConfiguration configuration = new AudioConfiguration();
            configuration.setResamplingQuality(AudioConfiguration.ResamplingQuality.HIGH);
            tail = new ResamplingPcmAudioFilter(configuration, channels, tail,
                    inputRate, MusicLoaderImpl.SAMPLE_RATE);
        }
        this.chain = tail;
    }

    @Override
    public void accept(short[] interleaved, int frames) throws IOException {
        if (frames <= 0) {
            return;
        }
        if (samples.length != channels || samples[0].length < frames) {
            samples = new float[channels][Math.max(frames, 4096)];
        }
        for (int channel = 0; channel < channels; channel++) {
            final float[] target = samples[channel];
            // int の掛け算で index を出す (frame 数 × channel 数は 24bit でも 20 万未満)。
            for (int frame = 0, index = channel; frame < frames; frame++, index += channels) {
                target[frame] = interleaved[index] / 32768f;
            }
        }
        try {
            chain.process(samples, 0, frames);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while converting");
        }
    }

    /** 末尾の flush と queue の終端記録。正常終了の時だけ呼ぶ。 */
    void finish() throws IOException {
        try {
            chain.flush();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted while flushing");
        }
        queue.end();
    }

    /**
     * 48kHz mono S16LE の bytes を queue へ積む末尾。ここから先は PCM bytes だけを扱う。
     *
     * <p>3ch 以上は {@link ChannelCountPcmAudioFilter} を通った後の 2ch が来るので、
     * この sink の float 経路だけを使う (short 経路は使わない)。
     */
    private static final class ShortSink implements UniversalPcmAudioFilter {

        private final PcmQueue queue;

        ShortSink(PcmQueue queue) {
            this.queue = queue;
        }

        @Override
        public void process(float[][] input, int offset, int length) throws InterruptedException {
            if (length <= 0) {
                return;
            }
            final byte[] out = new byte[length * 2];
            final float[] left = input[0];
            final float[] right = input.length > 1 ? input[1] : null;
            for (int i = 0; i < length; i++) {
                final float value = right == null
                        ? left[offset + i]
                        : (left[offset + i] + right[offset + i]) * 0.5f;
                int sample = Math.round(value * 32768f);
                if (sample > 32767) {
                    sample = 32767;
                } else if (sample < -32768) {
                    sample = -32768;
                }
                out[i * 2] = (byte) sample;
                out[i * 2 + 1] = (byte) (sample >> 8);
            }
            try {
                queue.put(out);
            } catch (final InterruptedIOException e) {
                throw new InterruptedException("queue closed");
            }
        }

        @Override
        public void process(short[] input, int offset, int length) {
            throw new UnsupportedOperationException("float path only");
        }

        @Override
        public void process(ShortBuffer buffer) {
            throw new UnsupportedOperationException("float path only");
        }

        @Override
        public void process(short[][] input, int offset, int length) {
            throw new UnsupportedOperationException("float path only");
        }

        @Override
        public void seekPerformed(long requestedTime, long providedTime) {
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
