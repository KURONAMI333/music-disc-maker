package com.kuronami.musicdiscmaker.audio.cache;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.lavaplayer.api.IOpusEncoder;

/**
 * 再生中の PCM を受け取り、Opus に圧縮してキャッシュファイルへ書く。
 *
 * <h2>確定条件 (ここが機能の要)</h2>
 * <b>「ソースが終端 (-1) を返した」かつ「受け取った PCM の長さが申告尺の
 * {@value #COMPLETE_RATIO_PERCENT}% 以上」</b>の両方が揃った時だけ {@code .part} を確定させる。
 *
 * <ul>
 *   <li>終端だけでは足りない: {@code LavaAudioSource} は<b>回線が 10 秒詰まった時にも -1 を返す</b>
 *       ので、自然終端と途中放棄を区別できない。切り詰められた曲を焼くと、cache-first の下で
 *       <b>以後その曲は永久に途中で切れる</b> (rot より悪い。劣化が恒久化するため)。</li>
 *   <li>長さだけでも足りない: 曲が終わる前に停止されたのか、まだ鳴っている最中なのかが分からない。</li>
 * </ul>
 *
 * <p>{@value #COMPLETE_RATIO_PERCENT}% の許容は、途中の {@code read == 0} (瞬間的な取りこぼし)
 * も同時に縛る。裏返すと、<b>合計 2% までの内部欠落は通る</b>。
 *
 * <h2>スレッド</h2>
 * {@link #accept} は MC の streaming スレッドから呼ばれるので、そこでは
 * <b>コピーしてキューへ入れるだけ</b>。フレーム分割・Opus 圧縮・ファイル書き込みは専用スレッドで行う。
 * キューが溢れたらキャッシュを諦める (再生を止めない = fail-soft)。
 */
public final class AudioCacheWriter {

    /** 申告尺のこの割合まで受け取れて初めて「完走」とみなす。 */
    private static final int COMPLETE_RATIO_PERCENT = 98;
    /** 圧縮待ちキューの深さ。1 要素は最大 8KB (≒85ms) なので 256 で約 20 秒ぶんの余裕。 */
    private static final int QUEUE_CAPACITY = 256;
    /** キュー待ちの上限。閉じ要求はフラグで来るので、この間隔で見直す。 */
    private static final long POLL_TIMEOUT_MS = 200L;

    private final AudioCacheStore store;
    private final String key;
    private final String url;
    private final long maxBytes;
    private final IOpusEncoder encoder;
    private final AudioCacheFormat.Header header;
    /** 完走したとみなすのに必要な PCM バイト数。 */
    private final long requiredPcmBytes;

    private final BlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final Thread worker;
    private final AtomicBoolean closing = new AtomicBoolean();
    /** ソースが終端を返した (自然終端 or バッファ枯渇)。{@link #accept} と同じスレッドから立つ。 */
    private volatile boolean sawEnd;
    /** キューが溢れた / 書き込みに失敗した = このセッションのキャッシュは諦める。 */
    private volatile boolean broken;

    private AudioCacheWriter(AudioCacheStore store, String key, String url, long durationMs,
            long maxBytes, IOpusEncoder encoder) {
        this.store = store;
        this.key = key;
        this.url = url;
        this.maxBytes = maxBytes;
        this.encoder = encoder;
        this.header = new AudioCacheFormat.Header(AudioCacheFormat.CHANNELS,
                AudioCacheFormat.SAMPLE_RATE, encoder.frameSamples(), durationMs);
        final long fullPcmBytes = durationMs * AudioCacheFormat.SAMPLE_RATE / 1000L
                * AudioCacheFormat.CHANNELS * (AudioCacheFormat.BITS_PER_SAMPLE / 8);
        this.requiredPcmBytes = fullPcmBytes * COMPLETE_RATIO_PERCENT / 100L;
        this.worker = new Thread(this::run, "music_disc_maker-cache-write");
        this.worker.setDaemon(true);
        this.worker.setPriority(Thread.MIN_PRIORITY);
    }

    /**
     * 書き手を開く。{@link AudioCacheStore#claim} は<b>呼び出し側が済ませておくこと</b>
     * (開けなかった時に権を返す責任も呼び出し側にある)。
     *
     * @return 書き手。encoder が無い / ファイルを開けない時は {@code null}
     */
    @Nullable
    public static AudioCacheWriter open(AudioCacheStore store, String key, String url, long durationMs,
            long maxBytes, @Nullable IOpusEncoder encoder) {
        if (encoder == null || durationMs <= 0L) {
            return null;
        }
        final AudioCacheWriter writer = new AudioCacheWriter(store, key, url, durationMs, maxBytes, encoder);
        writer.worker.start();
        return writer;
    }

    /**
     * 再生に渡した PCM をそのまま受け取る (streaming スレッド)。
     *
     * <p><b>必ずコピーする。</b> {@code src} は呼び出し側が使い回すバッファなので、参照のまま
     * キューへ入れると次の read で中身が書き換わり、雑音だらけのファイルができる
     * (ビルドもテストも素通りする種類の壊れ方)。
     */
    public void accept(byte[] src, int off, int len) {
        if (broken || closing.get() || len <= 0) {
            return;
        }
        final byte[] copy = new byte[len];
        System.arraycopy(src, off, copy, 0, len);
        if (!queue.offer(copy)) {
            // 圧縮が追いつかない = このセッションは諦める。再生は止めない。
            broken = true;
            MusicDiscMaker.LOGGER.debug("音源キャッシュの書き込みが追いつかないので諦める: {}", url);
        }
    }

    /** ソースが終端 (-1) を返したことを伝える (streaming スレッド)。 */
    public void endOfStream() {
        sawEnd = true;
    }

    /**
     * 書き手を畳む。確定判定と後始末は<b>書き込みスレッドの上で</b>行うので、
     * 呼び出し側 (main thread / streaming スレッド) はここで待たされない。
     */
    public void close() {
        closing.set(true);
        // ここで worker を interrupt しない。Files.newOutputStream の実体は
        // AbstractInterruptibleChannel なので、書き込み中に割り込むと
        // ClosedByInterruptException でファイルが閉じられる = 自然終端で毎回キャッシュを
        // 落とすことになる。代わりに書き込みスレッドが待ち時間つきの poll で閉じ要求を見る。
    }

    // ── 書き込みスレッド ────────────────────────────────────────────────

    private void run() {
        final Path part = store.partFor(key);
        OutputStream out = null;
        final short[] frame = new short[header.frameSamples() * header.channels()];
        final byte[] packet = new byte[AudioCacheFormat.MAX_PACKET_BYTES];
        int framePos = 0;          // frame に溜まったサンプル数
        int pendingLowByte = -1;   // 奇数バイトで切れた S16LE の下位バイト
        long pcmBytes = 0L;
        boolean failed = false;

        try {
            Files.createDirectories(part.getParent());
            out = new BufferedOutputStream(Files.newOutputStream(part,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE), 1 << 16);
            AudioCacheFormat.writeHeader(out, header);

            while (true) {
                byte[] chunk = queue.poll();
                if (chunk == null) {
                    if (closing.get()) {
                        break; // 閉じ要求 + キュー空 = 終わり
                    }
                    try {
                        chunk = queue.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                    } catch (final InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        broken = true;
                        break;
                    }
                    if (chunk == null) {
                        continue; // 待ち時間切れ → closing を見直す
                    }
                }
                if (broken) {
                    continue; // 諦めた後も、キューは空にして参照を落とす
                }
                pcmBytes += chunk.length;
                // S16LE → short[]。チャンク境界でサンプルが割れることがあるので下位バイトを持ち越す。
                int i = 0;
                while (i < chunk.length) {
                    if (pendingLowByte < 0) {
                        pendingLowByte = chunk[i++] & 0xFF;
                        if (i >= chunk.length) {
                            break;
                        }
                    }
                    final int high = chunk[i++];
                    frame[framePos++] = (short) ((high << 8) | pendingLowByte);
                    pendingLowByte = -1;
                    if (framePos == frame.length) {
                        if (!writeFrame(out, frame, packet)) {
                            failed = true;
                            break;
                        }
                        framePos = 0;
                    }
                }
                if (failed) {
                    broken = true;
                }
            }

            // 端数フレームは無音で埋めて 1 フレームにする (捨てると末尾 20ms 未満が欠ける)。
            if (!broken && framePos > 0) {
                java.util.Arrays.fill(frame, framePos, frame.length, (short) 0);
                if (!writeFrame(out, frame, packet)) {
                    broken = true;
                }
            }
            out.flush();
        } catch (final IOException ex) {
            broken = true;
            MusicDiscMaker.LOGGER.debug("音源キャッシュの書き込みに失敗 ({}): {}", url, ex.toString());
        } finally {
            closeQuietly(out);
            encoder.close();
        }

        finish(pcmBytes);
    }

    private boolean writeFrame(OutputStream out, short[] frame, byte[] packet) {
        final int written = encoder.encode(frame, 0, packet);
        if (written <= 0) {
            return false;
        }
        try {
            AudioCacheFormat.writeFrame(out, packet, written);
            return true;
        } catch (final IOException ex) {
            return false;
        }
    }

    /** 確定するか捨てるかを決める。ここが唯一の出口 (権は必ず返る)。 */
    private void finish(long pcmBytes) {
        final boolean complete = sawEnd && !broken && pcmBytes >= requiredPcmBytes;
        if (!complete) {
            store.discard(key);
            if (!broken) {
                MusicDiscMaker.LOGGER.debug(
                        "音源キャッシュを確定しない ({}): 終端={} / 受信 {} byte (必要 {} byte)",
                        url, sawEnd, pcmBytes, requiredPcmBytes);
            }
            return;
        }
        if (store.commit(key, maxBytes)) {
            MusicDiscMaker.LOGGER.info("音源をキャッシュした: {} ({} KB)", url, pcmBytes / 1024L);
        }
    }

    private static void closeQuietly(@Nullable OutputStream out) {
        if (out == null) {
            return;
        }
        try {
            out.close();
        } catch (final IOException ignored) {
            // 閉じられなくても .part は捨てられる
        }
    }
}
