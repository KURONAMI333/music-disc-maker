package com.kuronami.musicdiscmaker.beat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.Config;
import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.audio.LoaderHolder;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.IMusicLoader;
import com.kuronami.musicdiscmaker.network.UrlGuard;

import net.minecraft.server.MinecraftServer;

/**
 * URL ごとのビートマップの在処。メモリ LRU → ディスクキャッシュ → その場解析 の 3 段。
 *
 * <p><b>解析は server 側で行う。</b> dedicated server にも LavaPlayer 一式が載っていて
 * {@code openStream} が呼べる (既存の URL 解決が server 経路なのと同じ)。これで全 client に同じ
 * 信号が出て、無人サーバでも動き、録画のたびに同じ絵になる。
 *
 * <h2>キャッシュの置き場</h2>
 * {@code <server or game dir>/music_disc_maker/cache/<sha1(url)>.beat}
 *
 * <p>ワールドの下ではなくインスタンス直下に置く: ビートマップは URL から決まる純粋な派生物で
 * ワールドに依存しないので、シングルプレイでワールドを作り直すたびに再解析するのは無駄。
 * <b>P5 (音源ローカルキャッシュ) は同じディレクトリの {@code <同じ sha1>.audio} を使えば、
 * 1 パスの副作用でビートマップも書ける</b> (キーが同一 = 同じ曲が二重に登録されない)。
 * そのため公開 API はここの {@link #ensure} / {@link #peek} 2 本に絞ってある。
 */
public final class BeatMaps {

    /** メモリに載せる最大曲数。1 曲 5 分で約 100KB なので上限は小さくてよい。 */
    private static final int MEMORY_ENTRIES = 32;
    private static final String CACHE_DIR = "music_disc_maker/cache";
    private static final String EXTENSION = ".beat";
    /** 申告尺のこの割合まで解析できて初めて「完成」とみなし、ディスクへ焼く。 */
    private static final double COMPLETE_RATIO = 0.95;

    /** アクセス順 LRU。読みも書きも短い synchronized で守る (tick から触るので待たせない)。 */
    private static final Map<String, BeatMap> MEMORY = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, BeatMap> eldest) {
            return size() > MEMORY_ENTRIES;
        }
    };
    /** 解析中の key。同じ URL の二重解析を防ぐ。 */
    private static final Set<String> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    /**
     * key ごとの「うまくいかなかった解析」の回数。{@link #MAX_ATTEMPTS} で打ち切る。
     *
     * <p>これが無いと 2 通りの無限ループになる: ① ディスクの申告尺が実体より長い場合
     * (再アップロード後の古いディスク・細工された component) に、毎回の再生で永久に再解析し続ける
     * ② リンク切れの URL を、再生中ずっと毎秒叩き続ける。
     * {@code durationMs} は component 由来で server が再導出していない値なので、信頼しきった判定にはできない。
     *
     * <p>{@link #install} で外から載せたマップはここに数字を持たない = 権威として扱われ、再解析されない。
     */
    private static final Map<String, Integer> FAILED_ATTEMPTS = new ConcurrentHashMap<>();
    /** key ごとの「直近に出した見送り理由」。同じ理由を毎秒書かないための状態。 */
    private static final Map<String, String> LAST_SKIP = new ConcurrentHashMap<>();
    /** 1 つの URL につき許す解析の回数。 */
    private static final int MAX_ATTEMPTS = 2;
    /** server が変わったら (ワールド切替・再起動) 進行中の解析を捨てるための世代番号。 */
    private static final AtomicInteger GENERATION = new AtomicInteger();

    private static volatile MinecraftServer currentServer;
    private static volatile Path cacheDir;
    private static volatile Semaphore slots;
    private static volatile int slotPermits;
    private static volatile ExecutorService pool;
    /**
     * 解析に使う loader。差し替えられるのは、実解析の経路
     * ({@code ensure → load → 解析 → publish → .beat 書き出し → 読み戻し}) を headless で
     * 通せるようにするため。この経路は {@code install} で合成マップを差し込むテストでは
     * 一度も踏まれない = 実機でしか壊れが出ない面だった。
     */
    private static volatile Supplier<IMusicLoader> loaderSource = LoaderHolder::get;

    private BeatMaps() {
    }

    /**
     * 解析済みならビートマップを返す。進行中なら「そこまで解析できた」途中のマップを返す
     * (先頭から順に埋まるので、再生位置が解析カーソルを追い越すまでは 0 が出るだけ)。
     *
     * @return 無ければ {@code null}
     */
    @Nullable
    public static BeatMap peek(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        final String key = keyOf(url);
        synchronized (MEMORY) {
            return MEMORY.get(key);
        }
    }

    /**
     * 別経路で作ったビートマップを載せる。以後 {@link #ensure} はこの URL の解析を起こさない。
     *
     * <p>P5 (音源ローカルキャッシュ) が音源を 1 パス流す副作用としてビートマップも作る場合の
     * 受け口。headless テストが合成マップを差し込むのにも使う。ディスクへは書かない
     * (書き手が完成を保証できないため)。
     */
    public static void install(String url, BeatMap map) {
        if (url == null || url.isBlank() || map == null) {
            return;
        }
        final String key = keyOf(url);
        synchronized (MEMORY) {
            MEMORY.put(key, map);
        }
    }

    /**
     * この URL のビートマップを用意する (非同期)。既にメモリにあるか解析中なら何もしない。
     * ディスクに載っていれば読み込み、無ければその場でストリームを開いて解析する。
     *
     * <p>無限長 (ラジオ / ライブ) は呼び出し側で弾くこと。尺が確定しないので完成できない。
     */
    public static void ensure(MinecraftServer server, String url, long durationMs) {
        if (server == null || url == null || url.isBlank()) {
            return; // 呼び出し側の前提崩れ。key が作れないのでログの単位も作れない
        }
        final String key = keyOf(url);
        if (durationMs <= 0L) {
            skipped(key, url, "尺が不明 (durationMs=" + durationMs + ")", false);
            return;
        }
        if (!Config.beatEnabled()) {
            skipped(key, url, "config でビート連動が無効", false);
            return;
        }
        bindServer(server);
        final int attempts = FAILED_ATTEMPTS.getOrDefault(key, 0);
        synchronized (MEMORY) {
            if (MEMORY.containsKey(key)) {
                // 自分の解析が尺に届かなかった時だけ、上限つきでやり直す。
                // 外から {@link #install} で載せたマップと、完走したマップは数字を持たない = 権威。
                if (attempts == 0) {
                    return; // 権威マップが載っている = 正常。無言でよい
                }
                if (attempts >= MAX_ATTEMPTS) {
                    skipped(key, url, "尺に届かないまま試行上限に達している ("
                            + attempts + "/" + MAX_ATTEMPTS + ")・解析済みの範囲までしか出ない", true);
                    return;
                }
            } else if (attempts >= MAX_ATTEMPTS) {
                // 解析そのものが通らない URL (リンク切れ・拒否・回線断)。毎秒叩き続けない。
                skipped(key, url, "解析が通らないまま試行上限に達している ("
                        + attempts + "/" + MAX_ATTEMPTS + ")・この URL は以後 0 のまま", true);
                return;
            }
        }
        if (!IN_FLIGHT.add(key)) {
            return; // 既に解析中。無言でよい (要求のログは submit 時に 1 回出ている)
        }
        final int generation = GENERATION.get();
        final ExecutorService executor = pool;
        if (executor == null) {
            IN_FLIGHT.remove(key);
            skipped(key, url, "解析スレッドが無い (server 停止と競合)", true);
            return;
        }
        try {
            // 「そもそも走らなかった」と「走ったが間に合わなかった」を事後に区別するための 1 行。
            // 完了と失敗しか出ないと、コンパレータが 0 のままだった理由をログから絞れない。
            LAST_SKIP.remove(key);
            MusicDiscMaker.LOGGER.info("ビート解析を要求: {} ({}ms)", url, durationMs);
            executor.submit(() -> {
                try {
                    load(key, url, durationMs, generation);
                } catch (final Throwable t) {
                    giveUp(key, url, t.toString());
                } finally {
                    IN_FLIGHT.remove(key);
                }
            });
        } catch (final RuntimeException ex) {
            // server 停止と競合して executor が閉じた直後。tick から呼ばれるので投げ返さない。
            IN_FLIGHT.remove(key);
            skipped(key, url, "解析を投入できない: " + ex, true);
        }
    }

    /**
     * 解析を起こさずに帰った理由を残す。{@code ensure} は再生中ずっと定期的に呼ばれるので、
     * <b>同じ理由が続く間は 1 回だけ</b>出す (理由が変われば = 状態が動けばまた出る)。
     *
     * <p>無言の早期 return が 3 つ在ったせいで、実機でコンパレータが 0 のままだった時に
     * 「解析が走らなかった」と「走ったが間に合わなかった」をログから区別できなかった。
     */
    private static void skipped(String key, String url, String reason, boolean warn) {
        if (reason.equals(LAST_SKIP.put(key, reason))) {
            return;
        }
        if (warn) {
            MusicDiscMaker.LOGGER.warn("ビート解析を見送り: {} ({})", url, reason);
        } else {
            MusicDiscMaker.LOGGER.info("ビート解析を見送り: {} ({})", url, reason);
        }
    }

    /** 解析に使う loader を差し替える (テスト用)。前の値を返すので finally で戻すこと。 */
    public static Supplier<IMusicLoader> swapLoader(Supplier<IMusicLoader> replacement) {
        final Supplier<IMusicLoader> previous = loaderSource;
        loaderSource = replacement == null ? LoaderHolder::get : replacement;
        return previous;
    }

    /** この URL について覚えていること (マップ・失敗回数・見送り理由) を全部忘れる (テスト用)。 */
    public static void reset(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        final String key = keyOf(url);
        forget(key);
        FAILED_ATTEMPTS.remove(key);
        LAST_SKIP.remove(key);
        IN_FLIGHT.remove(key);
    }

    /** この URL のディスクキャッシュのパス (テストの後始末用)。未束縛なら null。 */
    @Nullable
    public static Path cachePathOf(String url) {
        final Path dir = cacheDir;
        return dir == null ? null : dir.resolve(keyOf(url) + EXTENSION);
    }

    /**
     * server 停止時に呼ぶ。進行中の解析を打ち切り、解析スレッドを畳む。
     *
     * <p><b>メモリ LRU は消さない。</b> ビートマップは URL から決まる純粋な派生物でワールドにも
     * server にも依存しないので、ワールドを出入りするたびに捨てるとディスクから読み直すだけ無駄になる
     * (上限 {@value #MEMORY_ENTRIES} 曲の LRU なので置いておいても増え続けない)。打ち切られた
     * 途中までのマップは解析スレッド側が {@code forget} するので、中途半端なものは残らない。
     */
    public static void shutdown() {
        GENERATION.incrementAndGet();
        currentServer = null;
        cacheDir = null;
        // 打ち切られた解析が「進行中」の印を残したままだと、次の server で二度と解析されない。
        IN_FLIGHT.clear();
        LAST_SKIP.clear();
        final ExecutorService executor = pool;
        pool = null;
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    // ── 内部 ────────────────────────────────────────────────────────────

    private static synchronized void bindServer(MinecraftServer server) {
        if (currentServer == server && pool != null) {
            ensureSlots();
            return;
        }
        shutdown();
        currentServer = server;
        cacheDir = server.getServerDirectory().resolve(CACHE_DIR);
        pool = Executors.newCachedThreadPool(runnable -> {
            final Thread thread = new Thread(runnable, "music_disc_maker-beat");
            thread.setDaemon(true);
            // 解析は帯域待ちが支配的だが、server tick を邪魔しないよう優先度は下げておく。
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        ensureSlots();
    }

    /** 同時解析数の上限。config を変えたら作り直す。 */
    private static void ensureSlots() {
        final int permits = Math.max(1, Config.beatMaxConcurrentAnalyses());
        if (slots == null || slotPermits != permits) {
            slots = new Semaphore(permits);
            slotPermits = permits;
        }
    }

    private static void load(String key, String url, long durationMs, int generation) {
        final Path dir = cacheDir;
        if (dir == null || generation != GENERATION.get()) {
            return;
        }
        // ① ディスクキャッシュ
        final Path file = dir.resolve(key + EXTENSION);
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                final BeatMap cached = BeatMap.read(in);
                if (cached != null) {
                    publish(key, cached, generation);
                    LAST_SKIP.remove(key);
                    // 「キャッシュ命中で最初から出る」と「その場解析で出るまで待った」は
                    // 実機の見え方が全く違うので、どちらだったかを残す。
                    MusicDiscMaker.LOGGER.info("ビートマップをキャッシュから読み込み: {}", url);
                    return;
                }
            } catch (final IOException | RuntimeException ex) {
                MusicDiscMaker.LOGGER.warn("ビートマップキャッシュの読み込みに失敗 ({}): {}", file, ex.toString());
            }
            deleteQuietly(file); // 壊れている / 旧形式 → 作り直す
        }

        // ② その場解析。追い越すまでは 0 が出るだけなので、待たせずに公開してから埋める。
        final Semaphore permits = slots;
        if (permits == null || !permits.tryAcquire()) {
            // DEBUG だと実機ログに出ない = 「解析を要求したのに何も起きない」が説明できない。
            skipped(key, url, "同時解析の上限に達している (beatMaxConcurrentAnalyses)", false);
            return;
        }
        try {
            UrlGuard.enforce(url); // SSRF 遮断。再生経路と同じ構え
            final BeatMap previous = peekByKey(key);
            final BeatMap map = BeatMap.forDuration(durationMs);
            // 初回だけ空のマップを即公開する (解析カーソルが再生位置を追い越したら出始める形)。
            // やり直しの時に公開してしまうと、前回取れていた良い部分が空で上書きされ、
            // 出力が 0 に落ちてまた登り直しになる。
            if (previous == null) {
                publish(key, map, generation);
            }
            final long started = System.currentTimeMillis();
            try (IAudioSource source = loaderSource.get().openStream(url, 0L)) {
                if (source == null) {
                    giveUp(key, url, "ストリームを開けない");
                    return;
                }
                final boolean finished = BeatAnalyzer.analyze(source, map, Config.beatFftSize(),
                        () -> generation == GENERATION.get());
                if (!finished) {
                    if (previous == null) {
                        forget(key); // 打ち切り = 中途半端なマップを残さない
                    }
                    return; // server 停止による打ち切りは失敗に数えない
                }
            }
            // 前回より取れていれば差し替える (やり直しで良くなった分を反映する)。
            if (previous == null || map.coveredMs() > previous.coveredMs()) {
                publish(key, map, generation);
            }
            // PCM ソースの終端 (-1) は「曲が終わった」と「回線が詰まって諦めた」を区別できない
            // (LavaAudioSource は 10 秒枯渇でも -1 を返す)。尺どおり取れたかで判定しないと、
            // 通信が細った 1 回の結果が「完成品」としてキャッシュに焼き付き、以後その曲は
            // 途中から永久に無反応になる。
            if (map.coveredMs() < durationMs * COMPLETE_RATIO) {
                final int attempts = FAILED_ATTEMPTS.merge(key, 1, Integer::sum);
                MusicDiscMaker.LOGGER.warn(
                        "ビート解析が尺に届かなかったのでキャッシュしない: {} ({}ms / {}ms・{}/{} 回目)",
                        url, map.coveredMs(), durationMs, attempts, MAX_ATTEMPTS);
                return; // メモリには残る (取れたところまでは今の再生で使える)
            }
            FAILED_ATTEMPTS.remove(key);
            LAST_SKIP.remove(key);
            MusicDiscMaker.LOGGER.info("ビート解析が完了: {} ({} frames / {}ms)",
                    url, map.ready(), System.currentTimeMillis() - started);
            store(file, map);
        } catch (final Throwable t) {
            giveUp(key, url, t.toString());
        } finally {
            permits.release();
        }
    }

    /**
     * 解析が通らなかった時の後始末。試行回数を進め、上限に達したらそれ以上叩かない。
     * 上限が無いと、リンク切れ・拒否・回線断の URL を再生中ずっと毎秒叩き続けることになる
     * ({@code tickBeat} がマップ不在を見て頼み直すため)。
     */
    private static void giveUp(String key, String url, String reason) {
        final int attempts = FAILED_ATTEMPTS.merge(key, 1, Integer::sum);
        forget(key);
        MusicDiscMaker.LOGGER.warn("ビート解析に失敗 ({}): {} ({}/{} 回目)",
                url, reason, attempts, MAX_ATTEMPTS);
    }

    @Nullable
    private static BeatMap peekByKey(String key) {
        synchronized (MEMORY) {
            return MEMORY.get(key);
        }
    }

    private static void publish(String key, BeatMap map, int generation) {
        if (generation != GENERATION.get()) {
            return;
        }
        synchronized (MEMORY) {
            MEMORY.put(key, map);
        }
    }

    private static void forget(String key) {
        synchronized (MEMORY) {
            MEMORY.remove(key);
        }
    }

    private static void store(Path file, BeatMap map) {
        try {
            Files.createDirectories(file.getParent());
            final Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(temp)) {
                map.write(out);
            }
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            prune(file.getParent());
        } catch (final IOException ex) {
            MusicDiscMaker.LOGGER.warn("ビートマップの保存に失敗 ({}): {}", file, ex.toString());
        }
    }

    /** 上限を超えたら古い順に消す (LRU 相当。更新時刻を使う)。 */
    private static void prune(Path dir) {
        final long limit = (long) Math.max(0, Config.beatCacheMaxMB()) * 1024L * 1024L;
        if (limit <= 0L) {
            return;
        }
        final List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(EXTENSION)).forEach(files::add);
        } catch (final IOException ex) {
            return;
        }
        long total = 0L;
        for (final Path p : files) {
            total += sizeOf(p);
        }
        if (total <= limit) {
            return;
        }
        files.sort(Comparator.comparingLong(BeatMaps::modifiedAt));
        for (final Path p : files) {
            if (total <= limit) {
                break;
            }
            total -= sizeOf(p);
            deleteQuietly(p);
        }
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (final IOException ex) {
            return 0L;
        }
    }

    private static long modifiedAt(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (final IOException ex) {
            return 0L;
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (final IOException ignored) {
            // 消せなくても実害はない (次回上書きされる)
        }
    }

    /**
     * URL → ファイル名。ディスクが保持する URL は解決済みの正規 URI ({@code TrackInfo.uri}) なので、
     * 同じ動画は必ず同じ文字列になる (再生リスト等のパラメータは解決の時点で落ちている)。
     */
    static String keyOf(String url) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-1");
            final byte[] hash = digest.digest(url.trim().getBytes(StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder(hash.length * 2);
            for (final byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-1 が使えない", ex);
        }
    }
}
