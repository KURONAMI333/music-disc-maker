package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;

/**
 * 「意図した終了」の印を、ストリームでなく<b>インスタンス側</b>が持つことの固定。
 *
 * <h2>落ちていた形</h2>
 * 印を立てるのは停止の時だが、その瞬間にストリームが開栓済みとは限らない
 * ({@code SoundManager#play} の受理判定より後に {@code getCustomStream} が走る経路が実機で
 * 観測されている。`_research/FIRST_PLAY_SILENT.md` §2)。印をストリームに立てる作りだと、
 * その時 {@code null} なので<b>印が落ちる</b>。後から生まれたストリームは印を持たずに終端へ着き、
 * 「理由の付かない終了警告」を出す = 誤報。
 *
 * <p>持ち主をストリームより長生きする側へ移し、そこが作る全てのストリームで同じ印を共有する。
 * 共有しているので<b>引き継ぎのコードは要らない</b> — 後から生まれたストリームは印を持って
 * 生まれる。
 */
class LavaPlayerAudioStreamExpectedEndTest {

    /** 開いた直後に終端を返す音源 (1 バイトも鳴らずに終わる = 誤報が出る条件)。 */
    private static final class EndedSource implements IAudioSource {

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
            return -1;
        }

        @Override
        public void close() {
        }
    }

    private final List<PlaybackFailure> reported = new ArrayList<>();

    /** インスタンス側が持つ印。{@code DiscSoundInstance} が作るストリームは全部これを共有する。 */
    private final AtomicBoolean expectedEnd = new AtomicBoolean(false);

    /** 印を共有しないストリーム (3 引数の構築子) は、従来どおり自分の印だけを見ること。 */
    @Test
    void aStreamThatEndsWithoutAudioAndWithoutAMarkStillReportsIt() {
        final LavaPlayerAudioStream stream =
                new LavaPlayerAudioStream(new EndedSource(), null, reported::add);

        stream.read(256);

        assertEquals(1, reported.size(),
                "一音も鳴らずに終わったのに黙っている (完全な無音のまま何も出ない)");
    }

    /**
     * <b>これが今回の回帰テスト。</b> 印を立てた<b>後</b>に生まれたストリームが、その印を持って
     * いること。
     */
    @Test
    void aStreamBornAfterTheMarkInheritsIt() {
        expectedEnd.set(true); // 開栓より前に停止が入った (実機で起きた順序)

        final LavaPlayerAudioStream late =
                new LavaPlayerAudioStream(new EndedSource(), null, reported::add, expectedEnd);
        late.read(256);

        assertTrue(reported.isEmpty(),
                "止めたのはこちらなのに失敗として報告している (理由の付かない終了警告): " + reported);
    }

    /** 既に開栓済みのストリームへ後から立てる従来の順序も、同じ印で通ること。 */
    @Test
    void aStreamThatAlreadyExistsSeesTheMarkToo() {
        final LavaPlayerAudioStream stream =
                new LavaPlayerAudioStream(new EndedSource(), null, reported::add, expectedEnd);

        expectedEnd.set(true); // 再生中にディスクを抜いた
        stream.read(256);

        assertTrue(reported.isEmpty(), "曲の途中で止めるたびに失敗が出る: " + reported);
    }

    /** 印を立てていなければ、共有していても報告は出ること (印が効きすぎていないこと)。 */
    @Test
    void sharingTheMarkDoesNotSilenceARealSilentEnd() {
        final LavaPlayerAudioStream stream =
                new LavaPlayerAudioStream(new EndedSource(), null, reported::add, expectedEnd);

        stream.read(256);

        assertEquals(1, reported.size(), "止めていないのに黙っている: " + reported);
    }
}
