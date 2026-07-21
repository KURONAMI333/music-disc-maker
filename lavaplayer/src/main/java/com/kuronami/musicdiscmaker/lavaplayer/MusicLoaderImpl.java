package com.kuronami.musicdiscmaker.lavaplayer;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.IMusicLoader;
import com.kuronami.musicdiscmaker.lavaplayer.api.ResolveException;
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

    // YouTube リンクの host 判定と 11 桁 video ID 抽出。host が YouTube 系の時だけ正規化する
    // (他サービスの URL に v= が含まれても触らない)。
    private static final Pattern YT_HOST =
            Pattern.compile("(?i)^(?:https?://)?(?:www\\.|m\\.|music\\.)?(?:youtube\\.com|youtu\\.be)/");
    private static final Pattern YT_VIDEO_ID =
            Pattern.compile("(?i)(?:youtu\\.be/|/shorts/|/embed/|[?&]v=)([A-Za-z0-9_-]{11})");

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

    /**
     * URL を解決する。失敗時は理由つき {@link ResolveException} を投げる
     * (mod 側の GUI が理由別メッセージを出せるように分類する)。
     */
    @Override
    public TrackInfo resolve(String url) {
        // Spotify URL: og タグからクリーンなメタを取り、YouTube 検索で再生ソースを得る (クレデンシャル不要)
        if (SpotifyResolver.isSpotifyTrack(url)) {
            return resolveViaSpotify(url);
        }
        final AudioTrack track = loadTrackSync(url);
        final AudioTrackInfo info = track.getInfo();
        final String[] cleaned = MetadataCleaner.clean(info.title, info.author);
        return new TrackInfo(cleaned[0], cleaned[1], info.length, info.uri, info.identifier, info.isStream);
    }

    private TrackInfo resolveViaSpotify(String spotifyUrl) {
        final String[] meta = SpotifyResolver.fetchMeta(spotifyUrl);
        if (meta == null || meta[0].isBlank()) {
            LOGGER.warn("Spotify メタ取得失敗: {}", spotifyUrl);
            throw new ResolveException(FailureReason.CONNECTION_FAILED);
        }
        final String query = (meta[1].isBlank() ? "" : meta[1] + " ") + meta[0];
        final AudioTrack yt = loadTrackSync("ytsearch:" + query);
        final AudioTrackInfo info = yt.getInfo();
        // 表示は Spotify のクリーンなメタ、再生は YouTube の uri
        return new TrackInfo(meta[0], meta[1], info.length, info.uri, info.identifier, info.isStream);
    }

    @Override
    public IAudioSource openStream(String url, long startMs) {
        final AudioTrack track;
        try {
            track = loadTrackSync(url);
        } catch (final ResolveException ex) {
            // 再生側 (client) は理由を使わないので従来どおり null で失敗を表す。
            LOGGER.warn("再生用ロード失敗 ({}): {}", url, ex.reason());
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

    /**
     * 単曲の YouTube リンク (watch / youtu.be / shorts / embed) に再生リスト等のパラメータが
     * 付いている場合、video ID だけを取り出して素の watch URL に直す。
     *
     * <p>{@code watch?v=<有効動画>&list=<削除済み/非公開の名前付きプレイリスト>} のような URL は、
     * 動画自体は有効でも playlist 解決が 404 になり全 client が失敗する。プレイリストやミックスの
     * 文脈からコピーした URL で起きるため、単曲意図なら list/index/start_radio を捨てて確実に通す。
     * YouTube 以外の URL・プレイリスト専用 URL・検索クエリ (ytsearch:) はそのまま返す。
     */
    static String normalizeYoutubeUrl(String url) {
        if (url == null || !YT_HOST.matcher(url).find()) {
            return url;
        }
        final Matcher m = YT_VIDEO_ID.matcher(url);
        if (m.find()) {
            return "https://www.youtube.com/watch?v=" + m.group(1);
        }
        return url;
    }

    /**
     * URL を同期ロードする。失敗時は理由つき {@link ResolveException} を投げる (null を返さない)。
     * noMatches → UNSUPPORTED_URL / タイムアウト → CONNECTION_FAILED / loadFailed →
     * 例外メッセージから PRIVATE / REGION / AGE / CONNECTION に分類。
     */
    private AudioTrack loadTrackSync(String url) {
        final String normalized = normalizeYoutubeUrl(url);
        final CompletableFuture<AudioTrack> future = new CompletableFuture<>();
        apm.loadItem(new AudioReference(normalized, null), new AudioLoadResultHandler() {
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

        final AudioTrack track;
        try {
            track = future.get(30, TimeUnit.SECONDS);
        } catch (final TimeoutException ex) {
            LOGGER.warn("URL ロードがタイムアウト ({})", url);
            throw new ResolveException(FailureReason.CONNECTION_FAILED);
        } catch (final ExecutionException ex) {
            LOGGER.warn("URL ロード失敗 ({}): {}", url, String.valueOf(ex.getCause()));
            throw new ResolveException(classify(ex.getCause()));
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResolveException(FailureReason.CONNECTION_FAILED);
        }
        if (track == null) {
            // どの source manager も一致しない = 対応外 URL / 検索ヒットなし。
            throw new ResolveException(FailureReason.UNSUPPORTED_URL);
        }
        return track;
    }

    /**
     * ロード失敗の原因を {@link FailureReason} に分類する。lavaplayer/YouTube のエラーメッセージは
     * 英語固定なので語句一致で判別する。判別できない失敗は接続失敗として扱う。
     */
    private static FailureReason classify(Throwable cause) {
        if (cause == null) {
            return FailureReason.CONNECTION_FAILED;
        }
        final String raw = cause.getMessage();
        final String m = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        if (m.contains("age") || m.contains("confirm your age") || m.contains("sign in to confirm")) {
            return FailureReason.AGE_RESTRICTED;
        }
        if (m.contains("region") || m.contains("country") || m.contains("not available in your")
                || m.contains("blocked it in your")) {
            return FailureReason.REGION_LOCKED;
        }
        if (m.contains("private") || m.contains("removed") || m.contains("deleted")
                || m.contains("no longer available") || m.contains("does not exist")
                || m.contains("unavailable") || m.contains("terminated")) {
            return FailureReason.PRIVATE_OR_REMOVED;
        }
        return FailureReason.CONNECTION_FAILED;
    }
}
