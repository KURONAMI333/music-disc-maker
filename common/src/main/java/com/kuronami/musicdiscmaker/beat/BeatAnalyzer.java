package com.kuronami.musicdiscmaker.beat;

import java.util.function.BooleanSupplier;

import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;

/**
 * mono S16LE の PCM を {@link BeatMap} へ落とす解析器。MC 型に一切依存しないので common に置ける
 * (= P7 の横展開は写経で済む)。
 *
 * <p>1 frame ごとに (a) 4 帯域の平均パワー (b) スペクトラルフラックス を出す。判定 (閾値・感度・
 * 平滑化) は一切ここでやらない — それらは config でライブに変えられなければならないので実行時
 * ({@link BeatOutput}) の仕事。ここは「音がどうだったか」だけを記録する。
 */
public final class BeatAnalyzer {

    private BeatAnalyzer() {
    }

    /** 無音の対数を取らないための下駄。 */
    private static final double EPSILON = 1e-12;
    /** 1 回の read で受けるバイト数。 */
    private static final int READ_CHUNK = 64 * 1024;
    /** 基準 (95 パーセンタイル) を引き直す間隔 (frame)。20ms hop なので 250 frame = 5 秒。 */
    private static final int REFERENCE_INTERVAL = 250;

    /**
     * {@code source} を終端まで pull しながら {@code out} を埋める。呼び出しスレッドを占有する
     * (専用スレッドから呼ぶこと)。
     *
     * @param source    PCM ソース。mono / 16bit を前提にする (MDM は全ソースで mono 化済み)
     * @param out       書き込み先
     * @param fftSize   変換長 (2 のべき乗)
     * @param keepGoing false を返したら途中で打ち切る (server 停止・キャンセル)
     * @return 打ち切られずに終端 (または申告尺) まで読めたら true
     */
    public static boolean analyze(IAudioSource source, BeatMap out, int fftSize, BooleanSupplier keepGoing) {
        final int sampleRate = source.sampleRate();
        final int bytesPerSample = Math.max(1, source.bitsPerSample() / 8);
        final int channels = Math.max(1, source.channels());
        final int frameBytes = bytesPerSample * channels;
        final Fft fft = new Fft(fftSize);

        // 帯域ごとの bin 範囲を先に引いておく。
        final BeatBand[] bands = BeatBand.values();
        final int[] fromBin = new int[bands.length];
        final int[] toBin = new int[bands.length];
        for (int b = 0; b < bands.length; b++) {
            fromBin[b] = fft.binOf(bands[b].lowHz(), sampleRate);
            toBin[b] = Math.max(fromBin[b] + 1, fft.binOf(bands[b].highHz(), sampleRate));
        }

        final int hopSamples = Math.max(1, sampleRate * out.hopMs() / 1000);
        // 直近 fftSize サンプルの循環バッファ。素の左シフトだと 1 サンプルごとに fftSize の
        // コピーが要り (48kHz × 2048 = 毎秒 1 億回) 解析がデコードより遅くなる。
        final float[] ring = new float[fftSize];
        int writePos = 0;
        int filled = 0;

        final float[] frame = new float[fftSize];
        final float[] re = new float[fftSize];
        final float[] im = new float[fftSize];
        final float[] mag = new float[fftSize / 2];
        final float[] prevMag = new float[fftSize / 2];
        final double[] bandDb = new double[bands.length];

        final byte[] raw = new byte[READ_CHUNK];
        int sinceHop = 0;
        boolean emittedAny = false;
        int framesSinceReference = 0;
        int carry = 0; // サンプル境界をまたいだ端数バイト

        while (true) {
            if (!keepGoing.getAsBoolean()) {
                return false;
            }
            final int read = source.read(raw, carry, raw.length - carry);
            if (read < 0) {
                break; // 終端
            }
            if (read == 0) {
                continue; // 一時的な枯渇。EOF ではないので回し続ける
            }
            final int available = carry + read;
            final int usable = available - available % frameBytes;

            for (int p = 0; p < usable; p += frameBytes) {
                // mono S16LE 前提。多チャンネルが来ても先頭チャンネルだけ見る (MDM は mono 固定)。
                final int sample = (short) ((raw[p] & 0xFF) | (raw[p + 1] << 8));
                ring[writePos] = sample / 32768f;
                writePos = writePos + 1 == fftSize ? 0 : writePos + 1;
                if (filled < fftSize) {
                    filled++;
                    if (filled < fftSize) {
                        continue; // 最初の 1 窓が埋まるまでは frame を出さない
                    }
                }
                if (++sinceHop < hopSamples && emittedAny) {
                    continue;
                }
                sinceHop = 0;
                emittedAny = true;

                // 循環バッファを古い順に取り出す (writePos が最古の位置)。
                final int tail = fftSize - writePos;
                System.arraycopy(ring, writePos, frame, 0, tail);
                System.arraycopy(ring, 0, frame, tail, writePos);
                fft.magnitudeSpectrum(frame, re, im, mag);

                for (int b = 0; b < bands.length; b++) {
                    final double meanSquare = fft.bandMeanSquare(mag, fromBin[b], toBin[b]);
                    bandDb[b] = 10.0 * Math.log10(meanSquare + EPSILON);
                }
                // スペクトラルフラックス = 前フレームからの振幅増分の半波整流和。
                double flux = 0.0;
                for (int k = 0; k < mag.length; k++) {
                    final float diff = mag[k] - prevMag[k];
                    if (diff > 0f) {
                        flux += diff;
                    }
                }
                System.arraycopy(mag, 0, prevMag, 0, mag.length);
                // 帯域レベルと同じ dB スケールに載せる (実行時の正規化を 1 本の式で書けるように)。
                final double fluxDb = 20.0 * Math.log10(flux / mag.length + EPSILON);

                out.append(bandDb, fluxDb);
                if (++framesSinceReference >= REFERENCE_INTERVAL) {
                    framesSinceReference = 0;
                    out.refreshReference(); // 進行中の出力にも正規化を効かせる
                }
                if (out.ready() >= out.capacity()) {
                    out.markComplete();
                    return true; // 申告尺ぶん埋まった
                }
            }

            carry = available - usable;
            if (carry > 0) {
                System.arraycopy(raw, usable, raw, 0, carry); // arraycopy は重なりを正しく扱う
            }
        }
        out.markComplete();
        return true;
    }
}
