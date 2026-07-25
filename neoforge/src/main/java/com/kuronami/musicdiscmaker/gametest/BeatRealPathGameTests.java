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

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
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

    /**
     * 未解析の URL に {@code ensure} を撃つと、解析が走り、{@code peek} が有限の dBFS を返す
     * ところまで到達すること。<b>ここが通らない = コンパレータが 0 のままになる</b>。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = BATCH_ENSURE, timeoutTicks = DEADLINE_TICKS + 40)
    public static void ensureReachesPeekableBeatMap(GameTestHelper helper) {
        BeatMaps.reset(URL_ENSURE);
        final Supplier<IMusicLoader> previous = BeatMaps.swapLoader(() -> new ToneLoader(TRACK_MS));
        final long startedAt = helper.getLevel().getGameTime();
        BeatMaps.ensure(helper.getLevel().getServer(), URL_ENSURE, TRACK_MS);

        helper.succeedWhen(() -> {
            final long waited = helper.getLevel().getGameTime() - startedAt;
            final BeatMap map = BeatMaps.peek(URL_ENSURE);
            if (map == null) {
                helper.assertTrue(waited < DEADLINE_TICKS,
                        "ensure から " + waited + " tick 経ってもビートマップが公開されない"
                                + " (解析が起きていない)");
                helper.fail("ビートマップがまだ公開されていない");
                return;
            }
            final double db = map.peakDb(BeatBand.LOW, 0L, 50L);
            if (Double.isNaN(db)) {
                helper.assertTrue(waited < DEADLINE_TICKS,
                        "ensure から " + waited + " tick 経っても先頭の dBFS が読めない"
                                + " (解析カーソルが進んでいない)");
                helper.fail("解析カーソルがまだ先頭に届いていない");
                return;
            }
            // 合成トーンなので必ず有限値。無音下駄 (EPSILON) の -240dB 付近なら解析が空回りしている。
            helper.assertTrue(db > -120.0, "先頭の dBFS が無音相当: " + db);
            cleanUp(URL_ENSURE, previous);
        });
    }

    /**
     * 解析が完走したら {@code .beat} がディスクへ焼かれ、次の {@code ensure} は
     * <b>解析せずに</b>そこから読み戻して有限値を返すこと (= 2 回目以降は最初から信号が出る)。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = BATCH_CACHE, timeoutTicks = DEADLINE_TICKS + 40)
    public static void analysedMapRoundTripsThroughDiskCache(GameTestHelper helper) {
        BeatMaps.reset(URL_CACHE);
        final ToneLoader first = new ToneLoader(TRACK_MS);
        final Supplier<IMusicLoader> previous = BeatMaps.swapLoader(() -> first);
        final long startedAt = helper.getLevel().getGameTime();
        BeatMaps.ensure(helper.getLevel().getServer(), URL_CACHE, TRACK_MS);

        helper.succeedWhen(() -> {
            final long waited = helper.getLevel().getGameTime() - startedAt;
            final Path file = BeatMaps.cachePathOf(URL_CACHE);
            helper.assertTrue(file != null, "キャッシュ先が解決できない (server 未束縛)");
            if (!Files.isRegularFile(file)) {
                helper.assertTrue(waited < DEADLINE_TICKS,
                        "ensure から " + waited + " tick 経っても .beat が焼かれない");
                helper.fail(".beat がまだ焼かれていない");
                return;
            }
            // メモリから落として、ディスクだけが残った状態にする。
            BeatMaps.reset(URL_CACHE);
            final ToneLoader second = new ToneLoader(TRACK_MS);
            BeatMaps.swapLoader(() -> second);
            BeatMaps.ensure(helper.getLevel().getServer(), URL_CACHE, TRACK_MS);
            // 読み戻しは同期 IO なので、次の tick には載っている。
            helper.runAfterDelay(2L, () -> {
                try {
                    final BeatMap cached = BeatMaps.peek(URL_CACHE);
                    helper.assertTrue(cached != null, ".beat から読み戻せていない");
                    helper.assertFalse(Double.isNaN(cached.peakDb(BeatBand.LOW, 0L, 50L)),
                            "読み戻したマップから先頭の dBFS が読めない");
                    helper.assertTrue(second.openStreamCalls == 0,
                            "ディスクキャッシュがあるのに再解析している: " + second.openStreamCalls);
                } finally {
                    deleteCache(URL_CACHE);
                    cleanUp(URL_CACHE, previous);
                }
                helper.succeed();
            });
        });
    }

    private static void cleanUp(String url, Supplier<IMusicLoader> previous) {
        BeatMaps.swapLoader(previous);
        BeatMaps.reset(url);
    }

    private static void deleteCache(String url) {
        final Path file = BeatMaps.cachePathOf(url);
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (final java.io.IOException ex) {
            MusicDiscMaker.LOGGER.warn("テスト用ビートマップを消せない ({}): {}", file, ex.toString());
        }
    }

    /** 申告尺ぶんの決定的な矩形波を返すだけの偽 loader。ネットワークに出ない。 */
    private static final class ToneLoader implements IMusicLoader {

        private final long durationMs;
        volatile int openStreamCalls;

        ToneLoader(long durationMs) {
            this.durationMs = durationMs;
        }

        @Override
        public com.kuronami.musicdiscmaker.lavaplayer.api.TrackInfo resolve(String url) {
            return null; // 解析経路は解決済み URL しか受け取らない
        }

        @Override
        public IAudioSource openStream(String url, long startMs) {
            openStreamCalls++;
            return new ToneSource(durationMs);
        }
    }

    /** mono S16LE・440Hz 相当の矩形波。申告尺ぶん返したら終端 (-1)。 */
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
