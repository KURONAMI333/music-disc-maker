package com.kuronami.musicdiscmaker.lavaplayer;

import java.io.DataInput;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureClassifier;
import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.IMusicLoader;
import com.kuronami.musicdiscmaker.lavaplayer.api.IMusicLoader.TrackHint;
import com.kuronami.musicdiscmaker.lavaplayer.api.OpenStreamResult;
import com.kuronami.musicdiscmaker.lavaplayer.api.ResolveException;
import com.kuronami.musicdiscmaker.lavaplayer.api.TrackInfo;
import com.kuronami.musicdiscmaker.lavaplayer.api.UrlGate;
import com.kuronami.musicdiscmaker.lavaplayer.api.YoutubeUrls;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerProbe;
import com.sedmelluq.discord.lavaplayer.container.MediaContainerRegistry;
import com.sedmelluq.discord.lavaplayer.container.adts.AdtsContainerProbe;
import com.sedmelluq.discord.lavaplayer.container.flac.FlacContainerProbe;
import com.sedmelluq.discord.lavaplayer.container.matroska.MatroskaContainerProbe;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegContainerProbe;
import com.sedmelluq.discord.lavaplayer.container.mp3.Mp3ContainerProbe;
import com.sedmelluq.discord.lavaplayer.container.wav.WavContainerProbe;
import com.sedmelluq.discord.lavaplayer.format.Pcm16AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.bandcamp.BandcampAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.DefaultSoundCloudDataLoader;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.DefaultSoundCloudDataReader;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.DefaultSoundCloudPlaylistLoader;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudDataLoader;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudDataReader;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudFormatHandler;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudTrackFormat;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudPlaylistLoader;
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.vimeo.VimeoAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;

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
    /** mod → MC に渡す実効チャンネル数 (mono = OpenAL の 3D 距離減衰に必須)。 */
    static final int CHANNELS = 1;
    // lavaplayer には stereo 出力を要求する。mono (1ch) を要求すると一部ソース (SoundCloud の
    // MP3 progressive 等) で downmix が適用されず、stereo PCM が mono ラベルのまま出てくる。
    // それを MC が mono 48kHz として再生すると倍の尺 = 半速 + オクターブ低 (slow+低音) になる。
    // stereo で確実に受け、LavaAudioSource が全ソース一律に mono へ downmix する。
    private static final int LAVA_OUTPUT_CHANNELS = 2;

    // YouTube リンクの 11 桁 video ID 抽出。host の判定は mod 側と共有する (YoutubeUrls) —
    // 片方だけ直すと loader と client でやり直す集合がずれる。
    private static final Pattern YT_VIDEO_ID =
            Pattern.compile("(?i)(?:youtu\\.be/|/shorts/|/embed/|[?&]v=)([A-Za-z0-9_-]{11})");
    /** SoundCloud の単曲 URL か (再生時の代替ソース探しを出す相手)。 */
    private static final Pattern SC_HOST =
            Pattern.compile("(?i)^(?:https?://)?(?:www\\.|m\\.)?soundcloud\\.com/");

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

    /**
     * YouTube で鳴らせなかった時に代わりを探す検索接頭辞 (試す順)。
     *
     * <p>{@code bcsearch:} は 2026-08-18 時点で結果を返さない — Bandcamp の検索ページが
     * 「Client Challenge」(JS を要求する bot 判定) を返すようになり、lavaplayer が読む
     * {@code .searchresult} が HTML に無い。<b>それでも残してある</b>のは、失敗する時は
     * HTTP 1 往復で {@code noMatches} に落ちるだけで、Bandcamp 側か lavaplayer 側が直れば
     * コードを変えずに効き始めるため。<b>ここを「動く逃げ道」として案内してはいけない。</b>
     */
    private static final String[] ALTERNATE_SEARCH_PREFIXES = {"scsearch:", "bcsearch:"};
    /**
     * SoundCloud の曲がプレビューしか無い・DRM の時に探す先。YouTube を先に、次に SoundCloud の
     * 別の投稿 (一覧の経路はプレビュー・DRM の曲を飛ばすので、全曲を配っている投稿だけが残る)。
     * サーバーが YouTube に弾かれている時は検索が空振りし、SoundCloud の側へ進む。
     */
    private static final String[] SOUNDCLOUD_SUBSTITUTE_PREFIXES = {"ytsearch:", "scsearch:"};
    /**
     * 1 つの検索で中身を見る候補の数。1 回の応答に入っている件数なので、増やしても通信は増えない。
     * 上位が全部カバーやリミックスでも後ろまで見る。
     */
    private static final int MAX_CANDIDATES = 10;
    /** oEmbed で曲名を取る時の待ち上限 (返るのは数百バイトの JSON)。 */
    private static final long OEMBED_TIMEOUT_MS = 5_000L;
    /** watch ページから元の尺を取る時の待ち上限 (約 700KB 読んだところで打ち切る)。 */
    private static final long WATCH_PAGE_TIMEOUT_MS = 6_000L;
    /** 代替ソースの検索 1 回あたりの待ち上限。 */
    private static final long ALTERNATE_SEARCH_TIMEOUT_MS = 8_000L;
    /**
     * 代替ソース探しの全体予算。ここに収める理由は、既に {@link #SYNC_RETRY} で最大 20 秒
     * 使い切った後に積まれる時間だから ({@code DiscFabrication} の解決プールは 2 本しかない)。
     *
     * <p>各段の上限を足すとこれを超えるが、<b>締切で頭を押さえてある</b>ので伸びない。
     * 尺の取得は<b>候補が 1 つでも見つかってから</b>行う (曲名すら一致しない時に 1 往復を捨てない)。
     */
    private static final long SUBSTITUTE_BUDGET_MS = 20_000L;

    /**
     * 制作時に<b>1 回だけ</b>予算を取り直す量。{@link #SUBSTITUTE_BUDGET_MS} を使い切って
     * 時間切れになった時の二巡目。
     *
     * <p>予算で切るのは「遅い回線で利用者を待たせ続けない」ためで、曲が無いという結論では
     * ない。一巡目で 2 つの検索が両方とも応答を返さないと、候補が在るかどうかも分からないまま
     * 盤を作れないことになる。そこで<b>制作時だけ</b>もう一度だけ予算を置き直す。
     *
     * <p>再生時は取り直さない。鳴らなかった盤は鳴らないままで、client 側のやり直し
     * ({@code TransientOpenRetry}) に任せた方が、聞いている間の待ち時間が短い。
     */
    private static final long SUBSTITUTE_REFILL_BUDGET_MS = 15_000L;

    /**
     * 一覧 (検索結果・set・liked) から落とした曲を埋め直す時の、一覧 1 つ分の予算。
     *
     * <p>1 曲ずつ探すので、単曲の予算をそのまま積むと一覧が返らない。上限で頭を押さえ、
     * 使い切ったら残りは従来どおり落とす (一覧そのものは出る)。
     */
    private static final long LIST_SUBSTITUTE_BUDGET_MS = 30_000L;

    /**
     * 再生時に見つけた代替 URL を覚えておく件数。
     *
     * <p>同じ盤を何度も鳴らすたびに検索をやり直すと、そのつど数秒待たされるうえに、検索の
     * 結果が揺れると同じ盤から違う曲が鳴りうる。見つけた先を覚えておけば 2 回目以降は
     * 元の URL を試さずに済む。
     *
     * <p>これは<b>メモリだけ</b>の覚え書きで、ディスクや保存領域には書かない。プロセスが
     * 終われば消える (相手の配信が戻っているかもしれない状態を焼き付けない)。
     */
    private static final int MAX_PLAYBACK_SUBSTITUTES = 32;
    /**
     * 再生時に見つけた代替 URL を覚えておく時間 (ms)。
     *
     * <p>覚えている間は<b>元の URL を試さずに</b>代替先を開く。無期限にすると、一度弾かれた盤は
     * 代替先が消えるまで元の URL を二度と試さない (相手の配信が戻っていても気づけない)。
     * 30 分は「同じ盤を続けて何度も鳴らす」間は効き、次のセッションでは元の URL から試す長さ。
     */
    static final long PLAYBACK_SUBSTITUTE_TTL_MS = 30L * 60L * 1000L;

    /**
     * 保存領域へ入れてよいコンテナの probe。保存したバイトは後で全 client が
     * {@link #openTrustedFile} 経由でそのまま鳴らすため、<b>音声だけで完結し、
     * かつシークが機能するコンテナ種別だけ</b>に絞る。ADTS 等のシーク不能入力や
     * mp4 系 ({@link MpegContainerProbe}) はここには入れない (stage 3 の normalize が
     * {@link #INPUT_PROBES} で読み、MKA へ変換してから保存する)。
     *
     * <p><b>matroska の追加検査</b>: {@link MatroskaContainerProbe} は DocType や
     * track 種別を見ないため、映像 track を含む MKV もこの probe で受理される。
     * 映像バイトが原本ごと保存領域へ入らないよう、{@link #loadTrustedFile} は検出が
     * matroska だった trusted file の track 一覧を {@link MatroskaTrackScanner} で
     * 調べ、VIDEO が 1 本でもあれば拒否する (音声のみ MKA / WebM は受理される)。
     *
     * <p>順序は構造化コンテナ (magic byte 確定) を先、mp3 (frame sync 走査) を最後。
     * プレイリスト系 probe (M3U/PLS/plain) は参照先を辿るため両 registry とも入れない。
     */
    static final List<MediaContainerProbe> SAVE_PROBES =
            List.of(new MatroskaContainerProbe(), new WavContainerProbe(), new FlacContainerProbe(),
                    new Mp3ContainerProbe());
    static final MediaContainerRegistry SAVE_REGISTRY = new MediaContainerRegistry(SAVE_PROBES);
    /**
     * 入力側の probe。利用者が選ぶファイルを decode するだけなら decodable なコンテナを
     * 広く受理してよい (映像トラックは読み捨て)。stage 3 の normalizeLocalFile が使う。
     */
    static final List<MediaContainerProbe> INPUT_PROBES =
            List.of(new MatroskaContainerProbe(), new MpegContainerProbe(), new ClosingOggContainerProbe(),
                    new WavContainerProbe(), new FlacContainerProbe(), new AdtsContainerProbe(),
                    new Mp3ContainerProbe());
    static final MediaContainerRegistry INPUT_REGISTRY = new MediaContainerRegistry(INPUT_PROBES);

    private final AudioPlayerManager apm;
    /**
     * 信頼済みファイル専用。通常の {@link #apm} へは登録しない。
     *
     * <p>登録すると従来の {@link #resolve(String)} と {@link #openStream(String, long)} が
     * 任意のファイルパスも解決し始め、URL 用の拒否境界を迂回できる。専用 API から
     * {@link LocalAudioSourceManager#loadItem} を直接呼ぶことで入口を分離する。
     * こちらは保存形式だけを認める {@link #SAVE_REGISTRY} 側。
     */
    private final LocalAudioSourceManager localFiles;
    /**
     * stage 3 の normalize が持込ファイルを decode する時に使う入力側 manager。
     * 保存境界 ({@link #localFiles}) とは別 registry なので、入力として読めても
     * 保存形式でないもの (mp4 等) がそのまま store へ入ることはない。
     */
    private final LocalAudioSourceManager localInputs;
    /** 登録できた YouTube source manager (登録に失敗したら {@code null})。 */
    private final YoutubeAudioSourceManager youtube;
    /**
     * visitorId の入れ替え口。<b>manager の参照を持たないとリセットを呼ぶ相手が居ない</b> —
     * これが「起動時に一度弾かれると再起動まで全リンクが死ぬ」の直接の原因だった。
     */
    private final YoutubeSession session;
    /**
     * 盤の URL → 再生時に見つけた代替 URL と、それを覚えておく期限。値が鳴らなくなったら捨てる。
     *
     * <p>LRU で {@link #MAX_PLAYBACK_SUBSTITUTES} 件に抑える。アクセス順で捨てるのは、
     * よく鳴らす盤の覚えを残すため (件数だけ見て全部消すと、次の再生でまた検索になる)。
     *
     * <p>期限 ({@link #PLAYBACK_SUBSTITUTE_TTL_MS}) を持つのは、覚えた代替先を<b>元の URL より
     * 先に</b>開くため。期限を切らないと、元の URL が再び鳴るようになっていても気づけない。
     */
    private final Map<String, RememberedSubstitute> playbackSubstitutes = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, RememberedSubstitute> eldest) {
                    return size() > MAX_PLAYBACK_SUBSTITUTES;
                }
            });
    /**
     * 代替 URL の期限を測る時計。テストで差し替えて、
     * {@link #PLAYBACK_SUBSTITUTE_TTL_MS} を実際に待たずに失効を確かめる。
     */
    private final LongSupplier clockMs;
    /**
     * 代わりの配信元を開く直前の URL 検査。mod 側が {@link IMusicLoader#setUrlGate} で差し込む。
     *
     * <p>差し込まれていない間は<b>代わりの配信元を開かない</b> ({@link #openAllowed} が false)。
     * 検査できない URL へは接続しない、を既定にしてある。
     */
    private volatile UrlGate urlGate;

    /**
     * 今のスレッドが「一覧から落とした曲の代わりを探す」検索の中に居るか。
     *
     * <p>一覧の埋め戻しは {@code apm.loadItem} で検索を走らせる。その検索が返す一覧でも同じ
     * ことをすると、1 曲の代わりを探すためにさらに一覧を埋め戻そうとして待ち時間が積み上がる
     * (入れ子の一覧で落とした曲まで探す意味も薄い)。内側では埋め戻さない。
     */
    static final ThreadLocal<Boolean> LIST_SUBSTITUTE_SUPPRESSED = ThreadLocal.withInitial(() -> false);

    /**
     * 再生時に見つけた代替 URL と、それを覚えておく期限。
     *
     * @param uri         代わりの URL
     * @param expiresAtMs これを過ぎたら忘れる (壁時計 ms)
     */
    private record RememberedSubstitute(String uri, long expiresAtMs) {

        boolean expiredAt(long nowMs) {
            return nowMs >= expiresAtMs;
        }
    }

    public MusicLoaderImpl() {
        this.apm = new DefaultAudioPlayerManager();
        // 既定 registry は M3U/PLS 等のプレイリスト probe を含むため流用しない。
        // 保存側は音声だけでシーク可能なコンテナ種別、入力側は decode できるコンテナ全体に分ける。
        // (matroska probe は DocType を見ず動画入り MKV も通る点は SAVE_PROBES の javadoc 参照)
        this.localFiles = new LocalAudioSourceManager(SAVE_REGISTRY);
        this.localInputs = new LocalAudioSourceManager(INPUT_REGISTRY);
        this.clockMs = System::currentTimeMillis;
        configureOutput(this.apm);
        this.youtube = registerYoutube();
        this.session = youtube == null ? YoutubeSession.NONE
                : new YoutubeTokenSession(youtube, System::currentTimeMillis);
        // 一覧から落とした曲を埋め直す時、この loader の検索 (substitute) を借りる。
        // 自分で source manager を抱えると、検索がまたこの manager を通って再帰する。
        register(() -> PreviewAwareSoundCloud.create(this));
        register(BandcampAudioSourceManager::new);
        register(VimeoAudioSourceManager::new);
        register(TwitchStreamAudioSourceManager::new);
        register(HttpAudioSourceManager::new);
    }

    /**
     * テスト専用の骨組み。実ソースを登録しないので、外から偽の source manager を差し込める。
     *
     * <p>本番の経路で偽の manager を先に登録すると、{@code HttpAudioSourceManager} が
     * watch URL を横取りして失敗の形が変わってしまう。再生時の代替ソース探しは
     * 「元の URL が特定の理由で開けない」ことが前提なので、その前提を作れる入口を分けてある。
     *
     * @param apm     偽の source manager を登録する先
     * @param youtube 有効にする YouTube manager ({@code null} なら入れ替え経路も無効)
     * @param session visitorId の入れ替え口 ({@code null} 不可)
     * @param clockMs 代替 URL の期限を測る時計 (テストで進める)
     */
    MusicLoaderImpl(AudioPlayerManager apm, YoutubeAudioSourceManager youtube, YoutubeSession session,
            LongSupplier clockMs) {
        this.apm = apm;
        this.localFiles = new LocalAudioSourceManager(SAVE_REGISTRY);
        this.localInputs = new LocalAudioSourceManager(INPUT_REGISTRY);
        this.clockMs = clockMs;
        configureOutput(apm);
        this.youtube = youtube;
        this.session = session;
    }

    /**
     * lavaplayer 出力を stereo / 48kHz / S16 little-endian に固定する。mono 化は
     * {@link LavaAudioSource} が担う (lavaplayer の mono downmix はソースによって効かないため
     * = SoundCloud の slow+低音バグ)。
     */
    private static void configureOutput(AudioPlayerManager apm) {
        apm.getConfiguration().setOutputFormat(
                new Pcm16AudioDataFormat(LAVA_OUTPUT_CHANNELS, SAMPLE_RATE, 960, false));
    }

    /**
     * YouTube source manager を作って登録し、<b>その参照を返す</b>。
     *
     * <h2>client を 1 本だけにしている理由</h2>
     * 鳴らない client を並べる害は「無駄」では済まない。youtube-source のフォールバックが
     * 働くのは<b>ロード段階まで</b>で、どれかが {@code AudioTrack} を返した時点で確定する。
     * 「ロードには成功するが再生できない」client が前に居ると、<b>失敗が同期側 (解決) を
     * 素通りして非同期側 (再生スレッド) に回る</b>。戻り値の無い経路に落ちた失敗は再試行を
     * 書くのが難しく、利用者からは「再生中と出るのに無音」に見える (2026-08-14 実測。
     * 既定 4 本のうち {@code Web} がこの形で、{@code WebEmbedded} は player 設定エラー、
     * {@code Music} は watch URL に noMatches だった)。1 本だけなら失敗はほぼ
     * {@link #loadTrackSync} に出るので、素直に再試行できる。
     *
     * <h2>その 1 本が {@link YoutubeIosClient} である理由</h2>
     * 長らく {@code AndroidVr} だったが、<b>2026-08-17 に YouTube 側で失効した</b> —
     * player API が {@code ANDROID_VR: This video requires login.} を返す。版を上げても
     * 直らない (1.65.10 は player を通すが、ストリームが先頭 256KiB だけ 206 で 1MB 以降 403)。
     * 回線の評判ではなく<b>クライアント単位の失効</b>で、同一回線・同時刻に iOS は完走した。
     *
     * <p>{@link YoutubeIosClient} は iOS を {@code 21.32.4} + {@code WEB_PLAYER_PARAMS} で
     * 名乗る。upstream のリリース 1.18.2 の {@code Ios} は {@code 19.45.4} +
     * {@code MOBILE_PLAYER_PARAMS} で HTTP 400 になるので、そのままでは使えない
     * (なぜ snapshot を引かないかはそのクラスの javadoc)。
     *
     * <p>{@code requirePlayerScript()} が false = <b>署名暗号 (player.js) を触らない</b>のは
     * {@code AndroidVr} 時代と同じ。他 MOD が繰り返し壊れている「YouTube が player.js を
     * 変えるたびに死ぬ」故障モードへの免疫は保たれている。cipher 依存の client を足すと失う。
     *
     * <h2>検索だけ別の client に渡している理由</h2>
     * <b>iOS は検索の結果を 0 件しか返さない</b> (2026-08-21 実測。同じ問い合わせで
     * {@code Web} と {@code AndroidVr} は 20 件、{@code Ios} と {@code Music} は 0 件)。
     * 検索が死ぬと Spotify のリンクが道連れになる — {@code resolveViaSpotify} は og タグの
     * 曲名を {@code ytsearch:} で引くのが本筋の経路で、ここが空だと代替ソースの別音源に落ちる。
     *
     * <p>そこで {@link YoutubeSearchClient} ({@code Web} を検索専用に絞ったもの) を並べる。
     * <b>これはフォールバックではない</b> — 両者の {@code canHandleRequest} が重ならないので、
     * どの入力もちょうど 1 つの client にしか行かない (watch URL は iOS だけ、
     * {@code ytsearch:} は Web だけ)。上に書いた「ロードに成功して再生で死ぬ」害は、
     * 検索用の client が watch URL を<b>一度も見ない</b>ことで構造から消してある。
     * 鳴らすのは常に iOS ({@link #openStream} は URL から解決し直す)。
     *
     * <h2>実測 (2026-08-21・この経路そのもので)</h2>
     * {@code YoutubeLivePlaybackProbe} が {@link #resolve} と {@link #openStream} を直接叩き、
     * 復号 PCM を末尾まで読み切って尺と突き合わせている。<b>解決の成功だけでは足りない</b> —
     * 上記 1.65.10 のように、途中で 403 になる形が実在する。
     *
     * @return 登録できた manager、失敗したら {@code null}
     */
    private YoutubeAudioSourceManager registerYoutube() {
        try {
            final YoutubeIosClient client = new YoutubeIosClient();
            final YoutubeAudioSourceManager manager =
                    new YoutubeAudioSourceManager(client, new YoutubeSearchClient());
            apm.registerSourceManager(manager);
            LOGGER.debug("Registered source manager: {} (client: {} {}, search: WEB)",
                    manager.getSourceName(), client.getIdentifier(), YoutubeIosClient.CLIENT_VERSION);
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
     *
     * <p>YouTube が「この動画は観られない」種類の失敗を返した場合だけ、
     * {@link #substituteForYoutube} が別のソースで同じ曲を探す。<b>ここ (ディスクを作る時) に
     * 置くのが要点</b> — 再生時に探すと、各クライアントが独立に検索して人によって違う曲が鳴る。
     */
    @Override
    public TrackInfo resolve(String url) {
        // Spotify URL: og タグからクリーンなメタを取り、YouTube 検索で再生ソースを得る (クレデンシャル不要)
        if (SpotifyResolver.isSpotifyTrack(url)) {
            return resolveViaSpotify(url);
        }
        final AudioTrack track;
        try {
            track = loadTrackSync(url);
        } catch (final ResolveException ex) {
            final TrackInfo substitute = ex instanceof UnplayableSoundCloudResolve soundCloud
                    ? substituteForSoundCloud(url, soundCloud.track, true)
                    : substituteForYoutube(normalizeYoutubeUrl(url), ex.reason());
            if (substitute != null) {
                return substitute;
            }
            // 隔離 classloader 側の型 (UnplayableSoundCloudResolve) を外へ出さない。
            throw ex instanceof UnplayableSoundCloudResolve ? new ResolveException(ex.reason(), ex.detail()) : ex;
        }
        final AudioTrackInfo info = track.getInfo();
        final String normalized = normalizeYoutubeUrl(url);
        // 説明欄を読むのは自動生成の配信曲 (Topic) と、題に曲名が無い動画 (作品名と OP/ED だけ) の時だけ。
        final YoutubeWatchPage.Result watch = isNormalizedWatchUrl(normalized)
                && (isTopicAuthor(info.author) || MetadataCleaner.needsDescription(info.title, info.author))
                ? YoutubeWatchPage.fetch(normalized, WATCH_PAGE_TIMEOUT_MS)
                : YoutubeWatchPage.Result.EMPTY;
        final SourceMetadata source = sourceMetadata(info.title, info.author, watch);
        return new TrackInfo(source.displayTitle(), source.displayAuthor(), info.length, info.uri, info.identifier,
                info.isStream, safe(info.artworkUrl));
    }

    @Override
    public TrackInfo resolveTrustedFile(Path file) {
        final AudioTrack track = loadTrustedFile(file);
        final AudioTrackInfo info = track.getInfo();
        // MP3 だけは実 frame 索引側で尺を確定する (既存 seeker が MPEG-2/2.5 の Xing を
        // 見落とすため)。Matroska/WAV/FLAC 等の他コンテナは probe が返す尺が正しい。
        final long durationMs = isMp3Container(track)
                ? LocalMp3Index.read(file).durationMs() : info.length;
        // LocalAudioSourceManager の uri / identifier は絶対パス。item metadata や通信へ
        // 端末のパスを漏らさないよう、ローカル音源で不要な欄は境界で空にする。
        // ID3 の title / artist は利用者が明示的に付けた構造化メタデータなので、動画サイト向けの
        // MetadataCleaner ("Artist - Song" 分割や Official 除去) は掛けず、そのまま尊重する。
        return new TrackInfo(localTag(info.title, "Unknown title"),
                localTag(info.author, "Unknown artist"), durationMs, "", "", false, "");
    }

    @Override
    public TrackInfo normalizeLocalFile(Path input, Path output) {
        return new LocalFileNormalizer(this).normalize(input, output);
    }

    /**
     * 検出されたコンテナが MP3 か。{@link LocalMp3Index} の尺/seek 補正は MP3 frame
     * 構造を前提とするため、検出形式が MP3 の時だけ適用する。
     */
    private static boolean isMp3Container(AudioTrack track) {
        return track instanceof LocalAudioTrack local
                && local.getContainerTrackFactory().probe instanceof Mp3ContainerProbe;
    }

    /** ID3 欠落時の lavaplayer placeholder を公開メタ情報へ残さない。 */
    private static String localTag(String value, String placeholder) {
        final String safe = safe(value);
        return placeholder.equals(safe) ? "" : safe;
    }

    private TrackInfo resolveViaSpotify(String spotifyUrl) {
        final String[] meta = SpotifyResolver.fetchMeta(spotifyUrl);
        if (meta == null || meta[0].isBlank()) {
            LOGGER.warn("Failed to fetch Spotify metadata: {}", spotifyUrl);
            throw new ResolveException(FailureReason.CONNECTION_FAILED,
                    "could not read the Spotify page metadata");
        }
        final String query = (meta[1].isBlank() ? "" : meta[1] + " ") + meta[0];
        final AudioTrack yt;
        try {
            yt = loadTrackSync("ytsearch:" + query);
        } catch (final ResolveException ex) {
            // Spotify 経路は最終的に YouTube 検索に落ちるので、YouTube が弾かれると道連れになる。
            // 曲名は既に og タグから取れているので oEmbed は要らない。検索が bot 判定で弾かれると
            // 結果 0 件 = UNSUPPORTED_URL で返ってくるので、それも発火対象に含める。
            if (firesSubstitute(ex.reason()) || ex.reason() == FailureReason.UNSUPPORTED_URL) {
                // og タグの曲名とアーティストは既に分かれているので、MetadataCleaner の
                // 「Artist - Song」分割をかけない (Spotify の "Song - Remastered 2011" が壊れる)。
                // Spotify の URL には watch ページが無いので、尺は分からないまま探す。
                final TrackInfo substitute = substitute(meta[0], meta[0], meta[1], meta[0], meta[1],
                        spotifyUrl, null, null,
                        clockMs.getAsLong() + SUBSTITUTE_BUDGET_MS);
                if (substitute != null) {
                    return substitute;
                }
            }
            throw ex;
        }
        final AudioTrackInfo info = yt.getInfo();
        // 表示は Spotify のクリーンなメタ、再生は YouTube の uri。ジャケットは YouTube 側のもの。
        return new TrackInfo(meta[0], meta[1], info.length, info.uri, info.identifier,
                info.isStream, safe(info.artworkUrl));
    }

    /**
     * その失敗は「YouTube 側の都合でこの動画が観られない」ものか。
     *
     * <p>ここを間違えると害になる。<b>利用者の回線が死んでいる時に別ソースを探しても同じく
     * 失敗するだけで、待ち時間が伸びる。</b>
     *
     * <ul>
     * <li>{@link FailureReason#BOT_CHECK} — bot 判定。MOD 側では解決できない (MDM_DECISIONS D2)</li>
     * <li>{@link FailureReason#AGE_RESTRICTED} — 年齢制限。実際には YouTube 経路でほぼ到達しない
     *     (D7) が、分類が届いた時の扱いは bot 判定と同じ</li>
     * <li>{@link FailureReason#REGION_LOCKED} — 地域制限</li>
     * <li>{@link FailureReason#PRIVATE_OR_REMOVED} — 非公開・削除済み。削除済みでも oEmbed は
     *     曲名を返す (実測) ので、曲そのものが別のソースに在ることは十分ありうる。
     *     本当に存在しない ID なら oEmbed が 404 になって自動的に諦める</li>
     * </ul>
     *
     * <p><b>入れないもの</b>: {@link FailureReason#CONNECTION_FAILED} は利用者の回線障害なので、
     * 別ソースを探しても同じく失敗して遅くなるだけ。{@link FailureReason#UNKNOWN} は分類器の
     * 既定値 = 何が起きたか分かっていない。
     * {@link FailureReason#SOURCE_REFUSED} も入れない — 発火集合は MDM_DECISIONS D11 のままにする。
     * D11 の覆し条件は「その理由で『別ソースに在って YouTube に無い』実例を示すこと」で、
     * 番号つきの拒否についてその実例を持っていない。
     * {@link FailureReason#BLOCKED_URL} は SSRF ガードが拒んだ URL なので、二度と解決しにいかない。
     * {@link FailureReason#UNSUPPORTED_URL} は「そもそも YouTube の URL ではない」。
     *
     * @param reason 解決が返した理由
     * @return 代替ソース探しを始めてよいなら {@code true}
     */
    static boolean firesSubstitute(FailureReason reason) {
        return switch (reason) {
            case BOT_CHECK, AGE_RESTRICTED, REGION_LOCKED, PRIVATE_OR_REMOVED -> true;
            default -> false;
        };
    }

    /**
     * YouTube の watch URL が鳴らせなかった時に、oEmbed で曲名を取って別のソースを探す。
     *
     * <p>相手が YouTube であることを {@link #isYoutubeTarget} で確かめてから動く。理由だけで
     * 判断してはいけない — 直リンク HTTP のホストが 403 を返すと {@code BOT_CHECK} に分類される
     * (MDM_DECISIONS D8) が、その URL に oEmbed は無く、探す語も得られない。
     *
     * @param normalizedUrl 正規化済みの URL
     * @param reason        解決が返した理由
     * @return 代わりに使えるトラック。検索に踏み切れなかった場合は {@code null}
     *         (対象外の URL・発火しない理由・oEmbed が曲名を返さない時。この場合呼び出し側は
     *         元の理由のまま失敗させる)
     * @throws ResolveException 検索が実際に走って使える候補が無かった
     *         ({@link FailureReason#NO_ALTERNATIVE_FOUND})
     */
    private TrackInfo substituteForYoutube(String normalizedUrl, FailureReason reason) {
        if (!isYoutubeTarget(normalizedUrl) || isSearch(normalizedUrl) || !firesSubstitute(reason)) {
            return null;
        }
        final long deadline = clockMs.getAsLong() + SUBSTITUTE_BUDGET_MS;
        final String[] meta = YoutubeOEmbed.fetch(normalizedUrl, OEMBED_TIMEOUT_MS);
        if (meta == null) {
            LOGGER.info("No oEmbed metadata for the blocked YouTube link ({}) [{}]",
                    normalizedUrl, reason);
            return null;
        }
        final YoutubeWatchPage.Result watch = isTopicAuthor(meta[1]) || MetadataCleaner.needsDescription(meta[0], meta[1])
                ? YoutubeWatchPage.fetch(normalizedUrl,
                        Math.min(WATCH_PAGE_TIMEOUT_MS, deadline - clockMs.getAsLong()))
                : YoutubeWatchPage.Result.EMPTY;
        final SourceMetadata source = sourceMetadata(meta[0], meta[1], watch);
        return substitute(meta[0], source.searchTitle(), source.searchAuthor(),
                source.displayTitle(), source.displayAuthor(), normalizedUrl, normalizedUrl,
                isTopicAuthor(meta[1]) ? watch : null, deadline);
    }

    /**
     * SoundCloud の単曲がプレビューしか無い・DRM で鳴らせない時に、同じ曲を別の配信元から探す。
     *
     * <p>曲名・投稿者・全曲の長さは SoundCloud の track JSON が持っている (プレビューの曲でも
     * {@code full_duration} は全長)。長さが分かるので、抜粋や別の版は {@link TrackMatch#durationFits}
     * で落ちる。表示は SoundCloud の曲名のまま、盤に焼く URL だけが見つけた先になる。
     *
     * <p><b>曲名が空でも諦めない</b>。{@code title} を欠いた track JSON があり、そのまま捨てると
     * 「プレビューだけの曲」と区別が付かないまま盤が作れない。SoundCloud の URL は
     * {@code /<user>/<曲名をハイフンで繋いだ slug>} なので、slug から検索語を作れる。
     *
     * @param originUrl 元の SoundCloud URL (ログ用・slug の取得元)
     * @param track     鳴らせなかった曲の情報
     * @param mayRefill 予算を使い切った時に 1 回だけ取り直してよいか (制作時のみ true)
     * @return 代わりに使えるトラック。検索に踏み切れなかった場合は {@code null}
     * @throws ResolveException 検索が実際に走って使える候補が無かった
     *         ({@link FailureReason#NO_ALTERNATIVE_FOUND})
     */
    private TrackInfo substituteForSoundCloud(String originUrl, UnplayableSoundCloudTrack track,
            boolean mayRefill) {
        final String title = track.title.isBlank() ? titleFromSoundCloudUrl(originUrl) : track.title;
        final SourceMetadata source = sourceMetadata(title, track.author, YoutubeWatchPage.Result.EMPTY);
        LOGGER.info("SoundCloud cannot stream the full track ({}); looking for another source", originUrl);
        return substitute(SOUNDCLOUD_SUBSTITUTE_PREFIXES, title, source.searchTitle(),
                source.searchAuthor(), source.displayTitle(), source.displayAuthor(), originUrl, null,
                new YoutubeWatchPage.Result(track.fullDurationMs, "", "", ""),
                clockMs.getAsLong() + SUBSTITUTE_BUDGET_MS, mayRefill);
    }

    /**
     * 曲名とアーティストから別のソースを検索し、<b>同じ録音とみなせる候補だけ</b>を返す。
     *
     * <p>全プレフィックスを回して何も採れなければ
     * {@link FailureReason#NO_ALTERNATIVE_FOUND} を投げる (D45 / D46。「探したが無かった」を
     * 元の理由と取り違えないため)。{@code null} が返るのは<b>検索を走らせ切れなかった時</b> —
     * 曲名が空で query を作れない時、アーティストも尺も分からず一致を判定できない時、
     * 予算を使い切った時、そして<b>検索そのものが失敗した時</b> ({@code loadFailed} / 時間切れ)。
     * その場合は呼び出し側が従来の失敗に落ちる。無理に何か鳴らさない — 違う曲が鳴るのは、
     * 鳴らないより悪い。
     *
     * <h2>アーティストが分からない時は曲名だけで引く</h2>
     * SoundCloud の set や投稿者名を欠いた track JSON ではアーティストが空になる。そこで
     * 検索ごと諦めると、曲名は分かっているのに盤が作れない。曲名だけで引き、候補の側では
     * <b>アーティスト名を名乗っていること</b>と<b>尺</b>を要求する
     * ({@link TrackMatch#sameRecordingWithoutKnownArtist})。尺が分からない時は探さない
     * (同名異曲を弾く材料が残らない)。
     *
     * <h2>尺で抜粋を落とし、残った中では元に一番近いものを採る</h2>
     * 同じ曲名・同じアーティストでも<b>尺の違う版が並ぶ</b> (2026-08-18 実測: Interscope が
     * SoundCloud に上げた「LMFAO - Sorry For Party Rocking」は 1:30 の販促版で、YouTube 側の
     * 同じ曲は 7:19)。元の尺は player API が拒んでも<b>watch ページから取れる</b>ので、
     * それと突き合わせて明らかな抜粋を落とす ({@link TrackMatch#durationFits})。
     *
     * <p>尺が取れなかった時は<b>従来どおり一番長いものを採る</b>。bot 判定で watch ページごと
     * 弾かれる環境では尺は永久に取れないので、そこで機能を止めない。
     *
     * @param markerTitle 版の目印を探す元のタイトル
     * @param title       探す曲名 (整形済み)
     * @param author      探すアーティスト (整形済み)
     * @param displayTitle 表示に残す元ソースの曲名
     * @param displayAuthor 表示に残す元ソースのアーティスト
     * @param originUrl   元の URL (ログ用)
     * @param watchUrl    元の尺を取れる watch URL ({@code null} 可 = 尺は分からない)
     * @param watchResult 曲情報のため先に取得済みのwatch結果 ({@code null} 可)
     * @param deadline    これを過ぎたら諦める時刻 (壁時計 ms)
     * @return 代わりに使えるトラック。検索を走らせ切れなかった場合は {@code null}
     * @throws ResolveException 全プレフィックスを検索して使える候補が無かった
     *         ({@link FailureReason#NO_ALTERNATIVE_FOUND})
     */
    private TrackInfo substitute(String markerTitle, String title, String author,
            String displayTitle, String displayAuthor, String originUrl,
            String watchUrl, YoutubeWatchPage.Result watchResult, long deadline) {
        return substitute(ALTERNATE_SEARCH_PREFIXES, markerTitle, title, author, displayTitle,
                displayAuthor, originUrl, watchUrl, watchResult, deadline, true);
    }

    /**
     * {@link #substitute(String, String, String, String, String, String, String, YoutubeWatchPage.Result, long)}
     * の本体。探す先 ({@code prefixes}) を呼び出し側が選べる。
     *
     * @param prefixes  順に試す検索の接頭辞
     * @param mayRefill 予算を使い切った時に 1 回だけ取り直してよいか
     *                  ({@link #SUBSTITUTE_REFILL_BUDGET_MS})
     */
    private TrackInfo substitute(String[] prefixes, String markerTitle, String title, String author,
            String displayTitle, String displayAuthor, String originUrl,
            String watchUrl, YoutubeWatchPage.Result watchResult, long deadline, boolean mayRefill) {
        final Match match = searchSubstitute(prefixes, markerTitle, title, author, originUrl, watchUrl,
                watchResult, deadline, mayRefill);
        if (match == null) {
            return null;
        }
        final AudioTrackInfo info = match.track().getInfo();
        return new TrackInfo(displayTitle, displayAuthor, info.length, info.uri, info.identifier,
                info.isStream, safe(info.artworkUrl));
    }

    /**
     * 一致した候補。{@link AudioTrack} のまま返すのは、一覧の埋め戻しが<b>それをそのまま並べる</b>
     * ため。URL だけ返して読み直すと、見つけた時の往復がもう一度増える。
     *
     * @param track 一致した候補 (読み込み済み)
     */
    private record Match(AudioTrack track) {
    }

    /**
     * 代替ソースを検索し、同じ録音とみなせる候補を 1 つ返す。
     *
     * <p>アーティストが空なら曲名だけで引き、{@link TrackMatch#sameRecordingWithoutKnownArtist} と
     * 尺で判定する。尺も分からない時は<b>検索しない</b> — 曲名だけでは同名異曲・カバーを弾けない。
     *
     * <p>予算は {@link #clockMs} で測る。テストは時計を進めて時間切れを作れる。
     *
     * @return 一致した候補。検索を走らせ切れなかった場合は {@code null}
     * @throws ResolveException 検索が実際に走って使える候補が無かった
     *         ({@link FailureReason#NO_ALTERNATIVE_FOUND})
     */
    private Match searchSubstitute(String[] prefixes, String markerTitle, String title, String author,
            String originUrl, String watchUrl, YoutubeWatchPage.Result watchResult, long deadline,
            boolean mayRefill) {
        if (title.isBlank()) {
            return null;
        }
        final boolean artistKnown = !author.isBlank();
        // -1 = まだ取りに行っていない / 0 = 取得済みだが取れなかった。
        long sourceMs = watchResult == null ? -1L : watchResult.durationMs();
        if (!artistKnown && sourceMs <= 0L) {
            // 曲名だけで採ると同名異曲やカバーが素通りする。尺という別の材料が無いなら探さない。
            LOGGER.info("No artist and no length to judge a match for ({}); not searching", originUrl);
            return null;
        }
        final String query = artistKnown ? author + " " + title : title;
        // 検索そのものが失敗したか。0 件とは別物 — 0 件は「探したが無かった」、失敗は
        // 「探せていない」で、呼び出し側が次に取れる手が違う (失敗を 0 件に潰すと、
        // 元の理由が一時的なのに「代わりも無い」と確定させてしまう)。
        boolean searchFailed = false;
        boolean refilled = false;
        for (int i = 0; i < prefixes.length; i++) {
            long budget = Math.min(ALTERNATE_SEARCH_TIMEOUT_MS, deadline - clockMs.getAsLong());
            if (budget <= 0L) {
                if (!mayRefill || refilled) {
                    LOGGER.info("Ran out of time while looking for an alternate source ({})", originUrl);
                    return null;
                }
                // 一巡目で使い切った。制作時だけ予算を置き直し、同じ接頭辞から引き直す。
                refilled = true;
                deadline = clockMs.getAsLong() + SUBSTITUTE_REFILL_BUDGET_MS;
                budget = Math.min(ALTERNATE_SEARCH_TIMEOUT_MS, SUBSTITUTE_REFILL_BUDGET_MS);
                LOGGER.info("Out of time looking for an alternate source ({}); retrying once", originUrl);
            }
            final Candidates candidates = loadCandidates(prefixes[i] + query, budget);
            searchFailed |= candidates.failed();
            final List<AudioTrack> matched = new ArrayList<>();
            for (final AudioTrack candidate : candidates.tracks()) {
                final AudioTrackInfo info = candidate.getInfo();
                // 曲の代わりに無限長ストリームや長さ不明のものを掴まない。
                if (info.isStream || info.length <= 0L || info.length == Long.MAX_VALUE) {
                    continue;
                }
                final boolean same = artistKnown
                        ? TrackMatch.sameRecordingFromCleanSource(markerTitle, title, author,
                                info.title, info.author)
                        : TrackMatch.sameRecordingWithoutKnownArtist(markerTitle, title,
                                info.title, info.author);
                if (same) {
                    matched.add(candidate);
                }
            }
            if (matched.isEmpty()) {
                continue;
            }
            if (sourceMs < 0L) {
                // 曲名の一致が 1 つでも出てから初めて尺を取りに行く。
                sourceMs = YoutubeWatchPage.durationMs(watchUrl,
                        Math.min(WATCH_PAGE_TIMEOUT_MS, deadline - clockMs.getAsLong()));
            }
            final AudioTrack best = pick(matched, author, sourceMs, originUrl);
            if (best != null) {
                final AudioTrackInfo info = best.getInfo();
                final String[] cleaned = MetadataCleaner.clean(info.title, info.author);
                LOGGER.info("Substituting an alternate source for {} -> {} ({} - {}, {}ms against {}ms)",
                        originUrl, info.uri, cleaned[1], cleaned[0], info.length, sourceMs);
                return new Match(best);
            }
        }
        if (searchFailed) {
            // 1 つでも検索が答えなかったら「探したが無かった」とは言えない。元の理由のまま
            // 失敗させる — 呼び出し側は null を「探し切れなかった」として扱う。
            LOGGER.info("An alternate source search failed for ({}); keeping the original failure",
                    originUrl);
            return null;
        }
        LOGGER.info("No matching track on the alternate sources for ({})", originUrl);
        // ここが「検索が実際に走って、同じ録音とみなせる候補が1つも無かった」の唯一の出口。
        // 時間切れ・query 不成立・検索失敗は上の return null (元の理由のまま) なので、この理由が
        // 出る時は常に「探したが無かった」を意味する (MDM_DECISIONS D45 / D46)。
        throw new ResolveException(FailureReason.NO_ALTERNATIVE_FOUND,
                "searched \"" + title + "\" on the alternate sources with no usable copy");
    }

    /**
     * 曲名が一致した候補から 1 つ選ぶ。
     *
     * <p>元の尺が分かっているなら、釣り合わないもの (抜粋・寄せ集め) を落として<b>元に一番近い</b>
     * ものを採る。分かっていないなら<b>一番長い</b>ものを採る
     * (短縮版はフル尺より短いという性質だけを使う)。
     *
     * <p>尺の近さが並んだ候補の間では、<b>投稿の住所がアーティスト名を名乗っている方</b>を先に採る
     * ({@link TrackMatch#bestByDuration(long[], boolean[], long)})。名乗る候補が無ければ
     * 尺の近さだけで決まるので、これは足切りではない。
     *
     * @param matched   曲名 (と分かっていればアーティスト) が一致した候補
     * @param author    探しているアーティスト (整形済み・空のことがある)
     * @param sourceMs  元の尺 (ms)。{@code 0} 以下なら分からない
     * @param originUrl 元の URL (ログ用)
     * @return 選んだ候補。尺で全部落ちたら {@code null}
     */
    private static AudioTrack pick(List<AudioTrack> matched, String author, long sourceMs,
            String originUrl) {
        final long[] lengths = new long[matched.size()];
        final boolean[] mentions = new boolean[matched.size()];
        for (int i = 0; i < lengths.length; i++) {
            final AudioTrackInfo info = matched.get(i).getInfo();
            lengths[i] = info.length;
            mentions[i] = TrackMatch.mentionsArtist(author, slugOf(info.uri), info.author);
            if (!TrackMatch.durationFits(sourceMs, info.length)) {
                LOGGER.info("Dropping a length mismatch for {}: {}ms against {}ms ({})",
                        originUrl, info.length, sourceMs, info.uri);
            }
        }
        final int chosen = TrackMatch.bestByDuration(lengths, mentions, sourceMs);
        return chosen < 0 ? null : matched.get(chosen);
    }

    /**
     * 一覧から落とした曲を、同じ曲の別の配信元で埋め直す。
     *
     * <p>一覧の経路 (検索結果・liked) は鳴らせない曲を {@code null} にして飛ばす。飛ばしたまま
     * 返すと、検索結果の先頭が試聴版だった時に<b>一覧が 1 件ずれる</b> (選ぶ曲が変わる) か、
     * 全件がそうなら {@code noMatches} になって「対応外のリンクです」という嘘になる。落とした
     * 曲の情報が残っているので、単曲の時と同じ検索で代わりを探し、<b>同じ位置</b>へ戻す。
     *
     * <p>set は落とした曲を<b>位置ごと消す</b>ので、対応する {@code null} が無い。余った分は
     * 末尾へ足す (曲が消えるより、順序が後ろへ寄る方がまし)。
     *
     * <p>予算は {@link #LIST_SUBSTITUTE_BUDGET_MS} で一覧 1 つ分。使い切ったら残りは従来どおり
     * 落とす — 一覧そのものは返す。
     *
     * @param tracks  読み込み済みの一覧 ({@code null} = 落とした曲)
     * @param dropped 落とした曲 (読んだ順)
     * @return 埋め直した一覧
     */
    List<AudioTrack> fillSubstitutes(List<AudioTrack> tracks, List<DroppedTrack> dropped) {
        final long deadline = clockMs.getAsLong() + LIST_SUBSTITUTE_BUDGET_MS;
        final Iterator<DroppedTrack> next = dropped.iterator();
        final List<AudioTrack> filled = new ArrayList<>(tracks.size() + dropped.size());
        for (final AudioTrack track : tracks) {
            if (track != null) {
                filled.add(track);
                continue;
            }
            if (!next.hasNext()) {
                continue;
            }
            final AudioTrack replacement = substituteListEntry(next.next(), deadline);
            if (replacement != null) {
                filled.add(replacement);
            }
        }
        while (next.hasNext()) {
            final AudioTrack replacement = substituteListEntry(next.next(), deadline);
            if (replacement != null) {
                filled.add(replacement);
            }
        }
        return filled;
    }

    /**
     * 一覧から落とした 1 曲の代わりを探す。見つからなければ {@code null} (従来どおり落とす)。
     *
     * <p>曲名が空なら URL の slug から作る。ここで諦めると、曲名を欠いた track JSON の曲だけが
     * 一覧から永遠に消える。
     *
     * @param dropped  落とした曲
     * @param deadline 一覧 1 つ分の予算の締切 (壁時計 ms)
     * @return 代わりに並べるトラック。見つからない・探し切れない場合は {@code null}
     */
    private AudioTrack substituteListEntry(DroppedTrack dropped, long deadline) {
        if (clockMs.getAsLong() >= deadline) {
            return null;
        }
        final String title = dropped.title().isBlank()
                ? titleFromSoundCloudUrl(dropped.url()) : dropped.title();
        if (title.isBlank()) {
            return null;
        }
        final SourceMetadata source = sourceMetadata(title, dropped.author(),
                YoutubeWatchPage.Result.EMPTY);
        try {
            final Match match = searchSubstitute(SOUNDCLOUD_SUBSTITUTE_PREFIXES, title,
                    source.searchTitle(), source.searchAuthor(), dropped.url(), null,
                    new YoutubeWatchPage.Result(dropped.durationMs(), "", "", ""), deadline, false);
            return match == null ? null : match.track();
        } catch (final ResolveException ex) {
            // 一覧では「代わりも無い」を伝える相手が居ない (1 曲分の話)。従来どおり落とす。
            LOGGER.debug("No substitute for a dropped list entry ({})", dropped.url(), ex);
            return null;
        }
    }

    /**
     * URL から scheme と host を落として、投稿者と slug の部分だけを返す。
     *
     * <p>host を残すと {@code soundcloud.com} がどの候補の住所にも入るので、
     * {@code Cloud} のように短くて host の一部に噛むアーティスト名では
     * 全候補が「名乗っている」ことになり、順位付けの信号が死ぬ。
     *
     * @param uri 候補の URL ({@code null} 可)
     * @return {@code 投稿者/slug} の部分。切り出せなければ元の文字列
     */
    private static String slugOf(String uri) {
        if (uri == null) {
            return "";
        }
        final int scheme = uri.indexOf("//");
        final int host = uri.indexOf('/', scheme < 0 ? 0 : scheme + 2);
        return host < 0 ? uri : uri.substring(host + 1);
    }

    /** SoundCloud の URL で、曲名ではないパス (set・いいね一覧など)。 */
    private static final java.util.Set<String> SOUNDCLOUD_NON_TRACK_PATHS =
            java.util.Set.of("sets", "likes", "tracks", "albums", "reposts", "popular", "charts");

    /**
     * SoundCloud の URL の slug を曲名として読む。
     *
     * <p>SoundCloud は {@code /<投稿者>/<曲名をハイフンで繋いだ slug>} で曲を指すので、track JSON が
     * {@code title} を欠いていても検索語を作れる (2026-11-01 実測)。曲名ではなくパスの種類
     * ({@code /<user>/sets/...}) は slug ではないので空を返す。
     *
     * <p>記号を落として語を空白で繋いだだけの文字列なので、表示ではなく<b>検索語</b>に使う。
     *
     * @param url SoundCloud の URL ({@code null} 可)
     * @return 曲名として読んだ slug。読めなければ空
     */
    static String titleFromSoundCloudUrl(String url) {
        final String path = slugOf(url);
        final int slash = path.indexOf('/');
        if (slash < 0) {
            return "";
        }
        String slug = path.substring(slash + 1);
        final int query = slug.indexOf('?');
        if (query >= 0) {
            slug = slug.substring(0, query);
        }
        final int nestedSlash = slug.indexOf('/');
        if (nestedSlash >= 0) {
            slug = slug.substring(0, nestedSlash);
        }
        if (slug.isEmpty() || SOUNDCLOUD_NON_TRACK_PATHS.contains(slug.toLowerCase(Locale.ROOT))) {
            return "";
        }
        return slug.replace('-', ' ').replace('_', ' ').trim();
    }

    /**
     * 代替ソース検索 1 回分の結果。
     *
     * <p>{@code failed} を持つのは、「検索が走って 0 件」と「検索が答えなかった」を
     * 呼び出し側で区別するため。前者は「探したが無かった」、後者は「探せていない」で、
     * 元の失敗を確定させてよいかが変わる。
     *
     * @param tracks 候補 (最大 {@link #MAX_CANDIDATES} 件)
     * @param failed 検索そのものが失敗したか
     */
    private record Candidates(List<AudioTrack> tracks, boolean failed) {

        static Candidates found(List<AudioTrack> tracks) {
            return new Candidates(tracks, false);
        }

        static Candidates unanswered() {
            return new Candidates(List.of(), true);
        }
    }

    /**
     * 検索の結果を先頭から数件まで読む。検索そのものが失敗したら {@code failed} を立てて返す
     * (代替ソース探しは「見つからなければ従来の失敗に落ちる」だけなので、ここで投げない)。
     *
     * <p>入れ子の一覧では<b>埋め戻しを行わない</b> ({@link #LIST_SUBSTITUTE_SUPPRESSED})。
     * ここから走る検索の先で落とした曲まで代わりを探すと、1 曲のために待ち時間が積み上がる。
     *
     * @param query     {@code scsearch:} 等の接頭辞つき検索クエリ
     * @param timeoutMs 待ち上限
     * @return 候補 (最大 {@link #MAX_CANDIDATES} 件) と、検索が答えたか
     */
    private Candidates loadCandidates(String query, long timeoutMs) {
        // この検索は「一覧から落とした曲の代わり」を探すために走ることがある。入れ子の一覧でも
        // 同じことをすると、待ち時間が積み上がるだけで結果が変わらないので内側では埋め戻さない。
        final boolean outer = LIST_SUBSTITUTE_SUPPRESSED.get();
        LIST_SUBSTITUTE_SUPPRESSED.set(true);
        try {
            return searchCandidates(query, timeoutMs);
        } finally {
            LIST_SUBSTITUTE_SUPPRESSED.set(outer);
        }
    }

    /**
     * {@link #loadCandidates} の本体 (入れ子の抑止を外した検索)。
     *
     * @param query     {@code scsearch:} 等の接頭辞つき検索クエリ
     * @param timeoutMs 待ち上限
     * @return 候補 (最大 {@link #MAX_CANDIDATES} 件) と、検索が答えたか
     */
    private Candidates searchCandidates(String query, long timeoutMs) {
        final CompletableFuture<Candidates> future = new CompletableFuture<>();
        apm.loadItem(new AudioReference(query, null), new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                future.complete(Candidates.found(List.of(track)));
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                final List<AudioTrack> tracks = new ArrayList<>();
                for (final AudioTrack track : playlist.getTracks()) {
                    if (track != null && tracks.size() < MAX_CANDIDATES) {
                        tracks.add(track);
                    }
                }
                future.complete(Candidates.found(tracks));
            }

            @Override
            public void noMatches() {
                future.complete(Candidates.found(List.of()));
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                LOGGER.debug("Alternate source search failed ({})", query, exception);
                future.complete(Candidates.unanswered());
            }
        });
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (final TimeoutException | ExecutionException ex) {
            LOGGER.debug("Alternate source search did not answer in time ({})", query);
            return Candidates.unanswered();
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Candidates.unanswered();
        }
    }

    /**
     * 元ソースの表示情報と代替検索用の正規化情報を分ける。
     * 配信クレジットを採用できた場合は構造化済み情報として両方に使う。
     */
    static SourceMetadata sourceMetadata(String rawTitle, String rawAuthor,
            YoutubeWatchPage.Result watch) {
        final String title = safe(rawTitle).trim();
        final String author = safe(rawAuthor).trim();
        if (watch != null
                && title.equals(watch.title())
                && author.equals(watch.author())) {
            final var distributed = YoutubeMusicMetadata.fromDescription(
                    title, author, watch.shortDescription());
            if (distributed.isPresent()) {
                final YoutubeMusicMetadata.Value value = distributed.orElseThrow();
                if (!allGenericNames(value.artists())) {
                    final String artists = String.join(" · ", value.artists());
                    // cleaner 経路と同じ前置き除去を掛け、説明欄の有無で表示・検索が
                    // 変わらないようにする ("Adele - Hello" on "Adele - Topic" → "Hello")
                    final String valueTitle = MetadataCleaner.stripArtistPrefix(
                            value.title(), value.artists().get(0));
                    // 検索語は先頭アーティストに絞る: 「A · B · C」全文を投げると
                    // 区切り文字が検索語を汚しうるため、主アーティスト+曲名で引く
                    return new SourceMetadata(valueTitle, artists,
                            valueTitle, value.artists().get(0));
                }
            }
        }

        final boolean samePage = watch != null && title.equals(watch.title()) && author.equals(watch.author());
        final String[] display = MetadataCleaner.display(title, author, samePage ? watch.shortDescription() : null);
        final String[] search = MetadataCleaner.clean(title, author);
        if (isGenericTopicAuthor(author)) {
            // Release / Various Artists は自動生成配信の仮置き名で、曲固有情報が取れない時に
            // アーティストとして検索・表示へ流さない。Topicでない同名の投稿者は対象外。
            display[1] = "";
            search[1] = "";
        }
        return new SourceMetadata(display[0], display[1], search[0], search[1]);
    }

    private static boolean isTopicAuthor(String author) {
        return author != null && author.trim().toLowerCase(Locale.ROOT).endsWith(" - topic");
    }

    private static boolean isGenericTopicAuthor(String author) {
        if (!isTopicAuthor(author)) {
            return false;
        }
        final String prefix = author.trim().substring(0,
                author.trim().length() - " - Topic".length()).trim();
        return isGenericName(prefix);
    }

    /** 配信クレジットのアーティスト列が全部「仮置き名」なら真 (実名として採用しない)。 */
    private static boolean allGenericNames(java.util.List<String> artists) {
        if (artists == null || artists.isEmpty()) {
            return true;
        }
        for (final String artist : artists) {
            if (!isGenericName(artist)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isGenericName(String name) {
        final String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        return n.equals("release") || n.equals("various artists");
    }

    private static boolean isNormalizedWatchUrl(String url) {
        return url != null
                && url.matches("https://www\\.youtube\\.com/watch\\?v=[A-Za-z0-9_-]{11}");
    }

    record SourceMetadata(String displayTitle, String displayAuthor,
            String searchTitle, String searchAuthor) {
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
     * 盤の曲情報を使って、再生時にも代替ソースを探す。
     *
     * <h2>なぜ再生時にも探すか</h2>
     * 代替ソース探しは制作時 ({@link #resolve}) に置いてある — 再生時に各 client が独立に
     * 検索すると、人によって違う曲が鳴るため。だが<b>制作時には鳴っていた URL が、後から
     * YouTube 側で弾かれる</b>ことがある (bot 判定は回線と時機で変わる)。その時に何もしないと、
     * 盤は永久に鳴らないままになる。制作時と同じ検索をここでも使い、見つかった先を覚えておく。
     *
     * <h2>検索の条件</h2>
     * <ul>
     * <li>YouTube の単曲 URL で、理由が {@link #firesSubstitute} の集合にある時
     *     (制作時の発火集合と同じ。直リンク HTTP の 403 は {@code BOT_CHECK} に分類されるが、
     *     {@link #isYoutubeTarget} が偽なのでここへ来ない)</li>
     * <li>SoundCloud の単曲 URL で、理由が {@link FailureReason#PREVIEW_ONLY} /
     *     {@link FailureReason#DRM_PROTECTED} の時 (制作時に見逃した盤・後から試聴版へ
     *     変わった盤の拾い直し)</li>
     * </ul>
     *
     * <p><b>watch ページは取りに行かない</b>。尺は盤が持っている値を使うので、oEmbed も
     * watch ページも要らない (取ると再生のたびに数百 KB の往復が増える)。尺が {@code 0}
     * なら尺で足切りせず、一番長い候補を採る。
     *
     * @param url     鳴らなかった盤の URL
     * @param failure その失敗 (SoundCloud の試聴版は曲情報もここに載っている)
     * @param hint    盤が持っている曲情報 ({@code null} 可)
     * @return 代わりに鳴らせるトラック。探す条件に当たらない・探し切れなかった場合は
     *         {@code null} (呼び出し側は元の理由のまま失敗させる)
     * @throws ResolveException 検索が実際に走って使える候補が無かった
     *         ({@link FailureReason#NO_ALTERNATIVE_FOUND})。ただし元の失敗が一時的なものなら
     *         投げずに {@code null} を返す (元の理由でやり直せるようにするため)
     */
    private TrackInfo substituteForPlayback(String url, ResolveException failure, TrackHint hint) {
        try {
            return searchSubstituteForPlayback(url, failure, hint);
        } catch (final ResolveException noAlternative) {
            // 元の失敗が一時的なもの (bot 判定・接続断) なら、「代わりも無い」で確定させない。
            // 検索が 0 件でも、時間を置けば元の URL が鳴りうるので、元の理由を返して
            // client 側のやり直し ({@code TransientOpenRetry}) に乗せる。制作時は逆で、
            // 焼く前に「代わりも無い」を利用者へ伝える必要があるのでこの読み替えはしない。
            if (noAlternative.reason() != FailureReason.NO_ALTERNATIVE_FOUND
                    || !isRetryable(failure.reason(), false)) {
                throw noAlternative;
            }
            LOGGER.info("No alternate source for {} now; keeping the transient failure [{}]",
                    url, failure.reason());
            return null;
        }
    }

    /**
     * {@link #substituteForPlayback} の検索本体。見つからなければ
     * {@link FailureReason#NO_ALTERNATIVE_FOUND} を投げる (読み替えは呼び出し側)。
     */
    private TrackInfo searchSubstituteForPlayback(String url, ResolveException failure, TrackHint hint) {
        final String normalized = normalizeYoutubeUrl(url);
        if (isSearch(normalized)) {
            return null;
        }
        final FailureReason reason = failure.reason();
        final boolean soundCloud = isSoundCloudTarget(normalized);
        if (soundCloud) {
            if (reason != FailureReason.PREVIEW_ONLY && reason != FailureReason.DRM_PROTECTED) {
                return null;
            }
            // 試聴版・DRM の失敗は曲名・投稿者・全長を持って帰る。盤のメタより新しいので優先する
            // (制作時と同じ経路 = 同じ検索語・同じ尺で探せる)。
            if (failure instanceof UnplayableSoundCloudResolve unplayable) {
                // 再生時は予算を取り直さない (鳴らなかった盤は鳴らないまま。client 側のやり直しへ)。
                return substituteForSoundCloud(url, unplayable.track, false);
            }
        } else if (!isYoutubeTarget(normalized) || !firesSubstitute(reason)) {
            return null;
        }
        if (hint == null) {
            return null;
        }
        final String title = safe(hint.title()).trim();
        final String author = safe(hint.author()).trim();
        if (title.isBlank() || author.isBlank()) {
            // 曲名かアーティストが無いと query を作れない。制作時と同じく「探さずに諦める」。
            LOGGER.info("No title/author on the disc to look for another source ({})", url);
            return null;
        }
        // 0 以下 = 尺は分からない。負の値は渡さない (substitute は負を見ると watch ページへ
        // 尺を取りに行く。ここでは絶対に取りに行かせない)。
        final long sourceMs = Math.max(0L, hint.durationMs());
        final String[] prefixes = soundCloud ? SOUNDCLOUD_SUBSTITUTE_PREFIXES : ALTERNATE_SEARCH_PREFIXES;
        // 再生時は予算を取り直さない ({@link #SUBSTITUTE_REFILL_BUDGET_MS})。
        return substitute(prefixes, title, title, author, title, author, url, null,
                new YoutubeWatchPage.Result(sourceMs, "", "", ""),
                clockMs.getAsLong() + SUBSTITUTE_BUDGET_MS, false);
    }

    /**
     * 再生用に 1 本開く (失敗は {@link ResolveException} のまま返す)。
     *
     * @param url     開く URL
     * @param startMs 開始位置 (ミリ秒)
     * @return 開けたソース
     * @throws ResolveException 開けなかった
     */
    private OpenStreamResult openPlayback(String url, long startMs) throws ResolveException {
        return OpenStreamResult.ok(startPlayback(loadTrackSync(url), startMs, url));
    }

    @Override
    public OpenStreamResult openStreamDetailed(String url, long startMs, TrackHint hint) {
        // 前回見つけた代替先があれば、元の URL を試す前にそこを開く。鳴らなかった盤は
        // たいてい同じ理由で鳴らないので、毎回検索の数秒を待たせない。
        final RememberedSubstitute remembered = playbackSubstitutes.get(url);
        if (remembered != null) {
            if (remembered.expiredAt(clockMs.getAsLong())) {
                // 期限が切れたら元の URL から試し直す。相手の配信が戻っていることがある。
                LOGGER.debug("Forgot the alternate source found earlier for {} (expired)", url);
                playbackSubstitutes.remove(url, remembered);
            } else if (!openAllowed(remembered.uri())) {
                LOGGER.warn("Refusing the alternate source found earlier for {}"
                        + " (outside the allowed hosts)", url);
                playbackSubstitutes.remove(url, remembered);
            } else {
                try {
                    final OpenStreamResult reused = openPlayback(remembered.uri(), startMs);
                    LOGGER.info("Playing the alternate source found earlier for {} -> {}",
                            url, remembered.uri());
                    return reused;
                } catch (final ResolveException ex) {
                    LOGGER.info("The alternate source found earlier no longer plays ({}): [{}] {}",
                            remembered.uri(), ex.reason(), ex.detail());
                    playbackSubstitutes.remove(url, remembered);
                }
            }
        }
        final ResolveException directFailure;
        try {
            return openPlayback(url, startMs);
        } catch (final ResolveException ex) {
            LOGGER.warn("Failed to load track for playback ({}) [{}] {}", url, ex.reason(),
                    ex.detail());
            directFailure = ex;
        }
        final TrackInfo substitute;
        try {
            substitute = substituteForPlayback(url, directFailure, hint);
        } catch (final ResolveException ex) {
            // 探したが無かった (NO_ALTERNATIVE_FOUND)。元の理由ではなくこちらを返す —
            // 「観られない」と「代わりも無い」では、利用者が次にできることが違う。
            LOGGER.warn("No usable alternate source for playback ({}) [{}] {}", url, ex.reason(),
                    ex.detail());
            return OpenStreamResult.failed(ex.reason(), ex.detail());
        }
        if (substitute == null) {
            return OpenStreamResult.failed(directFailure.reason(), directFailure.detail());
        }
        // 検索で見つけた先は mod 側の入口 (UrlGuard) を通っていない。内部アドレス等を
        // 指していたら開かない — 覚えにも残さない (次も同じ URL を掴むので)。
        if (!openAllowed(substitute.uri())) {
            LOGGER.warn("Refusing an alternate source for {} (outside the allowed hosts)", url);
            return OpenStreamResult.failed(FailureReason.BLOCKED_URL,
                    "the alternate source is outside the allowed hosts");
        }
        playbackSubstitutes.put(url, new RememberedSubstitute(substitute.uri(),
                clockMs.getAsLong() + PLAYBACK_SUBSTITUTE_TTL_MS));
        try {
            final OpenStreamResult alternative = openPlayback(substitute.uri(), startMs);
            LOGGER.info("Playing an alternate source for {} -> {}", url, substitute.uri());
            return alternative;
        } catch (final ResolveException ex) {
            LOGGER.warn("The alternate source found for playback also failed ({}) [{}] {}",
                    substitute.uri(), ex.reason(), ex.detail());
            playbackSubstitutes.remove(url, substitute.uri());
            return OpenStreamResult.failed(ex.reason(), ex.detail());
        }
    }

    @Override
    public void setUrlGate(UrlGate gate) {
        this.urlGate = gate;
    }

    /**
     * その URL を開いてよいか。検査が差し込まれていなければ {@code false}。
     *
     * <p>盤の URL は mod 側の入口で {@code UrlGuard} を通っているが、再生時に検索で見つける
     * 代わりの配信元は loader の中で決まるので、ここでしか検査できない ({@link UrlGate})。
     * 差し込みが無い時を fail-closed にしてあるのは、検査できない URL へは接続しないため。
     *
     * @param url 開こうとしている URL ({@code null} 可)
     * @return 開いてよければ {@code true}
     */
    private boolean openAllowed(String url) {
        final UrlGate gate = urlGate;
        return gate != null && gate.allows(url);
    }

    @Override
    public IAudioSource openTrustedFile(Path file, long startMs) {
        try {
            return beginLocalPlayback(file, startMs);
        } catch (final ResolveException ex) {
            LOGGER.warn("Failed to load a trusted local audio file [{}] {}", ex.reason(),
                    ex.detail());
            return null;
        }
    }

    @Override
    public OpenStreamResult openTrustedFileDetailed(Path file, long startMs) {
        try {
            return OpenStreamResult.ok(beginLocalPlayback(file, startMs));
        } catch (final ResolveException ex) {
            LOGGER.warn("Failed to load a trusted local audio file [{}] {}", ex.reason(),
                    ex.detail());
            return OpenStreamResult.failed(ex.reason(), ex.detail());
        }
    }

    private IAudioSource beginLocalPlayback(Path file, long startMs) {
        final AudioTrack track = loadTrustedFile(file);
        // seek 位置の補正も MP3 だけ。他コンテナはコンテナ側 seeker が正確なので生の値を渡す。
        final long correctedStartMs = startMs <= 0L ? 0L
                : isMp3Container(track) ? LocalMp3Index.read(file).lavaSeekMs(startMs) : startMs;
        return beginPlayback(track, correctedStartMs);
    }

    /**
     * 保存領域の検証を済ませたファイルを同期ロードする。
     *
     * <p>ここは root を知らないため、保存領域内かどうかは判定できない。呼び出し側が内容ハッシュを
     * 検証して解決した {@link Path} だけを受け取る契約であり、元のアップロード名や任意の文字列を
     * このメソッドへ通してはいけない。
     */
    private AudioTrack loadTrustedFile(Path file) {
        Objects.requireNonNull(file, "file");
        final Path normalized = file.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized) || !Files.isReadable(normalized)) {
            throw new ResolveException(FailureReason.UNKNOWN,
                    "trusted local audio file is not readable");
        }

        final AudioItem item;
        try {
            item = localFiles.loadItem(apm, new AudioReference(normalized.toString(), null));
        } catch (final Throwable thrown) {
            LOGGER.warn("Failed to inspect a trusted local audio file", thrown);
            throw new ResolveException(FailureReason.UNKNOWN,
                    "could not inspect the local audio file");
        }
        if (!(item instanceof AudioTrack track)) {
            throw new ResolveException(FailureReason.UNKNOWN,
                    "unsupported or invalid local audio file");
        }
        // matroska probe は DocType や track 種別を見ないため、映像 track を含む MKV も
        // 音声として受理してしまう。映像バイトが原本ごと保存・転送される経路を閉じるため、
        // 保存境界では track 一覧に VIDEO が無いことをここで確認する (音声のみの
        // MKA / WebM はそのまま通る)。検査不能な構造も拒否側に倒す。
        if (track instanceof LocalAudioTrack local
                && local.getContainerTrackFactory().probe instanceof MatroskaContainerProbe) {
            final boolean audioOnly;
            try {
                audioOnly = MatroskaTrackScanner.isAudioOnly(normalized);
            } catch (final IOException e) {
                throw new ResolveException(FailureReason.UNKNOWN,
                        "could not verify the matroska track list");
            }
            if (!audioOnly) {
                throw new ResolveException(FailureReason.UNKNOWN,
                        "video tracks cannot be stored as audio");
            }
        }
        return track;
    }

    /**
     * 解決済みトラックを希望位置から鳴らし始める ({@code openStream} 系の共通後半)。
     *
     * <p>YouTube だけは {@link RetryingAudioSource} で包む。client を 1 本だけにしても
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
                REOPEN_RETRIES, RetryingAudioSource.DEFAULT_GRACE_MS);
    }

    /** normalize が入力 decode に使う、保存用と分離した入力 registry。 */
    LocalAudioSourceManager inputFiles() {
        return localInputs;
    }

    /** normalize が decode 用 player を立てる manager。 */
    AudioPlayerManager playerManager() {
        return apm;
    }

    /** player を作ってトラックを流し始める (包む前の素のソース)。 */
    LavaAudioSource beginPlayback(AudioTrack track, long startMs) {
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
        if (!YoutubeUrls.isYoutubeHost(url)) {
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
        return YoutubeUrls.isSearchQuery(url);
    }

    /** YouTube を相手にする URL か (セッションの入れ替えが効く相手だけ再試行する)。 */
    static boolean isYoutubeTarget(String url) {
        return YoutubeUrls.isYoutube(url);
    }

    /** SoundCloud の URL か (試聴版・DRM の時に代替ソースを探す相手)。 */
    static boolean isSoundCloudTarget(String url) {
        return url != null && SC_HOST.matcher(url).find();
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
     *
     * <p>{@link FailureReason#PREVIEW_ONLY} は {@code default} のまま false でよい。相手が
     * 配信していないものは、開き直しても visitorId を替えても出てこない。
     *
     * <p>{@link FailureReason#SOURCE_REFUSED} を<b>明示で</b>書いてあるのは、ここが
     * {@code default -> false} に落ちると 5xx が再試行されなくなるため。この理由が生まれる前は
     * 番号つきの失敗が {@link FailureReason#CONNECTION_FAILED} に同居していて再試行されていた。
     */
    static boolean isRetryable(FailureReason reason, boolean search) {
        return switch (reason) {
            case BOT_CHECK, CONNECTION_FAILED, SOURCE_REFUSED, UNKNOWN -> true;
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
                // ytsearch/scsearch は候補列として返るので先頭を採る。通常URLで selected が無い
                // AudioPlaylist は純粋な再生リストであり、単曲盤へ黙って先頭だけを焼かない。
                if (selected == null && isSearch(url) && !playlist.getTracks().isEmpty()) {
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
            if (cause instanceof UnplayableSoundCloudTrack soundCloud) {
                throw new UnplayableSoundCloudResolve(reason, detail, soundCloud);
            }
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

    /** {@link UnplayableSoundCloudTrack} を {@link #resolve} まで運ぶ失敗。理由は元のまま。 */
    static final class UnplayableSoundCloudResolve extends ResolveException {

        private static final long serialVersionUID = 1L;

        final transient UnplayableSoundCloudTrack track;

        UnplayableSoundCloudResolve(FailureReason reason, String detail, UnplayableSoundCloudTrack track) {
            super(reason, detail);
            this.track = track;
        }
    }

    /**
     * 試聴版だけの曲を<b>失敗として返す</b> SoundCloud source manager。
     *
     * <h2>なぜ要るか</h2>
     * SoundCloud GO+ の曲は冒頭の数十秒しか配信されないのに、track の JSON は尺を
     * {@code full_duration} = <b>全長</b>で名乗る。だから {@code TrackMatch#durationFits} の
     * 尺検査は素通りし、MDM は「全長のディスク」を作ってしまう。再生は始まるが途中で無音になり、
     * <b>画面には何も出ない</b> (2026-08-23 の利用者報告 C17)。
     *
     * <p>ライブラリには判定 ({@code monetization_model: SUB_HIGH_TIER}) と切り替え口
     * ({@code Builder#withFilterOutPreviewTracks}) が既に在り、直読み・プレイリスト・検索の
     * 3 経路すべてで参照される。<b>既定で切ってあるだけ</b>なので、立てれば済む。
     *
     * <h2>なぜ builder でなく継承なのか</h2>
     * フラグを立てただけだと、弾かれた曲は<b>戻り値 {@code null}</b> になり
     * ({@code loadTrack(String, boolean)} の実測バイトコード)、呼び出し元の
     * {@code processAsSingleTrack} がそれを {@code AudioReference.NO_TRACK} に変え、
     * {@code DefaultAudioPlayerManager} が {@code noMatches()} を呼ぶ。MDM の
     * {@link MusicLoaderImpl#loadOnce} はそれを {@link FailureReason#UNSUPPORTED_URL} =
     * 「非対応のリンクです。YouTube か音声の直リンクをお試しください」に落とす。
     * <b>正当な SoundCloud のリンクに対してこれは嘘になる</b>。理由が分かるようにするのが
     * この版の目的なので、null を理由つきの例外に変える。
     *
     * <p>目印は {@link FailureClassifier#PREVIEW_MARKER} 一本に縛ってある。ここで自前の文面を
     * 書くと、分類器が拾えず {@link FailureReason#UNKNOWN} へ黙って落ちる。
     *
     * <h2>2 引数の側を override する理由</h2>
     * 1 引数の {@code loadTrack(String)} は 2 引数版へ {@code invokevirtual} で委譲している。
     * 2 引数の側を override すれば、どちらの入口から来ても通る。逆 (1 引数だけを override) は
     * 現在の呼び出し経路にたまたま乗っているだけになる。
     *
     * <p>{@code allowFilter} が false の時に投げないのは、その時の {@code null} は
     * 「試聴版だったから」ではないため (フィルタを通らない経路の戻り値)。
     */
    static final class PreviewAwareSoundCloud extends SoundCloudAudioSourceManager {

        /**
         * 署名付き CDN 用の HTTP。<b>SoundCloud のフィルタを通さない</b> — 素の
         * {@code SoundCloudHttpContextFilter} は {@code sndcdn.com} 以外の host に
         * {@code client_id} を足し、それが SoundCloud の署名付き URL
         * ({@code playback.media-streaming.soundcloud.cloud}) を 403 にする。
         */
        private final HttpInterfaceManager mediaHttp =
                HttpClientTools.createDefaultThreadLocalManager();

        /**
         * 一覧 (検索・liked・set) で落とした曲の記録。このスレッドの一覧 1 つ分だけ持つ。
         *
         * <p>一覧の曲は 1 件ずつ {@code loadFromTrackData} (set は {@code isTrackBlocked}) を通り、
         * 落とした曲は {@code null} か「読み飛ばし」になる。<b>どの曲を落としたか</b>を残しておかないと、
         * 呼び出し側は「一覧が短い」ことしか分からず、代わりを探せない。
         *
         * <p>例外ではなくこの記録で渡すのは、落とすのが失敗ではなく一覧 1 件の扱いだから
         * ({@link DroppedTrack})。
         */
        private static final ThreadLocal<List<DroppedTrack>> DROPPED =
                ThreadLocal.withInitial(ArrayList::new);

        /** 落とした曲を記録する。{@code SINGLE_TRACK} の時は呼ばない (単曲は例外で運ぶ)。 */
        static void recordDropped(JsonBrowser trackData, boolean preview) {
            DROPPED.get().add(DroppedTrack.from(trackData, preview));
        }

        /**
         * このスレッドで一覧から落とした曲を取り出し、記録を空にする。
         *
         * @return 落とした曲 (読んだ順)
         */
        static List<DroppedTrack> takeDropped() {
            final List<DroppedTrack> dropped = new ArrayList<>(DROPPED.get());
            DROPPED.get().clear();
            return dropped;
        }

        /** この SoundCloud manager を持つ loader。{@code null} なら埋め戻しをしない (試験の単体利用)。 */
        private final MusicLoaderImpl owner;

        // package-private: 試験が data loader を差し替えて単曲の経路を通すため。
        PreviewAwareSoundCloud(SoundCloudDataReader dataReader,
                SoundCloudDataLoader dataLoader, SoundCloudFormatHandler formatHandler,
                SoundCloudPlaylistLoader playlistLoader) {
            this(dataReader, dataLoader, formatHandler, playlistLoader, null);
        }

        PreviewAwareSoundCloud(SoundCloudDataReader dataReader, SoundCloudDataLoader dataLoader,
                SoundCloudFormatHandler formatHandler, SoundCloudPlaylistLoader playlistLoader,
                MusicLoaderImpl owner) {
            // 引数の並びは createDefault と同じ。最後の true だけが違う (= 試聴版を弾く)。
            super(true, dataReader, dataLoader, formatHandler, playlistLoader, true);
            this.owner = owner;
        }

        /**
         * {@code createDefault} と同じ部品で組む (あちらは戻り値の型が固定なので使えない)。
         *
         * <p>format handler だけは AAC (fMP4) の HLS を足した {@link SoundCloudAacFormatHandler}。
         * プレイリスト (set) の曲は manager を通らずに組み立てられるので、そちらも
         * {@link AacAwarePlaylistLoader} で同じ差し替えを通す。
         */
        static PreviewAwareSoundCloud create() {
            return create(null);
        }

        /**
         * 一覧から落とした曲を埋め直せる manager を作る。
         *
         * @param owner 検索を借りる loader。{@code null} なら埋め戻しをしない
         */
        static PreviewAwareSoundCloud create(MusicLoaderImpl owner) {
            final SoundCloudDataReader dataReader = new DefaultSoundCloudDataReader();
            final SoundCloudDataLoader dataLoader = new DefaultSoundCloudDataLoader();
            final SoundCloudAacFormatHandler formatHandler = new SoundCloudAacFormatHandler();
            // set の曲は isTrackBlocked が true の曲を黙って飛ばす。DRM・試聴版の曲 1 件で
            // set 全体が落ちないよう、その判定を format handler の結果から作る。
            final AacAwarePlaylistLoader playlistLoader = new AacAwarePlaylistLoader(
                    new DefaultSoundCloudPlaylistLoader(dataLoader,
                            new SkippingDataReader(dataReader, formatHandler), formatHandler));
            final PreviewAwareSoundCloud manager = new PreviewAwareSoundCloud(dataReader,
                    dataLoader, formatHandler, playlistLoader, owner);
            // 曲の組み立ては manager 側の口 (adapt) を借りる。生成順の都合でここで挿す。
            playlistLoader.attach(manager);
            return manager;
        }

        /**
         * 今のスレッドが「単曲の URL を読む」経路にいるか。単曲なら DRM・試聴版を理由つきの
         * 例外にし、一覧 (検索・liked) なら該当の曲だけ飛ばす。ライブラリは一覧の曲を 1 件ずつ
         * {@code loadFromTrackData} で組み立て、例外を握らないので、1 件の DRM で検索全体が
         * 落ちていた (2026-11-01 実測: {@code scsearch:ODESZA Say My Name})。
         */
        private static final ThreadLocal<Boolean> SINGLE_TRACK = ThreadLocal.withInitial(() -> false);

        @Override
        public AudioTrack loadTrack(String identifier, boolean allowFilter) {
            final boolean outer = SINGLE_TRACK.get();
            SINGLE_TRACK.set(true);
            final AudioTrack track;
            try {
                track = super.loadTrack(identifier, allowFilter);
            } finally {
                SINGLE_TRACK.set(outer);
            }
            if (track == null && allowFilter) {
                throw new FriendlyException(FailureClassifier.PREVIEW_MARKER,
                        FriendlyException.Severity.COMMON, null);
            }
            return adapt(track);
        }

        /**
         * 一覧の結果から、飛ばした曲の {@code null} を<b>代わりの曲で埋め直す</b>
         * (後段が {@code get(0)} 等で踏まないようにし、先頭の曲が消えて別の曲になるのを防ぐ)。
         *
         * <p>埋め戻しは入れ子の検索の内側では行わない ({@link #LIST_SUBSTITUTE_SUPPRESSED})。
         * 代わりを探すための検索の結果でも同じことをすると、待ち時間が積み上がるだけになる。
         */
        @Override
        public AudioItem loadItem(AudioPlayerManager manager, AudioReference reference) {
            final boolean suppressed = LIST_SUBSTITUTE_SUPPRESSED.get();
            if (!suppressed) {
                // 前の一覧の記録を持ち越さない (同じスレッドで解決が続く)。
                takeDropped();
            }
            final AudioItem item = super.loadItem(manager, reference);
            final List<DroppedTrack> dropped = suppressed ? List.of() : takeDropped();
            if (!(item instanceof AudioPlaylist playlist)) {
                return item;
            }
            final List<AudioTrack> tracks = playlist.getTracks();
            // contains(null) は List.of 系の不変リストで NPE を投げるので使わない。
            final boolean hasDropped = tracks.stream().anyMatch(Objects::isNull);
            if (dropped.isEmpty() && !hasDropped) {
                return item;
            }
            final List<AudioTrack> kept = owner != null && !dropped.isEmpty()
                    ? owner.fillSubstitutes(tracks, dropped)
                    : withoutNulls(tracks);
            return new BasicAudioPlaylist(playlist.getName(), kept, playlist.getSelectedTrack(),
                    playlist.isSearchResult());
        }

        /** {@code null} を除いた一覧。{@code null} が無ければ同じリストを返す。 */
        private static List<AudioTrack> withoutNulls(List<AudioTrack> tracks) {
            if (tracks.stream().noneMatch(Objects::isNull)) {
                return tracks;
            }
            final List<AudioTrack> kept = new ArrayList<>(tracks.size());
            for (final AudioTrack track : tracks) {
                if (track != null) {
                    kept.add(track);
                }
            }
            return kept;
        }

        /**
         * プレイリスト・検索・liked のすべてが通る組み立て口。AAC の曲だけ自前の track へ
         * 差し替える (素の {@code SoundCloudAudioTrack} は {@code getM3uInfo} から素の m3u track を
         * 作り、初期化セグメントを繋がないので鳴らない)。
         */
        @Override
        protected AudioTrack loadFromTrackData(JsonBrowser trackData) {
            try {
                return adapt(super.loadFromTrackData(trackData));
            } catch (FriendlyException e) {
                if (!isUnplayableMarker(e)) {
                    throw e;
                }
                final boolean preview = FailureClassifier.PREVIEW_MARKER.equals(e.getMessage());
                if (!SINGLE_TRACK.get()) {
                    LOGGER.debug("Skipping a SoundCloud track in a list: {}", e.getMessage());
                    // 一覧では呼び出し側がこの 1 曲を代わりの曲で埋め直せるよう、曲の情報を残す。
                    recordDropped(trackData, preview);
                    return null;
                }
                // 単曲: 曲の情報を持たせて返し、呼び出し側が同じ曲を別の配信元から探せるようにする。
                // プレビューの duration は 30 秒前後なので、full_duration が無い時に代わりに使うと
                // 正しい全曲の候補が長さで全部落ちる。その時は「長さ不明」(0) にする。DRM の duration は全長。
                final long full = trackData.get("full_duration").asLong(0L);
                throw new UnplayableSoundCloudTrack(e.getMessage(),
                        trackData.get("title").safeText(), trackData.get("user").get("username").safeText(),
                        full > 0L ? full : preview ? 0L : trackData.get("duration").asLong(0L));
            }
        }

        /** DRM・試聴版だけの曲を示す、MDM 自身の目印か。 */
        static boolean isUnplayableMarker(FriendlyException e) {
            final String message = e.getMessage();
            return FailureClassifier.DRM_MARKER.equals(message)
                    || FailureClassifier.PREVIEW_MARKER.equals(message);
        }

        /**
         * エンコード済み track の復元経路。ここだけ素の {@code SoundCloudAudioTrack} が
         * 生き残ると、AAC の曲が「読み込みは成功・再生で失敗」になる。
         */
        @Override
        public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) {
            return adapt(super.decodeTrack(trackInfo, input));
        }

        @Override
        public void shutdown() {
            super.shutdown();
            try {
                mediaHttp.close();
            } catch (IOException e) {
                LOGGER.debug("Failed to close the SoundCloud media HTTP client", e);
            }
        }

        /**
         * AAC の HLS を指す曲を {@link SoundCloudAacHlsAudioTrack} に差し替える。それ以外は
         * そのまま返す (YouTube・直リンク・SoundCloud の他形式の挙動を変えない)。
         *
         * @param track 差し替え前の track ({@code null} 可)
         * @return 差し替え後の track
         */
        AudioTrack adapt(AudioTrack track) {
            if (track == null) {
                return null;
            }
            final String identifier = track.getInfo().identifier;
            if (identifier == null || !identifier.startsWith(SoundCloudAacFormatHandler.AAC_PREFIX)
                    || track instanceof SoundCloudAacHlsAudioTrack) {
                return track;
            }
            return new SoundCloudAacHlsAudioTrack(track.getInfo(), this, mediaHttp,
                    identifier.substring(SoundCloudAacFormatHandler.AAC_PREFIX.length()));
        }
    }

    /**
     * SoundCloud の set (プレイリスト) を読み、AAC の曲だけ自前の track へ差し替える薄い包み。
     *
     * <p>set の曲は {@code DefaultSoundCloudPlaylistLoader} が manager を通さずに
     * {@code SoundCloudAudioTrack} として組み立てるので、{@code loadFromTrackData} の差し替えでは
     * 届かない。ここを通さないと、set の URL を貼った時だけ「解決は成功・再生で無音」になる。
     */
    static final class AacAwarePlaylistLoader implements SoundCloudPlaylistLoader {

        private final SoundCloudPlaylistLoader delegate;
        /** 曲の差し替えを借りる相手。生成順の都合で構築後に挿す ({@code null} の間は素通し)。 */
        private PreviewAwareSoundCloud owner;

        AacAwarePlaylistLoader(SoundCloudPlaylistLoader delegate) {
            this.delegate = delegate;
        }

        void attach(PreviewAwareSoundCloud owner) {
            this.owner = owner;
        }

        @Override
        public AudioPlaylist load(String identifier, HttpInterfaceManager httpInterfaceManager,
                Function<AudioTrackInfo, AudioTrack> trackFactory) {
            final AudioPlaylist playlist =
                    delegate.load(identifier, httpInterfaceManager, trackFactory);
            if (playlist == null || owner == null) {
                return playlist;
            }
            final List<AudioTrack> tracks = new ArrayList<>(playlist.getTracks().size());
            for (final AudioTrack track : playlist.getTracks()) {
                tracks.add(owner.adapt(track));
            }
            return new BasicAudioPlaylist(playlist.getName(), tracks,
                    owner.adapt(playlist.getSelectedTrack()), playlist.isSearchResult());
        }
    }

    /**
     * set (プレイリスト) の曲のうち、DRM・試聴版だけで鳴らせない曲を
     * {@code isTrackBlocked} として申告する reader。ライブラリの set loader は blocked の曲を
     * 飛ばすので、1 件の DRM で set 全体が落ちなくなる。判定は format handler と同じ結果を使う。
     */
    static final class SkippingDataReader implements SoundCloudDataReader {

        private final SoundCloudDataReader delegate;
        private final SoundCloudFormatHandler formatHandler;

        SkippingDataReader(SoundCloudDataReader delegate, SoundCloudFormatHandler formatHandler) {
            this.delegate = delegate;
            this.formatHandler = formatHandler;
        }

        @Override
        public boolean isTrackBlocked(JsonBrowser trackData) {
            if (delegate.isTrackBlocked(trackData)) {
                // ライブラリ自身が弾いた曲 (地域制限など) も一覧から消える。代わりを探せるよう記録する。
                PreviewAwareSoundCloud.recordDropped(trackData, false);
                return true;
            }
            try {
                formatHandler.chooseBestFormat(delegate.readTrackFormats(trackData));
                return false;
            } catch (FriendlyException e) {
                final boolean unplayable = PreviewAwareSoundCloud.isUnplayableMarker(e);
                if (unplayable) {
                    PreviewAwareSoundCloud.recordDropped(trackData,
                            FailureClassifier.PREVIEW_MARKER.equals(e.getMessage()));
                }
                return unplayable;
            } catch (RuntimeException e) {
                // 未知の形式などは従来どおり、読み込みの側で落とす。
                return false;
            }
        }

        @Override
        public JsonBrowser findTrackData(JsonBrowser rootData) {
            return delegate.findTrackData(rootData);
        }

        @Override
        public String readTrackId(JsonBrowser trackData) {
            return delegate.readTrackId(trackData);
        }

        @Override
        public AudioTrackInfo readTrackInfo(JsonBrowser trackData, String identifier) {
            return delegate.readTrackInfo(trackData, identifier);
        }

        @Override
        public List<SoundCloudTrackFormat> readTrackFormats(JsonBrowser trackData) {
            return delegate.readTrackFormats(trackData);
        }

        @Override
        public JsonBrowser findPlaylistData(JsonBrowser rootData, String kind) {
            return delegate.findPlaylistData(rootData, kind);
        }

        @Override
        public String readPlaylistName(JsonBrowser playlistData) {
            return delegate.readPlaylistName(playlistData);
        }

        @Override
        public String readPlaylistIdentifier(JsonBrowser playlistData) {
            return delegate.readPlaylistIdentifier(playlistData);
        }

        @Override
        public List<JsonBrowser> readPlaylistTracks(JsonBrowser playlistData) {
            return delegate.readPlaylistTracks(playlistData);
        }
    }
}
