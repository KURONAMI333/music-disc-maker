package com.kuronami.musicdiscmaker.gametest;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.PlaybackCursor;
import com.kuronami.musicdiscmaker.compat.curios.CuriosEquipmentAccess;
import com.kuronami.musicdiscmaker.event.BoomboxCarry;
import com.kuronami.musicdiscmaker.event.BoomboxPlayback;
import com.kuronami.musicdiscmaker.menu.BoomboxMenu;
import com.kuronami.musicdiscmaker.menu.BoomboxSource;
import com.kuronami.musicdiscmaker.network.ControlBoomboxPayload;
import com.kuronami.musicdiscmaker.network.ModNetwork;
import com.kuronami.musicdiscmaker.network.OpenEquippedBoomboxPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.platform.services.INetworkHelper;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;
//? if <1.21.11 {
/*import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
*///?}
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/**
 * Curios 専用 {@code boombox} slot の機体を、adapter が返す中立参照だけを使って検証する。
 * 実 Curios jar は GameTest 実行時 classpath に無い (compileOnly) ので、slot の内容は
 * {@link CuriosEquipmentAccess#install} へ入れた fake provider で模す。検証対象は
 * 「専用 slot 認可 / wire 値の再解決 / 装備中交換 / 走査の複製分離 / 所持⇔装備の連続性」で、
 * いずれも Curios 本体ではなく common 側の機構。
 */
//? if <1.21.11 {
/*@GameTestHolder("music_disc_maker")
*///?}
public final class CuriosBoomboxGameTests {
    private CuriosBoomboxGameTests() { }

    /** 専用 slot の正当な要求だけが menu を開く。偽造 slot/index/古い機体idは全て拒否する。 */
    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 200)
    *///?}
    public static void equippedOpenAuthorizesOnlyDedicatedSlot(GameTestHelper helper) {
        final ServerPlayer player = networkedPlayer(helper);
        final ItemStack box = box(7L);
        final AtomicReference<List<CuriosEquipmentAccess.Entry>> slot =
                new AtomicReference<>(List.of(new CuriosEquipmentAccess.Entry("boombox", 0, box)));
        withProvider(slot, () -> {
            // 偽造された slot id (boombox 以外) は解決自体を拒否する。
            ModNetwork.handleOpenEquippedBoombox(
                    new OpenEquippedBoomboxPayload("curio", 0, BoomboxCarry.idOf(box)), player);
            helper.assertTrue(!(player.containerMenu instanceof BoomboxMenu),
                    "boombox 以外の slot id の要求で menu が開いた");
            // 専用 slot は 1 枠なので index 1 は存在しない。
            ModNetwork.handleOpenEquippedBoombox(
                    new OpenEquippedBoomboxPayload("boombox", 1, BoomboxCarry.idOf(box)), player);
            helper.assertTrue(!(player.containerMenu instanceof BoomboxMenu),
                    "専用 slot に無い index の要求で menu が開いた");
            // 別機体の id を名乗る参照は開かない。
            ModNetwork.handleOpenEquippedBoombox(
                    new OpenEquippedBoomboxPayload("boombox", 0, 999_999L), player);
            helper.assertTrue(!(player.containerMenu instanceof BoomboxMenu),
                    "現在の機体 id と一致しない要求で menu が開いた");

            ModNetwork.handleOpenEquippedBoombox(
                    new OpenEquippedBoomboxPayload("boombox", 0, BoomboxCarry.idOf(box)), player);
            helper.assertTrue(player.containerMenu instanceof BoomboxMenu,
                    "専用 slot の正当な要求で menu が開かなかった");
            final BoomboxMenu menu = (BoomboxMenu) player.containerMenu;
            helper.assertTrue(menu.currentStack() == box && menu.stillValid(player),
                    "開いた menu が装備中の実物を指していない");

            // 初回openでは未採番IDをserverが付与する。そのIDをmenuへ固定した後も、同じslot実物を
            // server側が参照し続け、操作対象を検証できることを確認する。
            player.containerMenu = player.inventoryMenu;
            final ItemStack unassigned = new ItemStack(ModItems.BOOMBOX.get());
            slot.set(List.of(new CuriosEquipmentAccess.Entry("boombox", 0, unassigned)));
            ModNetwork.handleOpenEquippedBoombox(new OpenEquippedBoomboxPayload(
                    "boombox", 0, BoomboxContents.UNASSIGNED), player);
            helper.assertTrue(player.containerMenu instanceof BoomboxMenu,
                    "未採番の装備機体を開けなかった");
            final BoomboxMenu identifiedMenu = (BoomboxMenu) player.containerMenu;
            helper.assertTrue(BoomboxCarry.idOf(unassigned) != BoomboxContents.UNASSIGNED
                            && identifiedMenu.source().expectedId() == BoomboxCarry.idOf(unassigned)
                            && identifiedMenu.currentStack() == unassigned && identifiedMenu.stillValid(player),
                    "未採番機体に付与したIDとmenu参照が揃っていない");
        }, player);
        helper.succeed();
    }

    /** 装備中に slot の実物が差し替わったら menu は無効になり、別機体へ書かない。 */
    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 200)
    *///?}
    public static void equippedExchangeInvalidatesOpenMenu(GameTestHelper helper) {
        final ServerPlayer player = networkedPlayer(helper);
        final ItemStack box = box(11L);
        final AtomicReference<List<CuriosEquipmentAccess.Entry>> slot =
                new AtomicReference<>(List.of(new CuriosEquipmentAccess.Entry("boombox", 0, box)));
        withProvider(slot, () -> {
            ModNetwork.handleOpenEquippedBoombox(
                    new OpenEquippedBoomboxPayload("boombox", 0, BoomboxCarry.idOf(box)), player);
            helper.assertTrue(player.containerMenu instanceof BoomboxMenu,
                    "装備機体の menu が開かなかった");
            final BoomboxMenu menu = (BoomboxMenu) player.containerMenu;
            final long generation = BoomboxPlayback.stateOf(box).cursor().generation();

            // 同じidを持つ複製スタックへ差し替える。idだけの照合では旧menuが通ってしまう。
            final ItemStack other = box(11L);
            slot.set(List.of(new CuriosEquipmentAccess.Entry("boombox", 0, other)));
            helper.assertTrue(!menu.stillValid(player) && menu.currentStack().isEmpty(),
                    "slot 交換後も menu が旧実物を有効と見なした");

            final long otherGeneration = BoomboxPlayback.stateOf(other).cursor().generation();
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(menu.containerId,
                    generation, ControlBoomboxPayload.PLAY, 0L), player);
            helper.assertTrue(BoomboxPlayback.stateOf(other).cursor().state()
                            == PlaybackCursor.State.STOPPED
                            && BoomboxPlayback.stateOf(other).cursor().generation() == otherGeneration,
                    "交換後の機体へ無効化済み menu の操作が書き込まれた");
            helper.assertTrue(BoomboxPlayback.stateOf(box).cursor().generation() == generation,
                    "交換された旧機体の世代が menu 操作で進んだ");
        }, player);
        helper.succeed();
    }

    /** 装備中機体が鳴っている間、インベントリの同 id 複製は走査で別個体へ分離される。 */
    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 200)
    *///?}
    public static void duplicateIdInInventoryIsSeparatedFromEquipped(GameTestHelper helper) {
        final ServerPlayer player = networkedPlayer(helper);
        final long id = BoomboxContents.mintId();
        final ItemStack equippedBox = box(id);
        final AtomicReference<List<CuriosEquipmentAccess.Entry>> slot =
                new AtomicReference<>(List.of(new CuriosEquipmentAccess.Entry("boombox", 0, equippedBox)));
        withProvider(slot, () -> {
            ModNetwork.handleOpenEquippedBoombox(
                    new OpenEquippedBoomboxPayload("boombox", 0, id), player);
            helper.assertTrue(player.containerMenu instanceof BoomboxMenu,
                    "装備機体の menu が開かなかった");
            final BoomboxMenu menu = (BoomboxMenu) player.containerMenu;
            final long generation = BoomboxPlayback.stateOf(equippedBox).cursor().generation();
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(menu.containerId,
                    generation, ControlBoomboxPayload.PLAY, 0L), player);
            helper.assertTrue(BoomboxPlayback.stateOf(equippedBox).cursor().state()
                            == PlaybackCursor.State.PLAYING,
                    "装備機体が再生を開始しなかった");
            helper.assertTrue(BoomboxPlayback.isPlaying(id),
                    "装備機体の session が live になっていない");

            // /give や creative 複製相当: 同じ id の別スタックがインベントリへ入った。
            final ItemStack duplicate = box(id);
            player.getInventory().setItem(9, duplicate);
            BoomboxPlayback.scan(List.of(player), System.currentTimeMillis());
            helper.assertTrue(BoomboxCarry.idOf(duplicate) != id,
                    "走査が同 id 複製を別個体へ分離しなかった");
            helper.assertTrue(BoomboxCarry.idOf(equippedBox) == id
                            && BoomboxPlayback.isPlaying(id),
                    "走査が装備中の本来機体の id/session まで巻き込んだ");
        }, player);
        helper.succeed();
    }

    /** 装備⇔インベントリの移動で同じスタックが見失われない (session 打刻先が連続する条件)。 */
    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void equippedStackStaysCarriedAcrossMove(GameTestHelper helper) {
        final ServerPlayer player = networkedPlayer(helper);
        final ItemStack box = box(21L);
        final AtomicReference<List<CuriosEquipmentAccess.Entry>> slot =
                new AtomicReference<>(List.of(new CuriosEquipmentAccess.Entry("boombox", 0, box)));
        withProvider(slot, () -> {
            helper.assertTrue(BoomboxCarry.carried(player).contains(box),
                    "装備中の機体が鳴ってよい場所として見えていない");
            helper.assertTrue(BoomboxCarry.find(player, BoomboxCarry.idOf(box)) == box,
                    "装備中の機体 id が find で引けない");

            // 専用 slot → インベントリへ移す。同じ参照が引き続き carried に見えれば session は切れない。
            slot.set(List.of());
            player.getInventory().setItem(10, box);
            helper.assertTrue(BoomboxCarry.carried(player).contains(box),
                    "slot からインベントリへ動かした機体が鳴る場所から外れた");

            // インベントリ → 専用 slot へ戻す。
            player.getInventory().setItem(10, ItemStack.EMPTY);
            slot.set(List.of(new CuriosEquipmentAccess.Entry("boombox", 0, box)));
            helper.assertTrue(BoomboxCarry.carried(player).contains(box),
                    "インベントリから slot へ戻した機体が鳴る場所から外れた");
        }, player);
        helper.succeed();
    }

    /** 装備↔持ち物移動は再生中も同じsessionを保ち、退出後はstale猶予で止まり再参加で勝手に再開しない。 */
    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 200)
    *///?}
    public static void equippedMoveAndDisconnectPreserveOnlyTheOwnedLifecycle(GameTestHelper helper) {
        final ServerPlayer player = networkedPlayer(helper);
        final ItemStack box = box(BoomboxContents.mintId());
        final long id = BoomboxCarry.idOf(box);
        final AtomicReference<List<CuriosEquipmentAccess.Entry>> slot =
                new AtomicReference<>(List.of(new CuriosEquipmentAccess.Entry("boombox", 0, box)));
        withProvider(slot, () -> {
            helper.assertTrue(BoomboxPlayback.startCarried(player, box),
                    "装備中機体の再生開始に失敗した");
            final long generation = BoomboxPlayback.stateOf(box).cursor().generation();
            helper.assertTrue(BoomboxPlayback.isPlaying(id), "装備中sessionが開始していない");

            // Curiosから持ち物へ同じ実物を移し、online scanで同じsessionを引き継ぐ。
            slot.set(List.of());
            player.getInventory().setItem(10, box);
            final long moveTime = System.currentTimeMillis() + 10L;
            BoomboxPlayback.scan(List.of(player), moveTime);
            helper.assertTrue(BoomboxPlayback.isPlaying(id)
                            && BoomboxCarry.idOf(player.getInventory().getItem(10)) == id
                            && BoomboxPlayback.stateOf(box).cursor().generation() == generation,
                    "装備から持ち物への移動でsession・機体ID・再生世代が変わった");

            // 切断後はonline scanから外れ、stale猶予が経過した時点でsessionだけが止まる。
            player.getInventory().setItem(10, ItemStack.EMPTY);
            BoomboxPlayback.scan(List.of(), moveTime + 1L);
            BoomboxPlayback.sweep(helper.getLevel().getServer(),
                    moveTime + BoomboxPlayback.STALE_MS + 2L);
            helper.assertFalse(BoomboxPlayback.isPlaying(id), "退出した機体のstale sessionが残った");
            helper.assertTrue(BoomboxCarry.idOf(box) == id
                            && BoomboxPlayback.stateOf(box).cursor().generation() == generation,
                    "退出処理が保存スタックの機体IDまたは再生世代を壊した");
            helper.assertTrue(BoomboxPlayback.stateOf(box).cursor().state() == PlaybackCursor.State.STOPPED
                            && BoomboxPlayback.stateOf(box).cursor().generation() == generation,
                    "stale sweep後の公開再生状態が停止へ正規化されない、または世代が変わった");

            // 同じ保存スタックが再参加してもscanは新規再生を起こさない。再生操作はGUIから行う。
            player.getInventory().setItem(10, box);
            BoomboxPlayback.scan(List.of(player), moveTime + BoomboxPlayback.STALE_MS + 3L);
            helper.assertFalse(BoomboxPlayback.isPlaying(id), "再参加scanだけで再生sessionが復活した");
            helper.assertTrue(BoomboxCarry.idOf(player.getInventory().getItem(10)) == id,
                    "再参加後に同じ機体IDが保たれなかった");
        }, player);
        helper.succeed();
    }

    /** provider 未 install (Curios 未導入) では専用 slot の解決も走査の列挙も起きない。 */
    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void absentProviderKeepsVanillaBehavior(GameTestHelper helper) {
        final ServerPlayer player = networkedPlayer(helper);
        // install しない状態: provider は NONE。
        final ItemStack box = box(31L);
        helper.assertTrue(CuriosEquipmentAccess.resolve(player, "boombox", 0) == null
                        && CuriosEquipmentAccess.first(player) == null,
                "provider 未導入で装備解決が空を返さなかった");
        helper.assertTrue(!BoomboxCarry.carried(player).contains(box),
                "provider 未導入で装備スタックが carried に混入した");
        ModNetwork.handleOpenEquippedBoombox(
                new OpenEquippedBoomboxPayload("boombox", 0, BoomboxCarry.idOf(box)), player);
        helper.assertTrue(!(player.containerMenu instanceof BoomboxMenu),
                "Curios 未導入相当で open 要求が menu を開いた");
        player.discard();
        helper.succeed();
    }

    private static ItemStack box(long id) {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(), new CustomTrackData(
                "https://example.invalid/equipped-menu", "equipped-menu", "test", 120_000L, "", false));
        final PlaybackCursor cursor = new PlaybackCursor(PlaybackCursor.NO_INDEX, PlaybackCursor.NO_INDEX,
                PlaybackCursor.State.STOPPED, 1L);
        final ItemStack box = new ItemStack(ModItems.BOOMBOX.get());
        // id は機体識別子、cursor の世代は別体系。generation は負値を取れないので混ぜない。
        box.set(ModDataComponents.BOOMBOX_CONTENTS.get(),
                new BoomboxContents(disc, id).withPlayback(cursor, 0L));
        return box;
    }

    /**
     * fake provider を install して本体を実行し、成否に関わらず provider を NONE へ戻す
     * (static な global state なので他テストへ漏らさない)。player の menu と機体も片付ける。
     */
    private static void withProvider(AtomicReference<List<CuriosEquipmentAccess.Entry>> slot,
            Runnable body, ServerPlayer player) {
        final CapturingNetwork net = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(net);
        CuriosEquipmentAccess.install(p -> slot.get());
        try {
            body.run();
        } finally {
            try {
                cleanup(player);
            } finally {
                CuriosEquipmentAccess.install(null);
                Services.swapNetwork(previous);
            }
        }
    }

    private static void cleanup(ServerPlayer player) {
        try {
            // providerが有効なうちに専用slotを含めて止める。Curios上の機体はInventory走査に
            // 含まれないため、先にproviderを外すとGameTest間にsessionが漏れる。
            for (final CuriosEquipmentAccess.Entry entry : CuriosEquipmentAccess.equipped(player)) {
                stopIfIdentified(player, entry.stack());
            }
            // 走査対象から外して session の再着火を防いでから止める。
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                final ItemStack stack = player.getInventory().getItem(i);
                if (BoomboxCarry.isBoombox(stack)) {
                    stopIfIdentified(player, stack);
                    player.getInventory().setItem(i, ItemStack.EMPTY);
                }
            }
        } finally {
            try {
                player.containerMenu = player.inventoryMenu;
            } finally {
                player.discard();
            }
        }
    }

    private static void stopIfIdentified(ServerPlayer player, ItemStack stack) {
        final BoomboxContents contents = stack.getOrDefault(
                ModDataComponents.BOOMBOX_CONTENTS.get(), BoomboxContents.EMPTY);
        if (contents.hasId()) {
            BoomboxPlayback.stop((net.minecraft.server.level.ServerLevel) player.level(),
                    player.chunkPosition(), contents.id());
        }
    }

    private static ServerPlayer networkedPlayer(GameTestHelper helper) {
        final ServerPlayer player = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        return player;
    }
}
