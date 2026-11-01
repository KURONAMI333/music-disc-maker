package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.DataInput;
import java.io.DataOutput;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.IMusicLoader.TrackHint;
import com.kuronami.musicdiscmaker.lavaplayer.api.OpenStreamResult;
import com.kuronami.musicdiscmaker.lavaplayer.api.ResolveException;
import com.kuronami.musicdiscmaker.lavaplayer.api.TrackInfo;
import com.kuronami.musicdiscmaker.lavaplayer.api.UrlGate;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

/**
 * Apple Music / Deezer の単曲リンクから盤を作る経路を、偽の source manager と偽の応答で確かめる。
 *
 * <p>見たいのは「URL → 曲名 → YouTube 検索 → 盤」の<b>つながり全体</b>で、間の検索語の組み立てや
 * 表示と URI の持ち替えは、片側だけを試験すると壊れても気づけない。ネットワークには一切出ない
 * (取得口を差し替えてあるので、実装が曲ページを取りに行く形に戻ればこの試験は失敗する)。
 */
class MusicServiceResolveTest {

    private static final String APPLE_SONG =
            "https://music.apple.com/us/song/never-gonna-give-you-up/1558533901";
    private static final String APPLE_LOOKUP =
            "https://itunes.apple.com/lookup?id=1558533901&entity=song&country=us";
    private static final String APPLE_LOOKUP_WITHOUT_COUNTRY =
            "https://itunes.apple.com/lookup?id=1558533901&entity=song";
    private static final String DEEZER_TRACK = "https://www.deezer.com/en/track/3135556";
    private static final String DEEZER_LOOKUP = "https://api.deezer.com/track/3135556";
    private static final String FOUND = "https://www.youtube.com/watch?v=aaaaaaaaaaa";
    private static final String FOUND_LONG = "https://www.youtube.com/watch?v=bbbbbbbbbbb";
    private static final String YT_SEARCH = "ytsearch:Rick Astley Never Gonna Give You Up";
    private static final String SC_SEARCH = "scsearch:Rick Astley Never Gonna Give You Up";
    private static final String BC_SEARCH = "bcsearch:Rick Astley Never Gonna Give You Up";

    private static final String APPLE_JSON = "{\"resultCount\":1,\"results\":[{\"wrapperType\":\"track\","
            + "\"kind\":\"song\",\"trackId\":1558533901,\"trackName\":\"Never Gonna Give You Up\","
            + "\"artistName\":\"Rick Astley\",\"trackTimeMillis\":213000}]}";
    private static final String DEEZER_JSON = "{\"id\":3135556,\"readable\":true,"
            + "\"title\":\"Harder, Better, Faster, Stronger\",\"duration\":224,"
            + "\"artist\":{\"id\":27,\"name\":\"Daft Punk\"},"
            + "\"album\":{\"id\":302127,\"title\":\"Discovery\"},\"type\":\"track\"}";

    @Test
    void anAppleMusicLinkBecomesADiscOfTheYoutubeTrackItFound() {
        final FakeSource sources = new FakeSource(identifier -> identifier.equals(YT_SEARCH)
                ? track("Rick Astley - Never Gonna Give You Up (Official Video)", "Rick Astley",
                        213_000L, FOUND)
                : AudioReference.NO_TRACK);
        final List<String> fetched = new ArrayList<>();

        final TrackInfo info = loader(sources, recording(fetched, APPLE_JSON)).resolve(APPLE_SONG);

        // 表示は元サービスの曲名・アーティスト、盤に焼く URL は見つけた YouTube のもの。
        assertEquals("Never Gonna Give You Up", info.title());
        assertEquals("Rick Astley", info.author());
        assertEquals(213_000L, info.durationMs());
        assertEquals(FOUND, info.uri());
        // 読みに行ったのは照会先だけ。曲ページ (JS で組み立てられる HTML) は読まない。
        assertEquals(List.of(APPLE_LOOKUP), fetched);
        assertEquals(List.of(YT_SEARCH), sources.requests);
    }

    @Test
    void aDeezerLinkBecomesADiscOfTheYoutubeTrackItFound() {
        final FakeSource sources = new FakeSource(identifier -> identifier
                .equals("ytsearch:Daft Punk Harder, Better, Faster, Stronger")
                        ? track("Daft Punk - Harder, Better, Faster, Stronger", "Daft Punk",
                                226_000L, FOUND)
                        : AudioReference.NO_TRACK);
        final List<String> fetched = new ArrayList<>();

        final TrackInfo info = loader(sources, recording(fetched, DEEZER_JSON)).resolve(DEEZER_TRACK);

        assertEquals("Harder, Better, Faster, Stronger", info.title());
        assertEquals("Daft Punk", info.author());
        assertEquals(FOUND, info.uri());
        assertEquals(List.of(DEEZER_LOOKUP), fetched);
        assertEquals(List.of("ytsearch:Daft Punk Harder, Better, Faster, Stronger"),
                sources.requests);
    }

    @Test
    void anAlbumPageStaysUnsupportedAndIsNotFetched() {
        final List<String> fetched = new ArrayList<>();

        final ResolveException ex = assertThrows(ResolveException.class,
                () -> loader(new FakeSource(identifier -> AudioReference.NO_TRACK),
                        recording(fetched, APPLE_JSON))
                        .resolve("https://music.apple.com/us/album/never-gonna-give-you-up/1558533900"));

        assertEquals(FailureReason.UNSUPPORTED_URL, ex.reason());
        assertEquals(List.of(), fetched);
    }

    @Test
    void aLookalikeHostIsNotFetched() {
        final List<String> fetched = new ArrayList<>();

        final ResolveException ex = assertThrows(ResolveException.class,
                () -> loader(new FakeSource(identifier -> AudioReference.NO_TRACK),
                        recording(fetched, DEEZER_JSON))
                        .resolve("https://www.deezer.com.evil.example/track/3135556"));

        assertEquals(FailureReason.UNSUPPORTED_URL, ex.reason());
        assertEquals(List.of(), fetched);
    }

    @Test
    void aLookupTheUrlGateRefusesIsNotFetched() {
        final List<String> fetched = new ArrayList<>();
        final FakeSource sources = new FakeSource(identifier -> AudioReference.NO_TRACK);

        final ResolveException ex = assertThrows(ResolveException.class,
                () -> loader(sources, recording(fetched, APPLE_JSON), url -> false)
                        .resolve(APPLE_SONG));

        // 検査できない URL へは接続しない (loader は隔離 classloader なので UrlGuard はこの口経由)。
        assertEquals(FailureReason.BLOCKED_URL, ex.reason());
        assertEquals(List.of(), fetched);
        assertEquals(List.of(), sources.requests);
    }

    @Test
    void anUnreadableAnswerIsReportedAsAConnectionProblem() {
        final List<String> fetched = new ArrayList<>();
        final PageMeta.Fetcher fetcher = (url, timeoutMs) -> {
            fetched.add(url);
            throw new java.io.IOException("no answer");
        };

        final ResolveException ex = assertThrows(ResolveException.class,
                () -> loader(new FakeSource(identifier -> AudioReference.NO_TRACK), fetcher)
                        .resolve(APPLE_SONG));

        // リンクは正しいが相手に届かなかった。URL が対応外 (UNSUPPORTED_URL) とは別物。
        assertEquals(FailureReason.CONNECTION_FAILED, ex.reason());
        assertEquals(List.of(APPLE_LOOKUP, APPLE_LOOKUP, APPLE_LOOKUP), fetched);
    }

    @Test
    void anAnswerThatSaysTheTrackDoesNotExistIsNoAlternative() {
        final List<String> fetched = new ArrayList<>();
        final PageMeta.Fetcher fetcher = (url, timeoutMs) -> {
            fetched.add(url);
            return "{\"error\":{\"type\":\"DataException\",\"message\":\"no data\"}}";
        };

        final ResolveException ex = assertThrows(ResolveException.class,
                () -> loader(new FakeSource(identifier -> AudioReference.NO_TRACK), fetcher)
                        .resolve(DEEZER_TRACK));

        // 通信は成功していて、相手が「その曲は無い」と確定して答えた。取り直さない。
        assertEquals(FailureReason.NO_ALTERNATIVE_FOUND, ex.reason());
        assertEquals(List.of(DEEZER_LOOKUP), fetched);
    }

    @Test
    void aCountrySpecificAppleLookupFallsBackOnceToTheCountrylessOne() {
        final FakeSource sources = new FakeSource(identifier -> identifier.equals(YT_SEARCH)
                ? track("Rick Astley - Never Gonna Give You Up", "Rick Astley", 213_000L, FOUND)
                : AudioReference.NO_TRACK);
        final List<String> fetched = new ArrayList<>();
        final PageMeta.Fetcher fetcher = (url, timeoutMs) -> {
            fetched.add(url);
            return url.contains("country=us")
                    ? "{\"resultCount\":0,\"results\":[]}" : APPLE_JSON;
        };

        final TrackInfo info = loader(sources, fetcher).resolve(APPLE_SONG);

        assertEquals("Never Gonna Give You Up", info.title());
        assertEquals(List.of(APPLE_LOOKUP, APPLE_LOOKUP_WITHOUT_COUNTRY), fetched);
    }

    @Test
    void aFirstHitOfADifferentLengthIsNotUsed() {
        final FakeSource sources = new FakeSource(identifier -> {
            if (identifier.equals(YT_SEARCH)) {
                // 試聴版・抜粋を掴んだ形 (元 213 秒に対して 60 秒)。
                return track("Never Gonna Give You Up", "Rick Astley", 60_000L, FOUND);
            }
            return AudioReference.NO_TRACK;
        });

        final ResolveException ex = assertThrows(ResolveException.class,
                () -> loader(sources, recording(new ArrayList<>(), APPLE_JSON)).resolve(APPLE_SONG));

        // 尺の合う代わりも無い =「探したが無かった」。違う長さの動画を焼かない。
        assertEquals(FailureReason.NO_ALTERNATIVE_FOUND, ex.reason());
        // 同じ検索をもう一度 (2 件目以降の別動画) → 別の配信元、の順に探し直している。
        assertEquals(List.of(YT_SEARCH, YT_SEARCH, SC_SEARCH, BC_SEARCH), sources.requests);
    }

    @Test
    void aSearchThatFailsIsReportedAsAConnectionProblem() {
        // YouTube 検索は 0 件、代わりの SoundCloud / Bandcamp 検索はどちらも答えない。
        final FakeSource sources = new FakeSource(identifier -> {
            if (identifier.equals(YT_SEARCH)) {
                return AudioReference.NO_TRACK;
            }
            throw new FriendlyException("search is blocked",
                    FriendlyException.Severity.COMMON, new RuntimeException("blocked"));
        });

        final ResolveException ex = assertThrows(ResolveException.class,
                () -> loader(sources, recording(new ArrayList<>(), APPLE_JSON)).resolve(APPLE_SONG));

        // 「探せていない」ので、URL が対応外 (UNSUPPORTED_URL) ではなく接続の失敗として返す。
        assertEquals(FailureReason.CONNECTION_FAILED, ex.reason());
        assertEquals(List.of(YT_SEARCH, SC_SEARCH, BC_SEARCH), sources.requests);
    }

    @Test
    void runningOutOfSearchBudgetIsAConnectionProblem() {
        final AtomicBoolean expired = new AtomicBoolean();
        final FakeSource sources = new FakeSource(identifier -> {
            // 最初の代替検索が走った時点で締切を過ぎたことにする。
            if (identifier.startsWith("scsearch:")) {
                expired.set(true);
            }
            return AudioReference.NO_TRACK;
        });
        final java.util.function.LongSupplier clock =
                () -> expired.get() ? 3_600_000L : 0L;

        final ResolveException ex = assertThrows(ResolveException.class,
                () -> loaderAt(sources, recording(new ArrayList<>(), APPLE_JSON), clock)
                        .resolve(APPLE_SONG));

        // 予算切れは「探し切れていない」ので、代わりが無い (NO_ALTERNATIVE_FOUND) とは言わない。
        assertEquals(FailureReason.CONNECTION_FAILED, ex.reason());
        assertEquals(List.of(YT_SEARCH, SC_SEARCH), sources.requests);
    }

    @Test
    void aFailedResubstituteSearchIsAlsoAConnectionProblem() {
        final AtomicInteger youtubeSearches = new AtomicInteger();
        final FakeSource sources = new FakeSource(identifier -> {
            if (identifier.equals(YT_SEARCH) && youtubeSearches.incrementAndGet() == 1) {
                // 先頭は試聴版。2 回目以降の検索は答えない。
                return track("Never Gonna Give You Up", "Rick Astley", 60_000L, FOUND);
            }
            throw new FriendlyException("search is blocked",
                    FriendlyException.Severity.COMMON, new RuntimeException("blocked"));
        });

        final ResolveException ex = assertThrows(ResolveException.class,
                () -> loader(sources, recording(new ArrayList<>(), APPLE_JSON)).resolve(APPLE_SONG));

        assertEquals(FailureReason.CONNECTION_FAILED, ex.reason());
        assertEquals(List.of(YT_SEARCH, YT_SEARCH, SC_SEARCH, BC_SEARCH), sources.requests);
    }

    @Test
    void aLaterCandidateOfTheRightLengthIsUsed() {
        final AtomicInteger searches = new AtomicInteger();
        final FakeSource sources = new FakeSource(identifier -> {
            if (identifier.equals(YT_SEARCH)) {
                // 1 回目は試聴版、2 回目 (同じ検索の 2 件目以降) に全長の動画が出る形。
                return searches.incrementAndGet() == 1
                        ? track("Never Gonna Give You Up", "Rick Astley", 60_000L, FOUND)
                        : track("Rick Astley - Never Gonna Give You Up", "Rick Astley", 213_000L,
                                FOUND_LONG);
            }
            return AudioReference.NO_TRACK;
        });

        final TrackInfo info =
                loader(sources, recording(new ArrayList<>(), APPLE_JSON)).resolve(APPLE_SONG);

        // 尺の合う別動画を採り、表示は元サービスのまま。別の配信元までは降りない。
        assertEquals("Never Gonna Give You Up", info.title());
        assertEquals(FOUND_LONG, info.uri());
        assertEquals(List.of(YT_SEARCH, YT_SEARCH), sources.requests);
    }

    @Test
    void playbackDoesNotReadTheServicePage() {
        final List<String> fetched = new ArrayList<>();
        final FakeSource sources = new FakeSource(identifier -> AudioReference.NO_TRACK);

        final OpenStreamResult result = loader(sources, recording(fetched, APPLE_JSON))
                .openStreamDetailed(APPLE_SONG, 0L,
                        new TrackHint("Never Gonna Give You Up", "Rick Astley", 213_000L));

        // 再生は盤の URL (YouTube) で行う。制作時に 1 回だけ解決し、再生のたびに読み直さない。
        assertFalse(result.isOk());
        assertEquals(List.of(), fetched);
    }

    @Test
    void aYouTubeMusicWatchLinkIsLoadedAsThePlainWatchUrl() {
        final String musicUrl =
                "https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=RDdQw4w9WgXcQ&start_radio=1";
        final FakeSource sources = new FakeSource(identifier -> track(
                "Rick Astley - Never Gonna Give You Up", "Rick Astley", 213_000L, identifier));
        final List<String> fetched = new ArrayList<>();

        final TrackInfo info = loader(sources, recording(fetched, "")).resolve(musicUrl);

        // YouTube Music の単曲リンクは既存の正規化で通る (プレイリスト指定は捨てる)。
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", info.uri());
        assertEquals(List.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ"), sources.requests);
        assertEquals(List.of(), fetched);
    }

    /** 取得口。呼ばれた URL を記録し、決まった応答を返す。 */
    private static PageMeta.Fetcher recording(List<String> fetched, String body) {
        return (url, timeoutMs) -> {
            fetched.add(url);
            return body;
        };
    }

    private static MusicLoaderImpl loader(FakeSource sources, PageMeta.Fetcher fetcher) {
        return loader(sources, fetcher, url -> true);
    }

    private static MusicLoaderImpl loaderAt(FakeSource sources, PageMeta.Fetcher fetcher,
            java.util.function.LongSupplier clock) {
        final AudioPlayerManager apm = new DefaultAudioPlayerManager();
        final MusicLoaderImpl loader = new MusicLoaderImpl(apm, null, YoutubeSession.NONE, clock,
                fetcher);
        apm.registerSourceManager(sources);
        loader.setUrlGate(url -> true);
        return loader;
    }

    private static MusicLoaderImpl loader(FakeSource sources, PageMeta.Fetcher fetcher,
            UrlGate gate) {
        final AudioPlayerManager apm = new DefaultAudioPlayerManager();
        final MusicLoaderImpl loader = new MusicLoaderImpl(apm, null, YoutubeSession.NONE,
                System::currentTimeMillis, fetcher);
        apm.registerSourceManager(sources);
        // 本物の入口 (LoaderHolder) は UrlGuard を差し込む。ここでは URL の中身を問わず通す。
        loader.setUrlGate(gate);
        return loader;
    }

    private static AudioTrack track(String title, String author, long lengthMs, String uri) {
        return new FakeTrack(new AudioTrackInfo(title, author, lengthMs, uri, false, uri));
    }

    /** 呼ばれた identifier を順に記録し、応答をテスト側の関数へ委ねる manager。 */
    private static final class FakeSource implements AudioSourceManager {

        private final Function<String, AudioItem> handler;
        final List<String> requests = new CopyOnWriteArrayList<>();

        FakeSource(Function<String, AudioItem> handler) {
            this.handler = handler;
        }

        @Override
        public String getSourceName() {
            return "fake";
        }

        @Override
        public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
            requests.add(reference.identifier);
            return handler.apply(reference.identifier);
        }

        @Override
        public boolean isTrackEncodable(AudioTrack track) {
            return false;
        }

        @Override
        public void encodeTrack(AudioTrack track, DataOutput output) {
            throw new UnsupportedOperationException("not encodable");
        }

        @Override
        public AudioTrack decodeTrack(AudioTrackInfo info, DataInput input) {
            throw new UnsupportedOperationException("not decodable");
        }

        @Override
        public void shutdown() {
        }
    }

    /** 音を出さないトラック。開けたかどうかだけを見るテストなので、再生の中身は要らない。 */
    private static final class FakeTrack extends BaseAudioTrack {

        FakeTrack(AudioTrackInfo info) {
            super(info);
        }

        @Override
        public void process(LocalAudioTrackExecutor executor) {
        }

        @Override
        protected AudioTrack makeShallowClone() {
            return new FakeTrack(trackInfo);
        }

        @Override
        public AudioSourceManager getSourceManager() {
            return null;
        }
    }
}
