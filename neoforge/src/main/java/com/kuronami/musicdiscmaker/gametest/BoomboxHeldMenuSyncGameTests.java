package com.kuronami.musicdiscmaker.gametest;

import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.PlaybackCursor;
import com.kuronami.musicdiscmaker.event.BoomboxPlayback;
import com.kuronami.musicdiscmaker.menu.BoomboxMenu;
import com.kuronami.musicdiscmaker.menu.BoomboxSource;
import com.kuronami.musicdiscmaker.network.BoomboxStatePayload;
import com.kuronami.musicdiscmaker.network.ControlBoomboxPayload;
import com.kuronami.musicdiscmaker.network.ModNetwork;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.platform.services.INetworkHelper;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;
//? if <1.21.2 {
/*import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
*///?}
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** 手持ち Boombox の実 menu open と control 受付の server 側境界を固定する。 */
//? if <1.21.2 {
/*@GameTestHolder("music_disc_maker")
*///?}
public final class BoomboxHeldMenuSyncGameTests {
    private BoomboxHeldMenuSyncGameTests() { }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void mainHandOpenKeepsIdentityAcrossCursorUpdates(GameTestHelper helper) {
        final ServerPlayer player = networkedPlayer(helper);
        final ItemStack box = box(7L);
        withCapturedNetwork(net -> {
            player.setItemInHand(InteractionHand.MAIN_HAND, box);
            Services.MENU.openBoomboxMenu(player, BoomboxSource.held(InteractionHand.MAIN_HAND));
            helper.assertTrue(player.containerMenu instanceof BoomboxMenu,
                    "loaderのopenBoomboxMenuが手持ちmenuを開かなかった");
            final BoomboxMenu menu = (BoomboxMenu) player.containerMenu;
            assertSameOpenSource(helper, player, menu, box, "open直後");

            menu.broadcastChanges();
            assertSameOpenSource(helper, player, menu, box, "初回broadcast後");

            final long initialGeneration = BoomboxPlayback.stateOf(box).cursor().generation();
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(menu.containerId, initialGeneration,
                    ControlBoomboxPayload.PLAY, 0L), player);
            final var playing = BoomboxPlayback.stateOf(box);
            helper.assertTrue(playing.cursor().state() == PlaybackCursor.State.PLAYING
                            && playing.cursor().generation() > initialGeneration,
                    "main-handの正しいPLAYがserver cursorを開始しなかった");
            assertSameOpenSource(helper, player, menu, box, "PLAY更新後");

            menu.broadcastChanges();
            assertSameOpenSource(helper, player, menu, box, "PLAY broadcast後");
            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(menu.containerId,
                    playing.cursor().generation(), ControlBoomboxPayload.PAUSE, 0L), player);
            helper.assertTrue(BoomboxPlayback.stateOf(box).cursor().state() == PlaybackCursor.State.PAUSED,
                    "PLAYによるcomponent更新後のPAUSEがidentity判定で拒否された");
            assertSameOpenSource(helper, player, menu, box, "PAUSE更新後");
        }, () -> stopAndClose(player, box));
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void staleInitialGenerationIsRejectedThenCurrentAccepted(GameTestHelper helper) {
        final ServerPlayer player = networkedPlayer(helper);
        final ItemStack box = box(11L);
        withCapturedNetwork(net -> {
            player.setItemInHand(InteractionHand.MAIN_HAND, box);
            Services.MENU.openBoomboxMenu(player, BoomboxSource.held(InteractionHand.MAIN_HAND));
            helper.assertTrue(player.containerMenu instanceof BoomboxMenu,
                    "loaderのopenBoomboxMenuが手持ちmenuを開かなかった");
            final BoomboxMenu menu = (BoomboxMenu) player.containerMenu;
            final long currentGeneration = BoomboxPlayback.stateOf(box).cursor().generation();
            helper.assertTrue(currentGeneration > 0L, "陽性対照のserver generationが初期値のまま");
            menu.broadcastChanges();
            final var initialStates = net.of(BoomboxStatePayload.class);
            helper.assertTrue(initialStates.size() == 1,
                    "open後の初回broadcastがserver stateを1通送らなかった (" + initialStates.size() + "通)");
            final BoomboxStatePayload initialState = (BoomboxStatePayload) initialStates.get(0).payload();
            helper.assertTrue(initialState.containerId() == menu.containerId
                            && initialState.state().cursor().generation() == currentGeneration,
                    "open後の初回state同期が現在のserver generationを返していない");
            net.clear();

            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(menu.containerId, 0L,
                    ControlBoomboxPayload.PLAY, 0L), player);
            final var afterStale = BoomboxPlayback.stateOf(box);
            helper.assertTrue(afterStale.cursor().state() == PlaybackCursor.State.STOPPED
                            && afterStale.cursor().generation() == currentGeneration,
                    "初回state同期前を模した古いgenerationのPLAYをserverが受理した");
            assertSameOpenSource(helper, player, menu, box, "stale PLAY拒否後");
            final var rejectedStates = net.of(BoomboxStatePayload.class);
            helper.assertTrue(rejectedStates.size() == 1,
                    "古いgeneration拒否時に現在stateが1通返らなかった (" + rejectedStates.size() + "通)");
            final BoomboxStatePayload rejectedState = (BoomboxStatePayload) rejectedStates.get(0).payload();
            helper.assertTrue(rejectedState.containerId() == menu.containerId
                            && rejectedState.state().cursor().generation() == currentGeneration
                            && rejectedState.state().cursor().state() == PlaybackCursor.State.STOPPED,
                    "古いgeneration拒否後のstate同期が現在のserver cursorを返していない");
            net.clear();

            ModNetwork.handleControlBoombox(new ControlBoomboxPayload(menu.containerId, currentGeneration,
                    ControlBoomboxPayload.PLAY, 0L), player);
            final var afterCurrent = BoomboxPlayback.stateOf(box);
            helper.assertTrue(afterCurrent.cursor().state() == PlaybackCursor.State.PLAYING
                            && afterCurrent.cursor().generation() > currentGeneration,
                    "現在のgenerationで再送したPLAYをserverが受理しなかった");
            assertSameOpenSource(helper, player, menu, box, "current PLAY受理後");
            final var acceptedStates = net.of(BoomboxStatePayload.class);
            helper.assertTrue(!acceptedStates.isEmpty(),
                    "正しいgeneration受理後に現在stateが返らなかった");
            final BoomboxStatePayload acceptedState = (BoomboxStatePayload)
                    acceptedStates.get(acceptedStates.size() - 1).payload();
            helper.assertTrue(acceptedState.containerId() == menu.containerId
                            && acceptedState.state().cursor().generation() == afterCurrent.cursor().generation()
                            && acceptedState.state().cursor().state() == PlaybackCursor.State.PLAYING,
                    "正しいgeneration受理後のstate同期が更新後server cursorを返していない");
        }, () -> stopAndClose(player, box));
        helper.succeed();
    }

    private static ItemStack box(long generation) {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(), new CustomTrackData(
                "https://example.invalid/held-menu", "held-menu", "test", 120_000L, "", false));
        final PlaybackCursor cursor = new PlaybackCursor(PlaybackCursor.NO_INDEX, PlaybackCursor.NO_INDEX,
                PlaybackCursor.State.STOPPED, generation);
        final ItemStack box = new ItemStack(ModItems.BOOMBOX.get());
        box.set(ModDataComponents.BOOMBOX_CONTENTS.get(),
                new BoomboxContents(disc, BoomboxContents.mintId()).withPlayback(cursor, 0L));
        return box;
    }

    private static void assertSameOpenSource(GameTestHelper helper, ServerPlayer player, BoomboxMenu menu,
            ItemStack box, String phase) {
        helper.assertTrue(player.getItemInHand(InteractionHand.MAIN_HAND) == box
                        && menu.currentStack() == box && menu.stillValid(player),
                phase + "にmain-hand ItemStackの参照またはmenu有効性を失った");
    }

    private static void stopAndClose(ServerPlayer player, ItemStack box) {
        try {
            final BoomboxContents contents = box.getOrDefault(ModDataComponents.BOOMBOX_CONTENTS.get(),
                    BoomboxContents.EMPTY);
            if (contents.hasId()) {
                BoomboxPlayback.stop((net.minecraft.server.level.ServerLevel) player.level(),
                        player.chunkPosition(), contents.id());
            }
        } finally {
            try {
                player.containerMenu = player.inventoryMenu;
                player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            } finally {
                player.discard();
            }
        }
    }

    /**
     * GameTest の他fixtureが残した未交渉mock playerへchunk payloadを実送信しない。
     * menu open自体はloader実装を通し、Boomboxの送信要求とserver stateだけを捕捉する。
     */
    private static void withCapturedNetwork(java.util.function.Consumer<CapturingNetwork> body,
            Runnable cleanup) {
        final CapturingNetwork net = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(net);
        Throwable bodyFailure = null;
        try {
            body.accept(net);
        } catch (RuntimeException | Error failure) {
            bodyFailure = failure;
            throw failure;
        } finally {
            try {
                cleanup.run();
            } catch (RuntimeException | Error cleanupFailure) {
                if (bodyFailure != null) {
                    bodyFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            } finally {
                Services.swapNetwork(previous);
            }
        }
    }

    private static ServerPlayer networkedPlayer(GameTestHelper helper) {
        final ServerPlayer player = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        return player;
    }
}
