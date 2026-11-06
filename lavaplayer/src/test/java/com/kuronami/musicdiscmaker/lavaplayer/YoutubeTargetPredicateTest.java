package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.lavaplayer.api.YoutubeUrls;

/**
 * loader 側の「YouTube を相手にしている URL か」の判定を固定する。
 *
 * <p>この判定は loader (visitorId を入れ替えてやり直す相手) と mod 側
 * ({@code TransientOpenRetry} が時間を置いて開き直す相手) の両方が使う。片方だけ直すと、
 * loader は bot 判定として開き直すのに client は諦める (またはその逆) という食い違いが出る。
 * そこで {@link YoutubeUrls} を唯一の実装にし、<b>同じ例</b>で両側を固定する
 * (mod 側は {@code common} の {@code YoutubeUrlsTest} が同じ例を持つ)。
 */
class YoutubeTargetPredicateTest {

    /** YouTube を相手にしている例 (検索クエリを含む)。 */
    static final List<String> YOUTUBE = List.of(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "http://youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            "youtube.com/watch?v=dQw4w9WgXcQ",
            "ytsearch:Rick Astley Never Gonna Give You Up",
            "ytmsearch:Rick Astley Never Gonna Give You Up");

    /** YouTube を相手にしていない例。 */
    static final List<String> NOT_YOUTUBE = List.of(
            "https://soundcloud.com/pajlada/never-gonna-give-you-up",
            "https://example.com/song.mp3",
            "https://notyoutube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com.evil.example/watch?v=dQw4w9WgXcQ",
            "scsearch:Rick Astley Never Gonna Give You Up",
            "");

    @Test
    void theLoaderTreatsYoutubeAndSearchQueriesAsYoutubeTargets() {
        for (final String url : YOUTUBE) {
            assertTrue(MusicLoaderImpl.isYoutubeTarget(url), url);
        }
    }

    @Test
    void theLoaderDoesNotTreatOtherHostsAsYoutubeTargets() {
        for (final String url : NOT_YOUTUBE) {
            assertFalse(MusicLoaderImpl.isYoutubeTarget(url), url);
        }
        assertFalse(MusicLoaderImpl.isYoutubeTarget(null));
    }

    @Test
    void theLoaderAgreesWithTheSharedPredicateOnEveryExample() {
        for (final String url : YOUTUBE) {
            assertTrue(MusicLoaderImpl.isYoutubeTarget(url) == YoutubeUrls.isYoutube(url), url);
        }
        for (final String url : NOT_YOUTUBE) {
            assertTrue(MusicLoaderImpl.isYoutubeTarget(url) == YoutubeUrls.isYoutube(url), url);
        }
    }
}
