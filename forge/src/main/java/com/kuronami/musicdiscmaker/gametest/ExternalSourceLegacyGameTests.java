package com.kuronami.musicdiscmaker.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.event.ActiveDiscRegistry;
import com.kuronami.musicdiscmaker.event.ExternalPlaybackMirror;
import com.kuronami.musicdiscmaker.event.ForgeJukeboxHandler;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.network.ModPayload;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.platform.services.INetworkHelper;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.event.level.ChunkWatchEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * Forge 1.20.1 の外部音源 (Furniture の蓄音機) の受け口 {@link ExternalPlaybackMirror} と、chunk 再送の掃除が
 * それを守ることを固定する。実 Furniture jar は GameTest 実行時に無いので、蓄音機の代わりに jukebox 以外の
 * block entity (チェスト) を置き、蓄音機 mixin が毎 tick 呼ぶのと同じ口を直接叩く。
 */
@GameTestHolder(MusicDiscMaker.MODID)
@PrefixGameTestTemplate(false)
public final class ExternalSourceLegacyGameTests {
    private static final long DURATION_MS = 120_000L;

    private ExternalSourceLegacyGameTests() {
    }

    private static ItemStack customDisc(String url) {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        CustomMusicDiscItem.setTrack(disc, new CustomTrackData(url, "External", "tester", DURATION_MS, "", false));
        return disc;
    }

    /** 鳴らし始め・同じトラックの継続・止める、が registry と送信に一度ずつだけ現れること。 */
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void mirrorStartsOnceAndStops(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos rel = new BlockPos(1, 1, 1);
        helper.setBlock(rel, Blocks.CHEST);
        final BlockPos pos = helper.absolutePos(rel);
        final ResourceKey<Level> dim = level.dimension();
        final ItemStack disc = customDisc("https://example.invalid/external-start");

        final CapturingNetwork net = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(net);
        try {
            ExternalPlaybackMirror.mirror(level, pos, disc);
            ExternalPlaybackMirror.mirror(level, pos, disc);
            helper.assertTrue(ActiveDiscRegistry.current(dim, pos) != null, "外部音源の再生が registry に入っていない");
            helper.assertTrue(ActiveDiscRegistry.isExternal(dim, pos), "外部音源の再生に印が付いていない");
            helper.assertTrue(net.playsFor(pos).size() == 1,
                    "同じトラックの継続で再生 payload を送り直している (" + net.playsFor(pos).size() + " 通)");

            // バニラの盤に替わった (MDM ストリーム対象なし) → 止める。
            ExternalPlaybackMirror.mirror(level, pos, new ItemStack(Items.MUSIC_DISC_13));
            helper.assertTrue(ActiveDiscRegistry.current(dim, pos) == null, "MDM 以外の盤に替わっても registry が残っている");
            helper.assertTrue(!ActiveDiscRegistry.isExternal(dim, pos), "止めた後も外部音源の印が残っている");
            helper.assertTrue(net.stopsFor(pos) == 1, "停止 payload が 1 通送られていない (" + net.stopsFor(pos) + " 通)");

            // 鳴らしていない座標への null は何も送らない (蓄音機は停止中も毎 tick stopPlaying を通る)。
            ExternalPlaybackMirror.mirror(level, pos, null);
            helper.assertTrue(net.stopsFor(pos) == 1, "鳴らしていない座標へ停止 payload を送っている");
        } finally {
            Services.swapNetwork(previous);
            ExternalPlaybackMirror.mirror(level, pos, null);
        }
        helper.succeed();
    }

    /** 別 player の chunk 入りで外部音源の再生が消えず、入った player には経過分の offset が届くこと。 */
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void externalSourceKeepsItsOriginAcrossChunkResend(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos rel = new BlockPos(3, 1, 3);
        helper.setBlock(rel, Blocks.CHEST);
        final BlockPos pos = helper.absolutePos(rel);
        final ResourceKey<Level> dim = level.dimension();
        final ItemStack disc = customDisc("https://example.invalid/external-resend");

        ExternalPlaybackMirror.mirror(level, pos, disc);
        final long elapsedMs = 30_000L;
        final long origin = System.currentTimeMillis() - elapsedMs;
        ActiveDiscRegistry.start(dim, pos, CustomMusicDiscItem.getTrack(disc), origin);

        final ServerPlayer player = new ServerPlayer(level.getServer(), level,
                new GameProfile(UUID.randomUUID(), "external-late-joiner"));
        final CapturingNetwork net = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(net);
        try {
            ForgeJukeboxHandler.onChunkWatch(new ChunkWatchEvent.Watch(player, level.getChunkAt(pos), level));
            final List<PlayDiscPayload> lateJoin = net.playsFor(pos);
            helper.assertTrue(lateJoin.size() == 1,
                    "鳴っている外部音源が chunk に入った player へ送られていない (" + lateJoin.size() + " 通)");
            final long offset = lateJoin.get(0).startOffsetMs();
            helper.assertTrue(offset >= elapsedMs && offset < elapsedMs + 10_000L,
                    "chunk に入った player への offset が経過分になっていない (" + offset + "ms)");

            ExternalPlaybackMirror.mirror(level, pos, disc);
            final ActiveDiscRegistry.Playing after = ActiveDiscRegistry.current(dim, pos);
            helper.assertTrue(after != null && after.startMillis() == origin,
                    "chunk 再送の掃除で外部音源の再生が消え、起点が打ち直されている");
            helper.assertTrue(net.playsFor(pos).size() == 1,
                    "chunk 再送の後の mirror が chunk 内の全員へ offset 0 で送り直している (= 全員が頭出し)");
        } finally {
            Services.swapNetwork(previous);
            player.discard();
            ExternalPlaybackMirror.mirror(level, pos, null);
        }
        helper.succeed();
    }

    /** 外部音源のブロックが停止を送らずに消えたら、chunk 再送の掃除がその再生を忘れること。 */
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void removedExternalSourceIsForgottenOnChunkResend(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos rel = new BlockPos(5, 1, 5);
        helper.setBlock(rel, Blocks.CHEST);
        final BlockPos pos = helper.absolutePos(rel);
        final ResourceKey<Level> dim = level.dimension();

        ExternalPlaybackMirror.mirror(level, pos, customDisc("https://example.invalid/external-removed"));
        helper.setBlock(rel, Blocks.AIR);

        final ServerPlayer player = new ServerPlayer(level.getServer(), level,
                new GameProfile(UUID.randomUUID(), "external-removed-joiner"));
        final CapturingNetwork net = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(net);
        try {
            ForgeJukeboxHandler.onChunkWatch(new ChunkWatchEvent.Watch(player, level.getChunkAt(pos), level));
            helper.assertTrue(net.playsFor(pos).isEmpty(), "撤去済みの外部音源が chunk に入った player へ送られている");
            helper.assertTrue(ActiveDiscRegistry.current(dim, pos) == null,
                    "撤去済みの外部音源の再生を chunk 再送の掃除が忘れていない");
        } finally {
            Services.swapNetwork(previous);
            player.discard();
        }
        helper.succeed();
    }

    private record Sent(ModPayload payload, ServerPlayer player) {
    }

    private static final class CapturingNetwork implements INetworkHelper {
        private final List<Sent> sent = new ArrayList<>();

        private List<PlayDiscPayload> playsFor(BlockPos pos) {
            return sent.stream().map(Sent::payload).filter(PlayDiscPayload.class::isInstance)
                    .map(PlayDiscPayload.class::cast).filter(p -> p.jukeboxPos().equals(pos)).toList();
        }

        private long stopsFor(BlockPos pos) {
            return sent.stream().map(Sent::payload).filter(StopDiscPayload.class::isInstance)
                    .map(StopDiscPayload.class::cast).filter(p -> p.jukeboxPos().equals(pos)).count();
        }

        @Override
        public void sendToServer(ModPayload payload) {
            sent.add(new Sent(payload, null));
        }

        @Override
        public void sendToPlayer(ServerPlayer player, ModPayload payload) {
            sent.add(new Sent(payload, player));
        }

        @Override
        public void sendToPlayersTrackingChunk(ServerLevel level, ChunkPos chunk, ModPayload payload) {
            sent.add(new Sent(payload, null));
        }
    }
}
