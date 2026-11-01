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
 * 届いていなければ「曲が途中で終わる盤」を黙って作らない — レートを下限まで下げて 1 回だけ
 * 変換し直し ({@link #encodeWithinLimits})、それでも入らなければ尺の上限として明示失敗する。
 * encode した実尺が {@link #MAX_OUTPUT_DURATION_NS} に達した時も同じ規則で判定する
 * (宣言尺が取れない入力や宣言尺を偽装した入力でも黙って切らない)。
 * 呼び出しスレッドの interrupt を各反復で観測し、中断時は encoder close・
 * temp 削除を保証した上で明示失敗にする。
 *
 * <p><b>尺と出力サイズ</b>: 長い曲を「尺の上限超過」で失敗させない。同梱の
 * {@link OpusEncoder} は bitrate を設定できない（native の create は sampleRate / channels /
 * application / complexity だけを受け取り、libopus がレートから出力 bitrate を自動で決める。
 * 実測は {@link #MEASURED_BPS_48K} の javadoc）ので、宣言尺から 32MiB に収まる
 * <b>encoder のサンプルレート</b>を選び ({@link #rateFor})、下限 {@link #MIN_ENCODER_RATE}
 * まで下げても収まらない尺だけを {@link #MAX_DECLARED_DURATION_MS} で失敗にする。
 * 下限より上で選んだ曲が VBR の上振れで溢れた時は下限まで下げて 1 回だけ変換し直し、
 * 曲が最後まで入ることを優先する。レートを下げる時は 48kHz の PCM を
 * {@link HalfRateDecimator} で 24kHz へ落としてから encode する。
 *
 * <p><b>明示失敗</b>: 入力が読めない/大きすぎる、container が decode 不能、
 * 宣言尺が 2 時間超、再生中の decode 失敗、音声 0 frame、encoder の native 不在、
 * 生成物の検証不合格、発行時に {@code output} が既に存在した競合、発行の I/O 失敗。
 * hard link が使えない FS では、既存が無いことを確かめて move で発行する。
 * 失敗時は {@code output} を作らず一時ファイルも残さない。
 */
final class LocalFileNormalizer {

    private static final Logger LOGGER = LoggerFactory.getLogger(LocalFileNormalizer.class);

    /** 生成物の上限。保存契約 (32MiB) と同じ値。エンコード中に打ち切るので実ファイルは常に内側。 */
    static final long MAX_OUTPUT_BYTES = 32L * 1024 * 1024;
    /**
     * 宣言尺が分かる入力の上限。下限レート ({@link #MIN_ENCODER_RATE}) でも 32MiB に収まる尺で、
     * 2 時間 × ({@link #MEASURED_BPS_24K} + {@link #CONTAINER_BPS}) / 8 = 28,080,000 byte ≈ 26.8MiB
     * となり、上限 {@link #MAX_OUTPUT_BYTES} の 16% 内側に収まる (実測の最悪側 = 音楽的な信号
     * 27.39kbps でも 26.3MiB)。ここを超える尺はレートを下げても入らないので失敗にする。
     * 分からない/偽装された入力は encode 側の実尺・実バイトで判定する。
     */
    static final long MAX_DECLARED_DURATION_MS = 2L * 60L * 60_000L;
    /** 生成物の尺上限 (ns)。宣言尺と同じ 2 時間を encode した実尺側でも強制する。 */
    static final long MAX_OUTPUT_DURATION_NS = MAX_DECLARED_DURATION_MS * 1_000_000L;
    /** 入力ファイルの byte 上限 (設計 §8)。decode は streaming なので memory ではなく disk 問題を止める。 */
    static final long MAX_INPUT_BYTES = 2L * 1024 * 1024 * 1024;

    /** 1 Opus frame = 20ms。入力 (48kHz mono S16) は 960 sample = 1920 byte。 */
    private static final int INPUT_RATE = MusicLoaderImpl.SAMPLE_RATE;
    private static final int FRAME_INPUT_SAMPLES = INPUT_RATE / 50;
    private static final int FRAME_BYTES = FRAME_INPUT_SAMPLES * 2;
    private static final long FRAME_NS = 20_000_000L;
    /**
     * 短い曲の encoder サンプルレート (従来どおり)。このレートでは音質を変えない。
     */
    static final int DEFAULT_ENCODER_RATE = 48_000;
    /**
     * 長い曲で下げる encoder サンプルレートの下限 (24kHz)。
     *
     * <p>libopus はこのレートを super-wideband (12kHz) として扱い、出力は実測 25.7〜27.4kbps。
     * 2 時間の曲が 32MiB に 16% の余裕を残して収まるので、これ以上は下げない — 16kHz 以下の
     * レートは帯域が 8kHz 以下 (AM ラジオ程度) になり、音楽として聞けたものではなくなる。
     * その劣化は必要ない (24kHz で上限尺が収まる) ので、下限をここに置く。
     */
    static final int MIN_ENCODER_RATE = 24_000;
    /**
     * 同梱 native の create が受け取る唯一の調整値 (libopus の complexity 0〜10)。
     * 出力サイズへの効果はほぼ無い (実測: 0/3/5 で 51.20kbps、10 で 50.36kbps) ので、
     * 短い曲の音質を落とさない最大値のまま固定する。
     */
    static final int OPUS_COMPLEXITY = 10;
    /**
     * 実測した encoder の出力 (bps)。bitrate を指定できないので、レートごとの実測値を
     * 尺からの選択 ({@link #rateFor}) に使う。
     *
     * <p>48kHz mono・20ms frame・60 秒・complexity 10 の実測 (2026-11-01):
     * 白色雑音 50.36kbps / 正弦波を重ねた音楽的な信号 51.35kbps。libopus の自動 bitrate
     * (60×Fs/frame + Fs = 51.0kbps) とほぼ一致するので、その少し上を採る。
     * 24kHz は同じ信号で 25.68 / 27.39kbps。測り方と全レートの表、2 時間の見積は
     * {@code _work/mdm-310-execution/opus-size-probe-20261101/README.md}。
     */
    static final int MEASURED_BPS_48K = 52_000;
    static final int MEASURED_BPS_24K = 28_000;
    /**
     * 「まだ曲が続いている」と数える残り尺。これ以下の切れは曲の終わりと区別できない
     * (この pin 版の再生経路は末尾 1 frame = 20ms を返さない) ので、成功に含める。
     */
    static final long SIGNIFICANT_REMAINING_NS = 1_000_000_000L;
    /** 選んだレートの実測が目標をわずかに上回る分の余裕。出力見積は実 packet で別に検査する。 */
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

    /** encode 中の積み上げ (書けた packet・出力見積・尺)。上限の判定は {@link #addFrame} が持つ。 */
    private static final class Accumulator {

        final List<MkaWriter.OpusPacket> packets = new ArrayList<>();
        long estimate = ESTIMATE_FIXED_BYTES;
        long packetNs;
    }

    /** 検証済み temp を output 名へ付ける口 (製品は hard link)。 */
    @FunctionalInterface
    interface Linker {
        void link(Path output, Path temp) throws IOException;
    }

    /** 発行後の temp を消す口 (製品は {@link Files#delete})。 */
    @FunctionalInterface
    interface Deleter {
        void delete(Path temp) throws IOException;
    }

    private final MusicLoaderImpl loader;
    private final long maxOutputBytes;
    private final long maxDurationNs;
    private final IntFunction<OpusEncoder> encoderFactory;
    /** 発行直前に走る口 (試験が check→発行の窓への競合を注入する。製品は no-op)。 */
    private final Runnable beforePublish;
    private final Linker linker;
    private final Deleter deleter;

    LocalFileNormalizer(MusicLoaderImpl loader) {
        this(loader, MAX_OUTPUT_BYTES, MAX_OUTPUT_DURATION_NS, LocalFileNormalizer::newEncoder,
                () -> {});
    }

    LocalFileNormalizer(MusicLoaderImpl loader, long maxOutputBytes, long maxDurationNs,
            IntFunction<OpusEncoder> encoderFactory, Runnable beforePublish) {
        this(loader, maxOutputBytes, maxDurationNs, encoderFactory, beforePublish, Files::createLink);
    }

    /**
     * 試験が出力上限・尺上限・encoder 生成・発行直前・link を差し替える口 (製品経路は上の ctor)。
     */
    LocalFileNormalizer(MusicLoaderImpl loader, long maxOutputBytes, long maxDurationNs,
            IntFunction<OpusEncoder> encoderFactory, Runnable beforePublish, Linker linker) {
        this(loader, maxOutputBytes, maxDurationNs, encoderFactory, beforePublish, linker,
                Files::delete);
    }

    /** temp の削除も差し替える口 (発行成功後の削除失敗を試験する)。 */
    LocalFileNormalizer(MusicLoaderImpl loader, long maxOutputBytes, long maxDurationNs,
            IntFunction<OpusEncoder> encoderFactory, Runnable beforePublish, Linker linker,
            Deleter deleter) {
        this.loader = loader;
        this.maxOutputBytes = maxOutputBytes;
        this.maxDurationNs = maxDurationNs;
        this.encoderFactory = encoderFactory;
        this.beforePublish = beforePublish;
        this.linker = linker;
        this.deleter = deleter;
    }

    private static OpusEncoder newEncoder(int rate) {
        return new OpusEncoder(rate, MusicLoaderImpl.CHANNELS, OPUS_COMPLEXITY);
    }

    /**
     * 宣言尺と出力上限から、出力が 32MiB に収まる encoder のサンプルレートを選ぶ。
     *
     * <p>bitrate は encoder から指定できないので、レートそのものを下げる (libopus はレートに
     * 比例した bitrate を自動で選ぶ。{@link #MEASURED_BPS_48K})。尺が短い入力は
     * {@link #DEFAULT_ENCODER_RATE} のまま (品質を落とさない)。収まらない長さの時だけ
     * {@link #MIN_ENCODER_RATE} へ落とす。
     *
     * <p>尺が分からない入力 ({@code 0} / {@link Long#MAX_VALUE}) は既定レートで始める。
     * 上限尺を仮定して低い側に置くと、尺を持たない短い入力 (一部の ADTS 等) まで理由なく
     * 24kHz になってしまう。長ければ encode 中の実 packet の見積で溢れ、その時に
     * {@link #MIN_ENCODER_RATE} へ落として変換し直す ({@link #encodeWithinLimits})。
     */
    static int rateFor(long durationMs, long maxBytes) {
        if (durationMs <= 0L || durationMs == Long.MAX_VALUE) {
            return DEFAULT_ENCODER_RATE;
        }
        final long budget = maxBytes - maxBytes * OUTPUT_HEADROOM_PERCENT / 100L
                - ESTIMATE_FIXED_BYTES;
        if (budget <= 0L) {
            return MIN_ENCODER_RATE;
        }
        // この尺で上限に収まる総 bps。コンテナ分はレートに依らないので別に引く。
        final long allowed = budget * 8_000L / durationMs - CONTAINER_BPS;
        return allowed >= MEASURED_BPS_48K ? DEFAULT_ENCODER_RATE : MIN_ENCODER_RATE;
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
            // 入力の解決 (内容 probe → 必要なら直 decode) は 1 回だけ。宣言尺を取り、
            // pass ごとにはソースだけを開き直す。
            final PcmInput resolved = resolveInput(input);
            final long declaredMs = resolved.durationMs();
            if (declaredMs > 0L && declaredMs != Long.MAX_VALUE
                    && declaredMs > MAX_DECLARED_DURATION_MS) {
                throw durationLimit();
            }
            // 宣言尺から encoder のレートを決め、収まらなければ落として 1 回だけ変換し直す。
            final List<MkaWriter.OpusPacket> packets = encodeWithinLimits(resolved, declaredMs);
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
            discardTemp(temp);
        }
    }

    /**
     * temp の後片付け。消せなくても (Windows で別プロセスが開いている等) 取込の成否は変えない —
     * 発行済みなら output は検証済みの内容を指しており、失敗中なら元の失敗理由を隠さない。
     */
    private void discardTemp(Path temp) {
        if (!Files.exists(temp)) {
            return;
        }
        try {
            deleter.delete(temp);
        } catch (final IOException io) {
            LOGGER.debug("Could not remove the normalized temp file {}", temp, io);
        }
    }

    /** 入力の宣言尺 (ms)。分からない入力は {@code 0} / {@link Long#MAX_VALUE} のまま返す。 */
    private PcmInput resolveInput(Path input) {
        try {
            return playerInput(input);
        } catch (final ResolveException unsupported) {
            final PcmInput direct = directInput(input);
            if (direct != null) {
                LOGGER.info("Decoding {} directly ({})", input.getFileName(), direct.origin());
                return direct;
            }
            throw unsupported;
        }
    }

    /**
     * 通常経路: LavaPlayer の入力 registry で内容 probe し、track の宣言尺を取る。
     * 再生 stream は pass ごとに {@link #open()} で開き直す (変換し直しでも先頭から decode する)。
     */
    private PcmInput playerInput(Path input) {
        final AudioTrack track = loadInputTrack(input);
        final long durationMs;
        try {
            durationMs = track.getInfo().length;
        } finally {
            track.stop();
        }
        return new PcmInput() {
            @Override
            public long durationMs() {
                return durationMs;
            }

            @Override
            public Origin origin() {
                return Origin.PLAYER;
            }

            @Override
            public IAudioSource open() {
                final AudioTrack opened = loadInputTrack(input);
                boolean handedOver = false;
                try {
                    final IAudioSource source = loader.beginPlayback(opened, 0L);
                    handedOver = true;
                    return source;
                } finally {
                    if (!handedOver) {
                        opened.stop();
                    }
                }
            }
        };
    }

    /**
     * 通常の probe が扱えない入力の直 decode。対象外 (magic が違う・必要 track が無い) なら
     * {@code null} を返し、呼び出し側は通常経路の失敗理由をそのまま使う。
     */
    private PcmInput directInput(Path input) {
        return null;
    }

    /**
     * encoder を立てる。native 不在では理由つき失敗 (「読めないファイル」とは別の話)。
     * ここへ来る時点で input は内容 probe 済みだが、再生 stream はまだ開いていない。
     */
    private OpusEncoder openEncoder(int rate) {
        try {
            return encoderFactory.apply(rate);
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
     * 「曲が途中で終わる盤」を黙って作らない: レートを {@link #MIN_ENCODER_RATE} まで
     * 落として 1 回だけ変換し直し、それでも入らなければ尺の上限として明示失敗する。
     *
     * <p>尺の上限 ({@link #maxDurationNs}) に当たった時は下げても伸びないので変換し直さない。
     *
     * @param declaredMs 入力の宣言尺 (分からなければ 0 / {@link Long#MAX_VALUE})
     */
    private List<MkaWriter.OpusPacket> encodeWithinLimits(PcmInput input, long declaredMs) {
        final int rate = rateFor(declaredMs, maxOutputBytes);
        Pass pass = encodePass(input, rate);
        if (pass.stop() == StopReason.OUTPUT_BYTES && pass.isShort()
                && rate > MIN_ENCODER_RATE) {
            LOGGER.info("Re-encoding at {}Hz (was {}Hz) so the whole track fits",
                    MIN_ENCODER_RATE, rate);
            pass = encodePass(input, MIN_ENCODER_RATE);
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
     * 1 回分の decode → encode。入力を {@code rate} で encode し直せる形で受け取る
     * (変換し直しでも同じ入力の先頭から開き直す)。
     */
    Pass encodePass(Path input, int rate) {
        return encodePass(resolveInput(input), rate);
    }

    /**
     * 1 回分の decode → encode。入力のソースは pass ごとに開き直す。
     */
    Pass encodePass(PcmInput input, int rate) {
        IAudioSource source = null;
        OpusEncoder encoder = null;
        try {
            source = input.open();
            encoder = openEncoder(rate);
            return encodePcm(source, encoder, rate);
        } finally {
            if (source != null) {
                source.close();
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
     *
     * @param rate encoder のサンプルレート ({@link #DEFAULT_ENCODER_RATE} か
     *             {@link #MIN_ENCODER_RATE})。48kHz 未満では入力 block を
     *             {@link HalfRateDecimator} で間引いてから渡す
     */
    private Pass encodePcm(IAudioSource source, OpusEncoder encoder, int rate) {
        final Accumulator acc = new Accumulator();
        final ByteBuffer pcmIn = ByteBuffer.allocateDirect(FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        final ByteBuffer encoded = ByteBuffer.allocateDirect(16_000);
        final byte[] readBuffer = new byte[8_192];
        final int frameSamples = rate / 50;
        // 間引き後は 1 block (20ms) の出力が 1 frame に満たない (フィルタの先読み分) ので、
        // 揃った frame だけをここへ書いてから encode する。
        final HalfRateDecimator decimator = rate == INPUT_RATE ? null : new HalfRateDecimator();
        final ByteBuffer framePcm = decimator == null
                ? null
                : ByteBuffer.allocateDirect(frameSamples * 2).order(ByteOrder.LITTLE_ENDIAN);
        // 1 block でも流したか。24kHz は末尾の frame が先読み分だけ揃わないので、
        // 入力があった時だけ最後の 1 frame を 0 詰めで出す (無音の 20ms 盤を作らない)。
        boolean fed = false;

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
                    fed = true;
                    final StopReason stop = encodeBlock(acc, encoder, decimator, framePcm,
                            frameSamples, pcmIn, encoded);
                    if (stop != null) {
                        return stopped(source, acc, stop);
                    }
                    pcmIn.clear();
                }
            }
        }
        // 末尾の 1 block (20ms) 未満は 0 詰めして 1 block として流す (末尾 ~20ms を捨てない)。
        if (pcmIn.position() > 0) {
            fed = true;
            while (pcmIn.position() < FRAME_BYTES) {
                pcmIn.put((byte) 0);
            }
            // ここで上限に当たったら、この block の frame は既に間引き器から取り出して捨てて
            // いる。理由を見ずに続けると、後の小さい flush packet だけが通って 20ms が黙って
            // 抜け、END_OF_INPUT として返る (曲の途中なら切れたことに気づけない)。止めた時は
            // 他の block と同じく残りを測って返す。
            final StopReason stop = encodeBlock(acc, encoder, decimator, framePcm, frameSamples,
                    pcmIn, encoded);
            if (stop != null) {
                return stopped(source, acc, stop);
            }
        }
        // 間引きは位相の都合で最後の frame が先読み分 (31 sample) だけ揃わない。
        // 間引き器が 0 を足して最後の出力まで計算するので、実音が段差で切れない。
        if (decimator != null && fed) {
            framePcm.clear();
            decimator.flushFrame(framePcm);
            framePcm.flip();
            // 最後の 1 frame も他の block と同じく上限を見る。ここで溢れたのに理由を捨てると、
            // 末尾 20ms が黙って抜けたまま END_OF_INPUT として返る。
            final StopReason stop = addFrame(acc, encoder, framePcm, frameSamples, encoded);
            if (stop != null) {
                return stopped(source, acc, stop);
            }
        }
        // 終端到達後にも理由が届く経路があるので、閉じる前にもう一度見る。
        final PlaybackFault fault = source.playbackFault();
        if (fault != null) {
            throw new ResolveException(fault.reason(), fault.detail());
        }
        return new Pass(acc.packets, acc.packetNs, acc.estimate, 0L, StopReason.END_OF_INPUT);
    }

    /**
     * 20ms 分 (48kHz で {@link #FRAME_BYTES} byte) たまった入力を encoder へ渡す。48kHz は
     * そのまま 1 frame、24kHz は {@link HalfRateDecimator} で間引いてから揃った frame だけを渡す。
     *
     * @return 上限に当たった理由 (続けられるなら {@code null})
     */
    private StopReason encodeBlock(Accumulator acc, OpusEncoder encoder,
            HalfRateDecimator decimator, ByteBuffer framePcm, int frameSamples, ByteBuffer pcmIn,
            ByteBuffer encoded) {
        pcmIn.flip();
        if (decimator == null) {
            return addFrame(acc, encoder, pcmIn, frameSamples, encoded);
        }
        decimator.push(pcmIn.asShortBuffer());
        framePcm.clear();
        while (decimator.takeFrame(framePcm)) {
            framePcm.flip();
            final StopReason stop = addFrame(acc, encoder, framePcm, frameSamples, encoded);
            if (stop != null) {
                return stop;
            }
            framePcm.clear();
        }
        return null;
    }

    /**
     * 1 frame を encode して積む。尺の上限は encode の前に、出力の上限は encode の後に見る
     * (上限を超える packet は積まずに止める)。
     *
     * @return 上限に当たった理由 (積んだなら {@code null})
     */
    private StopReason addFrame(Accumulator acc, OpusEncoder encoder, ByteBuffer frame,
            int frameSamples, ByteBuffer encoded) {
        if (acc.packetNs + FRAME_NS > maxDurationNs) {
            return StopReason.DURATION;
        }
        final MkaWriter.OpusPacket packet = encodeFrame(encoder, frame, frameSamples, encoded);
        final long added = packet.data().length + ESTIMATE_PER_PACKET + clusterCost(acc.packetNs);
        if (acc.estimate + added > maxOutputBytes) {
            return StopReason.OUTPUT_BYTES;
        }
        acc.estimate += added;
        acc.packets.add(packet);
        acc.packetNs += FRAME_NS;
        return null;
    }

    /** 上限で止めた時の結果。残りの尺を実 decode で測ってから返す。 */
    private static Pass stopped(IAudioSource source, Accumulator acc, StopReason reason) {
        return new Pass(acc.packets, acc.packetNs, acc.estimate, remainingNs(source), reason);
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

    /** 尺の上限としての明示失敗。文言は {@code LocalImportDecisions} が too_long へ写す語を含める。 */
    private static ResolveException durationLimit() {
        return new ResolveException(FailureReason.UNKNOWN, "input exceeds the duration limit");
    }

    /** この packet が入ると新しい cluster が始まる時だけ cluster 構造分を足す (writer と同じ 30 秒境界)。 */
    private static long clusterCost(long packetStartNs) {
        return Math.round(packetStartNs / 1_000_000.0) % 30_000L < 20L ? ESTIMATE_PER_CLUSTER : 0L;
    }

    /**
     * {@code frame} (flip 済み・{@code frameSamples} 個の S16LE) を 1 packet にする。
     *
     * <p>{@code frameSamples} を呼び出し側から受け取るのは、24kHz では 1 frame が 480 sample
     * になるため (旧実装の 960 固定は 48kHz 専用だった)。
     */
    private static MkaWriter.OpusPacket encodeFrame(OpusEncoder encoder, ByteBuffer frame,
            int frameSamples, ByteBuffer encoded) {
        final ShortBuffer samples = frame.asShortBuffer();
        final int length;
        try {
            length = encoder.encode(samples, frameSamples, encoded);
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
     * temp を {@code output} 名へ発行する。基本は hard link — link(2) / Windows の
     * CreateHardLink は「既に存在すれば失敗」を FS が原子に保証するので、検証済みの内容が
     * 第三者の既存 output を置き換える経路は無い。temp と output は同じ dir なので
     * 別 FS 越えは起きない。
     *
     * <p>hard link を持たない FS (FAT 系・ネットワーク FS 等) では、出力先が無いことを
     * 確かめてから temp を move する (REPLACE_EXISTING も ATOMIC_MOVE も付けない。ATOMIC_MOVE は
     * 既存の target を置換しうる)。check→move の窓は
     * 残るが、hard link が使えない環境でも「変換できたのに保存できない」で終わらせない。
     * 既存 output は従来どおり {@code output file already exists} として拒否する。
     *
     * <p>発行後の temp 名はここで外し、失敗時は呼出し側の finally も削除を試す。
     * 削除の失敗は発行の失敗ではない — output は既に検証済みの内容を指しているので、
     * ここで投げると「保存できた取込」を失敗と表示してしまう。
     */
    void publish(Path temp, Path output) throws IOException {
        beforePublish.run();
        try {
            linker.link(output, temp);
        } catch (final FileAlreadyExistsException exists) {
            throw new ResolveException(FailureReason.UNKNOWN, "output file already exists");
        } catch (final UnsupportedOperationException | IOException unavailable) {
            moveWithoutReplacing(temp, output);
            return;
        }
        try {
            deleter.delete(temp);
        } catch (final IOException ignored) {
            // Windows で別プロセスが開いている等。finally の再削除と後片付けに任せる。
            LOGGER.debug("Could not remove the normalized temp file {}", temp, ignored);
        }
    }

    /** hard link が使えない FS 向けの代替発行。既存 output は置換しない。 */
    private static void moveWithoutReplacing(Path temp, Path output) throws IOException {
        if (Files.exists(output)) {
            throw new ResolveException(FailureReason.UNKNOWN, "output file already exists");
        }
        // ATOMIC_MOVE は付けない。付けると既存の target を置換するかが実装依存になる
        // (Files.move の javadoc)。temp と output は同じディレクトリなので、通常の move も
        // 同じ FS 内の rename で、既存の target があれば FileAlreadyExistsException になる。
        try {
            Files.move(temp, output);
        } catch (final FileAlreadyExistsException exists) {
            throw new ResolveException(FailureReason.UNKNOWN, "output file already exists");
        } catch (final IOException io) {
            throw new ResolveException(FailureReason.UNKNOWN,
                    "could not publish the normalized file");
        }
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
