package com.kuronami.musicdiscmaker.lavaplayer.codec;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.JavaLayerException;
import javazoom.jl.decoder.SampleBuffer;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayDeque;

/**
 * JLayer による MP3 decoder。native {@code Mp3DecoderLibrary} (mpg123 の feed mode) の代わり。
 *
 * <p>{@code Mp3TrackProvider} は 1 回の decode に 1 frame ちょうどを渡す。JLayer の
 * {@link Bitstream} / {@link Decoder} は bit reservoir を frame 間で持ち越すので、
 * 両者は使い回し、入力は {@link FeedStream} へ積んでいく。積んだ分を読み切ったら
 * 次の入力を待つ (= mpg123 の「need more」と同じ)。
 */
public final class JavaMp3Decoder {

    private final FeedStream feed = new FeedStream();
    private final BufferedInputStream source = new BufferedInputStream(feed);
    private final Decoder decoder = new Decoder();
    private Bitstream bitstream;

    /** @return {@code output} の先頭から書いた sample 数 (全 channel 合計)。足りなければ 0 */
    public int decode(ByteBuffer input, ShortBuffer output, int maxSamples) {
        final byte[] bytes = new byte[input.remaining()];
        input.duplicate().get(bytes);
        feed.add(bytes);
        if (bitstream == null) {
            bitstream = new Bitstream(source);
        }
        int written = 0;
        try {
            while (written < maxSamples && source.available() > 0) {
                final Header header = bitstream.readFrame();
                if (header == null) {
                    break;
                }
                try {
                    final SampleBuffer decoded = (SampleBuffer) decoder.decodeFrame(header, bitstream);
                    final int count = Math.min(decoded.getBufferLength(), maxSamples - written);
                    output.put(written, decoded.getBuffer(), 0, count);
                    written += count;
                } finally {
                    bitstream.closeFrame();
                }
            }
        } catch (final JavaLayerException | IOException ex) {
            throw new IllegalStateException("Decoding failed: " + ex.getMessage(), ex);
        }
        return written;
    }

    /** 積まれた byte を順に返し、空なら -1 (EOF ではなく「今は無い」)。 */
    private static final class FeedStream extends InputStream {
        private final ArrayDeque<byte[]> chunks = new ArrayDeque<>();
        private int offset;
        private int available;

        void add(byte[] chunk) {
            if (chunk.length > 0) {
                chunks.add(chunk);
                available += chunk.length;
            }
        }

        @Override
        public int read() {
            final byte[] one = new byte[1];
            return read(one, 0, 1) == 1 ? one[0] & 0xFF : -1;
        }

        @Override
        public int read(byte[] target, int targetOffset, int length) {
            if (length == 0) {
                return 0;
            }
            int copied = 0;
            while (copied < length && !chunks.isEmpty()) {
                final byte[] head = chunks.peek();
                final int count = Math.min(length - copied, head.length - offset);
                System.arraycopy(head, offset, target, targetOffset + copied, count);
                copied += count;
                offset += count;
                if (offset == head.length) {
                    chunks.poll();
                    offset = 0;
                }
            }
            available -= copied;
            return copied == 0 ? -1 : copied;
        }

        @Override
        public int available() {
            return available;
        }
    }
}
