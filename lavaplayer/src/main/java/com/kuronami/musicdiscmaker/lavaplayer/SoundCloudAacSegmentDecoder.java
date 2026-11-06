package com.kuronami.musicdiscmaker.lavaplayer;

import java.io.IOException;
import java.util.function.Supplier;

import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAacTrackConsumer;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegFileLoader;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegTrackInfo;
import com.sedmelluq.discord.lavaplayer.container.mpeg.reader.MpegFileTrackProvider;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudSegmentDecoder;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioProcessingContext;

/**
 * fMP4 のセグメントに AAC を持つ HLS の decoder。
 *
 * <p>渡される stream は<b>初期化セグメント ({@code EXT-X-MAP}) から始まり、その後に
 * メディアセグメントが続く</b>前提。その並びを作るのは {@link SoundCloudAacHlsAudioTrack}。
 * LavaPlayer 2.2.4-fix-j8 の素の m3u track はメディアセグメントしか繋がないので、
 * そのままでは {@link MpegFileLoader} が {@code moov} を見つけられず鳴らない。
 *
 * <p>復号は MP4 の既存実装 ({@code MpegAudioTrack}) と同じ経路を使う — {@link MpegFileLoader} で
 * {@code moov}/{@code mvex} を読み、{@link MpegAacTrackConsumer} にフレームを流す。fMP4 の
 * 断片は {@code MpegFragmentedFileTrackProvider} が処理する (この fork に既に在る)。
 *
 * <p>出典: lavalink-devs/lavaplayer の PR #202 (Apache-2.0、https://github.com/lavalink-devs/lavaplayer/pull/202) の
 * {@code SoundCloudAacSegmentDecoder} を、この版の API に合わせて書き直したもの。
 */
final class SoundCloudAacSegmentDecoder implements SoundCloudSegmentDecoder {

    /** AAC の MP4 track を表す handler 名と codec 名 (ISO/IEC 14496-12 の {@code soun} / MPEG-4 Audio)。 */
    private static final String HANDLER_SOUND = "soun";
    private static final String CODEC_AAC = "mp4a";

    private final Supplier<SeekableInputStream> nextStreamProvider;

    SoundCloudAacSegmentDecoder(Supplier<SeekableInputStream> nextStreamProvider) {
        this.nextStreamProvider = nextStreamProvider;
    }

    @Override
    public void prepareStream(boolean beginning) {
        // 初期化セグメントは毎回の stream の先頭に繋がるので、ここで状態を持つ必要がない。
    }

    @Override
    public void resetStream() {
        // 同上。seek は tracker が stream そのものを作り直して表す。
    }

    @Override
    public void playStream(AudioProcessingContext context, long startPosition, long desiredPosition)
            throws InterruptedException, IOException {
        final SeekableInputStream stream = nextStreamProvider.get();
        if (stream == null) {
            throw new FriendlyException("SoundCloud returned no audio segments for this track.",
                    FriendlyException.Severity.COMMON, null);
        }
        try (SeekableInputStream input = stream) {
            final MpegFileLoader file = new MpegFileLoader(input);
            file.parseHeaders();

            final MpegAacTrackConsumer consumer = new MpegAacTrackConsumer(context, findAacTrack(file));
            try {
                consumer.initialise();
                final MpegFileTrackProvider reader = file.loadReader(consumer);
                if (reader == null) {
                    throw new FriendlyException(
                            "SoundCloud sent an MP4 stream this player cannot read.",
                            FriendlyException.Severity.SUSPICIOUS, null);
                }
                // セグメント単位の seek は tracker が済ませている。ここでは「どの位置として鳴らすか」
                // だけを伝える (MP3 の decoder が recordSeek でやっているのと同じ役割)。
                consumer.seekPerformed(desiredPosition, startPosition);
                reader.provideFrames();
            } finally {
                consumer.close();
            }
        }
    }

    @Override
    public void close() {
        // stream は playStream の中で閉じる。ここに持つ状態は無い。
    }

    private static MpegTrackInfo findAacTrack(MpegFileLoader file) {
        for (final MpegTrackInfo track : file.getTrackList()) {
            if (HANDLER_SOUND.equals(track.handler) && CODEC_AAC.equals(track.codecName)) {
                return track;
            }
        }
        // 映像だけの MP4 や、AAC 以外 (Opus 等) が返ってきた場合。何が返ったかをログに残す。
        final StringBuilder found = new StringBuilder();
        for (final MpegTrackInfo track : file.getTrackList()) {
            found.append(track.handler).append('|').append(track.codecName).append(' ');
        }
        throw new FriendlyException("SoundCloud sent no AAC audio track (tracks: "
                + found.toString().trim() + ").", FriendlyException.Severity.SUSPICIOUS, null);
    }
}
