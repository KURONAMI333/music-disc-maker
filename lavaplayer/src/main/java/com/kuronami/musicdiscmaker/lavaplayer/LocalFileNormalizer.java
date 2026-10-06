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
import java.util.function.Supplier;

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
 * 超えないよう監視し、超える直前で encode を打ち切る (長い入力は先頭から
 * 32MiB 分の音声として成功する)。同じく encode した実尺が
 * {@link #MAX_OUTPUT_DURATION_NS} に達する直前でも打ち切る — 宣言尺が取れない
 * 入力や宣言尺を偽装した入力でも、出力は 60 分を超えない (硬い上限)。
 * 呼び出しスレッドの interrupt を各反復で観測し、中断時は encoder close・
 * temp 削除を保証した上で明示失敗にする。
 *
 * <p><b>明示失敗</b>: 入力が読めない/大きすぎる、container が decode 不能、
 * 宣言尺が 60 分超、再生中の decode 失敗、音声 0 frame、encoder の native 不在、
 * 生成物の検証不合格、置換しない発行 (hard link) が使えない FS、発行時に
 * {@code output} が既に存在した競合。失敗時は {@code output} を作らず
 * 一時ファイルも残さない。
 */
final class LocalFileNormalizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalFileNormalizer.class);

    /** 生成物の上限。保存契約 (32MiB) と同じ値。エンコード中に打ち切るので実ファイルは常に内側。 */
    static final long MAX_OUTPUT_BYTES = 32L * 1024 * 1024;
    /** 宣言尺が分かる入力の上限 (設計 §8)。分からない/偽装された入力は encode 側の実尺上限が効く。 */
    static final long MAX_DECLARED_DURATION_MS = 60L * 60_000L;
    /** 生成物の尺上限 (ns)。宣言尺と同じ 60 分を encode した実尺側でも強制する。 */
    static final long MAX_OUTPUT_DURATION_NS = MAX_DECLARED_DURATION_MS * 1_000_000L;
    /** 入力ファイルの byte 上限 (設計 §8)。decode は streaming なので memory ではなく disk 問題を止める。 */
    static final long MAX_INPUT_BYTES = 2L * 1024 * 1024 * 1024;

    /** 1 Opus frame = 20ms @48kHz mono (960 samples = 1920 byte の S16LE)。 */
    private static final int FRAME_SAMPLES = 960;
    private static final int FRAME_BYTES = FRAME_SAMPLES * 2;
    private static final long FRAME_NS = 20_000_000L;
    /** encoder の bitrate。品質値は bitrate に不感なので固定 (設計 §6 実測)。 */
    private static final int OPUS_BITRATE = 64_000;

    /** 出力見積の固定分 (EBML header + Segment/Info/Tracks)。実測 ~250 byte に余裕を持たせる。 */
    private static final long ESTIMATE_FIXED_BYTES = 512L;
    /** packet 1 個あたりの SimpleBlock 構造分 (block header 4 + element 外枠 ~4)。実測 ~7-8 byte。 */
    private static final long ESTIMATE_PER_PACKET = 12L;
    /** cluster 1 個あたりの構造分 (Cluster element + Timestamp)。30 秒ごとに 1 個。 */
    private static final long ESTIMATE_PER_CLUSTER = 16L;

    private final MusicLoaderImpl loader;
    private final long maxOutputBytes;
    private final long maxDurationNs;
    private final Supplier<OpusEncoder> encoderFactory;
    /** 発行直前に走る口 (試験が check→発行の窓への競合を注入する。製品は no-op)。 */
    private final Runnable beforePublish;

    LocalFileNormalizer(MusicLoaderImpl loader) {
        this(loader, MAX_OUTPUT_BYTES, MAX_OUTPUT_DURATION_NS, LocalFileNormalizer::newEncoder,
                () -> {});
    }

    /** 試験が出力上限・尺上限・encoder 生成・発行直前を差し替える口 (製品経路は上の ctor)。 */
    LocalFileNormalizer(MusicLoaderImpl loader, long maxOutputBytes, long maxDurationNs,
            Supplier<OpusEncoder> encoderFactory, Runnable beforePublish) {
        this.loader = loader;
        this.maxOutputBytes = maxOutputBytes;
        this.maxDurationNs = maxDurationNs;
        this.encoderFactory = encoderFactory;
        this.beforePublish = beforePublish;
    }

    private static OpusEncoder newEncoder() {
        return new OpusEncoder(MusicLoaderImpl.SAMPLE_RATE, MusicLoaderImpl.CHANNELS, OPUS_BITRATE);
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

        OpusEncoder encoder = null;
        IAudioSource source = null;
        AudioTrack track = null;
        try {
            // native が無い環境ではここで明示失敗 (試行する前に input の stream を開かない)。
            encoder = openEncoder();
            track = loadInputTrack(input);
            final long declaredMs = track.getInfo().length;
            if (declaredMs > 0L && declaredMs != Long.MAX_VALUE
                    && declaredMs > MAX_DECLARED_DURATION_MS) {
                throw new ResolveException(FailureReason.UNKNOWN,
                        "input exceeds the duration limit");
            }
            source = loader.beginPlayback(track, 0L);
            track = null; // 再生側が所有するので close は source が担う
            final List<MkaWriter.OpusPacket> packets = encodePcm(source, encoder);
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
            Files.deleteIfExists(temp);
        }
    }

    /** encoder を立てる。native 不在では理由つき失敗 (「読めないファイル」とは別の話)。 */
    private OpusEncoder openEncoder() {
        try {
            return encoderFactory.get();
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
     * mono 48kHz PCM を pull しながら Opus packet へ encode する。出力見積が
     * {@link #maxOutputBytes} に達したら、または encode した尺が {@link #maxDurationNs} に
     * 達したら打ち切る (= 先頭から収まる分だけを持つ有界な成功)。尺側の打ち切りは
     * 宣言尺を持たない/偽装した入力を 60 分以内へ収める硬い上限になる。
     * interrupt・再生中の decode 失敗は明示失敗。
     */
    private List<MkaWriter.OpusPacket> encodePcm(IAudioSource source, OpusEncoder encoder) {
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
                    // この frame を足すと尺上限を超えるなら、ここまでを有界な成功にする。
                    if (packetNs + FRAME_NS > maxDurationNs) {
                        return packets;
                    }
                    final MkaWriter.OpusPacket packet = encodeFrame(encoder, pcmIn, encoded);
                    final long added = packet.data().length + ESTIMATE_PER_PACKET + clusterCost(packetNs);
                    // 見積を先に確かめる。超えるならこの packet を捨てて打ち切る。
                    if (estimate + added > maxOutputBytes) {
                        return packets;
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
        return packets;
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
