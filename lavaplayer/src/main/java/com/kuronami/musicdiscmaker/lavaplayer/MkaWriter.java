package com.kuronami.musicdiscmaker.lavaplayer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * mono 48kHz Opus を納める音声専用 MKA (matroska audio) の writer
 * (MDM_310_VIDEO_AUDIO_DESIGN_CORRECTED_RESULT_20260927 §9)。
 *
 * <p>stage 3 の normalize が decode 済み PCM を OpusEncoder で切った packet 列を受け取り、
 * pin版 LavaPlayer の Matroska reader が尺・seek・frame 提供を正しく読める最小構成の
 * EBML 文書へ並べる。pin reader は Cues を無くても先頭走査で seek するため、
 * Cues / SeekHead / Tags / 時間コード index は書かない (書くなら後続 stage の追加)。
 *
 * <p>構成は EBML header + Segment{Info, Tracks, Cluster*}:
 * <ul>
 * <li>Info は TimestampScale=1_000_000 (1 tick = 1ms) と尺を f64 で持つ</li>
 * <li>Track は A_OPUS 1 本のみで、SamplingFrequency=48000 / Channels=1 を必ず書く
 *     (pin版 {@code MatroskaOpusTrackConsumer} はこの2値で {@code OpusPacketRouter} を作る)</li>
 * <li>Cluster は Track1 の SimpleBlock の列。block 相対 timecode は符号つき16bit
 *     しか持てないため、cluster の長さは 30 秒で切る</li>
 * <li>CodecPrivate に OpusHead、SeekPreRoll に 80ms を書く (ffmpeg 系 MKA と同じ値)</li>
 * </ul>
 *
 * <p>段階2では「保存できる音声だけで完結する MKA」を出すだけで、normalize・
 * picker・GUI には触れない。
 */
final class MkaWriter {

    static final int SAMPLE_RATE = 48_000;
    static final int CHANNELS = 1;

    /** Matroska 既定の TimestampScale (1 tick = 1ms)。 */
    private static final long TIMESTAMP_SCALE_NS = 1_000_000L;
    /** Opus デコーダーが seek 直後に必要とする巻き戻し量 (ns)。ffmpeg 系 MKA と同じ 80ms。 */
    private static final long SEEK_PRE_ROLL_NS = 80_000_000L;
    /** OpusHead の pre-skip (samples)。ffmpeg 系 MKA で一般的な 3840 (48kHz で 80ms)。 */
    private static final int PRE_SKIP_SAMPLES = 3_840;
    /** 1 cluster に収める最大長 (ms)。block の相対 timecode は符号つき16bit ≦32,767 tick。 */
    private static final long MAX_CLUSTER_MS = 30_000L;

    private static final byte[] MUXING_APP = "mdm-mka-writer".getBytes(StandardCharsets.UTF_8);

    /** encode 済み Opus packet 1 個と、その正確な尺 (ns)。packet は decoder へ渡す生のまま。 */
    record OpusPacket(byte[] data, long durationNs) {
        OpusPacket {
            if (data == null || data.length == 0 || durationNs <= 0L) {
                throw new IllegalArgumentException("Invalid Opus packet");
            }
        }
    }

    private MkaWriter() {}

    /**
     * packet 列を 1 本の音声専用 MKA として書き出す。
     *
     * @param file    書き出し先 (既存は上書き)
     * @param packets encode 済み Opus packet の再生順の列。1 個以上必須
     * @throws IOException 書き出しに失敗した
     */
    static void write(Path file, List<OpusPacket> packets) throws IOException {
        Objects.requireNonNull(file, "file");
        Files.write(file, document(packets));
    }

    /**
     * 文書全体を byte 列として返す。生成物の内容をメモリ上で検査する試験用。
     */
    static byte[] document(List<OpusPacket> packets) {
        if (packets == null || packets.isEmpty()) {
            throw new IllegalArgumentException("No Opus packets");
        }
        long totalNs = 0L;
        for (final OpusPacket packet : packets) {
            totalNs += packet.durationNs;
        }
        final double durationMs = (double) totalNs / (double) TIMESTAMP_SCALE_NS;
        return cat(ebmlHeader(), el(0x18538067L,
                cat(info(durationMs), tracks(), clusters(packets))));
    }

    // ---------- 構築 ----------

    private static byte[] ebmlHeader() {
        return el(0x1A45DFA3L, cat(
                uintEl(0x4286L, 1),       // EBMLVersion
                uintEl(0x42F7L, 1),       // EBMLReadVersion
                uintEl(0x42F2L, 4),       // EBMLMaxIDLength
                uintEl(0x42F3L, 8),       // EBMLMaxSizeLength
                strEl(0x4282L, "matroska"), // DocType
                uintEl(0x4287L, 4),       // DocTypeVersion
                uintEl(0x4285L, 2)));     // DocTypeReadVersion
    }

    private static byte[] info(double durationMs) {
        return el(0x1549A966L, cat(
                uintEl(0x2AD7B1L, TIMESTAMP_SCALE_NS), // TimestampScale (ns)
                f64El(0x4489L, durationMs),            // Duration (tick = ms)
                utf8El(0x4D80L, MUXING_APP),           // MuxingApp
                utf8El(0x5741L, MUXING_APP)));         // WritingApp
    }

    private static byte[] tracks() {
        final byte[] audio = el(0xE1L, cat(
                f32El(0xB5L, (float) SAMPLE_RATE),     // SamplingFrequency
                uintEl(0x9FL, CHANNELS)));             // Channels
        return el(0x1654AE6BL, el(0xAEL, cat(
                uintEl(0xD7L, 1),                      // TrackNumber
                uintEl(0x73C5L, 1),                    // TrackUID
                uintEl(0x83L, 2),                      // TrackType: audio
                uintEl(0xB9L, 1),                      // FlagEnabled
                uintEl(0x88L, 1),                      // FlagDefault
                uintEl(0x55AAL, 0),                    // FlagForced
                uintEl(0x9CL, 0),                      // FlagLacing
                strEl(0x22B59CL, "und"),               // Language
                strEl(0x86L, "A_OPUS"),                // CodecID
                el(0x63A2L, opusHead()),               // CodecPrivate
                uintEl(0x56BBL, SEEK_PRE_ROLL_NS),     // SeekPreRoll (ns)
                audio)));
    }

    /**
     * mapping family 0 の OpusHead (19 byte)。"OpusHead" + version + channels +
     * pre-skip (LE16 samples) + input rate (LE32) + gain (LE16) + family。
     */
    private static byte[] opusHead() {
        return ByteBuffer.allocate(19).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .put("OpusHead".getBytes(StandardCharsets.US_ASCII))
                .put((byte) 1)
                .put((byte) CHANNELS)
                .putShort((short) PRE_SKIP_SAMPLES)
                .putInt(SAMPLE_RATE)
                .putShort((short) 0)
                .put((byte) 0)
                .array();
    }

    private static byte[] clusters(List<OpusPacket> packets) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream clusterBlocks = new ByteArrayOutputStream();
        long clusterStartMs = 0L;
        long packetStartNs = 0L;
        for (final OpusPacket packet : packets) {
            final long packetStartMs = Math.round(packetStartNs / (double) TIMESTAMP_SCALE_NS);
            if (clusterBlocks.size() > 0 && packetStartMs - clusterStartMs > MAX_CLUSTER_MS) {
                writeCluster(out, clusterStartMs, clusterBlocks.toByteArray());
                clusterBlocks = new ByteArrayOutputStream();
            }
            if (clusterBlocks.size() == 0) {
                clusterStartMs = packetStartMs;
            }
            final int relative = (int) (packetStartMs - clusterStartMs);
            clusterBlocks.writeBytes(simpleBlock(relative, packet.data()));
            packetStartNs += packet.durationNs;
        }
        writeCluster(out, clusterStartMs, clusterBlocks.toByteArray());
        return out.toByteArray();
    }

    private static void writeCluster(ByteArrayOutputStream out, long startMs, byte[] blocks) {
        out.writeBytes(el(0x1F43B675L, cat(uintEl(0xE7L, startMs), blocks)));
    }

    /** track 1・keyframe・lacing なしの SimpleBlock (block 内 packet はちょうど 1 個)。 */
    private static byte[] simpleBlock(int relativeTimecode, byte[] packet) {
        final ByteBuffer block = ByteBuffer.allocate(4 + packet.length);
        block.put((byte) 0x81);                  // TrackNumber = 1 (EBML vint)
        block.putShort((short) relativeTimecode); // cluster 起点の相対 timecode (tick)
        block.put((byte) 0x80);                  // keyframe, lacing なし
        block.put(packet);
        return el(0xA3L, block.array());
    }

    // ---------- EBML プリミティブ ----------

    private static byte[] el(long id, byte[] payload) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeId(out, id);
        writeVintSize(out, payload.length);
        out.write(payload, 0, payload.length);
        return out.toByteArray();
    }

    /** EBML ID は先頭の marker bit を落とさず、そのままのバイト列で出す。 */
    private static void writeId(ByteArrayOutputStream out, long id) {
        int len = 1;
        while (len < 4 && (id >>> (8 * len)) != 0L) {
            len++;
        }
        for (int i = len - 1; i >= 0; i--) {
            out.write((int) (id >>> (8 * i)) & 0xFF);
        }
    }

    private static void writeVintSize(ByteArrayOutputStream out, long value) {
        for (int len = 1; len <= 8; len++) {
            final long max = (1L << (7 * len)) - 2L; // 全 bit 1 は unknown size の意味
            if (value <= max) {
                final long coded = value | (1L << (7 * len));
                for (int i = len - 1; i >= 0; i--) {
                    out.write((int) (coded >>> (8 * i)) & 0xFF);
                }
                return;
            }
        }
        throw new IllegalArgumentException("EBML element too large");
    }

    private static byte[] uintEl(long id, long value) {
        final ByteArrayOutputStream payload = new ByteArrayOutputStream();
        for (int i = 7; i >= 0; i--) {
            if ((value >>> (i * 8)) != 0L || i == 0 || payload.size() > 0) {
                payload.write((int) (value >>> (i * 8)) & 0xFF);
            }
        }
        return el(id, payload.toByteArray());
    }

    private static byte[] strEl(long id, String value) {
        return el(id, value.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] utf8El(long id, byte[] value) {
        return el(id, value);
    }

    private static byte[] f64El(long id, double value) {
        return el(id, ByteBuffer.allocate(8).putDouble(value).array());
    }

    private static byte[] f32El(long id, float value) {
        return el(id, ByteBuffer.allocate(4).putFloat(value).array());
    }

    private static byte[] cat(byte[]... parts) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (final byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }
}
