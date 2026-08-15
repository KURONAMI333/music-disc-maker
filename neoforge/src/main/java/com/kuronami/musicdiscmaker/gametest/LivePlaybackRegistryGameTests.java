package com.kuronami.musicdiscmaker.gametest;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.client.audio.LivePlaybackRegistry;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 移動構造物 (Create Aeronautics の sub-level) に載った音源で、指向性・範囲・音量の変更が
 * ちゃんと届くことを固定する。
 *
 * <h2>これが要る理由</h2>
 * server は 1 秒ごとに<b>その時点の</b>設定を載せて送り直す。受け手が「同じ座標で再生中なら
 * 早期 return」していたので、2 回目以降の payload が一度も読まれず、GUI で何を変えても
 * その音源にだけ永久に反映されなかった。本体の {@code DiscSoundInstance#tick} による BE 追従は
 * {@code StaticAnchor} 限定なので、Sable anchor はそちらでも拾われない = 逃げ道が無い。
 *
 * <p>ここでは {@code SableAudioClient} が実際に呼ぶ順番のまま {@link LivePlaybackRegistry} を
 * 叩く。値の押し込み先は {@link FakeVoice} なので、<b>押し込んだかどうかを実際に読める</b>
 * (「早期 return を消した」だけでは値が届かない — 押し込む側も要る)。
 */
@GameTestHolder(MusicDiscMaker.MODID)
public class LivePlaybackRegistryGameTests {

    private static final String TEMPLATE = "empty8x3x8";
    private static final long PLOT = 12345L;

    private static final String URL_A = "https://example.invalid/a.mp3";
    private static final String URL_B = "https://example.invalid/b.mp3";

    /**
     * <b>これが今回の回帰テスト。</b> 同じ曲の周期再送で、指向性・範囲・音量が鳴っている音源へ
     * 届くこと。届いた上で、鳴らし直し (再ロード) はしないこと。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void periodicResendAppliesTheCurrentSettingsLive(GameTestHelper helper) {
        final LivePlaybackRegistry<Long> registry = new LivePlaybackRegistry<>();
        final FakeVoice voice = playing(helper, registry, URL_A, 64, 100, true);

        // 1 秒後の再送。GUI で指向性 OFF・範囲 32・音量 40 に変えられている。
        final LivePlaybackRegistry.Decision resend = registry.request(PLOT, URL_A, 32, 40, false);

        helper.assertTrue(resend == LivePlaybackRegistry.Decision.LIVE_UPDATE,
                "同じ曲の再送が " + resend + " になっている (LOAD なら鳴らし直し、SKIP なら設定が永久に無視される)");
        helper.assertFalse(voice.directional, "指向性の変更が音源に届いていない");
        helper.assertTrue(voice.rangeBlocks == 32, "範囲の変更が届いていない: " + voice.rangeBlocks);
        helper.assertTrue(voice.volumePercent == 40, "音量の変更が届いていない: " + voice.volumePercent);
        helper.assertFalse(voice.stopped, "設定を変えただけで音が止まっている");
        helper.succeed();
    }

    /** 再送のたびに現在値が届き続けること (1 回だけ効いて以後固まる、にならない)。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void everyResendKeepsApplyingTheLatestSettings(GameTestHelper helper) {
        final LivePlaybackRegistry<Long> registry = new LivePlaybackRegistry<>();
        final FakeVoice voice = playing(helper, registry, URL_A, 64, 100, true);

        for (int volume = 90; volume >= 10; volume -= 20) {
            helper.assertTrue(registry.request(PLOT, URL_A, 64, volume, true)
                    == LivePlaybackRegistry.Decision.LIVE_UPDATE, "再送が LIVE_UPDATE でなくなっている");
            helper.assertTrue(voice.volumePercent == volume,
                    "音量スライダーの追従が止まっている (期待 " + volume + " / 実際 " + voice.volumePercent + ")");
        }
        helper.succeed();
    }

    /** 曲が変われば、古い音源を止めてから新しいロードへ進むこと。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void changingTheTrackStopsTheOldVoiceAndLoadsTheNewOne(GameTestHelper helper) {
        final LivePlaybackRegistry<Long> registry = new LivePlaybackRegistry<>();
        final FakeVoice first = playing(helper, registry, URL_A, 64, 100, true);

        final LivePlaybackRegistry.Decision swapped = registry.request(PLOT, URL_B, 64, 100, true);
        helper.assertTrue(swapped == LivePlaybackRegistry.Decision.LOAD,
                "曲が変わったのにロードへ進んでいない: " + swapped);
        helper.assertTrue(first.stopped, "古い曲が止められていない (2 曲同時に鳴る)");

        registry.loadFinished(PLOT, URL_B);
        final FakeVoice second = new FakeVoice("B");
        helper.assertTrue(registry.install(PLOT, URL_B, second), "新しい曲が登録できていない");
        helper.assertTrue(URL_B.equals(registry.activeUrl(PLOT)), "鳴っている曲が入れ替わっていない");
        helper.succeed();
    }

    /**
     * ロードしている間に曲が変わっていたら、遅れて完了した古い曲は鳴らさずに捨てること。
     * ここが無いと、差し替えた直後に古い曲が上書きで鳴り出す。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void aLoadThatFinishesAfterTheTrackChangedIsDiscarded(GameTestHelper helper) {
        final LivePlaybackRegistry<Long> registry = new LivePlaybackRegistry<>();

        helper.assertTrue(registry.request(PLOT, URL_A, 64, 100, true) == LivePlaybackRegistry.Decision.LOAD, "A");
        // A のロード中に曲が B へ変わる。
        registry.loadFinished(PLOT, URL_A);
        helper.assertTrue(registry.request(PLOT, URL_B, 64, 100, true) == LivePlaybackRegistry.Decision.LOAD, "B");

        helper.assertFalse(registry.install(PLOT, URL_A, new FakeVoice("late A")),
                "曲が変わった後に完了した古いロードが登録を受け付けられている");
        helper.assertTrue(registry.activeUrl(PLOT) == null, "捨てるべき音源が鳴っている扱いになっている");
        helper.succeed();
    }

    /**
     * ロード中の同じ URL の再送はロードを重ねないこと。<b>ここを世代トークンにすると</b>、
     * 再送間隔 (1 秒) より遅い音源が一度も鳴らなくなる (常に後発が先発を打ち消す)。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void resendsDuringTheInitialLoadDoNotStackOrCancelIt(GameTestHelper helper) {
        final LivePlaybackRegistry<Long> registry = new LivePlaybackRegistry<>();
        helper.assertTrue(registry.request(PLOT, URL_A, 64, 100, true) == LivePlaybackRegistry.Decision.LOAD,
                "最初の要求がロードに進んでいない");

        // ロードが 1 秒より長引き、その間に再送が 2 回来る。
        for (int i = 0; i < 2; i++) {
            helper.assertTrue(registry.request(PLOT, URL_A, 64, 100, true) == LivePlaybackRegistry.Decision.SKIP,
                    "ロード中の再送でロードを重ねている");
        }

        registry.loadFinished(PLOT, URL_A);
        final FakeVoice voice = new FakeVoice("A");
        helper.assertTrue(registry.install(PLOT, URL_A, voice),
                "最初のロードが再送に打ち消されて一度も鳴らない (世代トークンを使うとこうなる)");
        helper.succeed();
    }

    /** 失敗した URL は繋ぎ直さないこと (1Hz の再送でチャットとログが埋まらない)。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void aFailedUrlIsNotRetriedUntilTheTrackChanges(GameTestHelper helper) {
        final LivePlaybackRegistry<Long> registry = new LivePlaybackRegistry<>();
        registry.request(PLOT, URL_A, 64, 100, true);
        registry.loadFinished(PLOT, URL_A);
        registry.loadFailed(PLOT, URL_A);

        helper.assertTrue(registry.request(PLOT, URL_A, 64, 100, true) == LivePlaybackRegistry.Decision.SKIP,
                "失敗した URL を毎秒繋ぎ直している");
        helper.assertTrue(registry.request(PLOT, URL_B, 64, 100, true) == LivePlaybackRegistry.Decision.LOAD,
                "曲を変えても繋ぎ直さない (差し替えで復帰できない)");
        helper.succeed();
    }

    /** 鳴っている状態を作る共通手順 ({@code SableAudioClient} が通る順番と同じ)。 */
    private static FakeVoice playing(GameTestHelper helper, LivePlaybackRegistry<Long> registry,
            String url, int rangeBlocks, int volumePercent, boolean directional) {
        helper.assertTrue(registry.request(PLOT, url, rangeBlocks, volumePercent, directional)
                == LivePlaybackRegistry.Decision.LOAD, "最初の要求がロードに進んでいない");
        registry.loadFinished(PLOT, url);
        final FakeVoice voice = new FakeVoice(url);
        voice.setRangeBlocks(rangeBlocks);
        voice.setVolumePercent(volumePercent);
        voice.setDirectional(directional);
        helper.assertTrue(registry.install(PLOT, url, voice), "音源が登録できていない");
        return voice;
    }
}
