package com.kuronami.musicdiscmaker.lavaplayer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/**
 * 先読みの time-to-buffer 計測用に、ネットワークもエンコード遅延も持たない安定した音源を作る。
 *
 * <p>48kHz stereo 16bit PCM の WAV (= {@code MusicLoaderImpl} が lavaplayer に要求する出力
 * format とビット互換) をサイン波で生成する。lavaplayer 側の resample/channel 変換が発生しない
 * ため、計測したい「HTTP 転送 + frame buffer への詰め込み」だけを切り出せる。
 */
final class WavFixture {

    static final int SAMPLE_RATE = 48000;
    static final int CHANNELS = 2;
    static final int BYTES_PER_FRAME = CHANNELS * 2; // 16bit

    private WavFixture() {
    }

    /**
     * 指定秒数ぶんのサイン波 WAV を一時ファイルへ書き出す。
     *
     * @param dir          書き出し先ディレクトリ
     * @param durationSecs 長さ (秒)
     * @return 生成した WAV ファイルの絶対パス
     */
    static Path write(Path dir, int durationSecs) throws IOException {
        final int totalFrames = durationSecs * SAMPLE_RATE;
        final byte[] pcm = new byte[totalFrames * BYTES_PER_FRAME];
        final double freqHz = 440.0;
        for (int frame = 0; frame < totalFrames; frame++) {
            final double t = frame / (double) SAMPLE_RATE;
            final short sample = (short) (Math.sin(2 * Math.PI * freqHz * t) * 12000);
            final int off = frame * BYTES_PER_FRAME;
            for (int ch = 0; ch < CHANNELS; ch++) {
                final int o = off + ch * 2;
                pcm[o] = (byte) (sample & 0xFF);
                pcm[o + 1] = (byte) ((sample >> 8) & 0xFF);
            }
        }

        final AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                SAMPLE_RATE, 16, CHANNELS, BYTES_PER_FRAME, SAMPLE_RATE, false);
        final ByteArrayOutputStream wavBytes = new ByteArrayOutputStream();
        try (AudioInputStream ais = new AudioInputStream(
                new java.io.ByteArrayInputStream(pcm), format, totalFrames)) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wavBytes);
        }

        final Path file = dir.resolve("prefetch-fixture.wav");
        Files.write(file, wavBytes.toByteArray());
        return file;
    }
}
