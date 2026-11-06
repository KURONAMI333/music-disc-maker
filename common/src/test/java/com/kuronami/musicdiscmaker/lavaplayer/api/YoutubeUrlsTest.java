package com.kuronami.musicdiscmaker.lavaplayer.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * mod 側と loader 側が共有する URL 判定 ({@link YoutubeUrls}) を固定する。
 *
 * <p>例は loader 側の {@code YoutubeTargetPredicateTest} と同じ並び。同じ URL が片側だけ
 * YouTube と判定されると、loader は開き直すのに client は諦める (またはその逆) という
 * 食い違いになる。
 */
class YoutubeUrlsTest {

    static final List<String> YOUTUBE = List.of(
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "http://youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ",
            "https://m.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://music.youtube.com/watch?v=dQw4w9WgXcQ",
            "youtube.com/watch?v=dQw4w9WgXcQ",
            "ytsearch:Rick Astley Never Gonna Give You Up",
            "ytmsearch:Rick Astley Never Gonna Give You Up");

    static final List<String> NOT_YOUTUBE = List.of(
            "https://soundcloud.com/pajlada/never-gonna-give-you-up",
            "https://example.com/song.mp3",
            "https://notyoutube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com.evil.example/watch?v=dQw4w9WgXcQ",
            "scsearch:Rick Astley Never Gonna Give You Up",
            "");

    @Test
    void youtubeUrlsAndSearchQueriesAreYoutube() {
        for (final String url : YOUTUBE) {
            assertTrue(YoutubeUrls.isYoutube(url), url);
        }
    }

    @Test
    void otherHostsAreNotYoutube() {
        for (final String url : NOT_YOUTUBE) {
            assertFalse(YoutubeUrls.isYoutube(url), url);
        }
        assertFalse(YoutubeUrls.isYoutube(null));
    }

    @Test
    void onlyTheHostCheckMatchesYoutubeHosts() {
        // 検索クエリは正規化の対象ではないので、host 判定とは分けてある。
        assertTrue(YoutubeUrls.isYoutubeHost("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertTrue(YoutubeUrls.isYoutubeHost("https://youtu.be/dQw4w9WgXcQ"));
        assertFalse(YoutubeUrls.isYoutubeHost("ytsearch:Rick Astley"));
        assertFalse(YoutubeUrls.isYoutubeHost("https://example.com/song.mp3"));
        assertTrue(YoutubeUrls.isSearchQuery("ytsearch:Rick Astley"));
        assertTrue(YoutubeUrls.isSearchQuery("ytmsearch:Rick Astley"));
        assertFalse(YoutubeUrls.isSearchQuery("scsearch:Rick Astley"));
    }
}
