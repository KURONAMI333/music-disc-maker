package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Apple Music / Deezer の単曲リンク判定と、照会応答の読み取りを、ネットワークに出ずに確かめる。
 *
 * <p>ここで固定したいのは 2 つ。
 *
 * <ol>
 * <li><b>どこへ問い合わせるか</b> — プレイヤーが入れた URL をそのまま取得先にしない
 *     (ホスト名の完全一致 + 単曲のパス形で、数字の ID だけを取り出す)</li>
 * <li><b>何を曲名・アーティストとして読むか</b> — Deezer の応答はアルバム名も {@code title} と
 *     呼ぶので、読み違えると検索語が別の曲になる</li>
 * </ol>
 */
class MusicServiceLookupTest {

    private static final String APPLE_SONG =
            "https://music.apple.com/us/song/never-gonna-give-you-up/1558533901";
    private static final String APPLE_ALBUM =
            "https://music.apple.com/us/album/never-gonna-give-you-up/1558533900?i=1558533901";
    private static final String DEEZER_TRACK = "https://www.deezer.com/en/track/3135556";

    /** iTunes Lookup API の応答 (曲 ID 照会)。 */
    private static final String APPLE_JSON = "{\"resultCount\":1,\"results\":[{\"wrapperType\":\"track\","
            + "\"kind\":\"song\",\"trackId\":1558533901,\"trackName\":\"Never Gonna Give You Up\","
            + "\"artistName\":\"Rick Astley\",\"collectionName\":\"Whenever You Need Somebody\","
            + "\"trackTimeMillis\":213000}]}";

    /** Deezer のトラック照会。アルバムも {@code title} を持つ。 */
    private static final String DEEZER_JSON = "{\"id\":3135556,\"readable\":true,"
            + "\"title\":\"Harder, Better, Faster, Stronger\",\"duration\":224,"
            + "\"artist\":{\"id\":27,\"name\":\"Daft Punk\"},"
            + "\"album\":{\"id\":302127,\"title\":\"Discovery\"},\"type\":\"track\"}";

    @Test
    void anAppleMusicSongPageIsLookedUpByItsTrackId() {
        final MusicServiceLookup.Link link = MusicServiceLookup.match(APPLE_SONG);

        assertEquals(MusicServiceLookup.Service.APPLE_MUSIC, link.service());
        // 問い合わせ先は数字の ID とリンクの国だけで組み立てる (入れた URL を混ぜない)。
        assertEquals("https://itunes.apple.com/lookup?id=1558533901&entity=song&country=us",
                link.lookupUrl());
        assertEquals(APPLE_SONG, link.pageUrl());
    }

    @Test
    void anAppleMusicAlbumPageIsLiftedToTheTrackInIt() {
        // 曲を開いた時に出る /album/…?i=<曲 ID> の形。i が付いている時だけ単曲として扱う。
        final MusicServiceLookup.Link link = MusicServiceLookup.match(APPLE_ALBUM);

        assertEquals(MusicServiceLookup.Service.APPLE_MUSIC, link.service());
        assertEquals("https://itunes.apple.com/lookup?id=1558533901&entity=song&country=us",
                link.lookupUrl());
    }

    @Test
    void anAppleMusicLinkKeepsItsCountryInEitherCase() {
        for (final String url : new String[] {
                "https://music.apple.com/jp/song/never-gonna-give-you-up/1558533901",
                "https://music.apple.com/JP/song/never-gonna-give-you-up/1558533901"}) {
            final MusicServiceLookup.Link link = MusicServiceLookup.match(url);

            assertEquals("https://itunes.apple.com/lookup?id=1558533901&entity=song&country=jp",
                    link.lookupUrl(), url);
            // 国付きで 0 件だった時の引き直し先は国コード無し。
            assertEquals("https://itunes.apple.com/lookup?id=1558533901&entity=song",
                    link.retryLookupUrl(), url);
        }
    }

    @Test
    void anAppleMusicLinkWithoutACountryIsLookedUpWithoutOne() {
        final MusicServiceLookup.Link link = MusicServiceLookup.match(
                "https://music.apple.com/song/never-gonna-give-you-up/1558533901");

        assertEquals("https://itunes.apple.com/lookup?id=1558533901&entity=song",
                link.lookupUrl());
        assertNull(link.retryLookupUrl(), "国コードが無いリンクは引き直さない");
    }

    @Test
    void aPageWithoutATrackIsNotLookedUp() {
        final String[] notTracks = {
                // アルバム全体 (曲が特定できない)
                "https://music.apple.com/us/album/never-gonna-give-you-up/1558533900",
                "https://music.apple.com/us/playlist/essentials/pl.1234",
                "https://music.apple.com/us/artist/rick-astley/123456",
                "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT",
                // Deezer も同じ。track 以外のパスは単曲ではない。
                "https://www.deezer.com/en/album/302127",
                "https://www.deezer.com/en/playlist/908622995",
                "https://www.deezer.com/en/artist/27",
                // track でも ID が数字でないものは照会先を組み立てない。
                "https://www.deezer.com/en/track/not-a-number",
                "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
        };

        for (final String url : notTracks) {
            assertNull(MusicServiceLookup.match(url), "単曲でない URL を照会しようとした: " + url);
        }
    }

    @Test
    void aLookalikeHostIsNotLookedUp() {
        final String[] lookalikes = {
                // 前方一致で通してしまうと、この 2 つで任意のサイトの HTML を読ませられる。
                "https://www.deezer.com.evil.example/track/3135556",
                "https://music.apple.com@evil.example/song/slug/1558533901",
                "https://deezer.com.evil.example/track/3135556",
                // ホスト名の一部が一致するだけ (api./open./cdn.) の別ホスト。
                "https://api.deezer.com/track/3135556",
                "https://cdn.music.apple.com/song/slug/1558533901",
                // 別ドメインの deezer.com (TLD が続く形)。
                "https://deezer.com.br/track/3135556",
        };

        for (final String url : lookalikes) {
            assertNull(MusicServiceLookup.match(url), "紛らわしいホストを照会しようとした: " + url);
        }
    }

    @Test
    void aDeezerTrackPageIsLookedUpByItsTrackId() {
        for (final String url : new String[] {
                DEEZER_TRACK,
                "https://www.deezer.com/track/3135556",
                "https://m.deezer.com/en/track/3135556",
                "http://deezer.com/track/3135556"}) {
            final MusicServiceLookup.Link link = MusicServiceLookup.match(url);

            assertEquals(MusicServiceLookup.Service.DEEZER, link.service(), url);
            assertEquals("https://api.deezer.com/track/3135556", link.lookupUrl(), url);
        }
    }

    @Test
    void theITunesAnswerIsReadAsTitleArtistAndLength() {
        final MusicServiceLookup.Meta meta =
                MusicServiceLookup.parse(MusicServiceLookup.Service.APPLE_MUSIC, APPLE_JSON);

        assertEquals("Never Gonna Give You Up", meta.title());
        assertEquals("Rick Astley", meta.artist());
        // trackTimeMillis は既に ms。
        assertEquals(213_000L, meta.durationMs());
    }

    @Test
    void theDeezerAnswerIsReadAsTitleArtistAndLength() {
        final MusicServiceLookup.Meta meta =
                MusicServiceLookup.parse(MusicServiceLookup.Service.DEEZER, DEEZER_JSON);

        // title は曲名。アルバムの title (Discovery) を曲名にしない。
        assertEquals("Harder, Better, Faster, Stronger", meta.title());
        assertEquals("Daft Punk", meta.artist());
        // Deezer の duration は秒。ms へ直さないと尺の照合 (0.5〜3.0 倍) が全部外れる。
        assertEquals(224_000L, meta.durationMs());
    }

    @Test
    void jsonEscapesAreRestoredOnce() {
        final String apple = "{\"resultCount\":1,\"results\":[{\"trackName\":\"Rock \\u0026 Roll\","
                + "\"artistName\":\"AC\\/DC \\\"Live\\\"\",\"trackTimeMillis\":1000}]}";
        final MusicServiceLookup.Meta meta =
                MusicServiceLookup.parse(MusicServiceLookup.Service.APPLE_MUSIC, apple);

        assertEquals("Rock & Roll", meta.title());
        assertEquals("AC/DC \"Live\"", meta.artist());
    }

    @Test
    void anAnswerWithoutATrackIsNotRead() {
        assertNull(MusicServiceLookup.parse(MusicServiceLookup.Service.APPLE_MUSIC,
                "{\"resultCount\":0,\"results\":[]}"));
        assertNull(MusicServiceLookup.parse(MusicServiceLookup.Service.DEEZER,
                "{\"error\":{\"type\":\"DataException\",\"message\":\"no data\"}}"));
        assertNull(MusicServiceLookup.parse(MusicServiceLookup.Service.APPLE_MUSIC, ""));
        assertNull(MusicServiceLookup.parse(MusicServiceLookup.Service.DEEZER, null));
        // 曲名の欄が空のものは「読めた」ことにしない。
        assertNull(MusicServiceLookup.parse(MusicServiceLookup.Service.DEEZER,
                "{\"title\":\"\",\"duration\":10}"));
    }

    @Test
    void theLookupIsRetriedUntilAnAnswerCanBeRead() {
        final AtomicInteger calls = new AtomicInteger();
        final List<Long> sleeps = new ArrayList<>();
        final PageMeta.Fetcher fetcher = (url, timeoutMs) -> {
            calls.incrementAndGet();
            return calls.get() == 1 ? "<html></html>" : DEEZER_JSON;
        };
        final MusicServiceLookup.Link link = MusicServiceLookup.match(DEEZER_TRACK);

        final MusicServiceLookup.Meta meta =
                MusicServiceLookup.read(link, fetcher, () -> 0L, sleeps::add);

        // 中身の読めない応答も失敗として数え、取り直す。
        assertEquals("Harder, Better, Faster, Stronger", meta.title());
        assertEquals(2, calls.get());
        assertEquals(List.of(PageMeta.RETRY_DELAY_MS), sleeps);
        // 問い合わせ先は組み立てた API の URL だけ (曲ページを読みに行かない)。
        assertTrue(link.lookupUrl().startsWith("https://api.deezer.com/track/"));
    }

    @Test
    void theLookupGivesUpAfterThreeAttempts() {
        final AtomicInteger calls = new AtomicInteger();
        final List<Long> sleeps = new ArrayList<>();
        final PageMeta.Fetcher fetcher = (url, timeoutMs) -> {
            calls.incrementAndGet();
            throw new IOException("no answer");
        };
        final MusicServiceLookup.Link link = MusicServiceLookup.match(DEEZER_TRACK);

        final MusicServiceLookup.ReadResult result =
                MusicServiceLookup.readTrack(link, fetcher, () -> 0L, sleeps::add);
        assertEquals(MusicServiceLookup.ReadStatus.UNREADABLE, result.status());
        assertNull(result.meta());
        assertEquals(PageMeta.ATTEMPTS, calls.get());
        assertEquals(List.of(PageMeta.RETRY_DELAY_MS, PageMeta.RETRY_DELAY_MS), sleeps);
    }

    @Test
    void aDefinitiveNoTrackAnswerIsNotRetried() {
        for (final String url : new String[] {
                // 国コードの無い Apple リンクは、0 件でも引き直し先が無い。
                "https://music.apple.com/song/never-gonna-give-you-up/1558533901",
                DEEZER_TRACK}) {
            final AtomicInteger calls = new AtomicInteger();
            final PageMeta.Fetcher fetcher = (target, timeoutMs) -> {
                calls.incrementAndGet();
                return target.contains("itunes.apple.com")
                        ? "{\"resultCount\":0,\"results\":[]}"
                        : "{\"error\":{\"type\":\"DataException\",\"message\":\"no data\"}}";
            };
            final MusicServiceLookup.Link link = MusicServiceLookup.match(url);

            final MusicServiceLookup.ReadResult result =
                    MusicServiceLookup.readTrack(link, fetcher, () -> 0L, ms -> {});

            assertEquals(MusicServiceLookup.ReadStatus.NO_TRACK, result.status(), url);
            assertEquals(1, calls.get(), "確定した 0 件を通信失敗として取り直した: " + url);
        }
    }

    @Test
    void aCountrySpecificLookupWithNoTrackFallsBackOnceToTheCountrylessOne() {
        final List<String> fetched = new ArrayList<>();
        final PageMeta.Fetcher fetcher = (url, timeoutMs) -> {
            fetched.add(url);
            return url.contains("country=us")
                    ? "{\"resultCount\":0,\"results\":[]}" : APPLE_JSON;
        };
        final MusicServiceLookup.Link link = MusicServiceLookup.match(APPLE_SONG);

        final MusicServiceLookup.ReadResult result =
                MusicServiceLookup.readTrack(link, fetcher, () -> 0L, ms -> {});

        assertEquals(MusicServiceLookup.ReadStatus.FOUND, result.status());
        assertEquals("Never Gonna Give You Up", result.meta().title());
        assertEquals(List.of(
                "https://itunes.apple.com/lookup?id=1558533901&entity=song&country=us",
                "https://itunes.apple.com/lookup?id=1558533901&entity=song"), fetched);
    }

    @Test
    void aCountrySpecificLookupWithNoTrackAnywhereStopsAfterTheFallback() {
        final AtomicInteger calls = new AtomicInteger();
        final PageMeta.Fetcher fetcher = (url, timeoutMs) -> {
            calls.incrementAndGet();
            return "{\"resultCount\":0,\"results\":[]}";
        };
        final MusicServiceLookup.Link link = MusicServiceLookup.match(APPLE_SONG);

        final MusicServiceLookup.ReadResult result =
                MusicServiceLookup.readTrack(link, fetcher, () -> 0L, ms -> {});

        assertEquals(MusicServiceLookup.ReadStatus.NO_TRACK, result.status());
        assertEquals(2, calls.get(), "国付きと国無しの 2 回で止まる");
    }

    @Test
    void anUnreadableAnswerStaysUnreadableAfterTheRetries() {
        final AtomicInteger calls = new AtomicInteger();
        final PageMeta.Fetcher fetcher = (url, timeoutMs) -> {
            calls.incrementAndGet();
            return "<html></html>";
        };
        final MusicServiceLookup.Link link = MusicServiceLookup.match(DEEZER_TRACK);

        final MusicServiceLookup.ReadResult result =
                MusicServiceLookup.readTrack(link, fetcher, () -> 0L, ms -> {});

        assertEquals(MusicServiceLookup.ReadStatus.UNREADABLE, result.status());
        assertEquals(PageMeta.ATTEMPTS, calls.get());
    }
}
