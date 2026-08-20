package com.kuronami.musicdiscmaker.gametest;

import java.util.concurrent.atomic.AtomicLong;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.client.audio.PlaybackPrefetch;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * アルバムの次トラック先読み ({@link PlaybackPrefetch}) の状態機械を固定する。
 *
 * <h2>ここで一番大事な 1 本</h2>
 * {@link #aMissReturnsNullSoTheNormalLoadStillRuns} — <b>{@code claim} が「ロード中だからスキップ」を
 * 返さないこと</b>。先読みを「トークン付きの in-flight ロード」にすると、{@code LivePlaybackRegistry}
 * で過去に出した「解決が再送間隔より遅いと永久に着地しない = 永久に無音」と同型のバグになる。
 * 最悪ケースは重複ロード 1 本 (= 先読みを入れる前の挙動) で、それは許容できる。
 *
 * <h2>閉じ忘れを数える</h2>
 * 先読み 1 本は約 964KB + デコーダ状態 + HTTP 接続 1 本を握る。取り消し経路のどれかが閉じ忘れると
 * そのまま漏れるので、{@link FakeSource} は close 回数を数え、各テストが期待値と突き合わせる。
 *
 * <p>時計は {@link AtomicLong} で差し替えて、実時間を待たずに期限切れ (30 秒) を作る。
 */
@GameTestHolder(MusicDiscMaker.MODID)
public class PlaybackPrefetchGameTests {

    private static final String TEMPLATE = "empty8x3x8";
    private static final String KEY = "jukebox";

    private static final String URL_A = "https://example.invalid/a.mp3";
    private static final String URL_B = "https://example.invalid/b.mp3";

    /** 温めておいたソースがそのまま渡ること (これが当たると解決と 4 秒のプリバッファが丸ごと消える)。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void aWarmSourceIsHandedToThePlaybackThatAsksForIt(GameTestHelper helper) {
        final AtomicLong clock = new AtomicLong(1_000L);
        final PlaybackPrefetch<String> prefetch = new PlaybackPrefetch<>(clock::get);
        final FakeSource warm = new FakeSource();

        final PlaybackPrefetch.Ticket<String> ticket = prefetch.begin(KEY, URL_A);
        helper.assertTrue(ticket != null, "先読みが始まっていない");
        helper.assertTrue(prefetch.deliver(ticket, warm), "開けたソースが受け取られていない");

        helper.assertTrue(prefetch.claim(KEY, URL_A, 0L) == warm, "温めたソースが渡されていない");
        helper.assertTrue(warm.closes == 0, "渡したソースが閉じられている (鳴らす前に殺している)");
        helper.succeed();
    }

    /**
     * <b>不変条件。</b> ヒットしない時は {@code null} が返るだけで、「スキップしろ」の類は返らない。
     * ロード中の先読みがあっても {@code null}。呼び出し側は従来どおり普通のロードを走らせる。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void aMissReturnsNullSoTheNormalLoadStillRuns(GameTestHelper helper) {
        final AtomicLong clock = new AtomicLong(1_000L);
        final PlaybackPrefetch<String> prefetch = new PlaybackPrefetch<>(clock::get);

        helper.assertTrue(prefetch.claim(KEY, URL_A, 0L) == null, "何も抱えていないのに null 以外が返っている");

        // ロードが間に合わなかった場合。ここで「スキップ」を返すと永久に無音になる。
        final PlaybackPrefetch.Ticket<String> ticket = prefetch.begin(KEY, URL_A);
        helper.assertTrue(prefetch.claim(KEY, URL_A, 0L) == null,
                "ロード中の先読みが null 以外を返している (ここを in-flight ガードにすると永久に無音になる)");

        // 掴めなかった先読みは掴んだ時点で手放している。遅れて届いた分は受け取らない。
        final FakeSource late = new FakeSource();
        helper.assertFalse(prefetch.deliver(ticket, late), "取り消し済みの枠が遅れて届いたソースを受け取っている");
        late.close(); // 呼び出し側の後始末
        helper.assertTrue(late.closes == 1, "受け取られなかったソースの close が漏れている: " + late.closes);
        helper.succeed();
    }

    /** 次に鳴る曲が変わったら、古い先読みを閉じてから新しい枠を作ること。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void changingTheNextTrackClosesTheOldPrefetch(GameTestHelper helper) {
        final AtomicLong clock = new AtomicLong(1_000L);
        final PlaybackPrefetch<String> prefetch = new PlaybackPrefetch<>(clock::get);

        final FakeSource first = new FakeSource();
        prefetch.deliver(prefetch.begin(KEY, URL_A), first);

        final PlaybackPrefetch.Ticket<String> second = prefetch.begin(KEY, URL_B);
        helper.assertTrue(second != null, "曲が変わったのに新しい先読みが始まっていない");
        helper.assertTrue(first.closes == 1, "差し替えられた先読みが閉じられていない: " + first.closes);
        helper.assertTrue(URL_B.equals(prefetch.slotUrl(KEY)), "抱えている曲が入れ替わっていない");

        final FakeSource next = new FakeSource();
        prefetch.deliver(second, next);
        helper.assertTrue(prefetch.claim(KEY, URL_B, 0L) == next, "新しい先読みが掴めない");
        helper.assertTrue(prefetch.claim(KEY, URL_A, 0L) == null, "捨てたはずの古い曲がまだ掴める");
        helper.succeed();
    }

    /** 同じ曲を毎 tick 頼まれてもロードを重ねないこと (先読みが 20Hz で HTTP を開かない)。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void repeatedRequestsForTheSameTrackDoNotStack(GameTestHelper helper) {
        final AtomicLong clock = new AtomicLong(1_000L);
        final PlaybackPrefetch<String> prefetch = new PlaybackPrefetch<>(clock::get);

        final PlaybackPrefetch.Ticket<String> ticket = prefetch.begin(KEY, URL_A);
        helper.assertTrue(ticket != null, "最初の先読みが始まっていない");
        for (int i = 0; i < 20; i++) {
            helper.assertTrue(prefetch.begin(KEY, URL_A) == null, "同じ曲の先読みを重ねている");
        }
        final FakeSource warm = new FakeSource();
        helper.assertTrue(prefetch.deliver(ticket, warm), "最初の先読みが打ち消されている");
        helper.assertTrue(prefetch.claim(KEY, URL_A, 0L) == warm, "重ね防止が先読みそのものを潰している");
        helper.succeed();
    }

    /**
     * 期限切れ (30 秒) の先読みは閉じて渡さないこと。lavaplayer は 60 秒 {@code provide} されない
     * player を殺すので、<b>殺されたソースを掴むと完全な無音になる</b> (元のギャップより悪い)。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void anExpiredPrefetchIsClosedAndNeverHandedOut(GameTestHelper helper) {
        final AtomicLong clock = new AtomicLong(1_000L);
        final PlaybackPrefetch<String> prefetch = new PlaybackPrefetch<>(clock::get);

        final FakeSource stale = new FakeSource();
        prefetch.deliver(prefetch.begin(KEY, URL_A), stale);

        clock.addAndGet(PlaybackPrefetch.EXPIRY_MS);
        helper.assertTrue(prefetch.claim(KEY, URL_A, 0L) == null, "期限切れの先読みが渡されている (無音になる)");
        helper.assertTrue(stale.closes == 1, "期限切れの先読みが閉じられていない: " + stale.closes);

        // 掃除は claim を通らない経路でも走ること (誰も掴みに来なかった残骸)。
        final FakeSource orphan = new FakeSource();
        prefetch.deliver(prefetch.begin(KEY, URL_B), orphan);
        clock.addAndGet(PlaybackPrefetch.EXPIRY_MS);
        prefetch.begin(KEY, URL_A); // 次の先読みの入口で掃除が走る
        helper.assertTrue(orphan.closes == 1, "誰も掴まなかった先読みが閉じられていない: " + orphan.closes);
        helper.succeed();
    }

    /**
     * シーク・再送 ({@code startOffsetMs != 0}) には当てないこと。先読みは常に頭から開くので、
     * 当ててしまうと<b>途中から鳴るはずの曲が頭から鳴る</b>。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void aRequestWithAnOffsetDoesNotGetTheHeadOfTheTrack(GameTestHelper helper) {
        final AtomicLong clock = new AtomicLong(1_000L);
        final PlaybackPrefetch<String> prefetch = new PlaybackPrefetch<>(clock::get);

        final FakeSource warm = new FakeSource();
        prefetch.deliver(prefetch.begin(KEY, URL_A), warm);

        helper.assertTrue(prefetch.claim(KEY, URL_A, 42_000L) == null, "途中再生の要求に頭出しの先読みを当てている");
        helper.assertTrue(warm.closes == 1, "当てられなかった先読みが閉じられていない: " + warm.closes);
        helper.succeed();
    }

    /** 取り消し後に届いたソースは受け取らず、呼び出し側が閉じられること (どの経路でも漏らさない)。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void everyCancellationPathClosesExactlyOnce(GameTestHelper helper) {
        final AtomicLong clock = new AtomicLong(1_000L);
        final PlaybackPrefetch<String> prefetch = new PlaybackPrefetch<>(clock::get);

        // (1) ロード中に取り消し → 遅れて届いた分は受け取らない
        final PlaybackPrefetch.Ticket<String> cancelled = prefetch.begin(KEY, URL_A);
        prefetch.drop(KEY);
        final FakeSource late = new FakeSource();
        helper.assertFalse(prefetch.deliver(cancelled, late), "取り消し後の配達が受け取られている");
        late.close();

        // (2) 完了済みを取り消し (停止・ディスク交換・ブロック撤去・一時停止)
        final FakeSource dropped = new FakeSource();
        prefetch.deliver(prefetch.begin(KEY, URL_A), dropped);
        prefetch.drop(KEY);
        helper.assertTrue(dropped.closes == 1, "取り消した先読みが閉じられていない: " + dropped.closes);

        // (3) ワールド退出
        final FakeSource onExit = new FakeSource();
        prefetch.deliver(prefetch.begin(KEY, URL_B), onExit);
        prefetch.dropAll();
        helper.assertTrue(onExit.closes == 1, "退出時に先読みが閉じられていない: " + onExit.closes);

        // (4) 二重解放していないこと (RetryingAudioSource#close は冪等だが、数え間違いは設計の穴)
        helper.assertTrue(late.closes == 1, "配達を拒否したソースの close 回数が 1 でない: " + late.closes);
        helper.assertTrue(prefetch.slotUrl(KEY) == null, "取り消したのに枠が残っている");
        helper.succeed();
    }

    /**
     * 上限 ({@link PlaybackPrefetch#MAX_CONCURRENT_PREFETCH}) に達したら新しい鍵の先読みを始めない
     * こと。miss 扱いなので {@code claim} は {@code null} を返すだけで、呼び出し側は従来どおり
     * 普通のロードを走らせる (無音にはならない)。既存の鍵の曲差し替えは枠の再利用であって新規の
     * 鍵ではないので、上限には塞がれないこと。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void newPrefetchesAreNotStartedOnceTheLimitIsReached(GameTestHelper helper) {
        final AtomicLong clock = new AtomicLong(1_000L);
        final PlaybackPrefetch<String> prefetch = new PlaybackPrefetch<>(clock::get);

        for (int i = 0; i < PlaybackPrefetch.MAX_CONCURRENT_PREFETCH; i++) {
            final PlaybackPrefetch.Ticket<String> ticket = prefetch.begin("jukebox-" + i, URL_A);
            helper.assertTrue(ticket != null, "上限に達する前の先読みが始まっていない: " + i);
        }

        helper.assertTrue(prefetch.begin("jukebox-overflow", URL_A) == null,
                "上限に達しているのに新しい鍵の先読みが始まっている");
        helper.assertTrue(prefetch.claim("jukebox-overflow", URL_A, 0L) == null,
                "始まっていない先読みが claim できている (claim は miss で null 以外を返してはいけない)");

        // 既存の鍵の曲差し替えは枠の再利用であって新規の鍵ではないので、上限に塞がれない。
        final PlaybackPrefetch.Ticket<String> swapped = prefetch.begin("jukebox-0", URL_B);
        helper.assertTrue(swapped != null, "既存の鍵の曲差し替えが上限に塞がれている");
        helper.assertTrue(URL_B.equals(prefetch.slotUrl("jukebox-0")), "曲差し替えが反映されていない");
        helper.succeed();
    }

    /**
     * 単曲 repeat: 次に鳴る曲が<b>今鳴っているのと同じ URL</b> でも成立すること。
     *
     * <p>アルバムと違って単曲の折り返しは「同じ曲をもう一度頭から」なので、温める URL と
     * 鳴っている URL が一致する。<b>この置き場は URL でなく鍵 (座標) で持つ</b>ので枠は別で、
     * 温めた分が今鳴っている音源を閉じることは無い (実音の側も
     * {@code MusicLoaderImpl#beginPlayback} が周回ごとに独立した player を起こす)。
     *
     * <p>2 周目の {@code begin} が非 null を返すことまで縛るのは、そこが静かに死ぬから。
     * {@link PlaybackPrefetch#claim} が枠を手放さない実装に変わると、単曲 repeat は 2 周目以降
     * まったく先読みされなくなる (＝無音が戻る) のに、他のテストは全部緑のまま通る。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void singleTrackRepeatWarmsTheUrlItIsAlreadyPlaying(GameTestHelper helper) {
        final AtomicLong clock = new AtomicLong(1_000L);
        final PlaybackPrefetch<String> prefetch = new PlaybackPrefetch<>(clock::get);

        // 1 周目の終わり際: 折り返し用に「今鳴っているのと同じ URL」を温めて掴む。
        final FakeSource firstLoop = new FakeSource();
        prefetch.deliver(prefetch.begin(KEY, URL_A), firstLoop);
        helper.assertTrue(prefetch.claim(KEY, URL_A, 0L) == firstLoop, "折り返し用に温めた同じ URL が掴めていない");

        // 2 周目の終わり際: 掴んだ直後に、同じ URL の先読みをもう一度始められること。
        final PlaybackPrefetch.Ticket<String> nextLoop = prefetch.begin(KEY, URL_A);
        helper.assertTrue(nextLoop != null, "掴んだ後に同じ URL の先読みが始められない (2 周目以降が無音に戻る)");
        helper.assertTrue(firstLoop.closes == 0, "先読みが今鳴っている音源を閉じている: " + firstLoop.closes);

        final FakeSource secondLoop = new FakeSource();
        helper.assertTrue(prefetch.deliver(nextLoop, secondLoop), "2 周目の先読みが受け取られていない");
        helper.assertTrue(firstLoop.closes == 0, "2 周目を温める間に 1 周目の音源が閉じられている: " + firstLoop.closes);
        helper.assertTrue(prefetch.claim(KEY, URL_A, 0L) == secondLoop, "2 周目の折り返しで先読みが掴めない");
        helper.assertTrue(firstLoop.closes == 0, "2 周目を掴んだ時に 1 周目の音源が閉じられている: " + firstLoop.closes);
        helper.succeed();
    }

    /** close 回数を数えるだけのソース。PCM は一切返さない。 */
    private static final class FakeSource implements IAudioSource {

        private int closes;

        @Override
        public int sampleRate() {
            return 48_000;
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
            return -1;
        }

        @Override
        public void close() {
            closes++;
        }
    }
}
