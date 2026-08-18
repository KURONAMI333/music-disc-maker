package com.kuronami.musicdiscmaker.lavaplayer;

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
import com.kuronami.musicdiscmaker.lavaplayer.api.OpenStreamResult;
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
import dev.lavalink.youtube.clients.AndroidVr;

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
    /** mod → MC に渡す実効チャンネル数 (mono = OpenAL の 3D 距離減衰に必須)。 */
    static final int CHANNELS = 1;
    // lavaplayer には stereo 出力を要求する。mono (1ch) を要求すると一部ソース (SoundCloud の
    // MP3 progressive 等) で downmix が適用されず、stereo PCM が mono ラベルのまま出てくる。
    // それを MC が mono 48kHz として再生すると倍の尺 = 半速 + オクターブ低 (slow+低音) になる。
    // stereo で確実に受け、LavaAudioSource が全ソース一律に mono へ downmix する。
    private static final int LAVA_OUTPUT_CHANNELS = 2;

    // YouTube リンクの host 判定と 11 桁 video ID 抽出。host が YouTube 系の時だけ正規化する
    // (他サービスの URL に v= が含まれても触らない)。
    private static final Pattern YT_HOST =
            Pattern.compile("(?i)^(?:https?://)?(?:www\\.|m\\.|music\\.)?(?:youtube\\.com|youtu\\.be)/");
    private static final Pattern YT_VIDEO_ID =
            Pattern.compile("(?i)(?:youtu\\.be/|/shorts/|/embed/|[?&]v=)([A-Za-z0-9_-]{11})");

    /** URL 1 本を解決する時の待ち上限。 */
    private static final long LOAD_TIMEOUT_MS = 30_000L;
    /**
     * 再生中に落ちて開き直す時の待ち上限。ここは MC の streaming スレッドを塞ぐので、
     * 通常の解決 ({@link #LOAD_TIMEOUT_MS}) より短く切る。
     */
    private static final long REOPEN_TIMEOUT_MS = 10_000L;

    /**
     * 解決 (同期側) の再試行上限。失敗が速い bot 判定なら 5 回を数秒で使い切れるが、
     * 遅い失敗を繰り返して利用者を待たせないよう全体の締切も持つ。
     */
    private static final SessionRetry.Policy SYNC_RETRY =
            new SessionRetry.Policy(5, 20_000L, 300L);
    /** 再生中に落ちた時の開き直し回数。streaming スレッドを長く塞がないので 1 回だけ。 */
    private static final int REOPEN_RETRIES = 1;

    private final AudioPlayerManager apm;
    /** 登録できた YouTube source manager (登録に失敗したら {@code null})。 */
    private final YoutubeAudioSourceManager youtube;
    /**
     * visitorId の入れ替え口。<b>manager の参照を持たないとリセットを呼ぶ相手が居ない</b> —
     * これが「起動時に一度弾かれると再起動まで全リンクが死ぬ」の直接の原因だった。
     */
    private final YoutubeSession session;

    public MusicLoaderImpl() {
        this.apm = new DefaultAudioPlayerManager();
        // lavaplayer 出力は stereo / 48kHz / S16 little-endian。mono 化は LavaAudioSource が担う
        // (lavaplayer の mono downmix はソースによって効かないため = SoundCloud slow+低音バグ)。
        this.apm.getConfiguration().setOutputFormat(
                new Pcm16AudioDataFormat(LAVA_OUTPUT_CHANNELS, SAMPLE_RATE, 960, false));
        this.youtube = registerYoutube();
        this.session = youtube == null ? YoutubeSession.NONE
                : new YoutubeTokenSession(youtube, System::currentTimeMillis);
        register(SoundCloudAudioSourceManager::createDefault);
        register(BandcampAudioSourceManager::new);
        register(VimeoAudioSourceManager::new);
        register(TwitchStreamAudioSourceManager::new);
        register(HttpAudioSourceManager::new);
    }

    /**
     * YouTube source manager を作って登録し、<b>その参照を返す</b>。
     *
     * <h2>client を {@code AndroidVr} 単独にしている理由</h2>
     * 既定の {@code Music / AndroidVr / Web / WebEmbedded} のうち、実際に音を出せるのは
     * {@code AndroidVr} だけだった (2026-08-14 実測。{@code Web} は解決だけ成功して再生時に
     * 「No supported audio streams available」で落ち、{@code WebEmbedded} は player 設定エラー、
     * {@code Music} は watch URL に noMatches)。
     *
     * <p>鳴らない client を並べる害は「無駄」では済まない — {@code Web} が解決に成功してしまうと、
     * <b>失敗が同期側 (解決) を素通りして非同期側 (再生スレッド) に回る</b>。戻り値の無い経路に
     * 落ちた失敗は再試行を書くのが難しく、利用者からは「再生中と出るのに無音」に見える。
     * {@code AndroidVr} 単独なら失敗はほぼ {@link #loadTrackSync} に出るので、素直に再試行できる。
     *
     * <p>検索 ({@code ytsearch:}) も {@code AndroidVr} で通る = Spotify 経路 (og タグの曲名を
     * YouTube 検索で引く) は維持される。
     *
     * @return 登録できた manager、失敗したら {@code null}
     */
    private YoutubeAudioSourceManager registerYoutube() {
        try {
            final YoutubeAudioSourceManager manager = new YoutubeAudioSourceManager(new AndroidVr());
            apm.registerSourceManager(manager);
            LOGGER.debug("Registered source manager: {} (client: ANDROID_VR)", manager.getSourceName());
            return manager;
        } catch (final Throwable t) {
            LOGGER.warn("Failed to register the YouTube source manager", t);
            return null;
        }
    }

    private void register(Supplier<AudioSourceManager> supplier) {
        try {
            final AudioSourceManager manager = supplier.get();
            if (manager != null) {
                apm.registerSourceManager(manager);
                LOGGER.debug("Registered source manager: {}", manager.getSourceName());
            }
        } catch (final Throwable t) {
            LOGGER.warn("Failed to register the source managers", t);
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
        return new TrackInfo(cleaned[0], cleaned[1], info.length, info.uri, info.identifier,
                info.isStream, safe(info.artworkUrl));
    }

    private TrackInfo resolveViaSpotify(String spotifyUrl) {
        final String[] meta = SpotifyResolver.fetchMeta(spotifyUrl);
        if (meta == null || meta[0].isBlank()) {
            LOGGER.warn("Failed to fetch Spotify metadata: {}", spotifyUrl);
            throw new ResolveException(FailureReason.CONNECTION_FAILED,
                    "could not read the Spotify page metadata");
        }
        final String query = (meta[1].isBlank() ? "" : meta[1] + " ") + meta[0];
        final AudioTrack yt = loadTrackSync("ytsearch:" + query);
        final AudioTrackInfo info = yt.getInfo();
        // 表示は Spotify のクリーンなメタ、再生は YouTube の uri。ジャケットは YouTube 側のもの。
        return new TrackInfo(meta[0], meta[1], info.length, info.uri, info.identifier,
                info.isStream, safe(info.artworkUrl));
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    @Override
    public IAudioSource openStream(String url, long startMs) {
        final AudioTrack track;
        try {
            track = loadTrackSync(url);
        } catch (final ResolveException ex) {
            // 理由を要る呼び出し側は openStreamDetailed を使う。この signature は従来どおり null。
            LOGGER.warn("Failed to load track for playback ({}) [{}] {}", url, ex.reason(),
                    ex.detail());
            return null;
        }
        return startPlayback(track, startMs, url);
    }

    /**
     * 理由つきで開く。{@link #openStream} が捨てていた {@link ResolveException#reason()} を
     * そのまま境界の向こうへ渡す (client の画面に出る文面はここから分かれる)。
     */
    @Override
    public OpenStreamResult openStreamDetailed(String url, long startMs) {
        final AudioTrack track;
        try {
            track = loadTrackSync(url);
        } catch (final ResolveException ex) {
            LOGGER.warn("Failed to load track for playback ({}) [{}] {}", url, ex.reason(),
                    ex.detail());
            return OpenStreamResult.failed(ex.reason(), ex.detail());
        }
        return OpenStreamResult.ok(startPlayback(track, startMs, url));
    }

    /**
     * 解決済みトラックを希望位置から鳴らし始める ({@code openStream} 系の共通後半)。
     *
     * <p>YouTube だけは {@link RetryingAudioSource} で包む。{@code AndroidVr} 単独にしても
     * 「解決には成功し、再生スレッドの中で落ちる」経路は残るので、そこでもセッションを
     * 入れ替えて開き直せるようにしておく。他サービスは包まない (入れ替える visitorId が無く、
     * 終端で理由を待つぶんだけ遅くなる)。
     */
    private IAudioSource startPlayback(AudioTrack track, long startMs, String url) {
        final LavaAudioSource source = beginPlayback(track, startMs);
        final String target = normalizeYoutubeUrl(url);
        if (youtube == null || !isYoutubeTarget(target)) {
            return source;
        }
        return new RetryingAudioSource(source,
                () -> beginPlayback(loadOnce(target, REOPEN_TIMEOUT_MS), startMs),
                session, reason -> isRetryable(reason, isSearch(target)),
                REOPEN_RETRIES, RetryingAudioSource.DEFAULT_GRACE_MS, System::currentTimeMillis);
    }

    /** player を作ってトラックを流し始める (包む前の素のソース)。 */
    private LavaAudioSource beginPlayback(AudioTrack track, long startMs) {
        // 後から chunk に入った player へ途中から同期再生させるための seek。
        // seek 不可トラック (ライブ配信等) は先頭/ライブ端のまま再生する。
        if (startMs > 0L && track.isSeekable()) {
            try {
                track.setPosition(startMs);
            } catch (final Throwable t) {
                LOGGER.warn("Seek failed ({}ms), playing from the start instead", startMs, t);
            }
        }
        final AudioPlayer player = apm.createPlayer();
        // listener は playTrack より前に付ける。playTrack は再生を別スレッド
        // (lava-daemon-pool-playback-*) へ投げ、そこで落ちた TrackExceptionEvent は
        // その瞬間に居る listener へしか配られない (再送は無い)。後から付けると、
        // 開始直後に落ちる失敗 = まさに今直している失敗が永久に見えないままになる。
        final LavaAudioSource source = new LavaAudioSource(player);
        player.addListener(source);
        player.playTrack(track);
        return source;
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

    /** 検索クエリか (Spotify 経路が使う {@code ytsearch:} 系)。 */
    private static boolean isSearch(String url) {
        return url != null && (url.startsWith("ytsearch:") || url.startsWith("ytmsearch:"));
    }

    /** YouTube を相手にする URL か (セッションの入れ替えが効く相手だけ再試行する)。 */
    static boolean isYoutubeTarget(String url) {
        return url != null && (isSearch(url) || YT_HOST.matcher(url).find());
    }

    /**
     * その失敗理由は開き直して意味があるか。
     *
     * <p>やり直して直るのは「セッションが弾かれた」系だけ。非公開・削除済み・年齢制限・地域制限は
     * 何度 visitorId を替えても同じ結果なので、待たせるだけ損になる。
     *
     * <p>{@link FailureReason#UNSUPPORTED_URL} を検索の時だけ再試行に含めるのは、検索が bot 判定で
     * 弾かれると<b>結果ゼロ件 = noMatches</b> として返ってくるため (Spotify 経路がここに落ちる)。
     * 検索でない URL の「対応外」は本当に対応外なので、再試行しない。
     */
    static boolean isRetryable(FailureReason reason, boolean search) {
        return switch (reason) {
            case BOT_CHECK, CONNECTION_FAILED, UNKNOWN -> true;
            case UNSUPPORTED_URL -> search;
            default -> false;
        };
    }

    /**
     * URL を同期ロードする。失敗時は理由つき {@link ResolveException} を投げる (null を返さない)。
     *
     * <p>YouTube 相手の失敗は<b>セッションを入れ替えて数回やり直す</b> ({@link SessionRetry})。
     * youtube-source は最初に取った visitorId を抱え続け、YouTube がそれを弾き始めると同じ
     * manager では全 URL が失敗し続けるため。待つだけの再試行は効かない (対照 0/10)。
     */
    private AudioTrack loadTrackSync(String url) {
        final String normalized = normalizeYoutubeUrl(url);
        if (youtube == null || !isYoutubeTarget(normalized)) {
            return loadOnce(normalized, LOAD_TIMEOUT_MS);
        }
        final boolean search = isSearch(normalized);
        return SessionRetry.run(() -> loadOnce(normalized, LOAD_TIMEOUT_MS), session, SYNC_RETRY,
                reason -> isRetryable(reason, search), System::currentTimeMillis);
    }

    /**
     * URL を 1 回だけ同期ロードする。
     * noMatches → UNSUPPORTED_URL / タイムアウト → CONNECTION_FAILED / loadFailed →
     * 例外メッセージから PRIVATE / REGION / AGE / CONNECTION に分類。
     */
    private AudioTrack loadOnce(String normalized, long timeoutMs) {
        final String url = normalized;
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
            track = future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (final TimeoutException ex) {
            LOGGER.warn("Timed out while loading URL ({})", url);
            throw new ResolveException(FailureReason.CONNECTION_FAILED,
                    "no answer within " + timeoutMs + "ms");
        } catch (final ExecutionException ex) {
            // 集約例外なら client ごとの理由で分類し、同じ理由を detail にも使う。
            // 一番外側の文面 (「All clients failed to load the item.」) はどの失敗でも同じで、
            // 利用者が報告に貼っても何も伝わらない。
            final Throwable cause = ex.getCause();
            final FailureReason reason = ClientFailureDetails.classify(cause);
            final String detail = ClientFailureDetails.shortDetail(cause);
            final String verbose = ClientFailureDetails.verbose(cause);
            // 長い理由 (client ごとのスタックフレーム込み) と原因チェーンはログ側に出す。
            LOGGER.warn("Failed to load URL ({}) [{}] {}{}", url, reason, detail,
                    verbose.isEmpty() ? "" : System.lineSeparator() + verbose, cause);
            throw new ResolveException(reason, detail);
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResolveException(FailureReason.CONNECTION_FAILED, "interrupted while loading");
        }
        if (track == null) {
            // どの source manager も一致しない = 対応外 URL / 検索ヒットなし。
            LOGGER.warn("No source manager matched the URL ({})", url);
            throw new ResolveException(FailureReason.UNSUPPORTED_URL,
                    "no source manager matched");
        }
        return track;
    }

}
