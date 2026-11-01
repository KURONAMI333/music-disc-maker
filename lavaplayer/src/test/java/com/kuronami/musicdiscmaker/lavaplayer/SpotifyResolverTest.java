package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/**
 * Spotify の og タグ取得を、ネットワークに出ずに確かめる。
 *
 * <p>取得関数・時計・待ちを差し替えられるようにしてあるのは、この 1 往復が失敗すると
 * URL から曲名が分からず盤を作る手が消えるため。取りこぼし (接続断・og タグの無い JS シェル)
 * は直後に引き直すと通ることが多く、回数と間隔を実測できる形で固定する。
 *
 * <p>引き直しには<b>全体の締切</b>がある ({@code META_BUDGET_MS} 12 秒)。解決スレッドは
 * 2 本しかないので、無応答のページを掴んでもここを超えて待たない。
 */
class SpotifyResolverTest {

    private static final String URL = "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT";

    /** 実際の og タグと同じ並び。description は "アーティスト · アルバム · 曲 · 年"。 */
    private static final String HTML = "<html><head>"
            + "<meta property=\"og:title\" content=\"Song Title\">"
            + "<meta property=\"og:description\" content=\"Some Artist \u00b7 Album \u00b7 Song \u00b7 2020\">"
            + "</head></html>";

    /** og タグの無い JS シェル (通常の Chrome UA で返るページ)。 */
    private static final String SHELL = "<html><head><title>Spotify</title></head></html>";

    @Test
    void aFailedFetchIsRetriedAndTheSecondPageIsUsed() {
        final AtomicInteger calls = new AtomicInteger();
        final List<Long> sleeps = new ArrayList<>();
        final SpotifyResolver.PageFetcher fetcher = (url, timeoutMs) -> {
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

    @Test
    void aPageWithoutOgTagsIsRetriedToo() {
        final AtomicInteger calls = new AtomicInteger();
        final List<Long> sleeps = new ArrayList<>();
        final SpotifyResolver.PageFetcher fetcher =
                (url, timeoutMs) -> calls.incrementAndGet() == 1 ? SHELL : HTML;

        final String[] meta = SpotifyResolver.fetchMeta(URL, fetcher, () -> 0L, sleeps::add);

        // og タグの無い応答は例外ではないが、待っても同じものが返るとは限らない。取り直す。
        assertNotNull(meta, "og タグの無い応答を引き直していない");
        assertArrayEquals(new String[] {"Song Title", "Some Artist"}, meta);
        assertEquals(2, calls.get());
        assertEquals(List.of(500L), sleeps);
    }

    @Test
    void thePageIsFetchedAtMostThreeTimes() {
        final AtomicInteger calls = new AtomicInteger();
        final List<Long> sleeps = new ArrayList<>();
        final SpotifyResolver.PageFetcher fetcher = (url, timeoutMs) -> {
            calls.incrementAndGet();
            return SHELL;
        };

        final String[] meta = SpotifyResolver.fetchMeta(URL, fetcher, () -> 0L, sleeps::add);

        assertNull(meta);
        assertEquals(3, calls.get());
        // 最後の試行の後は待たない。
        assertEquals(List.of(500L, 500L), sleeps);
    }

    @Test
    void noAttemptIsMadeAfterTheBudgetIsSpent() {
        final AtomicLong now = new AtomicLong(0L);
        final AtomicInteger calls = new AtomicInteger();
        final List<Long> sleeps = new ArrayList<>();
        final SpotifyResolver.PageFetcher fetcher = (url, timeoutMs) -> {
            calls.incrementAndGet();
            // 1 往復で全体の上限 (12 秒) を使い切る。
            now.addAndGet(20_000L);
            throw new IOException("no answer");
        };

        final String[] meta = SpotifyResolver.fetchMeta(URL, fetcher, now::get, sleeps::add);

        assertNull(meta);
        // 締切を過ぎたら引き直さない (残り 0 秒の取得を積まない)。
        assertEquals(1, calls.get());
        assertEquals(List.of(), sleeps);
    }

    @Test
    void thePerAttemptTimeoutShrinksToTheRemainingBudget() {
        final AtomicLong now = new AtomicLong(0L);
        final List<Integer> timeouts = new ArrayList<>();
        final SpotifyResolver.PageFetcher fetcher = (url, timeoutMs) -> {
            timeouts.add(timeoutMs);
            if (timeouts.size() == 1) {
                // 1 回目で 10.5 秒使う。残りは 1.5 秒しかない。
                now.addAndGet(10_500L);
                return SHELL;
            }
            return HTML;
        };

        final String[] meta = SpotifyResolver.fetchMeta(URL, fetcher, now::get, sleepMs -> { });

        assertNotNull(meta);
        // 1 回目は上限どおり 10 秒、2 回目は残りに縮む。無応答でも締切を超えて待たない。
        assertEquals(List.of(10_000, 1_500), timeouts);
        assertEquals(2, timeouts.size());
    }
}
