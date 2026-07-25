package com.kuronami.musicdiscmaker.audio.cache;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 音源ローカルキャッシュのコンテナ形式。
 *
 * <pre>
 * header (16 byte)
 *   0  : magic  'M' 'D' 'M' 'A'
 *   4  : u8   version (= 1)
 *   5  : u8   channels (= 1)
 *   6  : u32  sampleRate (= 48000)
 *   10 : u16  frameSamples (= 960 = 20ms @48kHz)
 *   12 : u32  durationMs (ディスクの申告尺・参考値)
 * frames (ファイル末尾まで繰り返し)
 *   u16 length + Opus パケット
 * </pre>
 *
 * <p><b>なぜ Ogg Opus でなく自前コンテナか</b>: シークが機能ゲート
 * (途中参加同期・GUI シークバー) だから。20ms 固定フレームなら「何フレーム読み飛ばすか」が
 * 割り算 1 回で決まり、実装が保証できる。Ogg muxer を自作すると、いちばん怪しい部分
 * (granulepos とページ境界) を自作コードに預けることになる。
 *
 * <p>フレーム数はヘッダに持たない。書き込みは追記のみで、末尾まで読めば全フレームが得られる
 * (書き込み途中のファイルは {@code .part} のまま公開されないので、中途半端な長さは読み手に見えない)。
 *
 * <p>読み手側の安全弁: パケット長は {@link #MAX_PACKET_BYTES} で上限を切る。壊れた/細工された
 * キャッシュファイルで巨大 allocation を起こさないため ({@code JacketCache} の MAX_IMAGE_BYTES と同じ思想)。
 */
public final class AudioCacheFormat {

    /** ファイル先頭のマジック。 */
    static final byte[] MAGIC = {'M', 'D', 'M', 'A'};
    static final int VERSION = 1;
    public static final int HEADER_BYTES = 16;

    /** Opus パケット 1 個の上限 (実測の最大は 20ms/mono で 180 byte・仕様上の上限は 1275)。 */
    public static final int MAX_PACKET_BYTES = 4096;

    /** キャッシュが扱う PCM 形式。{@code MusicLoaderImpl} の出力と一致していること。 */
    public static final int SAMPLE_RATE = 48000;
    public static final int CHANNELS = 1;
    public static final int BITS_PER_SAMPLE = 16;
    /** 1 フレームのサンプル数 = 20ms @48kHz。 */
    public static final int FRAME_SAMPLES = 960;

    private AudioCacheFormat() {
    }

    /** ヘッダの中身。 */
    public record Header(int channels, int sampleRate, int frameSamples, long durationMs) {

        /** 1 フレームの再生時間 (ms)。 */
        public long frameDurationMs() {
            return frameSamples * 1000L / sampleRate;
        }

        /** 1 フレームぶんの PCM バイト数。 */
        public int framePcmBytes() {
            return frameSamples * channels * (BITS_PER_SAMPLE / 8);
        }
    }

    static void writeHeader(OutputStream out, Header header) throws IOException {
        final byte[] buf = new byte[HEADER_BYTES];
        System.arraycopy(MAGIC, 0, buf, 0, MAGIC.length);
        buf[4] = (byte) VERSION;
        buf[5] = (byte) header.channels();
        putInt(buf, 6, header.sampleRate());
        putShort(buf, 10, header.frameSamples());
        putInt(buf, 12, (int) Math.min(Integer.MAX_VALUE, Math.max(0L, header.durationMs())));
        out.write(buf);
    }

    /**
     * ヘッダを読む。形式が違う / 壊れている場合は {@code null}
     * (呼び出し側はキャッシュ不命中として扱い、ファイルを捨てる)。
     */
    static Header readHeader(InputStream in) throws IOException {
        final byte[] buf = new byte[HEADER_BYTES];
        if (!readFully(in, buf, 0, HEADER_BYTES)) {
            return null;
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (buf[i] != MAGIC[i]) {
                return null;
            }
        }
        if ((buf[4] & 0xFF) != VERSION) {
            return null;
        }
        final int channels = buf[5] & 0xFF;
        final int sampleRate = getInt(buf, 6);
        final int frameSamples = getShort(buf, 10);
        final long durationMs = getInt(buf, 12) & 0xFFFFFFFFL;
        // decoder は 48kHz mono 固定で作るので、そこと違う申告は受け付けない。
        // 通すと CachedAudioSource が嘘の format を MC へ申告し、ピッチ違いやゴミ混入になる
        // (キャッシュファイルは細工されうる = ゲームディレクトリ下の素のファイル)。
        if (channels != CHANNELS || sampleRate != SAMPLE_RATE
                || frameSamples < 1 || frameSamples > 5760) {
            return null;
        }
        return new Header(channels, sampleRate, frameSamples, durationMs);
    }

    /** 1 フレームを書く (u16 長 + パケット)。 */
    static void writeFrame(OutputStream out, byte[] packet, int length) throws IOException {
        if (length < 1 || length > MAX_PACKET_BYTES) {
            throw new IOException("Opus パケット長が範囲外: " + length);
        }
        out.write((length >>> 8) & 0xFF);
        out.write(length & 0xFF);
        out.write(packet, 0, length);
    }

    /**
     * 次のフレームの長さを読む。ファイル終端なら {@code -1}。
     *
     * @throws IOException 長さが範囲外 (= 壊れている) / 長さの途中で終端
     */
    static int readFrameLength(InputStream in) throws IOException {
        final int hi = in.read();
        if (hi < 0) {
            return -1; // 正常な終端
        }
        final int lo = in.read();
        if (lo < 0) {
            throw new EOFException("フレーム長が途中で切れている");
        }
        final int length = (hi << 8) | lo;
        if (length < 1 || length > MAX_PACKET_BYTES) {
            throw new IOException("Opus パケット長が範囲外: " + length);
        }
        return length;
    }

    static boolean readFully(InputStream in, byte[] dst, int off, int len) throws IOException {
        int done = 0;
        while (done < len) {
            final int n = in.read(dst, off + done, len - done);
            if (n < 0) {
                return false;
            }
            done += n;
        }
        return true;
    }

    private static void putInt(byte[] b, int off, int value) {
        b[off] = (byte) ((value >>> 24) & 0xFF);
        b[off + 1] = (byte) ((value >>> 16) & 0xFF);
        b[off + 2] = (byte) ((value >>> 8) & 0xFF);
        b[off + 3] = (byte) (value & 0xFF);
    }

    private static void putShort(byte[] b, int off, int value) {
        b[off] = (byte) ((value >>> 8) & 0xFF);
        b[off + 1] = (byte) (value & 0xFF);
    }

    private static int getInt(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static int getShort(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }
}
