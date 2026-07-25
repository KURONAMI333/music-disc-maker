package com.kuronami.musicdiscmaker.gametest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Supplier;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.beat.BeatBand;
import com.kuronami.musicdiscmaker.beat.BeatMap;
import com.kuronami.musicdiscmaker.beat.BeatMaps;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.IMusicLoader;
import com.kuronami.musicdiscmaker.lavaplayer.api.TrackInfo;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * ビート解析の<b>実経路</b>の headless テスト。
 *
 * <p>他のビートテストは全部 {@code BeatMaps.install} で合成マップを差し込むので、
 * {@code ensure → load → 解析 → publish → .beat 書き出し → 読み戻し → peek} を一度も通らない。
 * 実機で「初回再生だけコンパレータが無反応」が出た時に、この層が無いせいで
 * 「解析が走らなかった」と「走ったが間に合わなかった」を切り分けられなかった。
 *
 * <p>ネットワークには出ない。loader を偽物に差し替えて決定的な PCM を流し、URL は
 * TEST-NET-3 (RFC 5737 の文書用 IP リテラル) にして DNS も引かせない — SSRF ガードは
 * 実運用どおり通す。
 *
 * <h2>ここで踏んだ落とし穴（同型を書かないこと）</h2>
 * <ul>
 *   <li><b>{@code succeedWhen} の criterion の中から {@code runAfterDelay} で assert を登録しても
 *       絶対に走らない。</b> criterion が正常 return した<b>その tick で成功が確定</b>し、
 *       {@code GameTestInfo#tick} は以後 {@code runAtTickTimeMap} を処理しない。遅延フェーズが
 *       要るなら {@code startSequence} の {@code thenExecuteAfter} で繋ぐこと。
 *       （実害: 2 フェーズ目の assert を {@code == 999} に書き換えても緑のままだった）</li>
 *   <li><b>{@code .beat} は run をまたいで残る</b>（{@code neoforge/run} は永続）。冒頭で消さないと、
 *       前の run が焼いたファイルに命中して<b>解析経路を一度も通らないまま緑になる</b>。
 *       後始末側の削除は保証にならない（解析スレッドの {@code store()} が後から焼き直す）。</li>
 *   <li><b>loader の差し替えは {@code static} なので、失敗経路で戻し損ねると JVM 全体に残る。</b>
 *       偽 loader は<b>自分の URL 以外を本物へ委譲する</b>形にして、漏れても他へ効かないようにする。</li>
 * </ul>
 */
@GameTestHolder(MusicDiscMaker.MODID)
public class BeatRealPathGameTests {

    private static final String TEMPLATE = "empty8x3x8";
    // GameTest は同一 batch 内で並列に走る。loader の差し替えは static なので、2 本を同じ batch
    // に置くと片方の restore がもう片方の差し替えを潰す。batch を分けて直列化し、さらに URL も
    // 分けて (= キャッシュファイルも別) 状態を共有させない。
    private static final String BATCH_ENSURE = "beat_realpath_ensure";
    private static final String BATCH_CACHE = "beat_realpath_cache";

    /**
     * 文書用に予約された IP リテラル (RFC 5737 TEST-NET-3)。IP リテラルなので DNS を引かず、
     * かつ内部帯ではないので {@code UrlGuard} を実運用どおり通過する。
     */
    private static final String URL_ENSURE = "https://203.0.113.7/mdm-beat-ensure";
    private static final String URL_CACHE = "https://203.0.113.8/mdm-beat-cache";
    private static final long TRACK_MS = 4_000L;
    private static final int SAMPLE_RATE = 48_000;
    /** 解析は実時間の数十倍で進むので、この tick 数で出ないなら経路が壊れている。 */
    private static final int DEADLINE_TICKS = 160;
    private static final int TIMEOUT_TICKS = DEADLINE_TICKS + 60;

    /**
     * 未解析の URL に {@code ensure} を撃つと、<b>実際に解析が走り</b>、{@code peek} が有限の
     * dBFS を返すところまで到達すること。<b>ここが通らない = コンパレータが 0 のままになる</b>。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = BATCH_ENSURE, timeoutTicks = TIMEOUT_TICKS)
    public static void ensureReachesPeekableBeatMap(GameTestHelper helper) {
        final MinecraftServer server = helper.getLevel().getServer();
        deleteCache(server, URL_ENSURE); // 前の run のキャッシュに命中させない (hermetic の担保)
        BeatMaps.reset(URL_ENSURE);
        final ToneLoader loader = new ToneLoader(URL_ENSURE);
        final Supplier<IMusicLoader> previous = BeatMaps.swapLoader(() -> loader);
        BeatMaps.ensure(server, URL_ENSURE, TRACK_MS);

        deadline(helper, previous, URL_ENSURE, () -> {
            final BeatMap map = BeatMaps.peek(URL_ENSURE);
            return map == null
                    ? "ビートマップが公開されない (解析が起きていない)"
                    : "先頭の dBFS が読めない (解析カーソルが進んでいない)";
        });
        helper.startSequence()
                .thenWaitUntil(() -> {
                    final BeatMap map = BeatMaps.peek(URL_ENSURE);
                    helper.assertTrue(map != null, "ビートマップがまだ公開されていない");
                    helper.assertFalse(Double.isNaN(map.peakDb(BeatBand.LOW, 0L, 50L)),
                            "解析カーソルがまだ先頭に届いていない");
                })
                .thenExecute(() -> {
                    // ディスクキャッシュ命中では通れない形にしておく (テストの存在意義そのもの)。
                    helper.assertTrue(loader.openStreamCalls >= 1,
                            "解析経路を通らずに緑になっている (ディスクキャッシュ命中)");
                    // 合成トーンなので必ず有限値。無音下駄 (-240dB 付近) なら解析が空回りしている。
                    final double db = BeatMaps.peek(URL_ENSURE).peakDb(BeatBand.LOW, 0L, 50L);
                    helper.assertTrue(db > -120.0, "先頭の dBFS が無音相当: " + db);
                })
                .thenExecute(() -> cleanUp(server, URL_ENSURE, previous))
                .thenSucceed();
    }

    /**
     * 解析が完走したら {@code .beat} がディスクへ焼かれ、次の {@code ensure} は
     * <b>解析せずに</b>そこから読み戻して有限値を返すこと (= 2 回目以降は最初から信号が出る)。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = BATCH_CACHE, timeoutTicks = TIMEOUT_TICKS)
    public static void analysedMapRoundTripsThroughDiskCache(GameTestHelper helper) {
        final MinecraftServer server = helper.getLevel().getServer();
        deleteCache(server, URL_CACHE);
        BeatMaps.reset(URL_CACHE);
        final ToneLoader first = new ToneLoader(URL_CACHE);
        final ToneLoader second = new ToneLoader(URL_CACHE);
        final Supplier<IMusicLoader> previous = BeatMaps.swapLoader(() -> first);
        BeatMaps.ensure(server, URL_CACHE, TRACK_MS);

        deadline(helper, previous, URL_CACHE, () -> ".beat が焼かれない");
        helper.startSequence()
                .thenWaitUntil(() -> helper.assertTrue(
                        Files.isRegularFile(BeatMaps.cachePathOf(server, URL_CACHE)),
                        ".beat がまだ焼かれていない"))
                .thenExecute(() -> helper.assertTrue(first.openStreamCalls == 1,
                        "1 回目が解析経路を通っていない: openStream " + first.openStreamCalls + " 回"))
                // .beat が見えた直後は IN_FLIGHT の後片付けがまだ残っているので 1 tick 置く。
                .thenExecuteAfter(1, () -> {
                    BeatMaps.reset(URL_CACHE); // メモリから落として、ディスクだけが残った状態にする
                    BeatMaps.swapLoader(() -> second);
                    BeatMaps.ensure(server, URL_CACHE, TRACK_MS);
                })
                // 読み戻しは同期 IO なので、数 tick 置けば載っている。
                .thenExecuteAfter(3, () -> {
                    final BeatMap cached = BeatMaps.peek(URL_CACHE);
                    helper.assertTrue(cached != null, ".beat から読み戻せていない");
                    helper.assertFalse(Double.isNaN(cached.peakDb(BeatBand.LOW, 0L, 50L)),
                            "読み戻したマップから先頭の dBFS が読めない");
                    helper.assertTrue(second.openStreamCalls == 0,
                            "ディスクキャッシュがあるのに再解析している: " + second.openStreamCalls);
                })
                .thenExecute(() -> cleanUp(server, URL_CACHE, previous))
                .thenSucceed();
    }

    /**
     * 期限を過ぎても終わっていなければ、時刻源ならぬ loader を戻してから理由つきで落とす。
     * {@code runAtTickTime} の task は<b>成功が確定していない間だけ</b>処理されるので、
     * 正常に終わったテストでは発火しない。
     */
    private static void deadline(GameTestHelper helper, Supplier<IMusicLoader> previous, String url,
            Supplier<String> why) {
        helper.runAtTickTime(DEADLINE_TICKS, () -> {
            final String reason = why.get();
            cleanUp(helper.getLevel().getServer(), url, previous);
            helper.fail("ensure から " + DEADLINE_TICKS + " tick 経っても" + reason);
        });
    }

    private static void cleanUp(MinecraftServer server, String url, Supplier<IMusicLoader> previous) {
        BeatMaps.swapLoader(previous);
        BeatMaps.reset(url);
        deleteCache(server, url);
    }

    private static void deleteCache(MinecraftServer server, String url) {
        final Path file = BeatMaps.cachePathOf(server, url);
        try {
            Files.deleteIfExists(file);
        } catch (final java.io.IOException ex) {
            MusicDiscMaker.LOGGER.warn("テスト用ビートマップを消せない ({}): {}", file, ex.toString());
        }
    }

    /**
     * 指定 URL にだけ決定的な矩形波を返し、それ以外は本物の loader へ委譲する偽 loader。
     *
     * <p>委譲するのは<b>差し替えが漏れた時の被害を自分の URL に閉じ込める</b>ため。
     * 全 URL を横取りする作りだと、戻し損ねた瞬間に他のテスト・他の再生が矩形波になる。
     */
    private static final class ToneLoader implements IMusicLoader {

        private final String url;
        volatile int openStreamCalls;

        ToneLoader(String url) {
            this.url = url;
        }

        @Override
        public TrackInfo resolve(String requested) {
            return url.equals(requested) ? null : com.kuronami.musicdiscmaker.audio.LoaderHolder.get()
                    .resolve(requested);
        }

        @Override
        public IAudioSource openStream(String requested, long startMs) {
            if (!url.equals(requested)) {
                return com.kuronami.musicdiscmaker.audio.LoaderHolder.get().openStream(requested, startMs);
            }
            openStreamCalls++;
            return new ToneSource(TRACK_MS);
        }
    }

    /** mono S16LE の矩形波。申告尺ぶん返したら終端 (-1)。 */
    private static final class ToneSource implements IAudioSource {

        private final long totalBytes;
        private long written;
        private long sample;

        ToneSource(long durationMs) {
            this.totalBytes = SAMPLE_RATE * 2L * durationMs / 1000L;
        }

        @Override
        public int sampleRate() {
            return SAMPLE_RATE;
        }

        @Override
        public int channels() {
            return 1;
        }

        @Override
        public int bitsPerSample() {
            return 16;
        }

        @Override
        public boolean bigEndian() {
            return false;
        }

        @Override
        public int read(byte[] dst, int off, int len) {
            if (written >= totalBytes) {
                return -1;
            }
            final int n = (int) Math.min(len - (len % 2), totalBytes - written);
            for (int i = 0; i < n; i += 2) {
                // 矩形波: 低域にしっかりエネルギーが乗るので LOW 帯で必ず有限値になる。
                final short v = ((sample / 55) % 2 == 0) ? (short) 12000 : (short) -12000;
                dst[off + i] = (byte) (v & 0xFF);
                dst[off + i + 1] = (byte) ((v >> 8) & 0xFF);
                sample++;
            }
            written += n;
            return n;
        }

        @Override
        public void close() {
            // 何も掴んでいない
        }
    }
}
