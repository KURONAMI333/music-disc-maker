package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInput;
import java.io.DataOutput;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureClassifier;
import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.IMusicLoader.TrackHint;
import com.kuronami.musicdiscmaker.lavaplayer.api.OpenStreamResult;
import com.kuronami.musicdiscmaker.lavaplayer.api.ResolveException;
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
 * 代替ソース探し ({@code MusicLoaderImpl#substitute} と {@code #openStreamDetailed}) を、
 * 偽の source manager だけで確かめる。
 *
 * <p>本物の source manager を 1 つも登録しないのが要点。登録すると
 * {@code HttpAudioSourceManager} が watch URL を横取りし、「YouTube が弾いた」形を作れない。
 * ネットワークにも一切出ない (watch ページを取りに行かないことの確認にもなる —
 * 取りに行く実装ならこのテストは失敗するか、外部への往復で遅くなる)。
 *
 * <h2>検索の失敗と 0 件を分けている理由</h2>
 * 「検索が走って 0 件」は<b>探したが無かった</b>、「検索が答えなかった」は<b>探せていない</b>。
 * 前者は元の失敗を確定させてよく、後者は確定させてはいけない (一時的な bot 判定を
 * 「代わりも無い」に潰すと、時間を置けば直る盤が鳴らなくなる)。
 */
class PlaybackSubstituteTest {

    private static final String WATCH = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    private static final String ALTERNATE = "https://soundcloud.com/pajlada/never-gonna-give-you-up";
    private static final String SOUNDCLOUD = "https://soundcloud.com/label/song";
    private static final String SOUNDCLOUD_ALTERNATE = "https://www.youtube.com/watch?v=aaaaaaaaaaa";
    private static final String WATCH_SEARCH = "scsearch:Rick Astley Never Gonna Give You Up";
    private static final String BANDCAMP_SEARCH = "bcsearch:Rick Astley Never Gonna Give You Up";
    private static final TrackHint HINT =
            new TrackHint("Never Gonna Give You Up", "Rick Astley", 213_000L);

    @Test
    void playbackFallsBackToAnotherSourceWhenYoutubeRefusesTheWatchPage() {
        final FakeSource sources = new FakeSource(identifier -> {
            if (identifier.equals(WATCH)) {
                return refused();
            }
            if (identifier.startsWith("scsearch:")) {
                return track("Rick Astley - Never Gonna Give You Up (Official Video)", "Rick Astley",
                        213_000L, ALTERNATE);
            }
            if (identifier.equals(ALTERNATE)) {
                return track("Never Gonna Give You Up", "Rick Astley", 213_000L, ALTERNATE);
            }
            return AudioReference.NO_TRACK;
        });

        final OpenStreamResult result = loader(sources).openStreamDetailed(WATCH, 0L, HINT);

        assertTrue(result.isOk(), "代替ソースが開けていない: " + result.reason() + " " + result.detail());
        assertNotNull(result.source());
        // 盤の曲名と尺で 1 回だけ検索し、見つけた先を開いている。watch ページへは行かない。
        assertEquals(List.of(WATCH, WATCH_SEARCH, ALTERNATE), sources.requests);
    }

    @Test
    void playbackKeepsTheBotCheckWhenTheSearchHasNoCopy() {
        final FakeSource sources = new FakeSource(identifier -> {
            if (identifier.equals(WATCH)) {
                return refused();
            }
            return AudioReference.NO_TRACK;
        });

        final OpenStreamResult result = loader(sources).openStreamDetailed(WATCH, 0L, HINT);

        // 検索は答えたが 0 件。元の失敗 (bot 判定) が一時的なものなので、そちらを返して
        // client 側のやり直しに乗せる。「代わりも無い」で確定させない。
        assertEquals(FailureReason.BOT_CHECK, result.reason());
        // 探した事実が残る (接頭辞を 2 つとも回っている)。
        assertEquals(List.of(WATCH, WATCH_SEARCH, BANDCAMP_SEARCH), sources.requests);
    }

    @Test
    void playbackKeepsTheBotCheckWhenTheSearchItselfFails() {
        final FakeSource sources = new FakeSource(identifier -> {
            if (identifier.equals(WATCH)) {
                return refused();
            }
            if (identifier.startsWith("scsearch:") || identifier.startsWith("bcsearch:")) {
                throw new FriendlyException("search is unavailable",
                        FriendlyException.Severity.COMMON, null);
            }
            return AudioReference.NO_TRACK;
        });

        final OpenStreamResult result = loader(sources).openStreamDetailed(WATCH, 0L, HINT);

        // 検索が答えていない = 探せていない。0 件と同じ扱いにしてはいけない。
        assertEquals(FailureReason.BOT_CHECK, result.reason());
        assertEquals(List.of(WATCH, WATCH_SEARCH, BANDCAMP_SEARCH), sources.requests);
    }

    @Test
    void aDirectHttpLinkRefusedWith403IsNotSearched() {
        final String direct = "https://example.com/song.mp3";
        final FakeSource sources = new FakeSource(identifier -> {
            if (identifier.equals(direct)) {
                throw new FriendlyException("Status code 403", FriendlyException.Severity.COMMON, null);
            }
            return track("Never Gonna Give You Up", "Rick Astley", 213_000L, ALTERNATE);
        });

        final OpenStreamResult result = loader(sources).openStreamDetailed(direct, 0L, HINT);

        // 403 は BOT_CHECK に分類されるが、YouTube の URL ではないので代替ソースを探さない。
        assertEquals(FailureReason.BOT_CHECK, result.reason());
        assertEquals(List.of(direct), sources.requests);
    }

    @Test
    void theFoundAlternateSourceIsReusedOnTheNextPlayback() {
        final FakeSource sources = substitutableSource();
        final MusicLoaderImpl loader = loader(sources);

        assertTrue(loader.openStreamDetailed(WATCH, 0L, HINT).isOk());
        assertTrue(loader.openStreamDetailed(WATCH, 0L, HINT).isOk());

        // 2 回目は覚えている代替先を直接開く。検索も元の URL も試さない。
        assertEquals(List.of(WATCH, WATCH_SEARCH, ALTERNATE, ALTERNATE), sources.requests);
    }

    @Test
    void theRememberedAlternateSourceIsForgottenAfterItsTtl() {
        final AtomicLong now = new AtomicLong(1_000_000L);
        final FakeSource sources = substitutableSource();
        final MusicLoaderImpl loader = loader(sources, now::get);

        assertTrue(loader.openStreamDetailed(WATCH, 0L, HINT).isOk());
        // 覚えている間は元の URL を試さない。
        now.addAndGet(MusicLoaderImpl.PLAYBACK_SUBSTITUTE_TTL_MS - 1L);
        assertTrue(loader.openStreamDetailed(WATCH, 0L, HINT).isOk());
        assertEquals(List.of(WATCH, WATCH_SEARCH, ALTERNATE, ALTERNATE), sources.requests);

        // 期限を過ぎたら元の URL から試し直し、見つけた先を開き直す (覚えも更新する)。
        now.addAndGet(1L);
        assertTrue(loader.openStreamDetailed(WATCH, 0L, HINT).isOk());
        assertEquals(List.of(WATCH, WATCH_SEARCH, ALTERNATE, ALTERNATE, WATCH, WATCH_SEARCH, ALTERNATE),
                sources.requests);
    }

    @Test
    void aSoundCloudPreviewIsReplacedByAnotherSourceUsingTheTrackItReported() {
        final FakeSource sources = new FakeSource(identifier -> {
            if (identifier.equals(SOUNDCLOUD)) {
                throw new UnplayableSoundCloudTrack(FailureClassifier.PREVIEW_MARKER,
                        "Song Title", "Some Artist", 200_000L);
            }
            if (identifier.startsWith("ytsearch:")) {
                return track("Song Title", "Some Artist", 200_000L, SOUNDCLOUD_ALTERNATE);
            }
            if (identifier.equals(SOUNDCLOUD_ALTERNATE)) {
                return track("Song Title", "Some Artist", 200_000L, SOUNDCLOUD_ALTERNATE);
            }
            return AudioReference.NO_TRACK;
        });

        final OpenStreamResult result = loader(sources).openStreamDetailed(SOUNDCLOUD, 0L,
                new TrackHint("", "", 0L));

        assertTrue(result.isOk(), "試聴版の代わりが開けていない: " + result.reason() + " " + result.detail());
        // 盤のメタが空でも、失敗そのものが持っている曲情報で探せている。
        assertEquals(List.of(SOUNDCLOUD, "ytsearch:Some Artist Song Title", SOUNDCLOUD_ALTERNATE),
                sources.requests);
    }

    @Test
    void creatingADiscFailsWithNoAlternativeFoundWhenTheSearchHasNoCopy() {
        final FakeSource sources = soundCloudPreviewWithSearch(identifier -> AudioReference.NO_TRACK);

        final ResolveException ex = assertThrows(ResolveException.class,
                () -> loader(sources).resolve(SOUNDCLOUD));

        // 制作時は「探したが無かった」を利用者へ伝える (焼く前に判断できるように)。
        assertEquals(FailureReason.NO_ALTERNATIVE_FOUND, ex.reason());
        assertEquals(List.of(SOUNDCLOUD, "ytsearch:Some Artist Song Title",
                "scsearch:Some Artist Song Title"), sources.requests);
    }

    @Test
    void creatingADiscKeepsTheOriginalReasonWhenTheSearchItselfFails() {
        final FakeSource sources = soundCloudPreviewWithSearch(identifier -> {
            throw new FriendlyException("search is unavailable", FriendlyException.Severity.COMMON, null);
        });

        final ResolveException ex = assertThrows(ResolveException.class,
                () -> loader(sources).resolve(SOUNDCLOUD));

        // 探せていないので「代わりも無い」とは言えない。元の理由のまま失敗させる。
        assertEquals(FailureReason.PREVIEW_ONLY, ex.reason());
        assertEquals(List.of(SOUNDCLOUD, "ytsearch:Some Artist Song Title",
                "scsearch:Some Artist Song Title"), sources.requests);
    }

    @Test
    void anAlternateSourceOutsideTheAllowedHostsIsNotOpened() {
        final String internal = "http://169.254.169.254/latest/meta-data/";
        final FakeSource sources = new FakeSource(identifier -> {
            if (identifier.equals(WATCH)) {
                return refused();
            }
            if (identifier.startsWith("scsearch:")) {
                return track("Rick Astley - Never Gonna Give You Up (Official Video)", "Rick Astley",
                        213_000L, internal);
            }
            return AudioReference.NO_TRACK;
        });

        final AudioPlayerManager apm = new DefaultAudioPlayerManager();
        final MusicLoaderImpl loader = new MusicLoaderImpl(apm, null, YoutubeSession.NONE,
                System::currentTimeMillis);
        apm.registerSourceManager(sources);
        // 本物の入口 (LoaderHolder) は UrlGuard を差し込む。ここでは内部アドレスだけを拒む。
        loader.setUrlGate(url -> !url.contains("169.254.169.254"));

        final OpenStreamResult result = loader.openStreamDetailed(WATCH, 0L, HINT);

        assertEquals(FailureReason.BLOCKED_URL, result.reason());
        // 内部アドレスは開かない (偽 manager へも渡らない)。覚えにも残さない。
        assertFalse(sources.requests.contains(internal));
        assertEquals(List.of(WATCH, WATCH_SEARCH), sources.requests);
    }

    @Test
    void anAlternateSourceIsNotOpenedWhenNoGateIsInstalled() {
        final FakeSource sources = substitutableSource();
        final AudioPlayerManager apm = new DefaultAudioPlayerManager();
        final MusicLoaderImpl loader = new MusicLoaderImpl(apm, null, YoutubeSession.NONE,
                System::currentTimeMillis);
        apm.registerSourceManager(sources);

        final OpenStreamResult result = loader.openStreamDetailed(WATCH, 0L, HINT);

        // 検査できない URL へは接続しない (fail-closed)。盤の URL は mod 側で既に検査済み。
        assertEquals(FailureReason.BLOCKED_URL, result.reason());
        assertFalse(sources.requests.contains(ALTERNATE));
    }

    /** 元の URL が弾かれ、検索で 1 件見つかり、その先が鳴る source。 */
    private static FakeSource substitutableSource() {
        return new FakeSource(identifier -> {
            if (identifier.equals(WATCH)) {
                return refused();
            }
            if (identifier.startsWith("scsearch:")) {
                return track("Rick Astley - Never Gonna Give You Up (Official Video)", "Rick Astley",
                        213_000L, ALTERNATE);
            }
            if (identifier.equals(ALTERNATE)) {
                return track("Never Gonna Give You Up", "Rick Astley", 213_000L, ALTERNATE);
            }
            return AudioReference.NO_TRACK;
        });
    }

    /** SoundCloud の試聴版が鳴らず、検索 ({@code ytsearch:} / {@code scsearch:}) の応答を差し替える source。 */
    private static FakeSource soundCloudPreviewWithSearch(Function<String, AudioItem> search) {
        return new FakeSource(identifier -> {
            if (identifier.equals(SOUNDCLOUD)) {
                throw new UnplayableSoundCloudTrack(FailureClassifier.PREVIEW_MARKER,
                        "Song Title", "Some Artist", 200_000L);
            }
            if (identifier.startsWith("ytsearch:") || identifier.startsWith("scsearch:")) {
                return search.apply(identifier);
            }
            return AudioReference.NO_TRACK;
        });
    }

    /** 本物の source manager を 1 つも登録しない loader に、偽の manager を 1 つだけ入れる。 */
    private static MusicLoaderImpl loader(FakeSource sources) {
        return loader(sources, System::currentTimeMillis);
    }

    private static MusicLoaderImpl loader(FakeSource sources, LongSupplier clockMs) {
        final AudioPlayerManager apm = new DefaultAudioPlayerManager();
        final MusicLoaderImpl loader = new MusicLoaderImpl(apm, null, YoutubeSession.NONE, clockMs);
        apm.registerSourceManager(sources);
        // 本物の入口 (LoaderHolder) は UrlGuard を差し込む。ここでは URL の中身を問わず通す。
        loader.setUrlGate(url -> true);
        return loader;
    }

    /**
     * 「YouTube が弾いた」を表す応答。{@code FriendlyException} を投げると
     * {@code DefaultAudioPlayerManager} が {@code loadFailed} へ回し、分類器が
     * {@link FailureReason#BOT_CHECK} にする (本物の youtube-source と同じ形)。
     */
    private static AudioItem refused() {
        throw new FriendlyException("This video requires login.", FriendlyException.Severity.COMMON, null);
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
