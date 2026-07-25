package com.kuronami.musicdiscmaker.gametest;

import java.util.List;
import java.util.UUID;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.SilentSongs;
import com.kuronami.musicdiscmaker.event.BoomboxCarry;
import com.kuronami.musicdiscmaker.event.BoomboxHeartbeat;
import com.kuronami.musicdiscmaker.event.BoomboxPlayback;
import com.kuronami.musicdiscmaker.item.BoomboxItem;
import com.kuronami.musicdiscmaker.network.BoomboxPlayPayload;
import com.kuronami.musicdiscmaker.network.BoomboxStopPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.platform.services.INetworkHelper;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.EitherHolder;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.JukeboxPlayable;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * ブームボックス (純アイテムの携帯プレイヤー) の server 側ロジックの headless テスト。
 *
 * <p>固定するのは <b>実機ラウンド1 で却下された挙動</b>を中心に据えた 5 点:
 * ①持ち替えても鳴り続ける ②インベントリの外へ出たら止まる ③同じ URL の 2 台が独立
 * ④GUI の変更が鳴らし直さずに届く ⑤落として拾い直した個体が操作なしに鳴り出さない。
 * 実音と聴取挙動は client 側なのでここでは扱わない (実機確認帯)。
 *
 * <p>各テストは 1 tick の中で同期的に完結させてある。{@code BoomboxPlayback} のセッション表と
 * {@code Services.NETWORK} はどちらも static なので、{@code thenExecuteAfter} で tick を跨ぐと
 * 同一 batch 内で並列に走る他のテストと状態を奪い合う (P4 の地雷)。
 */
@GameTestHolder(MusicDiscMaker.MODID)
public class BoomboxGameTests {

    private static final String TEMPLATE = "empty8x3x8";
    private static final String URL_A = "https://example.invalid/a";

    /** 再生可能な custom disc (silent song つき) を 1 枚作る。 */
    private static ItemStack customDisc(String url, long durationMs) {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(),
                new CustomTrackData(url, "test", "tester", durationMs, "", false));
        // バニラの「再生中」状態に乗せる (DiscFabrication と同じ形)。
        disc.set(DataComponents.JUKEBOX_PLAYABLE,
                new JukeboxPlayable(new EitherHolder<>(SilentSongs.pick(durationMs)), false));
        return disc;
    }

    private static ItemStack loadedBoombox(String url) {
        final ItemStack stack = new ItemStack(ModItems.BOOMBOX.get());
        stack.set(ModDataComponents.BOOMBOX_CONTENTS.get(),
                new BoomboxContents(customDisc(url, 60_000L), 100, false));
        return stack;
    }

    /** 走査を 1 回だけ回す (テストは tick 間隔に依存しない)。 */
    private static void scan(ServerPlayer player) {
        BoomboxPlayback.scan(List.of(player), System.currentTimeMillis());
    }

    private interface Body {
        void run(ServerPlayer player, CapturingNetwork net);
    }

    /** static なセッション表と NETWORK を、テストごとに素の状態から始めて必ず戻す。 */
    private static void isolated(GameTestHelper helper, Body body) {
        final ServerPlayer player = helper.makeMockServerPlayerInLevel();
        final CapturingNetwork net = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(net);
        BoomboxPlayback.clear();
        try {
            body.run(player, net);
        } finally {
            BoomboxPlayback.clear();
            Services.swapNetwork(previous);
        }
        helper.succeed();
    }

    // ── 操作 ────────────────────────────────────────────────────────────

    /** トグル: ディスクなしでは鳴らない / 入っていれば鳴り、もう一度で止まる。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void boomboxToggle(GameTestHelper helper) {
        isolated(helper, (player, net) -> {
            final ItemStack empty = new ItemStack(ModItems.BOOMBOX.get());
            player.getInventory().items.set(0, empty);
            helper.assertFalse(BoomboxPlayback.toggle(player, empty), "ディスクなしで再生を受け付けている");
            helper.assertFalse(BoomboxPlayback.isPlaying(empty), "ディスクなしで鳴っている");

            final ItemStack loaded = loadedBoombox(URL_A);
            player.getInventory().items.set(0, loaded);
            helper.assertTrue(BoomboxPlayback.toggle(player, loaded), "ディスク入りで再生を受け付けない");
            helper.assertTrue(BoomboxPlayback.isPlaying(loaded), "再生が始まっていない");

            final List<CapturingNetwork.Sent> plays = net.of(BoomboxPlayPayload.class);
            helper.assertTrue(plays.size() == 1, "再生開始 packet が 1 通でない: " + plays.size());
            helper.assertTrue("entityAndSelf".equals(plays.get(0).kind()),
                    "再生 packet の宛先が entityAndSelf でない: " + plays.get(0).kind());
            final BoomboxPlayPayload play = (BoomboxPlayPayload) plays.get(0).payload();
            helper.assertTrue(play.ownerEntityId() == player.getId(), "持ち主の entityId が載っていない");
            helper.assertTrue(play.boomboxId().equals(loaded.get(ModDataComponents.BOOMBOX_ID.get())),
                    "payload のキーがアイテム個体の UUID でない");
            helper.assertFalse(play.directional(), "指向性の既定が OFF で送られていない");

            helper.assertTrue(BoomboxPlayback.toggle(player, loaded), "停止を受け付けない");
            helper.assertFalse(BoomboxPlayback.isPlaying(loaded), "停止できていない");
            helper.assertTrue(net.of(BoomboxStopPayload.class).size() == 1, "停止 packet が 1 通でない");
        });
    }

    /**
     * <b>持ち替えても鳴り続ける</b> (実機ラウンド1 の主要な却下点)。手に別のアイテムを持ち、
     * ブームボックスをインベントリ奥のスロットへ移しても走査は生存として扱い、keep-alive を撃つ。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void boomboxKeepsPlayingAfterSwitchingHeldItem(GameTestHelper helper) {
        isolated(helper, (player, net) -> {
            final ItemStack loaded = loadedBoombox(URL_A);
            player.setItemInHand(InteractionHand.MAIN_HAND, loaded);
            BoomboxPlayback.toggle(player, loaded);
            helper.assertTrue(BoomboxPlayback.isPlaying(loaded), "再生が始まっていない");

            // 手にはツルハシ、ブームボックスはインベントリ奥 (ホットバー外) へ。
            player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.DIAMOND_PICKAXE));
            player.getInventory().items.set(20, loaded);

            // keep-alive が撃たれるまで時間を進めた体で走査する。
            BoomboxPlayback.scan(List.of(player),
                    System.currentTimeMillis() + BoomboxPlayback.HEARTBEAT_MS + 1L);
            helper.assertTrue(BoomboxPlayback.isPlaying(loaded), "持ち替えたら止まった");
            helper.assertTrue(net.of(BoomboxPlayPayload.class).size() == 2,
                    "持ち替え後に keep-alive が撃たれていない: " + net.of(BoomboxPlayPayload.class).size());
            helper.assertTrue(net.of(BoomboxStopPayload.class).isEmpty(),
                    "持ち替えただけで停止 packet が出ている");
        });
    }

    /** カーソルに掴んでいる間も鳴り続ける (インベントリ画面でスタックを持ち上げた状態)。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void boomboxKeepsPlayingOnCursor(GameTestHelper helper) {
        isolated(helper, (player, net) -> {
            final ItemStack loaded = loadedBoombox(URL_A);
            player.getInventory().items.set(0, loaded);
            BoomboxPlayback.toggle(player, loaded);

            player.getInventory().items.set(0, ItemStack.EMPTY);
            player.containerMenu.setCarried(loaded); // 掴んだ状態
            scan(player);
            helper.assertTrue(BoomboxPlayback.isPlaying(loaded), "カーソルに掴んだら止まった");
            helper.assertTrue(net.of(BoomboxStopPayload.class).isEmpty(), "掴んだだけで停止している");
        });
    }

    /** インベントリの外へ出たら止まる (落とした / チェストへ入れた / 死亡ドロップ)。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void boomboxStopsWhenLeavingInventory(GameTestHelper helper) {
        isolated(helper, (player, net) -> {
            final ItemStack loaded = loadedBoombox(URL_A);
            player.getInventory().items.set(3, loaded);
            BoomboxPlayback.toggle(player, loaded);
            final UUID id = loaded.get(ModDataComponents.BOOMBOX_ID.get());

            player.getInventory().items.set(3, ItemStack.EMPTY); // 落とした
            scan(player);
            helper.assertFalse(BoomboxPlayback.isPlaying(loaded), "インベントリから出たのに鳴っている");
            final List<CapturingNetwork.Sent> stops = net.of(BoomboxStopPayload.class);
            helper.assertTrue(stops.size() == 1, "停止 packet が 1 通でない: " + stops.size());
            helper.assertTrue(((BoomboxStopPayload) stops.get(0).payload()).boomboxId().equals(id),
                    "停止 packet のキーが違う");
            helper.assertTrue("entityAndSelf".equals(stops.get(0).kind()),
                    "停止 packet の宛先が entityAndSelf でない");
        });
    }

    /** ディスクを抜くと止まる。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void boomboxStopsWhenDiscRemoved(GameTestHelper helper) {
        isolated(helper, (player, net) -> {
            final ItemStack loaded = loadedBoombox(URL_A);
            player.getInventory().items.set(0, loaded);
            BoomboxPlayback.toggle(player, loaded);

            loaded.set(ModDataComponents.BOOMBOX_CONTENTS.get(), BoomboxContents.EMPTY);
            scan(player);
            helper.assertFalse(BoomboxPlayback.isPlaying(loaded), "ディスクを抜いても鳴っている");
            helper.assertTrue(net.of(BoomboxStopPayload.class).size() == 1, "停止 packet が出ていない");
        });
    }

    /**
     * <b>同じ URL の 2 台が独立に鳴り、独立に止まる</b>。キーが URL でなくアイテム個体である
     * ことの検査 — URL キーだと片方を止めた瞬間にもう片方も黙る。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void boomboxUnitsWithSameUrlAreIndependent(GameTestHelper helper) {
        isolated(helper, (player, net) -> {
            final ItemStack first = loadedBoombox(URL_A);
            final ItemStack second = loadedBoombox(URL_A); // 同じ URL
            player.getInventory().items.set(0, first);
            player.getInventory().items.set(1, second);
            BoomboxPlayback.toggle(player, first);
            BoomboxPlayback.toggle(player, second);
            helper.assertTrue(BoomboxPlayback.activeSessionCount() == 2,
                    "同じ URL の 2 台が独立に鳴っていない: " + BoomboxPlayback.activeSessionCount());
            final UUID firstId = first.get(ModDataComponents.BOOMBOX_ID.get());
            final UUID secondId = second.get(ModDataComponents.BOOMBOX_ID.get());
            helper.assertFalse(firstId.equals(secondId), "2 台のキーが同一になっている");

            BoomboxPlayback.toggle(player, first); // 片方だけ止める
            helper.assertFalse(BoomboxPlayback.isPlaying(first), "止めた側が鳴ったまま");
            helper.assertTrue(BoomboxPlayback.isPlaying(second), "止めていない側まで黙った");
            final List<CapturingNetwork.Sent> stops = net.of(BoomboxStopPayload.class);
            helper.assertTrue(stops.size() == 1, "停止 packet が 1 通でない: " + stops.size());
            helper.assertTrue(((BoomboxStopPayload) stops.get(0).payload()).boomboxId().equals(firstId),
                    "停止 packet が別個体を指している");
        });
    }

    /** 複製で同じ UUID を持った 2 個目は、参照した時点で振り直される。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void boomboxDuplicateIdIsReminted(GameTestHelper helper) {
        isolated(helper, (player, net) -> {
            final UUID shared = UUID.randomUUID();
            final ItemStack first = loadedBoombox(URL_A);
            final ItemStack second = loadedBoombox(URL_A);
            first.set(ModDataComponents.BOOMBOX_ID.get(), shared);
            second.set(ModDataComponents.BOOMBOX_ID.get(), shared); // クリエ複製・/give 相当
            player.getInventory().items.set(0, first);
            player.getInventory().items.set(1, second);

            final UUID resolved = BoomboxPlayback.identify(player, second);
            helper.assertFalse(shared.equals(resolved), "重複した UUID が振り直されていない");
            helper.assertTrue(shared.equals(first.get(ModDataComponents.BOOMBOX_ID.get())),
                    "先にあった側の UUID まで変わっている");
        });
    }

    /** GUI の設定変更は鳴らし直さずに届く (停止 packet を挟まず、新しい値の keep-alive が 1 通)。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void boomboxConfigAppliesWithoutRestart(GameTestHelper helper) {
        isolated(helper, (player, net) -> {
            final ItemStack loaded = loadedBoombox(URL_A);
            player.getInventory().items.set(0, loaded);
            BoomboxPlayback.toggle(player, loaded);
            final UUID id = loaded.get(ModDataComponents.BOOMBOX_ID.get());

            BoomboxPlayback.applyConfig(player, id, 40, true);
            final BoomboxContents contents = loaded.get(ModDataComponents.BOOMBOX_CONTENTS.get());
            helper.assertTrue(contents != null && contents.volumePercent() == 40,
                    "音量が component に入っていない");
            helper.assertTrue(contents != null && contents.directional(),
                    "指向性が component に入っていない");
            helper.assertTrue(BoomboxPlayback.isPlaying(loaded), "設定変更で再生が切れている");
            helper.assertTrue(net.of(BoomboxStopPayload.class).isEmpty(),
                    "設定変更で停止 packet が出ている (鳴らし直しになる)");

            final List<CapturingNetwork.Sent> plays = net.of(BoomboxPlayPayload.class);
            helper.assertTrue(plays.size() == 2, "設定変更の即時送信が撃たれていない: " + plays.size());
            final BoomboxPlayPayload latest = (BoomboxPlayPayload) plays.get(1).payload();
            helper.assertTrue(latest.volumePercent() == 40, "新しい音量が送られていない");
            helper.assertTrue(latest.directional(), "新しい指向性が送られていない");
        });
    }

    /** 鳴っていないのに残ったフラグは走査で落とす (落として拾い直した個体が勝手に鳴り出さない)。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void boomboxStalePlayingFlagIsCleared(GameTestHelper helper) {
        isolated(helper, (player, net) -> {
            final ItemStack loaded = loadedBoombox(URL_A);
            loaded.set(ModDataComponents.BOOMBOX_ID.get(), UUID.randomUUID());
            loaded.set(ModDataComponents.BOOMBOX_PLAYING.get(), Boolean.TRUE); // 拾った時の残骸
            player.getInventory().items.set(0, loaded);

            scan(player);
            helper.assertFalse(BoomboxPlayback.isPlaying(loaded), "セッションが無いのに鳴っている扱い");
            helper.assertTrue(loaded.get(ModDataComponents.BOOMBOX_PLAYING.get()) == null,
                    "残ったフラグが落とされていない");
            helper.assertTrue(net.of(BoomboxPlayPayload.class).isEmpty(),
                    "操作していないのに再生 packet が出ている");
        });
    }

    /** 右クリックは<b>常に</b>トグル: シフト時だけは GUI 側へ行き、再生状態を動かさない。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void boomboxRightClickTogglesAndShiftDoesNot(GameTestHelper helper) {
        isolated(helper, (player, net) -> {
            final ItemStack loaded = loadedBoombox(URL_A);
            player.setItemInHand(InteractionHand.MAIN_HAND, loaded);

            player.setShiftKeyDown(false);
            helper.assertTrue(BoomboxItem.interact(player, loaded), "右クリックが消費されていない");
            helper.assertTrue(BoomboxPlayback.isPlaying(loaded), "右クリックで再生が始まらない");

            player.setShiftKeyDown(false);
            BoomboxItem.interact(player, loaded);
            helper.assertFalse(BoomboxPlayback.isPlaying(loaded), "右クリックで停止できない");
        });
    }

    // ── 継続の境界 (BoomboxCarry) ───────────────────────────────────────

    /** 鳴ってよい場所の集合: ホットバー・メイン・オフハンド・カーソルは入り、防具スロットは入らない。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void boomboxCarryBoundary(GameTestHelper helper) {
        isolated(helper, (player, net) -> {
            final ItemStack hotbar = new ItemStack(ModItems.BOOMBOX.get());
            final ItemStack deep = new ItemStack(ModItems.BOOMBOX.get());
            final ItemStack offhand = new ItemStack(ModItems.BOOMBOX.get());
            final ItemStack cursor = new ItemStack(ModItems.BOOMBOX.get());
            final ItemStack armor = new ItemStack(ModItems.BOOMBOX.get());
            player.getInventory().items.set(0, hotbar);
            player.getInventory().items.set(25, deep);
            player.getInventory().offhand.set(0, offhand);
            player.containerMenu.setCarried(cursor);
            player.getInventory().armor.set(0, armor);

            final List<ItemStack> carried = BoomboxCarry.carried(player);
            helper.assertTrue(carried.size() == 4, "鳴ってよい場所の数が違う: " + carried.size());
            helper.assertTrue(carried.contains(hotbar), "ホットバーが入っていない");
            helper.assertTrue(carried.contains(deep), "メインインベントリ奥が入っていない");
            helper.assertTrue(carried.contains(offhand), "オフハンドが入っていない");
            helper.assertTrue(carried.contains(cursor), "カーソルが入っていない");
            helper.assertFalse(carried.contains(armor), "防具スロットが入ってしまっている");
        });
    }

    // ── 純ロジック (MC 非依存の判断層) ──────────────────────────────────

    /** 走査 1 回ぶんの判断規則。曲の差し替え・尺の使い切り・間隔の 3 軸。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void boomboxHeartbeatDecisions(GameTestHelper helper) {
        helper.assertTrue(BoomboxHeartbeat.decide(false, false, 0L, 60_000L, 0L, 1_000L)
                == BoomboxHeartbeat.Action.START, "セッション不在が START でない");
        helper.assertTrue(BoomboxHeartbeat.decide(true, true, 5_000L, 60_000L, 0L, 1_000L)
                == BoomboxHeartbeat.Action.START, "曲の差し替えが START でない");
        helper.assertTrue(BoomboxHeartbeat.decide(true, false, 60_000L, 60_000L, 0L, 1_000L)
                == BoomboxHeartbeat.Action.END, "尺の使い切りが END でない");
        helper.assertTrue(BoomboxHeartbeat.decide(true, false, 5_000L, 60_000L, 1_200L, 1_000L)
                == BoomboxHeartbeat.Action.KEEP_ALIVE, "間隔経過が KEEP_ALIVE でない");
        helper.assertTrue(BoomboxHeartbeat.decide(true, false, 5_000L, 60_000L, 100L, 1_000L)
                == BoomboxHeartbeat.Action.IDLE, "間隔内が IDLE でない");
        // 無限長 (ラジオ/ライブ) は尺で終わらせない。終わらせると放送の途中で黙る。
        helper.assertTrue(BoomboxHeartbeat.decide(true, false, 9_000_000L, 0L, 1_200L, 1_000L)
                == BoomboxHeartbeat.Action.KEEP_ALIVE, "無限長が尺で終了扱いになっている");
        // 無限長に位置の概念は無い。経過を載せると late-join が途中から開こうとする。
        helper.assertTrue(BoomboxHeartbeat.offsetFor(true, 500_000L) == 0L,
                "ライブの offset が 0 でない");
        helper.assertTrue(BoomboxHeartbeat.offsetFor(false, 500_000L) == 500_000L,
                "有限尺の offset が経過でない");
        helper.succeed();
    }
}
