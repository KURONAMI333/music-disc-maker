package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureClassifier;
import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.DefaultSoundCloudDataLoader;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.DefaultSoundCloudDataReader;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.DefaultSoundCloudPlaylistLoader;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.DefaultSoundCloudTrackFormat;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudTrackFormat;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sun.net.httpserver.HttpServer;

/**
 * AAC (fMP4) の HLS を、ローカルの {@code HttpServer} から配って全編 decode・seek する試験と、
 * 試聴版・DRM・相対 URL・失敗時の文面のオフライン試験。fixture は
 * {@code src/test/resources/soundcloud-aac/} の自前生成 (4 秒の正弦波。生成コマンドは README.txt)。
 */
class SoundCloudAacPlaybackTest {

    private static final String AAC_MIME = "audio/mp4; codecs=\"mp4a.40.2\"";
    /** fixture の全長 (init + 3 セグメント: 2.020 + 1.997 + 0.023 秒)。 */
    private static final long FIXTURE_MS = 4_040L;

    private HttpServer server;
    private String base;
    private final HttpInterfaceManager plainHttp = HttpClientTools.createDefaultThreadLocalManager();
    private final HttpInterfaceManager mediaHttp = HttpClientTools.createDefaultThreadLocalManager();
    private DefaultAudioPlayerManager playerManager;

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
        playerManager = new DefaultAudioPlayerManager();
        playerManager.getConfiguration().setOutputFormat(StandardAudioDataFormats.COMMON_PCM_S16_BE);
    }

    @AfterEach
    void stop() throws IOException {
        server.stop(0);
        playerManager.shutdown();
        plainHttp.close();
        mediaHttp.close();
    }

    private static byte[] fixture(String name) throws IOException {
        try (InputStream in = SoundCloudAacPlaybackTest.class
                .getResourceAsStream("/soundcloud-aac/" + name)) {
            assertNotNull(in, name);
            return in.readAllBytes();
        }
    }

    private void serve(String path, int status, byte[] body) {
        server.createContext(path, exchange -> {
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            exchange.close();
        });
    }

    /** fixture を {@code /hls/} 以下で配り、lookup は絶対 URL の playlist を返す。 */
    private AudioTrack serveFixtureTrack() throws IOException {
        serve("/lookup", 200, ("{\"url\":\"" + base + "/hls/list.m3u8\"}").getBytes(StandardCharsets.UTF_8));
        serve("/hls/list.m3u8", 200, fixture("list.m3u8"));
        serve("/hls/init.mp4", 200, fixture("init.mp4"));
        for (final String line : new String(fixture("list.m3u8"), StandardCharsets.UTF_8).split("\n")) {
            if (line.endsWith(".m4s")) {
                serve("/hls/" + line, 200, fixture(line));
            }
        }
        server.start();
        final OfflineSoundCloud manager = new OfflineSoundCloud();
        playerManager.registerSourceManager(manager);
        final AudioTrackInfo info = new AudioTrackInfo("t", "a", FIXTURE_MS, "A:" + base + "/lookup",
                false, "https://soundcloud.com/a/b");
        return new SoundCloudAacHlsAudioTrack(info, manager, mediaHttp, base + "/lookup");
    }

    /** 再生が終わるまでに取り出せた PCM のバイト数から、decode できた長さ (ms) を返す。 */
    private long decodedMs(AudioTrack track, long seekToMs) throws Exception {
        final AudioPlayer player = playerManager.createPlayer();
        try {
            if (seekToMs > 0L) {
                track.setPosition(seekToMs);
            }
            player.playTrack(track);
            long bytes = 0L;
            int idle = 0;
            while (idle < 60) {
                final AudioFrame frame = player.provide(100, TimeUnit.MILLISECONDS);
                if (frame != null) {
                    bytes += frame.getDataLength();
                    idle = 0;
                } else if (player.getPlayingTrack() == null) {
                    break;
                } else {
                    idle++;
                }
            }
            // COMMON_PCM_S16_BE = 48 kHz・2ch・16bit = 192 バイト/ms。
            return bytes / 192L;
        } finally {
            player.destroy();
        }
    }

    @Test
    void initPlusSegmentsDecodeEndToEndWithRelativeUrls() throws Exception {
        final long ms = decodedMs(serveFixtureTrack(), 0L);
        // 取りこぼし (init の欠落・セグメントの脱落) は尺の大幅な不足として出る。この fixture は
        // ffmpeg 製で、LavaPlayer の AAC 経路では実尺の約 92% で出てくる (実際の SoundCloud 3 曲は
        // 実尺と一致 = link-probe-20261101 の記録。差の原因は未特定) ので、下限は 85% に置く。
        assertTrue(ms > FIXTURE_MS * 85L / 100L && ms < FIXTURE_MS * 105L / 100L,
                "decoded " + ms + " ms of " + FIXTURE_MS);
    }

    @Test
    void seekStartsAtTheContainingSegment() throws Exception {
        final long ms = decodedMs(serveFixtureTrack(), 2_500L);
        // seek はセグメント単位 (2.043 s 境界) で、desired 位置までは捨てる。残りは約 1.5 s。
        assertTrue(ms > 1_000L && ms < 2_100L, "decoded " + ms + " ms after seeking to 2500 ms");
    }

    // ---- 相対 URL ----------------------------------------------------------------------------

    @Test
    void relativeUrlsResolveAgainstThePlaylistUrl() {
        final String list = "https://cdn.example/a/b/list.m3u8?sig=1";
        assertEquals("https://cdn.example/a/b/seg0.m4s", SoundCloudAacHlsAudioTrack.resolveUrl(list, "seg0.m4s"));
        assertEquals("https://cdn.example/seg0.m4s", SoundCloudAacHlsAudioTrack.resolveUrl(list, "/seg0.m4s"));
        assertEquals("https://other.example/x.m4s",
                SoundCloudAacHlsAudioTrack.resolveUrl(list, "https://other.example/x.m4s"));
    }

    // ---- 失敗の文面 (署名付き URL を出さない・分類できる) ------------------------------------

    @Test
    void segment404IsRefusedAndTheMessageHasNoUrl() throws Exception {
        server.start();
        serve("/gone", 404, new byte[0]);
        try (HttpInterface http = mediaHttp.getInterface()) {
            final String url = base + "/gone?X-Amz-Signature=secret";
            final FriendlyException thrown = assertThrows(FriendlyException.class,
                    () -> SoundCloudAacHlsAudioTrack.openSegment(http,
                            new org.apache.http.client.methods.HttpGet(url)));
            assertFalse(thrown.getMessage().contains("127.0.0.1"), thrown.getMessage());
            assertFalse(thrown.getMessage().contains("secret"), thrown.getMessage());
            assertEquals(FailureReason.SOURCE_REFUSED, FailureClassifier.classify(thrown));
        }
    }

    @Test
    void segment403IsClassifiedAsRefused() throws Exception {
        server.start();
        serve("/denied", 403, new byte[0]);
        try (HttpInterface http = mediaHttp.getInterface()) {
            final FriendlyException thrown = assertThrows(FriendlyException.class,
                    () -> SoundCloudAacHlsAudioTrack.openSegment(http,
                            new org.apache.http.client.methods.HttpGet(base + "/denied")));
            assertEquals(FailureReason.BOT_CHECK, FailureClassifier.classify(thrown));
        }
    }

    // ---- 試聴版・DRM ------------------------------------------------------------------------

    private static SoundCloudTrackFormat fmt(String protocol, String mime, String path) {
        return new DefaultSoundCloudTrackFormat("1", protocol, mime,
                "https://api-v2.soundcloud.com/media/soundcloud:tracks:1/u/" + path);
    }

    @Test
    void previewIsNeverChosenWhenAFullAacExists() {
        final SoundCloudAacFormatHandler handler = new SoundCloudAacFormatHandler();
        final SoundCloudTrackFormat full = fmt("hls", AAC_MIME, "stream/hls");
        final SoundCloudTrackFormat chosen = handler.chooseBestFormat(List.of(
                fmt("hls", "audio/mpeg", "preview/hls"),
                fmt("progressive", "audio/mpeg", "preview/progressive"), full));
        assertSame(full, chosen);
    }

    @Test
    void previewOnlyTrackFailsAsPreviewOnly() {
        final SoundCloudAacFormatHandler handler = new SoundCloudAacFormatHandler();
        final FriendlyException thrown = assertThrows(FriendlyException.class,
                () -> handler.chooseBestFormat(List.of(
                        fmt("hls", "audio/mpeg", "preview/hls"),
                        fmt("progressive", "audio/mpeg", "preview/progressive"))));
        assertEquals(FailureReason.PREVIEW_ONLY, FailureClassifier.classify(thrown));
    }

    private static final String TRACK_HEAD = "{\"kind\":\"track\",\"id\":1,\"title\":\"T\","
            + "\"user\":{\"username\":\"U\"},\"full_duration\":200000,"
            + "\"permalink_url\":\"https://soundcloud.com/u/t\",\"artwork_url\":\"https://i1.sndcdn.com/artworks-x-large.jpg\",\"media\":{\"transcodings\":[";

    private static JsonBrowser trackJson(String transcodings) throws IOException {
        return JsonBrowser.parse(TRACK_HEAD + transcodings + "]}}");
    }

    private static String transcoding(String protocol, String mime, String path) {
        return "{\"url\":\"https://api-v2.soundcloud.com/media/soundcloud:tracks:1/u/" + path
                + "\",\"format\":{\"protocol\":\"" + protocol + "\",\"mime_type\":\""
                + mime.replace("\"", "\\\"") + "\"}}";
    }

    private static final String DRM_JSON = transcoding("cbc-encrypted-hls", AAC_MIME, "stream/cbc-encrypted-hls");
    private static final String PREVIEW_JSON = transcoding("hls", "audio/mpeg", "preview/hls");
    private static final String FULL_JSON = transcoding("hls", AAC_MIME, "stream/hls");

    @Test
    void listsSkipDrmAndPreviewTracksButSingleTracksExplainThem() throws Exception {
        final MusicLoaderImpl.PreviewAwareSoundCloud manager = MusicLoaderImpl.PreviewAwareSoundCloud.create();
        try {
            // 一覧の経路 (検索・liked): 飛ばす = null。
            assertNull(manager.loadFromTrackData(trackJson(DRM_JSON)));
            assertNull(manager.loadFromTrackData(trackJson(PREVIEW_JSON)));
            final AudioTrack ok = manager.loadFromTrackData(trackJson(FULL_JSON));
            assertInstanceOf(SoundCloudAacHlsAudioTrack.class, ok);

            // 全長 AAC と試聴版が両方ある曲は、全長を採る。
            final AudioTrack both = manager.loadFromTrackData(trackJson(PREVIEW_JSON + "," + FULL_JSON));
            assertInstanceOf(SoundCloudAacHlsAudioTrack.class, both);
        } finally {
            manager.shutdown();
        }
    }

    @Test
    void setLoaderTreatsDrmAndPreviewTracksAsBlocked() throws Exception {
        final MusicLoaderImpl.SkippingDataReader reader = new MusicLoaderImpl.SkippingDataReader(
                new DefaultSoundCloudDataReader(), new SoundCloudAacFormatHandler());
        assertTrue(reader.isTrackBlocked(trackJson(DRM_JSON)));
        assertTrue(reader.isTrackBlocked(trackJson(PREVIEW_JSON)));
        assertFalse(reader.isTrackBlocked(trackJson(FULL_JSON)));
    }
}
