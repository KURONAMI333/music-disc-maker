package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Spotify の og タグ取得を、ネットワークに出ずに確かめる。
 *
 * <p>取得関数・時計・待ちを差し替えられるようにしてあるのは、この 1 往復が失敗すると
 * URL から曲名が分からず盤を作る手が消えるため。取りこぼし (接続断・og タグの無い JS シェル)
 * は直後に引き直すと通ることが多く、回数と間隔を実測できる形で固定する。
 */
class SpotifyResolverTest {

    private static final String URL = "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT";

    /** 実際の og タグと同じ並び。description は "アーティスト · アルバム · 曲 · 年"。 */
    private static final String HTML = "<html><head>"
            + "<meta property=\"og:title\" content=\"Song Title\">"
            + "<meta property=\"og:description\" content=\"Some Artist \u00b7 Album \u00b7 Song \u00b7 2020\">"
            + "</head></html>";

    @Test
    void aFailedFetchIsRetriedAndTheSecondPageIsUsed() {
        final AtomicInteger calls = new AtomicInteger();
        final List<Long> sleeps = new ArrayList<>();
        final SpotifyResolver.PageFetcher fetcher = url -> {
            if (calls.incrementAndGet() == 1) {
                throw new IOException("connection reset");
            }
            return HTML;
        };

        final String[] meta = SpotifyResolver.fetchMeta(URL, fetcher, () -> 0L, sleeps::add);

        assertNotNull(meta, "2 回目の HTML から og タグを読めていない");
        assertArrayEquals(new String[] {"Song Title", "Some Artist"}, meta);
        // 1 回目で諦めず、1 度だけ間隔を置いて引き直している。
        assertEquals(2, calls.get());
        assertEquals(List.of(500L), sleeps);
    }
}
