package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureClassifier;
import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.player.event.TrackExceptionEvent;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.DefaultSoundCloudDataLoader;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.DefaultSoundCloudDataReader;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.DefaultSoundCloudPlaylistLoader;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.DefaultSoundCloudTrackFormat;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudM3uInfo;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudTrackFormat;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.MessageInput;
import com.sedmelluq.discord.lavaplayer.tools.io.MessageOutput;
import com.sedmelluq.discord.lavaplayer.tools.io.NonSeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DecodedTrackHolder;
import com.sun.net.httpserver.HttpServer;

/**
 * SoundCloud の AAC (fMP4) の HLS の経路 (format handler・track の復元・失敗時の振る舞い)。
 *
 * <p>実音声の decode と seek はネットワークを要するので、ここではなく
 * {@code link-probe-20261101/PlaybackProbe} で測っている。ここは<b>オフラインで決まること</b>
 * だけを固定する: 形式の選び方、DRM の判別、{@code A:} 識別子の往復、そして想定外の入力・
 * 取得失敗で「分かる例外になり、止まらない」こと。
 */
class SoundCloudAacTest {

    private static final String AAC_MIME = "audio/mp4; codecs=\"mp4a.40.2\"";
    private static final long WAIT_SECONDS = 20L;

    private HttpServer server;
    private String base;
    private final HttpInterfaceManager plainHttp = HttpClientTools.createDefaultThreadLocalManager();
    private final HttpInterfaceManager mediaHttp = HttpClientTools.createDefaultThreadLocalManager();
    private DefaultAudioPlayerManager playerManager;

    /** 通信を一切 client_id 付きにしない、オフライン用の SoundCloud manager。 */
    private final class OfflineSoundCloud extends SoundCloudAudioSourceManager {
        OfflineSoundCloud() {
            super(true, new DefaultSoundCloudDataReader(), new DefaultSoundCloudDataLoader(),
                    new SoundCloudAacFormatHandler(), new DefaultSoundCloudPlaylistLoader(
                            new DefaultSoundCloudDataLoader(), new DefaultSoundCloudDataReader(),
                            new SoundCloudAacFormatHandler()),
                    true);
        }

        @Override
        public HttpInterface getHttpInterface() {
            return plainHttp.getInterface();
        }
    }

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
        playerManager = new DefaultAudioPlayerManager();
    }

    @AfterEach
    void stop() throws IOException {
        server.stop(0);
        playerManager.shutdown();
        plainHttp.close();
        mediaHttp.close();
    }

    private static SoundCloudTrackFormat format(String protocol, String mime) {
        return new DefaultSoundCloudTrackFormat("progressive".equals(protocol) ? "preview" : "full",
                protocol, mime, "https://api-v2.soundcloud.com/media/x/" + protocol + "/" + mime);
    }

    // ---- format handler --------------------------------------------------------------------

    @Test
    void aacOnlyIsChosenAndGetsTheAacIdentifier() {
        final SoundCloudAacFormatHandler handler = new SoundCloudAacFormatHandler();
        final SoundCloudTrackFormat aac = format("hls", AAC_MIME);
        final SoundCloudTrackFormat chosen = handler.chooseBestFormat(
                List.of(aac, format("hls", "audio/mpegurl")));
        assertSame(aac, chosen);

        final String identifier = handler.buildFormatIdentifier(chosen);
        assertTrue(identifier.startsWith(SoundCloudAacFormatHandler.AAC_PREFIX));
        final SoundCloudM3uInfo info = handler.getM3uInfo(identifier);
        assertEquals(chosen.getLookupUrl(), info.lookupUrl);
    }

    @Test
    void existingFormatsKeepTheirPriorityOverAac() {
        final SoundCloudAacFormatHandler handler = new SoundCloudAacFormatHandler();
        final SoundCloudTrackFormat mp3 = format("hls", "audio/mpeg");
        final SoundCloudTrackFormat chosen = handler.chooseBestFormat(
                List.of(format("hls", AAC_MIME), mp3));
        assertSame(mp3, chosen);
        assertFalse(handler.buildFormatIdentifier(chosen)
                .startsWith(SoundCloudAacFormatHandler.AAC_PREFIX));
    }

    @Test
    void drmOnlyTrackFailsWithTheDrmReason() {
        final SoundCloudAacFormatHandler handler = new SoundCloudAacFormatHandler();
        final FriendlyException thrown = assertThrows(FriendlyException.class,
                () -> handler.chooseBestFormat(List.of(
                        format("cbc-encrypted-hls", AAC_MIME),
                        format("ctr-encrypted-hls", AAC_MIME),
                        format("cbc-encrypted-hls", "audio/mpegurl"))));
        assertEquals(FailureReason.DRM_PROTECTED, FailureClassifier.classify(thrown));
    }

    @Test
    void unknownFormatsAreNotMistakenForDrm() {
        final SoundCloudAacFormatHandler handler = new SoundCloudAacFormatHandler();
        final RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> handler.chooseBestFormat(List.of(format("rtmp", "audio/x-flv"))));
        assertNotEquals(FailureReason.DRM_PROTECTED, FailureClassifier.classify(thrown));
        assertThrows(RuntimeException.class, () -> handler.chooseBestFormat(List.of()));
    }

    // ---- track の往復 ------------------------------------------------------------------------

    @Test
    void aacTrackSurvivesEncodeAndDecode() throws Exception {
        final OfflineSoundCloud manager = new OfflineSoundCloud();
        playerManager.registerSourceManager(manager);
        final AudioTrackInfo info = new AudioTrackInfo("t", "a", 213_000L,
                "A:https://api-v2.soundcloud.com/media/soundcloud:tracks:293/x/stream/hls",
                false, "https://soundcloud.com/forss/flickermood");
        final AudioTrack original = new SoundCloudAacHlsAudioTrack(info, manager, mediaHttp,
                "https://api-v2.soundcloud.com/media/soundcloud:tracks:293/x/stream/hls");

        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        playerManager.encodeTrack(new MessageOutput(bytes), original);
        final DecodedTrackHolder decoded = playerManager
                .decodeTrack(new MessageInput(new ByteArrayInputStream(bytes.toByteArray())));

        assertNotNull(decoded.decodedTrack);
        assertEquals(info.identifier, decoded.decodedTrack.getInfo().identifier);
        assertEquals(213_000L, decoded.decodedTrack.getInfo().length);
    }

    @Test
    void decodeTrackRebuildsAnAacTrackFromTheIdentifier() throws Exception {
        final MusicLoaderImpl.PreviewAwareSoundCloud manager = MusicLoaderImpl.PreviewAwareSoundCloud.create();
        try {
            final AudioTrackInfo info = new AudioTrackInfo("t", "a", 1000L,
                    "A:https://api-v2.soundcloud.com/media/x/stream/hls", false,
                    "https://soundcloud.com/a/b");
            final AudioTrack rebuilt = manager.decodeTrack(info,
                    new DataInputStream(new ByteArrayInputStream(new byte[0])));
            assertInstanceOf(SoundCloudAacHlsAudioTrack.class, rebuilt);

            final AudioTrackInfo mp3 = new AudioTrackInfo("t", "a", 1000L,
                    "U:https://api-v2.soundcloud.com/media/x/stream/progressive", false,
                    "https://soundcloud.com/a/b");
            assertFalse(manager.decodeTrack(mp3,
                    new DataInputStream(new ByteArrayInputStream(new byte[0])))
                    instanceof SoundCloudAacHlsAudioTrack);
        } finally {
            manager.shutdown();
        }
    }

    // ---- decoder の入力異常 -------------------------------------------------------------------

    @Test
    void decoderRejectsAMissingStream() {
        final SoundCloudAacSegmentDecoder decoder = new SoundCloudAacSegmentDecoder(() -> null);
        final FriendlyException thrown = assertThrows(FriendlyException.class,
                () -> decoder.playStream(null, 0L, 0L));
        assertTrue(thrown.getMessage().contains("no audio segments"));
    }

    @Test
    void decoderFailsOnBytesThatAreNotAnAacMp4() {
        final byte[] junk = "<html>this is not an mp4</html>".getBytes(StandardCharsets.UTF_8);
        final SoundCloudAacSegmentDecoder decoder = new SoundCloudAacSegmentDecoder(
                () -> new NonSeekableInputStream(new ByteArrayInputStream(junk)));
        final Throwable thrown = assertTimeoutPreemptively(Duration.ofSeconds(WAIT_SECONDS),
                () -> assertThrows(Exception.class, () -> decoder.playStream(null, 0L, 0L)));
        assertNotNull(thrown);
    }

    @Test
    void decoderFailsOnAnEmptyStream() {
        final SoundCloudAacSegmentDecoder decoder = new SoundCloudAacSegmentDecoder(
                () -> new NonSeekableInputStream(new ByteArrayInputStream(new byte[0])));
        assertTimeoutPreemptively(Duration.ofSeconds(WAIT_SECONDS),
                () -> assertThrows(Exception.class, () -> decoder.playStream(null, 0L, 0L)));
    }

    // ---- track を実際に回した時の失敗 ---------------------------------------------------------

    private void serve(String path, int status, String body) {
        server.createContext(path, exchange -> {
            final byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
    }

    /** lookup を {@code /lookup}、m3u8 を {@code /list.m3u8} に置いて track を回し、最初の例外を返す。 */
    private Throwable playAndCaptureFailure(String lookupBody, int lookupStatus) throws Exception {
        serve("/lookup", lookupStatus, lookupBody);
        final OfflineSoundCloud manager = new OfflineSoundCloud();
        playerManager.registerSourceManager(manager);
        final AudioTrackInfo info = new AudioTrackInfo("t", "a", 10_000L, "A:" + base + "/lookup",
                false, "https://soundcloud.com/a/b");
        final AudioTrack track = new SoundCloudAacHlsAudioTrack(info, manager, mediaHttp,
                base + "/lookup");
        final AudioPlayer player = playerManager.createPlayer();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        player.addListener(new AudioEventAdapter() {
            @Override
            public void onTrackException(AudioPlayer p, AudioTrack t, FriendlyException ex) {
                failure.compareAndSet(null, ex);
                done.countDown();
            }

            @Override
            public void onTrackEnd(AudioPlayer p, AudioTrack t,
                    com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason reason) {
                done.countDown();
            }
        });
        player.playTrack(track);
        assertTrue(done.await(WAIT_SECONDS, TimeUnit.SECONDS),
                "the track neither failed nor ended (hung)");
        player.destroy();
        return failure.get();
    }

    @Test
    void emptyPlaylistFailsWithAReadableMessage() throws Exception {
        serve("/list.m3u8", 200, "#EXTM3U\n#EXT-X-ENDLIST\n");
        final Throwable failure = playAndCaptureFailure("{\"url\":\"" + base + "/list.m3u8\"}", 200);
        assertNotNull(failure);
        assertTrue(messages(failure).contains("empty stream playlist"), messages(failure));
    }

    @Test
    void missingPlaybackUrlFailsWithAReadableMessage() throws Exception {
        final Throwable failure = playAndCaptureFailure("{\"nothing\":1}", 200);
        assertNotNull(failure);
        assertFalse(messages(failure).isBlank());
    }

    @Test
    void lookupServerErrorFailsInsteadOfHanging() throws Exception {
        final Throwable failure = playAndCaptureFailure("", 500);
        assertNotNull(failure);
        assertNotEquals(FailureReason.UNKNOWN, FailureClassifier.classify(failure),
                "an HTTP status failure must not be UNKNOWN: " + messages(failure));
    }

    @Test
    void initSegmentNotFoundFailsInsteadOfHanging() throws Exception {
        final String list = "#EXTM3U\n#EXT-X-VERSION:6\n#EXT-X-MAP:URI=\"" + base + "/init.mp4\"\n"
                + "#EXTINF:10.0,\n" + base + "/seg1.m4s\n#EXT-X-ENDLIST\n";
        serve("/list.m3u8", 200, list);
        serve("/init.mp4", 404, "");
        serve("/seg1.m4s", 404, "");
        final Throwable failure = playAndCaptureFailure("{\"url\":\"" + base + "/list.m3u8\"}", 200);
        assertNotNull(failure);
        assertNotEquals(FailureReason.UNKNOWN, FailureClassifier.classify(failure),
                "a 404 segment must not be UNKNOWN: " + messages(failure));
        assertEquals(FailureReason.SOURCE_REFUSED, FailureClassifier.classify(failure));
        assertFalse(messages(failure).contains("127.0.0.1"),
                "the segment URL must not appear in the failure: " + messages(failure));
    }

    @Test
    void segmentThatIsNotMp4FailsInsteadOfHanging() throws Exception {
        final String list = "#EXTM3U\n#EXT-X-MAP:URI=\"" + base + "/init.mp4\"\n"
                + "#EXTINF:10.0,\n" + base + "/seg1.m4s\n#EXT-X-ENDLIST\n";
        serve("/list.m3u8", 200, list);
        serve("/init.mp4", 200, "<html>not an mp4</html>");
        serve("/seg1.m4s", 200, "<html>not an mp4</html>");
        final Throwable failure = playAndCaptureFailure("{\"url\":\"" + base + "/list.m3u8\"}", 200);
        assertNotNull(failure);
    }

    private static String messages(Throwable thrown) {
        final StringBuilder out = new StringBuilder();
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            out.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage()).append(" | ");
            if (t.getCause() == t) {
                break;
            }
        }
        return out.toString();
    }
}
