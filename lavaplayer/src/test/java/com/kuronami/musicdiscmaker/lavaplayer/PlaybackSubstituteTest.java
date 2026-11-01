package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInput;
import java.io.DataOutput;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureClassifier;
import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.IMusicLoader.TrackHint;
import com.kuronami.musicdiscmaker.lavaplayer.api.OpenStreamResult;
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
 * 再生時に元の URL が鳴らせなかった時の代替ソース探し ({@code MusicLoaderImpl#openStreamDetailed}
 * の 3 引数版) を、偽の source manager だけで確かめる。
 *
 * <p>本物の source manager を 1 つも登録しないのが要点。登録すると
 * {@code HttpAudioSourceManager} が watch URL を横取りし、「YouTube が弾いた」形を作れない。
 * ネットワークにも一切出ない (watch ページを取りに行かないことの確認にもなる —
 * 取りに行く実装ならこのテストは失敗するか、外部への往復で遅くなる)。
 */
class PlaybackSubstituteTest {

    private static final String WATCH = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    private static final String ALTERNATE = "https://soundcloud.com/pajlada/never-gonna-give-you-up";
    private static final String SOUNDCLOUD = "https://soundcloud.com/label/song";
    private static final String SOUNDCLOUD_ALTERNATE = "https://www.youtube.com/watch?v=aaaaaaaaaaa";
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
        assertEquals(List.of(WATCH, "scsearch:Rick Astley Never Gonna Give You Up", ALTERNATE),
                sources.requests);
    }

    @Test
    void playbackFailsWithNoAlternativeFoundWhenTheSearchHasNoCopy() {
        final FakeSource sources = new FakeSource(identifier -> {
            if (identifier.equals(WATCH)) {
                return refused();
            }
            return AudioReference.NO_TRACK;
        });

        final OpenStreamResult result = loader(sources).openStreamDetailed(WATCH, 0L, HINT);

        assertEquals(FailureReason.NO_ALTERNATIVE_FOUND, result.reason());
        // 探した事実が残る (接頭辞を 2 つとも回っている)。
        assertEquals(List.of(WATCH, "scsearch:Rick Astley Never Gonna Give You Up",
                "bcsearch:Rick Astley Never Gonna Give You Up"), sources.requests);
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
        final MusicLoaderImpl loader = loader(sources);

        assertTrue(loader.openStreamDetailed(WATCH, 0L, HINT).isOk());
        assertTrue(loader.openStreamDetailed(WATCH, 0L, HINT).isOk());

        // 2 回目は覚えている代替先を直接開く。検索も元の URL も試さない。
        assertEquals(List.of(WATCH, "scsearch:Rick Astley Never Gonna Give You Up", ALTERNATE,
                ALTERNATE), sources.requests);
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

    /** 本物の source manager を 1 つも登録しない loader に、偽の manager を 1 つだけ入れる。 */
    private static MusicLoaderImpl loader(FakeSource sources) {
        final AudioPlayerManager apm = new DefaultAudioPlayerManager();
        final MusicLoaderImpl loader = new MusicLoaderImpl(apm, null, YoutubeSession.NONE);
        apm.registerSourceManager(sources);
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
