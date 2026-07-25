package com.kuronami.musicdiscmaker.beat;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 1 曲ぶんの「時刻 → 帯域強度 / オンセット」の時系列。server が持ち、コンパレータ出力の唯一の根拠になる。
 *
 * <p><b>格納するのは生の dBFS であって 0..15 ではない。</b> 感度・下限 dB・帯域・モードは config で
 * ライブに変えられる必要があるので、量子化 (0..15 への写像) は実行時に行う。値は
 * [-60dB, 0dB] を 0..255 へ線形量子化した 1 byte / frame / 帯域。
 *
 * <p>正規化の基準として帯域ごとの 95 パーセンタイル ({@link #reference}) を持つ。曲ごとの音圧差を
 * ここで吸収するので、静かな曲でも同じ config でビートが出る。これは曲全体を見られる
 * オフライン解析だけができることで、リアルタイム検知に対する本質的な優位点。
 *
 * <p><b>スレッド安全性</b>: 解析スレッドが 1 本だけ書き、server tick が読む。配列は生成時に
 * 確保しきって再確保しない (伸長なし) ので、{@code volatile} な {@link #ready} の前進だけで
 * 「どこまで書けたか」が安全に伝わる。読み手は必ず {@code ready} を先に読む。
 */
public final class BeatMap {

    /** ファイル先頭のマジック "MDMB"。 */
    private static final int MAGIC = 0x4D444D42;
    private static final int FORMAT_VERSION = 1;
    /** 1 frame の時間長 (ms)。tick(50ms) より細かくして、出力オフセットの調整幅を tick 未満まで持たせる。 */
    public static final int HOP_MS = 20;
    /** 量子化の下端 (dBFS)。これより静かな音は 0 に潰れる。 */
    public static final double MIN_DB = -60.0;

    private final int hopMs;
    private final int capacity;
    /** [band][frame] の量子化済み dBFS。 */
    private final byte[][] levels;
    /** [frame] の量子化済みスペクトラルフラックス (オンセットの生値)。 */
    private final byte[] flux;
    /** 帯域ごとの 95 パーセンタイル (量子化スケール 0..255)。index 4 = flux 用。 */
    private volatile int[] reference = new int[BeatBand.values().length + 1];
    /** 解析済み frame 数。読み手はこれを先に読む。 */
    private volatile int ready;
    /** 最後まで解析しきったか (途中打ち切り / 進行中は false)。 */
    private volatile boolean complete;

    public BeatMap(int hopMs, int capacity) {
        this.hopMs = Math.max(1, hopMs);
        this.capacity = Math.max(1, capacity);
        this.levels = new byte[BeatBand.values().length][this.capacity];
        this.flux = new byte[this.capacity];
        // 未解析時に「基準が 0」で全開に見えないよう、既定は最大値にしておく。
        final int[] init = new int[BeatBand.values().length + 1];
        java.util.Arrays.fill(init, 255);
        this.reference = init;
    }

    /** 尺 (ms) から必要な frame 数を見積もる (端数と誤差ぶんの余裕を足す)。 */
    public static BeatMap forDuration(long durationMs) {
        final int frames = (int) Math.min(Integer.MAX_VALUE / 8L, durationMs / HOP_MS + 64L);
        return new BeatMap(HOP_MS, Math.max(64, frames));
    }

    public int hopMs() {
        return hopMs;
    }

    public int capacity() {
        return capacity;
    }

    public int ready() {
        return ready;
    }

    public boolean isComplete() {
        return complete;
    }

    /** 解析が届いている時刻の上限 (ms)。再生位置がこれを超えている間は出力 0 にする。 */
    public long coveredMs() {
        return (long) ready * hopMs;
    }

    // ── 書き込み (書き手は 1 スレッドだけ) ──────────────────────────────
    // 通常は BeatAnalyzer が埋めるが、別経路で解析結果を作って BeatMaps.install() に渡す
    // 使い方も想定して公開してある (P5 の音源キャッシュが 1 パスの副作用で埋める形)。

    /**
     * 1 frame ぶんを追記する。容量を超えたら黙って捨てる (尺の申告より長い曲)。
     *
     * @param bandDb 帯域ごとの dBFS (長さ = {@link BeatBand} の数)
     * @param fluxDb スペクトラルフラックスの dB 相当値
     */
    public void append(double[] bandDb, double fluxDb) {
        final int i = ready;
        if (i >= capacity) {
            return;
        }
        for (int b = 0; b < levels.length; b++) {
            levels[b][i] = quantize(bandDb[b]);
        }
        flux[i] = quantize(fluxDb);
        ready = i + 1; // 配列への書き込みが先・公開が後 (volatile write が可視性を担保する)
    }

    /** 現在の解析済み範囲から帯域ごとの 95 パーセンタイルを引き直す。 */
    public void refreshReference() {
        final int n = ready;
        final int[] next = new int[levels.length + 1];
        for (int b = 0; b < levels.length; b++) {
            next[b] = percentile95(levels[b], n);
        }
        next[levels.length] = percentile95(flux, n);
        reference = next;
    }

    /** 解析完了を宣言する (基準を最終値へ引き直す)。 */
    public void markComplete() {
        refreshReference();
        complete = true;
    }

    /** 量子化済みの値そのものが 0..255 のヒストグラム bin なので、数えるだけで正確に求まる。 */
    private static int percentile95(byte[] data, int count) {
        if (count <= 0) {
            return 255;
        }
        final int[] histogram = new int[256];
        for (int i = 0; i < count; i++) {
            histogram[data[i] & 0xFF]++;
        }
        final int target = (int) Math.ceil(count * 0.95);
        int cumulative = 0;
        for (int v = 0; v < 256; v++) {
            cumulative += histogram[v];
            if (cumulative >= target) {
                return Math.max(1, v);
            }
        }
        return 255;
    }

    private static byte quantize(double db) {
        final double clamped = Math.max(MIN_DB, Math.min(0.0, db));
        final int q = (int) Math.round((clamped - MIN_DB) * 255.0 / -MIN_DB);
        return (byte) Math.max(0, Math.min(255, q));
    }

    private static double dequantize(int raw) {
        return MIN_DB + raw * -MIN_DB / 255.0;
    }

    // ── 読み出し (server tick) ──────────────────────────────────────────

    /**
     * {@code [fromMs, toMs)} に入る frame の中の最大レベルを dBFS で返す。
     * tick(50ms) より frame が細かいので、tick 内のピークを取りこぼさないよう max プーリングする。
     *
     * @return dBFS。区間が未解析なら {@link Double#NaN}
     */
    public double peakDb(BeatBand band, long fromMs, long toMs) {
        return peak(levels[band.ordinal()], fromMs, toMs);
    }

    /** オンセットの生値 (dB 相当) を同じく max プーリングで返す。未解析は {@link Double#NaN}。 */
    public double peakFluxDb(long fromMs, long toMs) {
        return peak(flux, fromMs, toMs);
    }

    private double peak(byte[] data, long fromMs, long toMs) {
        final int limit = ready;
        if (limit <= 0 || fromMs < 0L) {
            return Double.NaN;
        }
        final int from = (int) (fromMs / hopMs);
        final int to = (int) Math.max(from + 1, toMs / hopMs);
        if (from >= limit) {
            return Double.NaN; // 解析が再生位置に追いついていない
        }
        int best = -1;
        for (int i = from; i < Math.min(to, limit); i++) {
            best = Math.max(best, data[i] & 0xFF);
        }
        return best < 0 ? Double.NaN : dequantize(best);
    }

    /** 正規化の基準レベル (dBFS)。曲ごとの音圧差はここで吸収される。 */
    public double referenceDb(BeatBand band) {
        return dequantize(reference[band.ordinal()]);
    }

    /** オンセットの基準レベル (dB 相当)。 */
    public double referenceFluxDb() {
        return dequantize(reference[levels.length]);
    }

    // ── 永続化 ──────────────────────────────────────────────────────────

    /** 完成したビートマップだけを書く (進行中は呼ばない)。 */
    public void write(OutputStream out) throws IOException {
        final DataOutputStream data = new DataOutputStream(out);
        data.writeInt(MAGIC);
        data.writeInt(FORMAT_VERSION);
        data.writeInt(hopMs);
        final int n = ready;
        data.writeInt(n);
        data.writeInt(levels.length);
        final int[] ref = reference;
        for (final int r : ref) {
            data.writeInt(r);
        }
        for (final byte[] band : levels) {
            data.write(band, 0, n);
        }
        data.write(flux, 0, n);
        data.flush();
    }

    /**
     * @return 読めたビートマップ。マジック / 版が違う (= 古い形式のキャッシュ) なら {@code null}
     */
    public static BeatMap read(InputStream in) throws IOException {
        final DataInputStream data = new DataInputStream(in);
        if (data.readInt() != MAGIC || data.readInt() != FORMAT_VERSION) {
            return null;
        }
        final int hopMs = data.readInt();
        final int frames = data.readInt();
        final int bands = data.readInt();
        if (hopMs <= 0 || frames < 0 || bands != BeatBand.values().length) {
            return null;
        }
        final BeatMap map = new BeatMap(hopMs, Math.max(1, frames));
        final int[] ref = new int[bands + 1];
        for (int i = 0; i < ref.length; i++) {
            ref[i] = data.readInt();
        }
        for (int b = 0; b < bands; b++) {
            data.readFully(map.levels[b], 0, frames);
        }
        data.readFully(map.flux, 0, frames);
        map.reference = ref;
        map.ready = frames;
        map.complete = true;
        return map;
    }
}
