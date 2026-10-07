package com.kuronami.musicdiscmaker.gametest;

//? if >=1.21 {
import java.util.List;

import com.kuronami.musicdiscmaker.compat.railways.ExternalCartPlayback;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.entity.GoldenJukeboxMinecartEntity;
import com.kuronami.musicdiscmaker.network.GoldenCartPlayPayload;
import com.kuronami.musicdiscmaker.network.GoldenCartStopPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.platform.services.INetworkHelper;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
//? if <1.21.11 {
/*import com.kuronami.musicdiscmaker.MusicDiscMaker;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
*///?}

/**
 * 他 MOD のジュークボックス付きトロッコ (Steam 'n' Rails) に入った MDM の盤の server 側ドライバ
 * {@link ExternalCartPlayback} を固定する。実 Steam 'n' Rails は GameTest に無いので、追従先の entity には
 * 媒体の入っていない金ジューク付きトロッコを使い、mixin が毎 tick 渡す「盤と状態」を直接渡す。
 */
//? if <1.21.11 {
/*@GameTestHolder(MusicDiscMaker.MODID)
*///?}
public final class ExternalCartPlaybackGameTests {
    private static final String TEMPLATE = "empty8x3x8";
    private static final long DURATION_MS = 60_000L;

    private ExternalCartPlaybackGameTests() {
    }

    /** 入れたら1回だけ鳴らし始め、抜いたら止め、別の盤に替えたら新しい世代で鳴らし直す。 */
    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    *///?}
    public static void startsStopsAndSwitchesWithTheDisc(GameTestHelper helper) {
        final var level = helper.getLevel();
        final Entity cart = cart(helper);
        final ServerPlayer player = listenerNear(helper, cart);
        final ExternalCartPlayback.State state = new ExternalCartPlayback.State();
        final ItemStack first = disc("https://example.invalid/external-cart-a");
        final ItemStack second = disc("https://example.invalid/external-cart-b");
        final CapturingNetwork net = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(net);
        try {
            ExternalCartPlayback.tick(cart, level, first, state);
            final List<GoldenCartPlayPayload> started = plays(net, cart);
            helper.assertTrue(started.size() == 1, "MDM の盤を入れて再生 payload が 1 通送られていない (" + started.size() + ")");
            helper.assertTrue(started.get(0).startOffsetMs() == 0L, "入れた直後の再生が頭からになっていない");
            helper.assertTrue(started.get(0).identity().sourceId().equals(cart.getUUID()),
                    "音源の識別子がトロッコの UUID になっていない");

            // 別の盤に替えた → 旧世代を止めて、新しい世代で頭から。
            net.clear();
            ExternalCartPlayback.tick(cart, level, second, state);
            helper.assertTrue(stops(net, cart) == 1, "盤の差し替えで旧世代の停止が送られていない");
            final List<GoldenCartPlayPayload> switched = plays(net, cart);
            helper.assertTrue(switched.size() == 1
                            && switched.get(0).identity().generation() > started.get(0).identity().generation()
                            && switched.get(0).track().url().equals("https://example.invalid/external-cart-b"),
                    "差し替えた盤が新しい世代で鳴り始めていない");

            // バニラの盤・空 → 止める (1 回だけ)。
            net.clear();
            ExternalCartPlayback.tick(cart, level, new ItemStack(Items.MUSIC_DISC_CAT), state);
            ExternalCartPlayback.tick(cart, level, ItemStack.EMPTY, state);
            helper.assertTrue(stops(net, cart) == 1, "MDM 以外の盤に替えて停止が 1 通になっていない (" + stops(net, cart) + ")");
            helper.assertTrue(plays(net, cart).isEmpty(), "MDM 以外の盤で再生 payload を送っている");
            helper.assertTrue(state.savedKey() == null, "止めた後も保存用の曲が残っている");
        } finally {
            Services.swapNetwork(previous);
            player.discard();
            cart.discard();
        }
        helper.succeed();
    }

    /** 読み込み直したトロッコは保存位置から鳴らし直し、鳴り終わっていた曲は鳴らさない。 */
    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 100)
    *///?}
    public static void resumesFromTheSavedOffsetAndStaysQuietAfterTheEnd(GameTestHelper helper) {
        final var level = helper.getLevel();
        final Entity cart = cart(helper);
        final ServerPlayer player = listenerNear(helper, cart);
        final String url = "https://example.invalid/external-cart-resume";
        final ItemStack disc = disc(url);
        final String key = disc.get(ModDataComponents.CUSTOM_TRACK.get()).sourceKey();
        final CapturingNetwork net = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(net);
        try {
            final ExternalCartPlayback.State resumed = new ExternalCartPlayback.State();
            resumed.restore(key, 30_000L);
            ExternalCartPlayback.tick(cart, level, disc, resumed);
            final List<GoldenCartPlayPayload> plays = plays(net, cart);
            helper.assertTrue(plays.size() == 1 && plays.get(0).startOffsetMs() == 30_000L,
                    "保存位置 30000ms から鳴らし直していない ("
                            + (plays.isEmpty() ? "送信なし" : plays.get(0).startOffsetMs() + "ms") + ")");
            helper.assertTrue(resumed.savedOffsetMs(cart) == 30_000L, "保存用の位置が再開位置と一致しない");

            // 別の曲の保存位置は使わない (盤が替わっていたら頭から)。
            net.clear();
            final ExternalCartPlayback.State otherKey = new ExternalCartPlayback.State();
            otherKey.restore("url:https://example.invalid/some-other-track", 30_000L);
            ExternalCartPlayback.tick(cart, level, disc, otherKey);
            helper.assertTrue(plays(net, cart).size() == 1 && plays(net, cart).get(0).startOffsetMs() == 0L,
                    "別の曲の保存位置で鳴らし直している");

            // 鳴り終わっていた曲は鳴らさない。
            net.clear();
            final ExternalCartPlayback.State ended = new ExternalCartPlayback.State();
            ended.restore(key, DURATION_MS + 1_000L);
            ExternalCartPlayback.tick(cart, level, disc, ended);
            helper.assertTrue(plays(net, cart).isEmpty(), "鳴り終わっていた曲を読み込み直しで鳴らしている");
        } finally {
            Services.swapNetwork(previous);
            player.discard();
            cart.discard();
        }
        helper.succeed();
    }

    private static Entity cart(GameTestHelper helper) {
        final var level = helper.getLevel();
        final BlockPos pos = helper.absolutePos(new BlockPos(2, 1, 2));
        final GoldenJukeboxMinecartEntity cart = new GoldenJukeboxMinecartEntity(
                level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        helper.assertTrue(level.addFreshEntity(cart), "stand-in minecart was not added");
        return cart;
    }

    /** mock player は world spawn に現れるので、可聴範囲 (既定 64 ブロック) に入るようトロッコの隣へ動かす。 */
    private static ServerPlayer listenerNear(GameTestHelper helper, Entity cart) {
        final ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setPos(cart.getX() + 1.0, cart.getY(), cart.getZ());
        return player;
    }

    private static ItemStack disc(String url) {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(),
                new CustomTrackData(url, "External cart", "GameTest", DURATION_MS, "", false));
        return disc;
    }

    private static List<GoldenCartPlayPayload> plays(CapturingNetwork net, Entity cart) {
        return net.of(GoldenCartPlayPayload.class).stream()
                .map(s -> (GoldenCartPlayPayload) s.payload())
                .filter(p -> p.entityId() == cart.getId() && !p.isStop())
                .toList();
    }

    private static long stops(CapturingNetwork net, Entity cart) {
        return net.of(GoldenCartStopPayload.class).stream()
                .map(s -> (GoldenCartStopPayload) s.payload())
                .filter(p -> p.entityId() == cart.getId())
                .count();
    }
}
//?}
