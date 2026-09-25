package com.kuronami.musicdiscmaker.lavaplayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.ResolveException;
import com.sedmelluq.discord.lavaplayer.natives.mp3.Mp3Decoder;

/**
 * ローカル MP3 のframe索引。
 *
 * <p>lavaplayer 2.2.4 の Xing seeker は MPEG-1 stereo 固定の offset 36 と
 * 1152 samples/frame を使う。MPEG-1 mono と MPEG-2/2.5 では Xing を見落とし、特に
 * 576 samples/frame の音源は尺とseek位置がずれる。持込ファイルだけ実frameを走査し、
 * 公開メタ情報の尺と、既存seekerへ渡す補正済み時刻を求める。
 * 呼び出し元のstoreが1曲の最大byte数を制限している契約なので、frame索引作成中は全体を
 * memoryへ読む。任意サイズの一般ファイルを直接渡す用途には使わない。
 */
final class LocalMp3Index {

    private static final int HEADER_BYTES = 4;
    private static final int MAX_FRAME_SYNC_SCAN = 2_048;
    private static final int XING_REQUIRED_FLAGS = 0x7;
    private static final long LAVA_SAMPLES_PER_FRAME = 1_152L;
    private static final int ID3V1_BYTES = 128;

    private final long[] offsets;
    private final int audioStartIndex;
    private final int audioFrameCount;
    private final int sampleRate;
    private final long samplesPerFrame;
    private final long encoderDelay;
    private final long encoderPadding;
    private final double lavaAverageFrameSize;
    private final boolean lavaRecognizesXing;
    private final long durationMs;

    private LocalMp3Index(long[] offsets, int audioStartIndex, int sampleRate, long samplesPerFrame,
            long encoderDelay, long encoderPadding, double lavaAverageFrameSize,
            boolean lavaRecognizesXing) {
        this.offsets = offsets;
        this.audioStartIndex = audioStartIndex;
        this.audioFrameCount = offsets.length - audioStartIndex;
        this.sampleRate = sampleRate;
        this.samplesPerFrame = samplesPerFrame;
        this.encoderDelay = encoderDelay;
        this.encoderPadding = encoderPadding;
        this.lavaAverageFrameSize = lavaAverageFrameSize;
        this.lavaRecognizesXing = lavaRecognizesXing;
        final long encodedSamples = Math.multiplyExact((long) audioFrameCount, samplesPerFrame);
        final long audibleSamples = Math.max(0L, encodedSamples - encoderDelay - encoderPadding);
        this.durationMs = Math.round(audibleSamples * 1_000.0D / sampleRate);
    }

    static LocalMp3Index read(Path file) {
        final byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (final IOException ex) {
            throw new ResolveException(FailureReason.UNKNOWN,
                    "could not inspect the local MP3 frames");
        }
        final int first = firstFrame(bytes);
        if (first < 0) {
            throw new ResolveException(FailureReason.UNKNOWN,
                    "the local file does not contain MP3 frames");
        }

        long[] offsets = new long[256];
        int count = 0;
        int cursor = first;
        int sampleRate = -1;
        int channelCount = -1;
        long samplesPerFrame = -1L;
        while (cursor + HEADER_BYTES <= bytes.length && isFrameHeader(bytes, cursor)) {
            final int frameSize = Mp3Decoder.getFrameSize(bytes, cursor);
            final int frameRate = Mp3Decoder.getFrameSampleRate(bytes, cursor);
            final int frameChannels = Mp3Decoder.getFrameChannelCount(bytes, cursor);
            final long frameSamples = Mp3Decoder.getSamplesPerFrame(bytes, cursor);
            if (frameSize <= HEADER_BYTES || cursor + frameSize > bytes.length
                    || frameRate <= 0 || frameChannels <= 0 || frameSamples <= 0L) {
                break;
            }
            if (sampleRate < 0) {
                sampleRate = frameRate;
                channelCount = frameChannels;
                samplesPerFrame = frameSamples;
            } else if (sampleRate != frameRate || channelCount != frameChannels
                    || samplesPerFrame != frameSamples) {
                throw new ResolveException(FailureReason.UNKNOWN,
                        "the local MP3 changes format between frames");
            }
            if (count == offsets.length) {
                offsets = Arrays.copyOf(offsets, offsets.length * 2);
            }
            offsets[count++] = cursor;
            cursor += frameSize;
        }
        if (count == 0) {
            throw new ResolveException(FailureReason.UNKNOWN,
                    "the local file does not contain complete MP3 frames");
        }
        if (!isSupportedTrailer(bytes, cursor)) {
            throw new ResolveException(FailureReason.UNKNOWN,
                    "the local MP3 is truncated or has unsupported trailing data");
        }

        final Gapless gapless = gapless(bytes, first, samplesPerFrame * Math.max(0L, count - 1L));
        final int audioStartIndex = gapless.metadataFrame() ? 1 : 0;
        if (count <= audioStartIndex) {
            throw new ResolveException(FailureReason.UNKNOWN,
                    "the local MP3 does not contain an audio frame");
        }
        return new LocalMp3Index(Arrays.copyOf(offsets, count), audioStartIndex,
                sampleRate, samplesPerFrame,
                gapless.delay(), gapless.padding(),
                Mp3Decoder.getAverageFrameSize(bytes, first), gapless.lavaRecognizesXing());
    }

    long durationMs() {
        return durationMs;
    }

    /**
     * lavaplayerの既存seekerが目的frameへ着くように時刻を翻訳する。
     * MPEG-1 stereoの正規Xingだけは既存実装が正しく読めるため、そのまま返す。
     */
    long lavaSeekMs(long audibleMs) {
        if (audibleMs <= 0L || durationMs <= 1L) {
            return 0L;
        }
        final long clampedMs = Math.min(audibleMs, durationMs - 1L);
        if (lavaRecognizesXing) {
            // Xing seekerはpercent=100で100要素のTOCを範囲外参照する。公開durationの丸めが
            // seeker内部の切り捨て値を1ms上回る場合もあるため、内部duration未満へ抑える。
            final long lavaDurationMs = (long) (audioFrameCount * LAVA_SAMPLES_PER_FRAME
                    * 1_000.0D / sampleRate);
            return Math.min(clampedMs, Math.max(0L, lavaDurationMs - 1L));
        }
        final long targetSample = Math.min(samplesPerFrame * audioFrameCount - 1L,
                encoderDelay + Math.round(clampedMs * (double) sampleRate / 1_000.0D));
        final int targetFrame = (int) Math.min(audioFrameCount - 1L,
                targetSample / samplesPerFrame);
        final long withinFrame = targetSample - targetFrame * samplesPerFrame;
        final long byteDistance = offsets[audioStartIndex + targetFrame] - offsets[0];

        // Mp3ConstantRateSeeker: byte = frameIndex * averageFrameSize - 8。
        // seek先が目的offsetを越すとframe scanは後ろへしか進めないため、floorで手前へ置く。
        final long lavaFrame = Math.max(0L,
                (long) Math.floor((byteDistance + 8.0D) / lavaAverageFrameSize));
        // seeker側で同じfake frameを選ぶ最小ms。frame内discard分を足しても次frameへ
        // 進まないよう上限を置く。
        final long lavaFrameMs = ceilDiv(lavaFrame * LAVA_SAMPLES_PER_FRAME * 1_000L,
                sampleRate);
        final long nextLavaFrameMs = ceilDiv((lavaFrame + 1L) * LAVA_SAMPLES_PER_FRAME * 1_000L,
                sampleRate);
        final long withinMs = withinFrame * 1_000L / sampleRate;
        return Math.min(lavaFrameMs + withinMs, nextLavaFrameMs - 1L);
    }

    private static int firstFrame(byte[] bytes) {
        int cursor = skipId3v2(bytes);
        final int end = Math.min(bytes.length - HEADER_BYTES, cursor + MAX_FRAME_SYNC_SCAN);
        for (; cursor <= end; cursor++) {
            if (!isFrameHeader(bytes, cursor)) {
                continue;
            }
            final int frameSize = Mp3Decoder.getFrameSize(bytes, cursor);
            if (frameSize > HEADER_BYTES && cursor + frameSize <= bytes.length) {
                return cursor;
            }
        }
        return -1;
    }

    /** 実際のMp3FrameReaderと同じ3条件でheaderを判定する。 */
    private static boolean isFrameHeader(byte[] bytes, int offset) {
        return Mp3Decoder.hasFrameSync(bytes, offset)
                && !Mp3Decoder.isUnsupportedVersion(bytes, offset)
                && Mp3Decoder.isValidFrame(bytes, offset);
    }

    private static int skipId3v2(byte[] bytes) {
        int cursor = 0;
        for (int tags = 0; tags < 3 && cursor + 10 <= bytes.length; tags++) {
            if (bytes[cursor] != 'I' || bytes[cursor + 1] != 'D' || bytes[cursor + 2] != '3') {
                break;
            }
            if ((bytes[cursor + 3] & 0xff) == 0xff || (bytes[cursor + 4] & 0xff) == 0xff
                    || (bytes[cursor + 6] & 0x80) != 0 || (bytes[cursor + 7] & 0x80) != 0
                    || (bytes[cursor + 8] & 0x80) != 0 || (bytes[cursor + 9] & 0x80) != 0) {
                throw new ResolveException(FailureReason.UNKNOWN,
                        "the local MP3 has a malformed ID3 header");
            }
            final int size = ((bytes[cursor + 6] & 0x7f) << 21)
                    | ((bytes[cursor + 7] & 0x7f) << 14)
                    | ((bytes[cursor + 8] & 0x7f) << 7)
                    | (bytes[cursor + 9] & 0x7f);
            final boolean footer = (bytes[cursor + 5] & 0x10) != 0;
            final long next = (long) cursor + 10L + size + (footer ? 10L : 0L);
            if (next > bytes.length) {
                return bytes.length;
            }
            cursor = (int) next;
        }
        return cursor;
    }

    /**
     * 途中で壊れたframeを「そこまでの短い曲」として受理しない。音声frameの後ろで許すのは
     * 何も無い場合、ID3v1、または一般的なAPEv2だけに絞る。
     */
    private static boolean isSupportedTrailer(byte[] bytes, int cursor) {
        if (cursor == bytes.length) {
            return true;
        }
        int audioEnd = bytes.length;
        if (audioEnd - cursor >= ID3V1_BYTES && ascii(bytes, audioEnd - ID3V1_BYTES, "TAG")) {
            audioEnd -= ID3V1_BYTES;
        }
        if (cursor == audioEnd) {
            return true;
        }
        if (audioEnd - cursor < 32 || !ascii(bytes, audioEnd - 32, "APETAGEX")) {
            return false;
        }
        final long apeSize = uint32Le(bytes, audioEnd - 20);
        if (apeSize < 32L || apeSize > audioEnd - cursor) {
            return false;
        }
        final long tagStart = audioEnd - apeSize;
        if (tagStart == cursor) {
            return true;
        }
        // APEv2 headerは任意で、footerと同じ32byte構造をtag本体の直前に持つ。
        return tagStart - 32L == cursor && ascii(bytes, cursor, "APETAGEX");
    }

    private static Gapless gapless(byte[] bytes, int frameStart, long encodedSamples) {
        final int version = (bytes[frameStart + 1] >>> 3) & 0x3;
        final boolean mono = ((bytes[frameStart + 3] >>> 6) & 0x3) == 0x3;
        final boolean hasCrc = (bytes[frameStart + 1] & 0x1) == 0;
        final int sideInfo = version == 0x3 ? (mono ? 17 : 32) : (mono ? 9 : 17);
        final int xing = frameStart + HEADER_BYTES + (hasCrc ? 2 : 0) + sideInfo;
        final int frameEnd = frameStart + Mp3Decoder.getFrameSize(bytes, frameStart);
        final boolean xingTag = ascii(bytes, xing, "Xing");
        final boolean infoTag = ascii(bytes, xing, "Info");
        if (xing + 8 > frameEnd || !(xingTag || infoTag)) {
            return new Gapless(false, false, 0L, 0L);
        }

        final int flags = int32(bytes, xing + 4);
        if ((flags & 0x1) != 0) {
            if (xing + 12 > frameEnd) {
                throw new ResolveException(FailureReason.UNKNOWN, "incomplete MP3 frame count");
            }
            final long declaredFrames = Integer.toUnsignedLong(int32(bytes, xing + 8));
            final long scannedFrames = encodedSamples / Mp3Decoder.getSamplesPerFrame(bytes, frameStart);
            // 本体のXing seekerは宣言値でdurationを計算する。実frame数と違う値を許すと、
            // こちらでclampしても本体ではpercent>=100となりTOCの範囲外へ進む。
            if (declaredFrames == 0L || declaredFrames != scannedFrames) {
                throw new ResolveException(FailureReason.UNKNOWN, "inconsistent MP3 frame count");
            }
        }
        final boolean requiredFields = (flags & XING_REQUIRED_FLAGS) == XING_REQUIRED_FLAGS;
        // Mp3XingSeekerはoffset 36の"Xing"かつflags 1/2/4が揃った時だけ使われる。
        // "Info"は同じ配置でもconstant-rate seekerへ落ちる。
        final boolean lavaRecognizesXing = xing - frameStart == 36 && xingTag && requiredFields;
        int cursor = xing + 8;
        if ((flags & 0x1) != 0) cursor += 4;
        if ((flags & 0x2) != 0) cursor += 4;
        if ((flags & 0x4) != 0) cursor += 100;
        if ((flags & 0x8) != 0) cursor += 4;
        // LAME/Lavc tagのencoder delayとpaddingはencoder string先頭から21byte後の3byte。
        final int gapless = cursor + 21;
        if (!requiredFields || cursor + 9 > frameEnd
                || !(ascii(bytes, cursor, "LAME") || ascii(bytes, cursor, "Lavc"))
                || gapless + 3 > frameEnd) {
            return new Gapless(true, lavaRecognizesXing, 0L, 0L);
        }
        final long delay = ((bytes[gapless] & 0xffL) << 4) | ((bytes[gapless + 1] & 0xf0) >>> 4);
        final long padding = ((bytes[gapless + 1] & 0x0fL) << 8) | (bytes[gapless + 2] & 0xffL);
        if (delay + padding >= encodedSamples) {
            return new Gapless(true, lavaRecognizesXing, 0L, 0L);
        }
        return new Gapless(true, lavaRecognizesXing, delay, padding);
    }

    private static boolean ascii(byte[] bytes, int offset, String value) {
        if (offset + value.length() > bytes.length) return false;
        for (int i = 0; i < value.length(); i++) {
            if ((char) bytes[offset + i] != value.charAt(i)) return false;
        }
        return true;
    }

    private static int int32(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
    }

    private static long uint32Le(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL) | ((bytes[offset + 1] & 0xffL) << 8)
                | ((bytes[offset + 2] & 0xffL) << 16) | ((bytes[offset + 3] & 0xffL) << 24);
    }

    private static long ceilDiv(long value, long divisor) {
        return value == 0L ? 0L : 1L + (value - 1L) / divisor;
    }

    private record Gapless(boolean metadataFrame, boolean lavaRecognizesXing,
            long delay, long padding) {
    }
}
