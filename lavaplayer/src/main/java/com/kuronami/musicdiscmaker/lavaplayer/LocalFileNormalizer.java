package com.kuronami.musicdiscmaker.lavaplayer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.PlaybackFault;
import com.kuronami.musicdiscmaker.lavaplayer.api.ResolveException;
import com.kuronami.musicdiscmaker.lavaplayer.api.TrackInfo;
import com.sedmelluq.discord.lavaplayer.natives.opus.OpusEncoder;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

/**
 * stage 3a の normalize コア。任意のローカル入力を decode し、mono 48kHz Opus の
 * 音声だけを持つ MKA へ変換する (設計: MDM_310_VIDEO_AUDIO_DESIGN_CORRECTED_RESULT_20260927 §9 stage3)。
 *
 * <p>経路は {@code localInputs.loadItem} (入力専用 registry で内容 probe) →
 * {@code beginPlayback} で mono 48kHz PCM を pull → {@link OpusEncoder} →
 * {@link MkaWriter}。入力のコンテナ・codec・映像バイトは生成物に写らず、
 * 出力は常に「音声だけで完結する保存形式」の MKA になる。
 *
 * <p><b>有界と中断</b>: packet を集めながら出力見積が {@link #MAX_OUTPUT_BYTES} を
 * 超えないよう監視し、超える直前で encode を止める。ただし止まった時に入力の終わりまで
 * 届いていなければ「曲が途中で終わる盤」を黙って作らない — bitrate を下げて 1 回だけ
 * 変換し直し ({@link #retryBitrate})、それでも入らなければ尺の上限として明示失敗する。
 * encode した実尺が {@link #MAX_OUTPUT_DURATION_NS} に達した時も同じ規則で判定する
 * (宣言尺が取れない入力や宣言尺を偽装した入力でも黙って切らない)。
 * 呼び出しスレッドの interrupt を各反復で観測し、中断時は encoder close・
 * temp 削除を保証した上で明示失敗にする。
 *
 * <p><b>尺と bitrate</b>: 長い曲を「尺の上限超過」で失敗させない。宣言尺から
 * 32MiB に収まる bitrate を選び ({@link #bitrateFor})、下限
 * {@link #MIN_OPUS_BITRATE} まで下げても収まらない尺だけを
 * {@link #MAX_DECLARED_DURATION_MS} で失敗にする。下限で選んだ曲でも VBR の上振れで
 * 溢れることがあるので、溢れた時は {@link #FINAL_MIN_OPUS_BITRATE} まで下げて
 * 曲が最後まで入ることを優先する。
 *
 * <p><b>明示失敗</b>: 入力が読めない/大きすぎる、container が decode 不能、
 * 宣言尺が 2 時間超、再生中の decode 失敗、音声 0 frame、encoder の native 不在、
 * 生成物の検証不合格、置換しない発行 (hard link) が使えない FS、発行時に
 * {@code output} が既に存在した競合。失敗時は {@code output} を作らず
 * 一時ファイルも残さない。
 */
final class LocalFileNormalizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalFileNormalizer.class);

    /** 生成物の上限。保存契約 (32MiB) と同じ値。エンコード中に打ち切るので実ファイルは常に内側。 */
    static final long MAX_OUTPUT_BYTES = 32L * 1024 * 1024;
    /**
     * 宣言尺が分かる入力の上限。下限 bitrate ({@link #MIN_OPUS_BITRATE}) でも 32MiB に収まる尺で、
     * 2 時間 × (32000 + {@link #CONTAINER_BPS}) / 8 = 31,680,000 byte ≈ 30.2MiB となり、上限
     * {@link #MAX_OUTPUT_BYTES} の 5.6% 内側に収まる。ここを超える尺は bitrate を下げても入らないので
     * 失敗にする。分からない/偽装された入力は encode 側の実尺・実バイトで判定する。
     */
    static final long MAX_DECLARED_DURATION_MS = 2L * 60L * 60_000L;
    /** 生成物の尺上限 (ns)。宣言尺と同じ 2 時間を encode した実尺側でも強制する。 */
    static final long MAX_OUTPUT_DURATION_NS = MAX_DECLARED_DURATION_MS * 1_000_000L;
    /** 入力ファイルの byte 上限 (設計 §8)。decode は streaming なので memory ではなく disk 問題を止める。 */
    static final long MAX_INPUT_BYTES = 2L * 1024 * 1024 * 1024;

    /** 1 Opus frame = 20ms @48kHz mono (960 samples = 1920 byte の S16LE)。 */
    private static final int FRAME_SAMPLES = 960;
    private static final int FRAME_BYTES = FRAME_SAMPLES * 2;
    private static final long FRAME_NS = 20_000_000L;
    /** encoder の bitrate。品質値は bitrate に不感なので固定 (設計 §6 実測)。短い曲はこの値のまま。 */
    private static final int OPUS_BITRATE = 64_000;
    /**
     * 長い曲で下げる bitrate の下限。これ以下は 32MiB に収まる尺が伸びず、劣化だけが増える
     * (2 時間はこの値で足りるので、下限を割る入力は尺の上限で失敗させる)。
     */
    static final int MIN_OPUS_BITRATE = 32_000;
    /**
     * 出力上限に当たって打ち切った時だけ 1 回使う最終下限。通常の選択は {@link #MIN_OPUS_BITRATE}。
     *
     * <p>2 時間 × (24000 + {@link #CONTAINER_BPS}) / 8 = 24,480,000 byte ≈ 23.3MiB なので、尺の
     * 上限内の入力なら必ず入る。bitrate を下げるほど音は劣化するが、下限で選んだ曲が VBR の
     * 上振れで溢れた時に「曲が途中で終わる盤」を作るより、最後まで鳴る盤の方が利用者の目的に合う。
     * libopus の受付範囲 (500〜512000bps) の内側。
     */
    static final int FINAL_MIN_OPUS_BITRATE = 24_000;
    /**
     * 「まだ曲が続いている」と数える残り尺。これ以下の切れは曲の終わりと区別できない
     * (この pin 版の再生経路は末尾 1 frame = 20ms を返さない) ので、成功に含める。
     */
    static final long SIGNIFICANT_REMAINING_NS = 1_000_000_000L;
    /** 選んだ bitrate の実測が目標をわずかに上回る分の余裕。出力見積は実 packet で別に検査する。 */
    private static final long OUTPUT_HEADROOM_PERCENT = 5L;

    /** 出力見積の固定分 (EBML header + Segment/Info/Tracks)。実測 ~250 byte に余裕を持たせる。 */
    private static final long ESTIMATE_FIXED_BYTES = 512L;
    /**
     * packet 1 個あたりの SimpleBlock 構造分 (block header 4 + element 外枠 ~4)。
     * 実測 7〜8 byte なので 8 を使う。以前の 12 は実際より 4 byte 多く見積もり、2 時間の曲では
     * 4 × 50 個/秒 × 7200 秒 ≈ 1.4MiB を上限の余白から差し引いていた (音声へ回せる分が減り、
     * 末尾が切れる側へ働く)。実測に合わせて音声へ回す。
     */
    static final long ESTIMATE_PER_PACKET = 8L;
    /** cluster 1 個あたりの構造分 (Cluster element + Timestamp)。30 秒ごとに 1 個。 */
    private static final long ESTIMATE_PER_CLUSTER = 16L;
    /** 尺から bitrate を選ぶ時のコンテナ分 (20ms ごとの構造 = {@link #ESTIMATE_PER_PACKET} × 50 個/秒 × 8 bit)。 */
    static final long CONTAINER_BPS = ESTIMATE_PER_PACKET * 50L * 8L;

    /** encode を止めた理由。 */
    enum StopReason {
        /** 入力の終わり (EOF) まで達した。 */
        END_OF_INPUT,
        /** 出力バイトの上限 ({@code maxOutputBytes}) に当たった。 */
        OUTPUT_BYTES,
        /** 実尺の上限 ({@code maxDurationNs}) に当たった。 */
        DURATION
    }

    /**
     * 1 回の encode の結果。
     *
     * @param packets     書けた packet (先頭から)
     * @param encodedNs   packet が表す尺
     * @param estimate    出力見積 (byte)
     * @param remainingNs 止めた後にまだ入力が続いていた尺 (EOF までなら 0)
     * @param stop        止まった理由
     */
    record Pass(List<MkaWriter.OpusPacket> packets, long encodedNs, long estimate, long remainingNs,
            StopReason stop) {

        /** 曲が途中で終わる盤になるか (入力の終わりに届かず、残りが 1 秒を超えている)。 */
        boolean isShort() {
            return stop != StopReason.END_OF_INPUT && remainingNs > SIGNIFICANT_REMAINING_NS;
        }
    }


    private final MusicLoaderImpl loader;
    private final long maxOutputBytes;
    private final long maxDurationNs;
    private final IntFunction<OpusEncoder> encoderFactory;
    /** 発行直前に走る口 (試験が check→発行の窓への競合を注入する。製品は no-op)。 */
    private final Runnable beforePublish;

    LocalFileNormalizer(MusicLoaderImpl loader) {
        this(loader, MAX_OUTPUT_BYTES, MAX_OUTPUT_DURATION_NS, LocalFileNormalizer::newEncoder,
                () -> {});
    }

    /** 試験が出力上限・尺上限・encoder 生成・発行直前を差し替える口 (製品経路は上の ctor)。 */
    LocalFileNormalizer(MusicLoaderImpl loader, long maxOutputBytes, long maxDurationNs,
            IntFunction<OpusEncoder> encoderFactory, Runnable beforePublish) {
        this.loader = loader;
        this.maxOutputBytes = maxOutputBytes;
        this.maxDurationNs = maxDurationNs;
        this.encoderFactory = encoderFactory;
        this.beforePublish = beforePublish;
    }

    private static OpusEncoder newEncoder(int bitrate) {
        return new OpusEncoder(MusicLoaderImpl.SAMPLE_RATE, MusicLoaderImpl.CHANNELS, bitrate);
    }

    /**
     * 宣言尺と出力上限から、出力が 32MiB に収まる Opus bitrate を選ぶ。
     *
     * <p>尺が短い入力は {@link #OPUS_BITRATE} のまま (品質を落とさない)。収まらない長さの時だけ
     * 下げ、下限 {@link #MIN_OPUS_BITRATE} で止める。尺が分からない入力
     * ({@code 0} / {@link Long#MAX_VALUE}) は上限尺を仮定して最も低い側に置く
     * (encode 中は実 packet で出力見積を別に検査するので、外しても失敗はしない)。
     */
    static int bitrateFor(long durationMs, long maxBytes) {
        final long ms = durationMs > 0L && durationMs != Long.MAX_VALUE
                ? durationMs
                : MAX_DECLARED_DURATION_MS;
        final long budget = maxBytes - maxBytes * OUTPUT_HEADROOM_PERCENT / 100L
                - ESTIMATE_FIXED_BYTES;
        if (budget <= 0L) {
            return MIN_OPUS_BITRATE;
        }
        final long allowed = budget * 8_000L / ms - CONTAINER_BPS;
        if (allowed <= MIN_OPUS_BITRATE) {
            return MIN_OPUS_BITRATE;
        }
        return (int) Math.min(allowed, OPUS_BITRATE);
    }

    /**
     * {@code input} を decode → Opus → MKA へ変換し、検証してから {@code output} へ
     * atomic rename する (ブロッキング・interrupt をキャンセルとして観測)。
     *
     * @return 生成した MKA のメタ情報
     * @throws ResolveException 失敗時 (output と temp は残さない)
     */
    TrackInfo normalize(Path input, Path output) {
        try {
            return run(input.toAbsolutePath().normalize(), output.toAbsolutePath().normalize());
        } catch (final ResolveException failure) {
            throw failure;
        } catch (final IOException io) {
            LOGGER.warn("Normalization failed with an I/O error", io);
            throw new ResolveException(FailureReason.UNKNOWN, "normalization i/o failed");
        } catch (final Throwable thrown) {
            // decode/encode の想定外 (native 内の失敗を含む) も理由つき失敗に畳む。
            LOGGER.warn("Normalization failed with an unexpected error", thrown);
            throw new ResolveException(FailureReason.UNKNOWN, "normalization failed");
        }
    }

    private TrackInfo run(Path input, Path output) throws IOException {
        if (!Files.isRegularFile(input) || !Files.isReadable(input)) {
            throw new ResolveException(FailureReason.UNKNOWN, "input file is not readable");
        }
        if (Files.size(input) <= 0L) {
            throw new ResolveException(FailureReason.UNKNOWN, "input file is empty");
        }
        if (Files.size(input) > MAX_INPUT_BYTES) {
            throw new ResolveException(FailureReason.UNKNOWN, "input file exceeds the size limit");
        }
        if (Files.exists(output)) {
            throw new ResolveException(FailureReason.UNKNOWN, "output file already exists");
        }
        final Path directory = output.getParent();
        if (directory == null) {
            throw new ResolveException(FailureReason.UNKNOWN, "output has no parent directory");
        }
        Files.createDirectories(directory);
        final Path temp = Files.createTempFile(directory, ".mdm-normalize-", ".tmp");
        try {
            final long declaredMs = declaredDurationMs(input);
            if (declaredMs > 0L && declaredMs != Long.MAX_VALUE
                    && declaredMs > MAX_DECLARED_DURATION_MS) {
                throw durationLimit();
            }
            // 宣言尺から bitrate を決め、収まらなければ下げて 1 回だけ変換し直す。
            final List<MkaWriter.OpusPacket> packets = encodeWithinLimits(input, declaredMs);
            if (packets.isEmpty()) {
                throw new ResolveException(FailureReason.UNKNOWN, "no decodable audio");
            }
            MkaWriter.write(temp, packets);
            final TrackInfo info = verify(temp);
            publish(temp, output);
            LOGGER.info("Normalized a local file into {} bytes ({}ms, {} packets)",
                    Files.size(output), info.durationMs(), packets.size());
            return info;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** 入力の宣言尺 (ms)。分からない入力は {@code 0} / {@link Long#MAX_VALUE} のまま返す。 */
    private long declaredDurationMs(Path input) {
        final AudioTrack track = loadInputTrack(input);
        try {
            return track.getInfo().length;
        } finally {
            track.stop();
        }
    }

    /**
     * encoder を立てる。native 不在では理由つき失敗 (「読めないファイル」とは別の話)。
     * ここへ来る時点で input は内容 probe 済みだが、再生 stream はまだ開いていない。
     */
    private OpusEncoder openEncoder(int bitrate) {
        try {
            return encoderFactory.apply(bitrate);
        } catch (final Throwable thrown) {
            LOGGER.warn("Opus encoder is unavailable", thrown);
            throw new ResolveException(FailureReason.UNKNOWN, "opus encoder is unavailable");
        }
    }

    /** 入力 registry で内容 probe して track を取る。decode 不能な入力はここで明示失敗。 */
    private AudioTrack loadInputTrack(Path input) {
        final AudioItem item;
        try {
            item = loader.inputFiles().loadItem(loader.playerManager(),
                    new AudioReference(input.toString(), null));
        } catch (final Throwable thrown) {
            LOGGER.warn("Failed to inspect the input file", thrown);
            throw new ResolveException(FailureReason.UNKNOWN,
                    "could not inspect the input file");
        }
        if (!(item instanceof AudioTrack track)) {
            throw new ResolveException(FailureReason.UNKNOWN,
                    "unsupported or undecodable input file");
        }
        return track;
    }

    /**
     * 上限に収まる形で encode する。上限に当たって途中で止まり、それでも曲が続いている時は
     * 「曲が途中で終わる盤」を黙って作らない: bitrate を下げて 1 回だけ変換し直し
     * ({@link #retryBitrate})、それでも入らなければ尺の上限として明示失敗する。
     *
     * <p>尺の上限 ({@link #maxDurationNs}) に当たった時は下げても伸びないので変換し直さない。
     *
     * @param declaredMs 入力の宣言尺 (分からなければ 0 / {@link Long#MAX_VALUE})
     */
    private List<MkaWriter.OpusPacket> encodeWithinLimits(Path input, long declaredMs) {
        final int bitrate = bitrateFor(declaredMs, maxOutputBytes);
        Pass pass = encodePass(input, bitrate);
        if (pass.stop() == StopReason.OUTPUT_BYTES && pass.isShort()) {
            final int retry = retryBitrate(bitrate, pass.encodedNs(), declaredMs, pass.estimate(),
                    maxOutputBytes);
            if (retry < bitrate) {
                LOGGER.info("Re-encoding at {}bps (was {}bps) so the whole track fits",
                        retry, bitrate);
                pass = encodePass(input, retry);
            }
        }
        if (pass.packets().isEmpty()) {
            // 「音声 0 frame」は既存の理由 (decode 不能) で返す。
            return pass.packets();
        }
        if (pass.isShort()) {
            throw durationLimit();
        }
        return pass.packets();
    }

    /**
     * 1 回分の decode → encode。入力は pass ごとに開き直す (変換し直しで先頭から decode するため)。
     */
    private Pass encodePass(Path input, int bitrate) {
        AudioTrack track = null;
        IAudioSource source = null;
        OpusEncoder encoder = null;
        try {
            track = loadInputTrack(input);
            encoder = openEncoder(bitrate);
            source = loader.beginPlayback(track, 0L);
            track = null; // 再生側が所有するので close は source が担う
            return encodePcm(source, encoder);
        } finally {
            if (source != null) {
                source.close();
            }
            if (track != null) {
                track.stop();
            }
            if (encoder != null) {
                try {
                    encoder.close();
                } catch (final Throwable ignored) {
                    // close 時の例外は握りつぶす (失敗経路の本体を上書きしない)
                }
            }
        }
    }

    /**
     * mono 48kHz PCM を pull しながら Opus packet へ encode する。出力見積が
     * {@link #maxOutputBytes} に達した時、または encode した尺が {@link #maxDurationNs} に
     * 達した時に止め、<b>入力の続きがどれだけ残っているか</b>を実 decode で測って返す
     * (残りが {@link #SIGNIFICANT_REMAINING_NS} 以下なら曲の終わりと同じ扱い)。
     * interrupt・再生中の decode 失敗は明示失敗。
     */
    private Pass encodePcm(IAudioSource source, OpusEncoder encoder) {
        final List<MkaWriter.OpusPacket> packets = new ArrayList<>();
        final ByteBuffer pcmIn = ByteBuffer.allocateDirect(FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        final ByteBuffer encoded = ByteBuffer.allocateDirect(16_000);
        final byte[] readBuffer = new byte[8_192];
        long estimate = ESTIMATE_FIXED_BYTES;
        long packetNs = 0L;

        while (true) {
            if (Thread.currentThread().isInterrupted()) {
                throw interrupted();
            }
            final PlaybackFault fault = source.playbackFault();
            if (fault != null) {
                throw new ResolveException(fault.reason(), fault.detail());
            }
            final int read = source.read(readBuffer, 0, readBuffer.length);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                sleep();
                continue;
            }
            int offset = 0;
            while (offset < read) {
                final int chunk = Math.min(read - offset, FRAME_BYTES - pcmIn.position());
                pcmIn.put(readBuffer, offset, chunk);
                offset += chunk;
                if (pcmIn.position() == FRAME_BYTES) {
                    // この frame を足すと尺上限を超えるなら止める。残りは測って呼び出し側が判定する。
                    if (packetNs + FRAME_NS > maxDurationNs) {
                        return stopped(source, packets, packetNs, estimate, StopReason.DURATION);
                    }
                    final MkaWriter.OpusPacket packet = encodeFrame(encoder, pcmIn, encoded);
                    final long added = packet.data().length + ESTIMATE_PER_PACKET + clusterCost(packetNs);
                    // 見積を先に確かめる。超えるならこの packet を捨てて止める。
                    if (estimate + added > maxOutputBytes) {
                        return stopped(source, packets, packetNs, estimate, StopReason.OUTPUT_BYTES);
                    }
                    estimate += added;
                    packets.add(packet);
                    packetNs += FRAME_NS;
                    pcmIn.clear();
                }
            }
        }
        // 末尾の 960 sample 未満は 0 詰めして 1 packet にする (末尾 ~20ms を捨てない)。
        if (pcmIn.position() > 0) {
            while (pcmIn.position() < FRAME_BYTES) {
                pcmIn.put((byte) 0);
            }
            final MkaWriter.OpusPacket packet = encodeFrame(encoder, pcmIn, encoded);
            final long added = packet.data().length + ESTIMATE_PER_PACKET + clusterCost(packetNs);
            if (packetNs + FRAME_NS <= maxDurationNs && estimate + added <= maxOutputBytes) {
                packets.add(packet);
            }
        }
        // 終端到達後にも理由が届く経路があるので、閉じる前にもう一度見る。
        final PlaybackFault fault = source.playbackFault();
        if (fault != null) {
            throw new ResolveException(fault.reason(), fault.detail());
        }
        return new Pass(packets, packetNs, estimate, 0L, StopReason.END_OF_INPUT);
    }

    /** 上限で止めた時の結果。残りの尺を実 decode で測ってから返す。 */
    private static Pass stopped(IAudioSource source, List<MkaWriter.OpusPacket> packets,
            long encodedNs, long estimate, StopReason reason) {
        return new Pass(packets, encodedNs, estimate, remainingNs(source), reason);
    }

    /**
     * 止めた後に入力がまだ続いているかを実 decode で測る。
     * {@link #SIGNIFICANT_REMAINING_NS} を超える残りがあるかだけ分かればよいので、1 秒ぶん
     * 読めた時点で止める (残り全部を decode しない)。読み出しが止まったままにならないよう
     * 10 秒で打ち切る。
     */
    private static long remainingNs(IAudioSource source) {
        final long wantBytes = (SIGNIFICANT_REMAINING_NS + FRAME_NS) / FRAME_NS * FRAME_BYTES;
        final byte[] buffer = new byte[8_192];
        final long deadline = System.nanoTime() + 10_000_000_000L;
        long bytes = 0L;
        while (bytes < wantBytes && System.nanoTime() < deadline) {
            final PlaybackFault fault = source.playbackFault();
            if (fault != null) {
                throw new ResolveException(fault.reason(), fault.detail());
            }
            final int read = source.read(buffer, 0, buffer.length);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                sleep();
                continue;
            }
            bytes += read;
        }
        return bytes * FRAME_NS / FRAME_BYTES;
    }

    /**
     * 上限に当たって打ち切った時に試す bitrate。1 回目の実測から「この入力の 1ms あたりの
     * 音声バイト」を出し、宣言尺ぶんを上限内に置ける値へ按分する。コンテナ分は bitrate に
     * 依らない (packet あたり固定) ので別に引く。
     *
     * <p>{@link #MIN_OPUS_BITRATE} を割ってよいのはこの経路だけで、{@link #FINAL_MIN_OPUS_BITRATE}
     * で止める。宣言尺が無い/偽装された入力は残り尺が読めないので半分にして 1 回だけ試す。
     *
     * @param encodedNs     1 回目が encode できた尺
     * @param measuredBytes 1 回目の出力見積 (コンテナ分を含む)
     * @return 下げる先の bitrate。既に {@link #FINAL_MIN_OPUS_BITRATE} なら同じ値を返す
     *         (= 呼び出し側は変換し直さず失敗に倒す)
     */
    static int retryBitrate(int bitrate, long encodedNs, long declaredMs, long measuredBytes,
            long maxBytes) {
        final long encodedMs = encodedNs / 1_000_000L;
        if (encodedMs <= 0L) {
            return FINAL_MIN_OPUS_BITRATE;
        }
        if (declaredMs <= 0L || declaredMs == Long.MAX_VALUE || declaredMs <= encodedMs) {
            return (int) Math.max(FINAL_MIN_OPUS_BITRATE, bitrate / 2L);
        }
        final double audioUsed = Math.max(1.0, measuredBytes - containerBytes(encodedMs));
        final long audioBudget = (maxBytes - containerBytes(declaredMs) - ESTIMATE_FIXED_BYTES)
                * (100L - OUTPUT_HEADROOM_PERCENT) / 100L;
        if (audioBudget <= 0L) {
            return FINAL_MIN_OPUS_BITRATE;
        }
        // 音声バイトは bitrate に比例する。実測の 1ms あたりを、尺ぶんの予算へ按分する。
        final double wanted = bitrate * (double) audioBudget * encodedMs
                / (audioUsed * declaredMs);
        return (int) Math.max(FINAL_MIN_OPUS_BITRATE,
                Math.min(bitrate - 1L, Math.round(wanted)));
    }

    /** その尺の出力に必ず載るコンテナ分 (byte)。{@link #ESTIMATE_PER_PACKET} と 20ms から出す。 */
    private static long containerBytes(long ms) {
        return ms / 20L * ESTIMATE_PER_PACKET;
    }

    /** 尺の上限としての明示失敗。文言は {@code LocalImportDecisions} が too_long へ写す語を含める。 */
    private static ResolveException durationLimit() {
        return new ResolveException(FailureReason.UNKNOWN, "input exceeds the duration limit");
    }

    /** この packet が入ると新しい cluster が始まる時だけ cluster 構造分を足す (writer と同じ 30 秒境界)。 */
    private static long clusterCost(long packetStartNs) {
        return Math.round(packetStartNs / 1_000_000.0) % 30_000L < 20L ? ESTIMATE_PER_CLUSTER : 0L;
    }

    private static MkaWriter.OpusPacket encodeFrame(OpusEncoder encoder, ByteBuffer pcmIn,
            ByteBuffer encoded) {
        pcmIn.flip();
        final ShortBuffer samples = pcmIn.asShortBuffer();
        final int length;
        try {
            length = encoder.encode(samples, FRAME_SAMPLES, encoded);
        } catch (final Throwable thrown) {
            throw new ResolveException(FailureReason.UNKNOWN, "opus encoding failed");
        }
        if (length <= 0) {
            throw new ResolveException(FailureReason.UNKNOWN, "opus encoding failed");
        }
        final byte[] data = new byte[length];
        encoded.get(data);
        return new MkaWriter.OpusPacket(data, FRAME_NS);
    }

    /**
     * 生成した MKA が実際の保存・再生境界で「尺&gt;0・decode できる・seek が効く」を確かめる。
     * ここで失敗する出力は置き換えない (temp のまま捨てる)。
     */
    private TrackInfo verify(Path temp) throws IOException {
        final TrackInfo info = loader.resolveTrustedFile(temp);
        if (info == null || info.durationMs() <= 0L) {
            throw new ResolveException(FailureReason.UNKNOWN,
                    "normalized output has no duration");
        }
        final long size = Files.size(temp);
        if (size <= 0L || size > maxOutputBytes
                || info.durationMs() > maxDurationNs / 1_000_000L) {
            throw new ResolveException(FailureReason.UNKNOWN,
                    "normalized output is out of bounds");
        }
        // 先頭から実 decode (読み出せなければ失敗)。
        readSome(loader.openTrustedFile(temp, 0L), "normalized output does not decode");
        // 途中 seek (尺の半分。ごく短い出力でも seek 経路を通る)。
        readSome(loader.openTrustedFile(temp, info.durationMs() / 2L),
                "normalized output does not seek");
        return info;
    }

    /** pull ソースから実バイトが出ることを確かめる (先頭分が来るまで短く待つ)。 */
    private static void readSome(IAudioSource source, String detail) throws IOException {
        if (source == null) {
            throw new ResolveException(FailureReason.UNKNOWN, detail);
        }
        final byte[] buffer = new byte[8_192];
        final long deadline = System.nanoTime() + 5_000_000_000L;
        try (source) {
            while (System.nanoTime() < deadline) {
                final PlaybackFault fault = source.playbackFault();
                if (fault != null) {
                    throw new ResolveException(fault.reason(), fault.detail());
                }
                final int read = source.read(buffer, 0, buffer.length);
                if (read > 0) {
                    return;
                }
                if (read < 0) {
                    break;
                }
                sleep();
            }
        }
        throw new ResolveException(FailureReason.UNKNOWN, detail);
    }

    /**
     * temp を {@code output} 名へ発行する。POSIX の rename ({@code Files.move} の
     * ATOMIC_MOVE) は既存の dst を黙って置換する (check-then-act の窓が残る) ので使わず、
     * hard link に切り替える — link(2) / Windows の CreateHardLink は「既に存在すれば
     * 失敗」を FS が原子に保証するので、検証済みの内容が第三者の既存 output を
     * 置き換える経路は無い。temp と output は同じ dir なので別 FS 越えは起きない。
     *
     * <p>hard link を持たない FS (FAT 系等) では置換しない発行を保証できず、
     * rename 系へ逃げず失敗に倒す (fail closed)。発行後の temp 名はここで外し、
     * 失敗時は呼出し側の finally も削除を試す。
     */
    void publish(Path temp, Path output) throws IOException {
        beforePublish.run();
        try {
            Files.createLink(output, temp);
        } catch (final FileAlreadyExistsException exists) {
            throw new ResolveException(FailureReason.UNKNOWN, "output file already exists");
        } catch (final UnsupportedOperationException unsupported) {
            throw new ResolveException(FailureReason.UNKNOWN,
                    "no-overwrite publish is not supported here");
        } catch (final IOException io) {
            // hard link 不可の FS (FAT 系・ネットワーク FS 等) でも rename 系へ逃げず閉じる
            throw new ResolveException(FailureReason.UNKNOWN,
                    "could not publish the normalized file");
        }
        Files.delete(temp);
    }

    private static void sleep() {
        try {
            Thread.sleep(2L);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw interrupted();
        }
    }

    private static ResolveException interrupted() {
        return new ResolveException(FailureReason.UNKNOWN, "interrupted");
    }
}
