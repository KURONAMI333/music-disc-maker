package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;

/**
 * Sol のリリース前レビュー ④ の土台: <b>「登録できた」ではなく「最初の実 PCM が渡った」</b>を
 * 外へ知らせる口を、実物の {@link LavaPlayerAudioStream} で確かめる。
 *
 * <h2>無音を数えてはいけない</h2>
 * {@code read} はソースが短く返しても<b>待たずに残りを無音で埋めて満杯の buffer を返す</b>
 * (空を返すと OpenAL のキューが空になり、MC が「鳴り終わった」と読んでチャンネルごと捨てる)。
 * だから「buffer を返した」= 音が出た、ではない。判別点は {@code emit} の 1 箇所だけで、
 * 埋めた無音は {@code padWithSilence} が別に数える。
 *
 * <p>ここは本物のスレッドを起こさない。通知は {@code read} の中で同期に起きる = これが production
 * の呼ばれ方そのもの (MC の単一の "Sound engine" スレッドが引く) で、競合する相手が居ない。
 */
class LavaPlayerAudioStreamFirstAudioTest {

    /** 出す量を外から決められる音源。{@code close} 済みかどうかも覚える。 */
    private static final class ScriptedSource implements IAudioSource {

        /** 1 回の {@code read} で返すバイト数。{@code 0} = 今は出せない (終端ではない)。 */
        volatile int nextRead;
        final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public int sampleRate() {
            return 48_000;
        }

        @Override
        public int channels() {
            return 1;
        }

        @Override
        public int bitsPerSample() {
            return 16;
        }

        @Override
        public boolean bigEndian() {
            return false;
        }

        @Override
        public int read(byte[] dst, int off, int len) {
            final int n = Math.min(nextRead, len);
            for (int i = 0; i < n; i++) {
                dst[off + i] = (byte) 0x7F; // 無音 (0) と見分けが付く値
            }
            return n;
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }

    private final ScriptedSource source = new ScriptedSource();
    private final AtomicInteger firstAudio = new AtomicInteger();
    /** 失敗の届け先は必ず差す。差さないと既定経路が {@code Minecraft.getInstance()} を掴む。 */
    private final LavaPlayerAudioStream stream =
            new LavaPlayerAudioStream(source, null, failure -> {
            });

    private ByteBuffer read(int size) {
        return stream.read(size);
    }

    /**
     * ソースが一度も実データを出さない間は、buffer が満杯で返っても通知しない。
     * <b>ここが「登録できただけでは Now Playing を出さない」の土台。</b>
     */
    @Test
    void paddedSilenceNeverCountsAsTheFirstAudio() {
        stream.setFirstAudioSink(firstAudio::incrementAndGet);
        source.nextRead = 0;

        for (int i = 0; i < 3; i++) {
            final ByteBuffer buffer = read(256);
            assertEquals(256, buffer.remaining(), "buffer は満杯で返る (空を返すと OpenAL が止まる)");
            while (buffer.hasRemaining()) {
                assertEquals(0, buffer.get(), "埋めたのは無音");
            }
        }
        assertEquals(0, firstAudio.get(), "無音パディングは「鳴り始めた」ではない");
    }

    /** 最初の実 PCM が渡った時点で 1 回だけ通知する。以降の実データでは増えない。 */
    @Test
    void firstRealPcmNotifiesExactlyOnce() {
        stream.setFirstAudioSink(firstAudio::incrementAndGet);

        source.nextRead = 0;
        read(256);
        assertEquals(0, firstAudio.get());

        source.nextRead = 256;
        read(256);
        assertEquals(1, firstAudio.get(), "最初の実 PCM で通知する");

        read(256);
        read(256);
        assertEquals(1, firstAudio.get(), "2 回目以降は通知しない");
    }

    /** 実データと無音が同じ buffer に混ざっても、通知は実データの分だけで 1 回。 */
    @Test
    void partiallyFilledBufferStillNotifiesOnce() {
        stream.setFirstAudioSink(firstAudio::incrementAndGet);
        source.nextRead = 64; // 要求 256 に対して 64 しか出せない → 残り 192 は無音

        final ByteBuffer buffer = read(256);
        assertEquals(256, buffer.remaining());
        assertEquals(1, firstAudio.get());

        int real = 0;
        while (buffer.hasRemaining()) {
            if (buffer.get() != 0) {
                real++;
            }
        }
        assertTrue(real > 0, "実データが混ざっていること");
    }

    /** 通知先を差していなくても {@code read} は素通しで動く (計測だけの経路を壊さない)。 */
    @Test
    void readWorksWithoutASink() {
        source.nextRead = 256;
        assertEquals(256, read(256).remaining());
    }
}
