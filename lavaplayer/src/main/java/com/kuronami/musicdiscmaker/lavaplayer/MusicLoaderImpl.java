package com.kuronami.musicdiscmaker.lavaplayer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.IMusicLoader;
import com.kuronami.musicdiscmaker.lavaplayer.api.TrackInfo;
import com.sedmelluq.discord.lavaplayer.format.Pcm16AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.bandcamp.BandcampAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.vimeo.VimeoAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;

import dev.lavalink.youtube.YoutubeAudioSourceManager;

/**
 * LavaPlayer を使う実装。隔離 classloader 側にロードされ、mod からは {@link IMusicLoader}
 * 経由でのみ呼ばれる。LavaPlayer 型は一切外へ露出しない。
 *
 * <p>v1.0 対応 service: YouTube / SoundCloud / Bandcamp / Vimeo / Twitch / HTTP stream。
 * 出力は mono 48kHz S16LE 固定 (jukebox 位置音声向け = OpenAL の 3D 化に mono が必要)。
 */
public class MusicLoaderImpl implements IMusicLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(MusicLoaderImpl.class);

    static final int SAMPLE_RATE = 48000;
    static final int CHANNELS = 1;

    private final AudioPlayerManager apm;

    public MusicLoaderImpl() {
        this.apm = new DefaultAudioPlayerManager();
        // mono / 48kHz / S16 little-endian。OpenAL の距離減衰には mono ソースが必要。
        this.apm.getConfiguration().setOutputFormat(new Pcm16AudioDataFormat(CHANNELS, SAMPLE_RATE, 960, false));
        register(YoutubeAudioSourceManager::new);
        register(SoundCloudAudioSourceManager::createDefault);
        register(BandcampAudioSourceManager::new);
        register(VimeoAudioSourceManager::new);
        register(TwitchStreamAudioSourceManager::new);
        register(HttpAudioSourceManager::new);
    }

    private void register(Supplier<AudioSourceManager> supplier) {
        try {
            final AudioSourceManager manager = supplier.get();
            if (manager != null) {
                apm.registerSourceManager(manager);
                LOGGER.debug("source manager 登録: {}", manager.getSourceName());
            }
        } catch (final Throwable t) {
            LOGGER.warn("source manager 登録失敗: {}", t.toString());
        }
    }

    @Override
    public TrackInfo resolve(String url) {
        // Spotify URL: og タグからクリーンなメタを取り、YouTube 検索で再生ソースを得る (クレデンシャル不要)
        if (SpotifyResolver.isSpotifyTrack(url)) {
            return resolveViaSpotify(url);
        }
        final AudioTrack track = loadTrackSync(url);
        if (track == null) {
            return null;
        }
        final AudioTrackInfo info = track.getInfo();
        final String[] cleaned = MetadataCleaner.clean(info.title, info.author);
        return new TrackInfo(cleaned[0], cleaned[1], info.length, info.uri, info.identifier, info.isStream);
    }

    private TrackInfo resolveViaSpotify(String spotifyUrl) {
        final String[] meta = SpotifyResolver.fetchMeta(spotifyUrl);
        if (meta == null || meta[0].isBlank()) {
            LOGGER.warn("Spotify メタ取得失敗: {}", spotifyUrl);
            return null;
        }
        final String query = (meta[1].isBlank() ? "" : meta[1] + " ") + meta[0];
        final AudioTrack yt = loadTrackSync("ytsearch:" + query);
        if (yt == null) {
            LOGGER.warn("Spotify→YouTube 検索失敗: {}", query);
            return null;
        }
        final AudioTrackInfo info = yt.getInfo();
        // 表示は Spotify のクリーンなメタ、再生は YouTube の uri
        return new TrackInfo(meta[0], meta[1], info.length, info.uri, info.identifier, info.isStream);
    }

    @Override
    public IAudioSource openStream(String url, long startMs) {
        final AudioTrack track = loadTrackSync(url);
        if (track == null) {
            return null;
        }
        // 後から chunk に入った player へ途中から同期再生させるための seek。
        // seek 不可トラック (ライブ配信等) は先頭/ライブ端のまま再生する。
        if (startMs > 0L && track.isSeekable()) {
            try {
                track.setPosition(startMs);
            } catch (final Throwable t) {
                LOGGER.warn("seek 失敗 ({}ms) → 先頭から再生: {}", startMs, t.toString());
            }
        }
        final AudioPlayer player = apm.createPlayer();
        player.playTrack(track);
        return new LavaAudioSource(player);
    }

    private AudioTrack loadTrackSync(String url) {
        final CompletableFuture<AudioTrack> future = new CompletableFuture<>();
        apm.loadItem(new AudioReference(url, null), new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                future.complete(track);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                AudioTrack selected = playlist.getSelectedTrack();
                if (selected == null && !playlist.getTracks().isEmpty()) {
                    selected = playlist.getTracks().get(0);
                }
                future.complete(selected);
            }

            @Override
            public void noMatches() {
                future.complete(null);
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                future.completeExceptionally(exception);
            }
        });

        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (final Exception ex) {
            LOGGER.warn("URL ロード失敗 ({}): {}", url, ex.toString());
            return null;
        }
    }
}
