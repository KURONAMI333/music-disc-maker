package com.kuronami.musicdiscmaker.lavaplayer;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * matroska / WebM の Tracks 要素だけを歩く軽量スキャナー。
 *
 * <p>pin版 {@code MatroskaContainerProbe} は DocType や track 種別を見ずに
 * 「matroska/webm」として受理するため、映像 track を持つ MKV も音声として解決できて
 * しまう。保存境界で「track 一覧に VIDEO が無い」ことを確認するためだけに使い、
 * cluster の中身や codec 設定は読まない。音声検査は probe/decoder 側の責務のまま。
 *
 * <p>読むのはヘッダ階層だけ。cluster に入っている映像バイトそのものは位置を飛ばして
 * 読まない (呼び出し側の store が 1 曲の最大 byte 数を制限している契約内)。
 */
final class MatroskaTrackScanner {

    private static final long ID_SEGMENT = 0x18538067L;
    private static final long ID_TRACKS = 0x1654AE6BL;
    private static final long ID_TRACK_ENTRY = 0xAEL;
    private static final long ID_TRACK_TYPE = 0x83L;
    private static final long TRACK_TYPE_VIDEO = 1L;

    private static final int FLAG_TRACK_SEEN = 1;
    private static final int FLAG_VIDEO_SEEN = 2;

    private MatroskaTrackScanner() {}

    /**
     * Tracks 要素が存在し、全 TrackEntry が音声 (TrackType=1 以外) なら {@code true}。
     * 映像 track が 1 本でもあれば {@code false}。Tracks が読めない / 無い構造は
     * 「音声だけ」と証明できないので {@code false}、構造自体が壊れていれば
     * {@link IOException}。いずれも呼び出し側では保存拒否として扱う。
     */
    static boolean isAudioOnly(Path file) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.READ)) {
            final Cursor cursor = new Cursor(channel);
            // 先頭は EBML header。次の master 要素が Segment。
            final long fileEnd = channel.size();
            int flags = 0;
            while (cursor.position() < fileEnd) {
                final Element element = readElement(cursor, fileEnd);
                if (element.id == ID_SEGMENT) {
                    flags |= scanSegment(cursor, element.end);
                } else {
                    cursor.position(element.end);
                }
            }
            // Tracks が 1 つも無い構造は「音声だけ」と証明できないので拒否側。
            return (flags & FLAG_TRACK_SEEN) != 0 && (flags & FLAG_VIDEO_SEEN) == 0;
        }
    }

    /** Segment 直下の全 Tracks 要素を走査し、track の有無と映像の有無を返す。 */
    private static int scanSegment(Cursor cursor, long segmentEnd) throws IOException {
        int flags = 0;
        while (cursor.position() < segmentEnd) {
            final Element element = readElement(cursor, segmentEnd);
            if (element.id == ID_TRACKS) {
                flags |= scanTracks(cursor, element.end);
            } else {
                cursor.position(element.end);
            }
        }
        return flags;
    }

    /** Tracks 直下の全 TrackEntry を調べる。reader が片方しか読まなくても
     *  複数 Tracks で片方へ映像を隠す形を残さないよう、全部を見る。 */
    private static int scanTracks(Cursor cursor, long tracksEnd) throws IOException {
        int flags = 0;
        while (cursor.position() < tracksEnd) {
            final Element element = readElement(cursor, tracksEnd);
            if (element.id == ID_TRACK_ENTRY) {
                flags |= FLAG_TRACK_SEEN;
                if (trackType(cursor, element.end) == TRACK_TYPE_VIDEO) {
                    flags |= FLAG_VIDEO_SEEN;
                }
            } else {
                cursor.position(element.end);
            }
        }
        return flags;
    }

    /** TrackEntry 直下から TrackType uint を読む。無ければ既定値 1 (video) として返す。 */
    private static long trackType(Cursor cursor, long entryEnd) throws IOException {
        while (cursor.position() < entryEnd) {
            final Element element = readElement(cursor, entryEnd);
            if (element.id == ID_TRACK_TYPE) {
                return readUint(cursor, element.end);
            }
            cursor.position(element.end);
        }
        return TRACK_TYPE_VIDEO;
    }

    // ---------- EBML プリミティブ ----------

    private record Element(long id, long end) {}
    private record Header(long id, long dataSize, long dataStart) {}

    /** id と data size を読み、payload の終端位置を返す。size が範囲外なら IOException。 */
    private static Element readElement(Cursor cursor, long bound) throws IOException {
        final Header header = readHeader(cursor);
        final long end = header.dataSize < 0L ? bound : header.dataStart + header.dataSize;
        if (end < header.dataStart || end > bound) {
            throw new IOException("Malformed matroska element (size exceeds its parent)");
        }
        return new Element(header.id, end);
    }

    private static Header readHeader(Cursor cursor) throws IOException {
        final int first = cursor.read();
        final int idLength = lengthOf(first);
        if (idLength < 0 || idLength > 4) {
            throw new IOException("Invalid EBML element id");
        }
        long id = first;
        for (int i = 1; i < idLength; i++) {
            id = (id << 8) | cursor.read();
        }
        final int sizeFirst = cursor.read();
        final int sizeLength = lengthOf(sizeFirst);
        if (sizeLength < 0) {
            throw new IOException("Invalid EBML element size");
        }
        long size = sizeFirst & (0xFF >>> sizeLength);
        for (int i = 1; i < sizeLength; i++) {
            size = (size << 8) | cursor.read();
        }
        if (size == (1L << (7 * sizeLength)) - 1L) {
            size = -1L; // 全 bit 1 = unknown size。呼び出し側の bound まで広がる。
        }
        return new Header(id, size, cursor.position());
    }

    /** 先頭 byte の最上位から数えて最初の 1 bit までの長さ (1〜8)。0x00 なら -1。 */
    private static int lengthOf(int first) {
        for (int len = 1; len <= 8; len++) {
            if ((first & (0x80 >>> (len - 1))) != 0) {
                return len;
            }
        }
        return -1;
    }

    /** uint payload (1〜8 byte) を読む。 */
    private static long readUint(Cursor cursor, long end) throws IOException {
        final long length = end - cursor.position();
        if (length <= 0L || length > 8L) {
            throw new IOException("Invalid matroska uint size");
        }
        long value = 0L;
        for (long i = 0L; i < length; i++) {
            value = (value << 8) | cursor.read();
        }
        return value;
    }

    /** 1 byte ずつの先読みと自由位置への seek だけを提供する小さなカーソル。 */
    private static final class Cursor {
        private final SeekableByteChannel channel;
        private final ByteBuffer one = ByteBuffer.allocate(1);

        Cursor(SeekableByteChannel channel) {
            this.channel = channel;
        }

        long position() throws IOException {
            return channel.position();
        }

        void position(long position) throws IOException {
            channel.position(position);
        }

        int read() throws IOException {
            one.clear();
            if (channel.read(one) < 0) {
                throw new EOFException("Truncated matroska structure");
            }
            return one.get(0) & 0xFF;
        }
    }
}
