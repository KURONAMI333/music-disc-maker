package com.kuronami.musicdiscmaker.lavaplayer;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.apache.http.client.methods.HttpGet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sedmelluq.discord.lavaplayer.container.playlists.ExtendedM3uParser;
import com.sedmelluq.discord.lavaplayer.container.playlists.HlsStreamSegment;
import com.sedmelluq.discord.lavaplayer.container.playlists.HlsStreamSegmentParser;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudHelper;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudSegmentDecoder;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.http.HttpStreamTools;
import com.sedmelluq.discord.lavaplayer.tools.io.ChainedInputStream;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.NonSeekableInputStream;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

/**
 * SoundCloud の AAC (fMP4) の HLS を鳴らす track。
 *
 * <h2>素の m3u track を使わない理由が 2 つある</h2>
 * <ol>
 * <li><b>初期化セグメントを繋ぐ必要がある</b>。SoundCloud の HLS は
 *     {@code #EXT-X-MAP:URI=".../init.mp4"} で {@code moov} を持つ初期化セグメントを別に配り、
 *     以後の {@code dataNNN.m4s} には {@code moof}+{@code mdat} しか入っていない。素の
 *     {@code SoundCloudM3uAudioTrack} はメディアセグメントしか繋がないので、MP4 の parser が
 *     {@code moov} を見つけられず鳴らない (issue #203 の「読み込みは成功・再生で失敗」)。</li>
 * <li><b>CDN に {@code client_id} を付けてはいけない</b>。SoundCloud の署名付き URL
 *     ({@code playback.media-streaming.soundcloud.cloud}) は、素の
 *     {@code SoundCloudHttpContextFilter} が {@code sndcdn.com} 以外の host に
 *     {@code client_id} を足すため <b>403</b> になる (実測: 付けると 403 / 付けないと 200)。
 *     よって API 用の interface (client_id 付き・自動更新あり) と、メディア用の
 *     interface (フィルタ無し) を分けて使う。</li>
 * </ol>
 *
 * <p>それ以外 (セグメント一覧の更新、seek の粒度、10 分ごとの URL 更新) は素の
 * {@code SoundCloudM3uAudioTrack} の作りをそのまま写している。出典:
 * lavalink-devs/lavaplayer の PR #202 (Apache-2.0、https://github.com/lavalink-devs/lavaplayer/pull/202)。ただし向こうは
 * {@code SoundCloudM3uAudioTrack} 自体を書き換えるのに対し、こちらは同梱 jar を差し替えない
 * 方針なので、AAC 用の track を別クラスとして持つ。
 */
final class SoundCloudAacHlsAudioTrack extends DelegatedAudioTrack {

    private static final Logger LOGGER = LoggerFactory.getLogger(SoundCloudAacHlsAudioTrack.class);

    /** セグメントの署名付き URL を取り直す間隔 (素の実装と同じ 10 分)。 */
    private static final long SEGMENT_UPDATE_INTERVAL_MS = TimeUnit.MINUTES.toMillis(10L);

    /** 初期化セグメントを指す HLS の指示子と、その引数名。 */
    private static final String INIT_SEGMENT_DIRECTIVE = "EXT-X-MAP";
    private static final String INIT_SEGMENT_ARGUMENT = "URI";

    private final SoundCloudAudioSourceManager sourceManager;
    /** client_id を付けない HTTP の持ち主 (署名付き CDN 用)。 */
    private final HttpInterfaceManager mediaHttp;
    /** 曲の HLS 一覧を返す JSON の URL ({@code A:} を除いたもの)。 */
    private final String lookupUrl;

    SoundCloudAacHlsAudioTrack(AudioTrackInfo trackInfo, SoundCloudAudioSourceManager sourceManager,
            HttpInterfaceManager mediaHttp, String lookupUrl) {
        super(trackInfo);
        this.sourceManager = sourceManager;
        this.mediaHttp = mediaHttp;
        this.lookupUrl = lookupUrl;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        // api = client_id 付き (JSON の取得だけに使う)。media = 署名付き URL 用 (付けると 403)。
        try (HttpInterface api = sourceManager.getHttpInterface();
                HttpInterface media = mediaHttp.getInterface();
                SegmentTracker tracker = createSegmentTracker(api, media)) {
            tracker.decoder.prepareStream(true);
            executor.executeProcessingLoop(
                    () -> tracker.decoder.playStream(executor.getProcessingContext(),
                            tracker.streamStartPosition, tracker.desiredPosition),
                    tracker::seekToTimecode,
                    true);
        }
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new SoundCloudAacHlsAudioTrack(trackInfo, sourceManager, mediaHttp, lookupUrl);
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return sourceManager;
    }

    private SegmentTracker createSegmentTracker(HttpInterface api, HttpInterface media) throws IOException {
        final SegmentTracker tracker = new SegmentTracker(api, media, loadSegments(api, media));
        tracker.decoder = new SoundCloudAacSegmentDecoder(tracker::createChainedStream);
        return tracker;
    }

    /**
     * 再生用のセグメント一覧と初期化セグメントを読む。
     *
     * @param api   client_id を付ける interface (JSON の取得に使う)
     * @param media client_id を付けない interface (署名付き URL の取得に使う)
     * @return 初期化セグメントの URL とメディアセグメントの一覧
     * @throws IOException 取得に失敗した
     */
    private SegmentList loadSegments(HttpInterface api, HttpInterface media) throws IOException {
        final String playbackUrl = SoundCloudHelper.loadPlaybackUrl(api, lookupUrl);
        if (playbackUrl == null || playbackUrl.isBlank()) {
            throw new FriendlyException("SoundCloud returned no playback URL for this track.",
                    FriendlyException.Severity.COMMON, null);
        }
        final String[] lines = HttpClientTools.fetchResponseLines(media, new HttpGet(playbackUrl),
                "stream segments list");
        return new SegmentList(findInitSegmentUrl(lines), HlsStreamSegmentParser.parseFromLines(lines));
    }

    /**
     * {@code #EXT-X-MAP:URI="..."} の URL を取り出す。fMP4 でない HLS (素の m3u8) では
     * {@code null} を返し、初期化セグメント無しで鳴らす。
     *
     * @param lines m3u8 の行
     * @return 初期化セグメントの URL。無ければ {@code null}
     */
    private static String findInitSegmentUrl(String[] lines) {
        for (final String lineText : lines) {
            final ExtendedM3uParser.Line line = ExtendedM3uParser.parseLine(lineText);
            if (line.isDirective() && INIT_SEGMENT_DIRECTIVE.equals(line.directiveName)) {
                return line.directiveArguments.get(INIT_SEGMENT_ARGUMENT);
            }
        }
        return null;
    }

    /** 1 回分の m3u8 から読み取った、初期化セグメントとメディアセグメントの組。 */
    private record SegmentList(String initSegmentUrl, List<HlsStreamSegment> segments) {
    }

    /**
     * セグメントの読み出しと seek。素の {@code SoundCloudM3uAudioTrack.SegmentTracker} と同じ
     * 作りに、初期化セグメントを stream の先頭へ差し込む状態を足したもの。
     */
    private final class SegmentTracker implements AutoCloseable {

        private final HttpInterface api;
        private final HttpInterface media;
        private final List<HlsStreamSegment> segments;
        private String initSegmentUrl;
        /** 次に stream を開く時、初期化セグメントを先に流すか。 */
        private boolean initSegmentPending;
        private long desiredPosition;
        private long streamStartPosition;
        private long lastUpdate;
        private SoundCloudSegmentDecoder decoder;
        private int segmentIndex;

        private SegmentTracker(HttpInterface api, HttpInterface media, SegmentList segmentList) {
            this.api = api;
            this.media = media;
            this.segments = new ArrayList<>(segmentList.segments());
            this.initSegmentUrl = segmentList.initSegmentUrl();
            this.lastUpdate = System.currentTimeMillis();
            if (this.segments.isEmpty()) {
                // 空の一覧で「鳴らないが再生中」を作らない。理由が分かる形で落とす。
                throw new FriendlyException("SoundCloud returned an empty stream playlist for this track.",
                        FriendlyException.Severity.COMMON, null);
            }
        }

        private SeekableInputStream createChainedStream() {
            // 初期化セグメントは fMP4 の断片を読む前に必ず 1 回だけ流す。seek のたびに
            // stream を作り直すので、その都度先頭へ戻す。
            this.initSegmentPending = this.initSegmentUrl != null;
            return new NonSeekableInputStream(new ChainedInputStream(this::getNextStream));
        }

        private void seekToTimecode(long timecode) throws IOException {
            long segmentTimecode = 0L;
            for (int i = 0; i < this.segments.size(); i++) {
                final Long duration = this.segments.get(i).duration;
                if (duration == null) {
                    break;
                }
                final long nextTimecode = segmentTimecode + duration;
                if (timecode >= segmentTimecode && timecode < nextTimecode) {
                    this.seekToSegment(i, timecode, segmentTimecode);
                    return;
                }
                segmentTimecode = nextTimecode;
            }
            this.seekToEnd();
        }

        private void seekToSegment(int index, long requestedTimecode, long segmentTimecode) throws IOException {
            this.decoder.resetStream();
            this.segmentIndex = index;
            this.desiredPosition = requestedTimecode;
            this.streamStartPosition = segmentTimecode;
            this.decoder.prepareStream(this.streamStartPosition == 0L);
        }

        private void seekToEnd() throws IOException {
            this.decoder.resetStream();
            this.segmentIndex = this.segments.size();
        }

        private InputStream getNextStream() {
            if (this.initSegmentPending) {
                this.initSegmentPending = false;
                return HttpStreamTools.streamContent(this.media, new HttpGet(this.initSegmentUrl));
            }
            final HlsStreamSegment segment = this.getNextSegment();
            return segment == null ? null : HttpStreamTools.streamContent(this.media, new HttpGet(segment.url));
        }

        private void updateSegmentList() {
            try {
                final SegmentList updated = loadSegments(this.api, this.media);
                final List<HlsStreamSegment> fresh = updated.segments();
                if (fresh.size() != this.segments.size()) {
                    LOGGER.error("For {}, received different number of segments on update, skipping.",
                            trackInfo.identifier);
                    return;
                }
                for (int i = 0; i < this.segments.size(); i++) {
                    if (!Objects.equals(fresh.get(i).duration, this.segments.get(i).duration)) {
                        LOGGER.error("For {}, segment {} has different length than previously on update.",
                                trackInfo.identifier, i);
                        return;
                    }
                }
                for (int i = 0; i < this.segments.size(); i++) {
                    this.segments.set(i, fresh.get(i));
                }
                this.initSegmentUrl = updated.initSegmentUrl();
            } catch (Exception e) {
                LOGGER.error("For {}, failed to update segment list, skipping.", trackInfo.identifier, e);
            }
        }

        private void checkSegmentListUpdate() {
            final long now = System.currentTimeMillis();
            final long delta = now - this.lastUpdate;
            if (delta > SEGMENT_UPDATE_INTERVAL_MS) {
                LOGGER.debug("For {}, {}ms has passed since last segment update, updating",
                        trackInfo.identifier, delta);
                this.updateSegmentList();
                this.lastUpdate = now;
            }
        }

        private HlsStreamSegment getNextSegment() {
            final int current = this.segmentIndex++;
            if (current < this.segments.size()) {
                this.checkSegmentListUpdate();
                return this.segments.get(current);
            }
            return null;
        }

        @Override
        public void close() throws Exception {
            // decoder の resetStream は IOException を投げうる (素の SegmentTracker も同じ形)。
            this.decoder.resetStream();
        }
    }
}
