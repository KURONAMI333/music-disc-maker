package com.kuronami.musicdiscmaker.lavaplayer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import com.sedmelluq.discord.lavaplayer.container.flac.FlacStreamInfo;
import com.sedmelluq.discord.lavaplayer.container.flac.frame.FlacFrameReader;
import com.sedmelluq.discord.lavaplayer.container.matroska.MatroskaStreamingFile;
import com.sedmelluq.discord.lavaplayer.container.matroska.MatroskaTrackConsumer;
import com.sedmelluq.discord.lavaplayer.container.matroska.format.MatroskaFileTrack;
import com.sedmelluq.discord.lavaplayer.source.local.LocalSeekableInputStream;
import com.sedmelluq.discord.lavaplayer.tools.io.BitStreamReader;

/**
 * MKV に入った FLAC 音声を自前で decode する。通常の入力 probe は MKV の音声を
 * Opus / Vorbis / AAC だけ受理するので、{@code A_FLAC} の track はここで読む。
 *
 * <p>LavaPlayer の public な {@link MatroskaStreamingFile} が block を取り出し
 * ({@code provideFrames})、{@link FlacFrameReader} が FLAC frame を PCM にする。
 * 2 つは pipe でつなぎ、全曲をメモリに溜めない (pipe 64KB + frame 1 つ分)。
 * block を取り出すスレッドは decode の間だけ立てる補助スレッドで、
 * {@link #decode} が戻る時 (EOF・失敗・中断のどれでも) 止めて file を閉じる。
 *
 * <p>映像 track の block は {@code provideFrames} が選んだ track の consumer にしか渡さないので、
 * 映像つき MKV でも FLAC だけが流れる。
 */
final class MatroskaFlacPcmDecoder implements PcmDecoder {

    private static final String CODEC_FLAC = "A_FLAC";
    private static final int PIPE_BYTES = 64 * 1024;
    private static final int FLAC_MAGIC_BYTES = 4;
    private static final int METADATA_HEADER_BYTES = 4;

    /** 宣言値。尺が分からなければ 0。 */
    record Info(int trackIndex, FlacStreamInfo stream, long durationMs) {
    }

    private final Path file;
    private final Info info;

    MatroskaFlacPcmDecoder(Path file, Info info) {
        this.file = file;
        this.info = info;
    }

    /**
     * Tracks を読んで {@code A_FLAC} の音声 track を探す。無ければ {@code null}
     * (呼び出し側は通常経路の失敗理由をそのまま使う)。構造が壊れていれば {@link IOException}。
     */
    static Info probe(Path file) throws IOException {
        try (LocalSeekableInputStream stream = new LocalSeekableInputStream(file.toFile())) {
            final MatroskaStreamingFile matroska = new MatroskaStreamingFile(stream);
            matroska.readFile();
            final MatroskaFileTrack track = flacTrack(matroska);
            if (track == null) {
                return null;
            }
            final FlacStreamInfo streamInfo = parseStreamInfo(track.codecPrivate);
            return new Info(track.index, streamInfo, durationMs(streamInfo, matroska));
        } catch (final RuntimeException broken) {
            throw new IOException("could not read the matroska headers", broken);
        }
    }

    private static MatroskaFileTrack flacTrack(MatroskaStreamingFile matroska) {
        final MatroskaFileTrack[] tracks = matroska.getTrackList();
        if (tracks == null) {
            return null;
        }
        for (final MatroskaFileTrack track : tracks) {
            if (track.type == MatroskaFileTrack.Type.AUDIO && CODEC_FLAC.equals(track.codecId)) {
                return track;
            }
        }
        return null;
    }

    /**
     * {@code codecPrivate} から STREAMINFO を取る。{@code "fLaC"} + metadata block header (4 byte)
     * + 34 byte の形と、34 byte 直置きの形の両方を受ける。
     */
    static FlacStreamInfo parseStreamInfo(byte[] codecPrivate) throws IOException {
        if (codecPrivate == null || codecPrivate.length < FlacStreamInfo.LENGTH) {
            throw new IOException("missing FLAC stream info");
        }
        int offset = 0;
        final boolean hasMagic = codecPrivate.length >= FLAC_MAGIC_BYTES
                && codecPrivate[0] == 'f' && codecPrivate[1] == 'L'
                && codecPrivate[2] == 'a' && codecPrivate[3] == 'C';
        if (hasMagic) {
            offset = FLAC_MAGIC_BYTES;
            // 直後は metadata block header (type 0 = STREAMINFO、長さ 24bit)。
            if (codecPrivate.length >= FLAC_MAGIC_BYTES + METADATA_HEADER_BYTES + FlacStreamInfo.LENGTH) {
                offset += METADATA_HEADER_BYTES;
            }
        }
        if (codecPrivate.length - offset < FlacStreamInfo.LENGTH) {
            throw new IOException("truncated FLAC stream info");
        }
        final byte[] bytes = new byte[FlacStreamInfo.LENGTH];
        System.arraycopy(codecPrivate, offset, bytes, 0, FlacStreamInfo.LENGTH);
        final FlacStreamInfo parsed = new FlacStreamInfo(bytes, false);
        if (parsed.sampleRate <= 0 || parsed.channelCount <= 0 || parsed.maximumBlockSize <= 0) {
            throw new IOException("invalid FLAC stream info");
        }
        return parsed;
    }

    /** STREAMINFO の総 sample 数を優先し、0 (不明) の時だけ matroska の Duration (ms) に落ちる。 */
    private static long durationMs(FlacStreamInfo stream, MatroskaStreamingFile matroska) {
        if (stream.sampleCount > 0L) {
            return stream.sampleCount * 1000L / stream.sampleRate;
        }
        final double duration = matroska.getDuration();
        return duration > 0d && !Double.isInfinite(duration) ? (long) duration : 0L;
    }

    @Override
    public int sampleRate() {
        return info.stream().sampleRate;
    }

    @Override
    public int channels() {
        return info.stream().channelCount;
    }

    @Override
    public void decode(PcmFrameSink sink) throws IOException, InterruptedException {
        final FlacStreamInfo stream = info.stream();
        final int channels = stream.channelCount;
        final PipedInputStream pipeIn = new PipedInputStream(PIPE_BYTES);
        final PipedOutputStream pipeOut = new PipedOutputStream(pipeIn);
        final LocalSeekableInputStream seekable = new LocalSeekableInputStream(file.toFile());
        final AtomicReference<Throwable> feederFailure = new AtomicReference<>();
        Thread feeder = null;
        try {
            final MatroskaStreamingFile matroska = new MatroskaStreamingFile(seekable);
            matroska.readFile();
            final MatroskaFileTrack track = trackAt(matroska, info.trackIndex());
            if (track == null) {
                throw new IOException("the FLAC track is gone");
            }
            feeder = new Thread(() -> feed(matroska, track, pipeOut, feederFailure),
                    "mdm-local-matroska-" + file.getFileName());
            feeder.setDaemon(true);
            feeder.start();

            final BufferedInputStream frames = new BufferedInputStream(pipeIn, PIPE_BYTES);
            final BitStreamReader bits = new BitStreamReader(frames);
            final int[][] raw = new int[channels][stream.maximumBlockSize];
            final short[][] pcm = new short[channels][stream.maximumBlockSize];
            final int[] scratch = new int[FlacFrameReader.TEMPORARY_BUFFER_SIZE];
            short[] interleaved = new short[stream.maximumBlockSize * channels];
            while (true) {
                if (Thread.interrupted()) {
                    throw new InterruptedException("interrupted while decoding");
                }
                final int count = FlacFrameReader.readFlacFrame(frames, bits, stream, raw, pcm, scratch);
                if (count <= 0) {
                    break;
                }
                if (count * channels > interleaved.length) {
                    interleaved = new short[count * channels];
                }
                for (int channel = 0; channel < channels; channel++) {
                    final short[] source = pcm[channel];
                    for (int frame = 0, index = channel; frame < count; frame++, index += channels) {
                        interleaved[index] = source[frame];
                    }
                }
                sink.accept(interleaved, count);
            }
            final Throwable failure = feederFailure.get();
            if (failure != null) {
                throw new IOException("could not read the FLAC audio", failure);
            }
        } catch (final InterruptedIOException interrupted) {
            final InterruptedException converted = new InterruptedException(interrupted.getMessage());
            converted.initCause(interrupted);
            throw converted;
        } finally {
            closeQuietly(pipeIn);
            closeQuietly(pipeOut);
            if (feeder != null) {
                feeder.interrupt();
                try {
                    feeder.join(1_000L);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            closeQuietly(seekable);
        }
    }

    private static MatroskaFileTrack trackAt(MatroskaStreamingFile matroska, int index) {
        final MatroskaFileTrack[] tracks = matroska.getTrackList();
        if (tracks != null) {
            for (final MatroskaFileTrack track : tracks) {
                if (track.index == index && CODEC_FLAC.equals(track.codecId)) {
                    return track;
                }
            }
        }
        return null;
    }

    /** 補助スレッド: 選んだ track の block を pipe へ書く。終わったら pipe を閉じて EOF にする。 */
    private static void feed(MatroskaStreamingFile matroska, MatroskaFileTrack track,
            PipedOutputStream out, AtomicReference<Throwable> failure) {
        try {
            matroska.provideFrames(new MatroskaTrackConsumer() {
                @Override
                public MatroskaFileTrack getTrack() {
                    return track;
                }

                @Override
                public void initialise() {
                }

                @Override
                public void seekPerformed(long requestedTimecode, long providedTimecode) {
                }

                @Override
                public void flush() {
                }

                @Override
                public void consume(ByteBuffer data) throws InterruptedException {
                    final byte[] bytes = new byte[data.remaining()];
                    data.get(bytes);
                    try {
                        out.write(bytes, 0, bytes.length);
                    } catch (final IOException closed) {
                        // 読み側が閉じた (close・失敗)。続けても無駄なので補助スレッドを抜ける。
                        throw new InterruptedException("reader closed");
                    }
                }

                @Override
                public void close() {
                }
            });
        } catch (final InterruptedException stopped) {
            // decode 側が閉じた。理由は読み側が持っている。
            Thread.currentThread().interrupt();
        } catch (final Throwable thrown) {
            failure.set(thrown);
        } finally {
            closeQuietly(out);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (final Exception ignored) {
            // 後始末の失敗は decode の結果を変えない。
        }
    }
}
