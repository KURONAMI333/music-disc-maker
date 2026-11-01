package com.kuronami.musicdiscmaker.lavaplayer;

import java.io.IOException;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.beatofthedrum.alacdecoder.AlacContext;
import com.beatofthedrum.alacdecoder.AlacUtils;

/**
 * ALAC (Apple Lossless、M4A) を自前で decode する。通常の入力 probe は MP4 の音声 track を
 * {@code mp4a} だけ受理するので、ALAC ({@code alac}) の M4A はここで読む。
 *
 * <p>使うのは {@code javasound-alac} のコア (MP4 の moov 解析込み) だけで、{@code javax.sound}
 * 向け SPI は同梱していない ({@code lavaplayer/build.gradle.kts} の repackAlac)。
 *
 * <p><b>sample の形</b>: {@link AlacUtils#AlacUnpackSamples} の戻り値は byte 数で、
 * 中身は byte ごとの表現になる (16bit は 1 要素 1 sample、24bit は 1 要素 1 byte の
 * 3 byte が 1 sample)。16bit へ落とす規則は同ライブラリの
 * {@code Alac2PcmAudioInputStream.format_samples} に合わせる:
 * 24bit は上位 2 byte、8bit は下位 8bit を 16bit の上位へ移す。
 *
 * <p><b>尺</b>: {@link AlacUtils#AlacGetNumSamples} は block 数 × 先頭 block の sample 数で、
 * frame 境界へ切り上げた値になる (実測 2 秒の a16 で 90,112 = 88,200 + 1,912)。encoder の
 * レート選択にだけ使うのでこの過大評価は許容する (表示には使わない)。
 */
final class AlacPcmDecoder implements PcmDecoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(AlacPcmDecoder.class);

    /** ライブラリ側の 1 回の出力上限 (1024 block × 24bit × 3)。 */
    private static final int UNPACK_BYTES = 1024 * 24 * 3;

    /** 宣言値。sample 数は frame 境界へ切り上げた概算。 */
    record Info(int sampleRate, int channels, int bitsPerSample, long samplesPerChannel,
            long durationMs) {
    }

    private final Path file;
    private final Info info;

    AlacPcmDecoder(Path file, Info info) {
        this.file = file;
        this.info = info;
    }

    /**
     * moov を読んで形式と尺を得る。ALAC の track が無い入力 (AAC の M4A) は
     * {@link com.beatofthedrum.alacdecoder.AlacException} で落ちるので、呼び出し側はそれを
     * 捕まえて通常経路の失敗理由をそのまま使う。
     */
    static Info probe(Path file) throws IOException {
        final AlacContext context = AlacUtils.AlacOpenFileInput(new AlacContext(), file.toFile());
        try {
            if (context == null || context.error) {
                throw failure(context);
            }
            final int sampleRate = AlacUtils.AlacGetSampleRate(context);
            final int channels = AlacUtils.AlacGetNumChannels(context);
            final int bitsPerSample = AlacUtils.AlacGetBitsPerSample(context);
            final int samples = AlacUtils.AlacGetNumSamples(context);
            if (context.error) {
                throw failure(context);
            }
            final long durationMs = sampleRate > 0 && samples > 0
                    ? samples * 1000L / sampleRate
                    : 0L;
            LOGGER.debug("ALAC input {}Hz {}ch {}bit {} samples", sampleRate, channels,
                    bitsPerSample, samples);
            return new Info(sampleRate, channels, bitsPerSample, Math.max(samples, 0), durationMs);
        } finally {
            AlacUtils.AlacCloseFile(context);
        }
    }

    private static IOException failure(AlacContext context) {
        if (context != null && context.error_message != null) {
            return context.error_message;
        }
        return new IOException("could not read the ALAC headers");
    }

    @Override
    public int sampleRate() {
        return info.sampleRate();
    }

    @Override
    public int channels() {
        return info.channels();
    }

    @Override
    public void decode(PcmFrameSink sink) throws IOException {
        final AlacContext context = AlacUtils.AlacOpenFileInput(new AlacContext(), file.toFile());
        try {
            if (context == null || context.error) {
                throw failure(context);
            }
            final int bytesPerSample = AlacUtils.AlacGetBytesPerSample(context);
            final int channels = info.channels();
            final int[] unpacked = new int[UNPACK_BYTES];
            final short[] interleaved = new short[UNPACK_BYTES];
            while (true) {
                final int bytes = AlacUtils.AlacUnpackSamples(context, unpacked);
                if (context.error) {
                    throw failure(context);
                }
                if (bytes <= 0) {
                    break;
                }
                final int samples = toInterleaved(interleaved, bytesPerSample, unpacked, bytes);
                final int frames = samples / channels;
                if (frames > 0) {
                    sink.accept(interleaved, frames);
                }
            }
        } finally {
            AlacUtils.AlacCloseFile(context);
        }
    }

    /**
     * ライブラリの byte 表現を S16LE の sample 列へ直す。
     *
     * @return 書いた sample 数 (channel を混ぜた総数)
     */
    private static int toInterleaved(short[] out, int bytesPerSample, int[] source, int bytes)
            throws IOException {
        switch (bytesPerSample) {
            case 1: {
                // 8bit は 1 要素 1 sample。16bit 出力の上位 byte へ移す。
                for (int i = 0; i < bytes; i++) {
                    out[i] = (short) (source[i] << 8);
                }
                return bytes;
            }
            case 2: {
                // 16bit は 1 要素 1 sample の下位 2 byte がそのまま S16LE。
                final int count = bytes / 2;
                for (int i = 0; i < count; i++) {
                    out[i] = (short) source[i];
                }
                return count;
            }
            case 3: {
                // 24bit は 1 要素 1 byte。上位 2 byte を取る (下位 8bit は落とす)。
                final int count = bytes / 3;
                for (int i = 0; i < count; i++) {
                    final int base = i * 3;
                    out[i] = (short) ((source[base + 1] & 0xFF) | (source[base + 2] << 8));
                }
                return count;
            }
            default:
                throw new IOException("unsupported ALAC sample size: " + bytesPerSample * 8 + "bit");
        }
    }
}
