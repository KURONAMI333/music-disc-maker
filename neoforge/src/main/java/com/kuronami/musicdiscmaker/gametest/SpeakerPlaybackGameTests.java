package com.kuronami.musicdiscmaker.gametest;

import java.util.List;
import java.util.Optional;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.block.SpeakerBlockEntity;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.SilentSongs;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.PlayVanillaDiscPayload;
import com.kuronami.musicdiscmaker.network.SpeakerSetPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.platform.services.INetworkHelper;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.JukeboxSongs;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
//? if >=26.1 {
//?} else {
/*import net.minecraft.world.item.EitherHolder;
*///?}
//? if <1.21.11 {
/*import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
*///?}
public final class SpeakerPlaybackGameTests {

    private SpeakerPlaybackGameTests() {
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty64x3x8", timeoutTicks = 200, batch = "speakerPlayback")
    *///?}
    public static void routesRemotePlayerAndDiffsSettingsAndRangeExit(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos sourceRel = new BlockPos(1, 1, 1);
        helper.setBlock(sourceRel, ModBlocks.GOLDEN_JUKEBOX.get());
        //? if <1.21.11 {
        /*final GoldenJukeboxBlockEntity source = helper.getBlockEntity(sourceRel);
        *///?} else {
        final GoldenJukeboxBlockEntity source = GameTestBlockEntities.get(helper, sourceRel, GoldenJukeboxBlockEntity.class);
        //?}
        helper.assertTrue(source != null, "Golden sourceを生成できていない");
        final BlockPos sourcePos = source.getBlockPos();
        final BlockPos speakerPos = sourcePos.offset(40, 0, 0);
        final BlockPos remainingSpeakerPos = sourcePos.offset(60, 0, 0);
        level.setBlock(speakerPos.south(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(speakerPos, ModBlocks.SPEAKER.get().defaultBlockState(), 3);
        level.setBlock(remainingSpeakerPos.south(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(remainingSpeakerPos, ModBlocks.SPEAKER.get().defaultBlockState(), 3);
        final SpeakerBlockEntity speaker = (SpeakerBlockEntity) level.getBlockEntity(speakerPos);
        final SpeakerBlockEntity remainingSpeaker =
                (SpeakerBlockEntity) level.getBlockEntity(remainingSpeakerPos);
        helper.assertTrue(speaker != null, "遠方speakerを生成できていない");
        helper.assertTrue(remainingSpeaker != null, "残存speakerを生成できていない");
        source.setRangeBlocks(16);

        final ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.teleportTo(speakerPos.getX() + 0.5D, speakerPos.getY() + 0.5D, speakerPos.getZ() + 0.5D);
        final ServerPlayer staleLegacyPlayer = helper.makeMockServerPlayerInLevel();
        staleLegacyPlayer.teleportTo(sourcePos.getX() + 15.5D, sourcePos.getY() + 0.5D,
                sourcePos.getZ() + 15.5D);

        final CapturingNetwork net = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(net);
        try {
            source.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, customDisc(helper));
            helper.assertTrue(net.sent().stream().filter(sent -> "chunk".equals(sent.kind())
                            && sent.payload() instanceof PlayDiscPayload).count() == 1,
                    "speaker未接続の開始が従来chunk Playを維持していない");

            net.clear();
            speaker.linkTo(level, sourcePos);
            tick(level, source);
            helper.assertTrue(forPlayer(net, staleLegacyPlayer).stream()
                            .filter(sent -> sent.payload() instanceof StopDiscPayload).count() == 1,
                    "単体再生からspeaker配送へ切り替える時に新聴取圏外の旧decoderをStopしていない");
            helper.assertTrue(forPlayer(net, player).stream()
                            .noneMatch(sent -> sent.payload() instanceof StopDiscPayload),
                    "新speaker圏内playerを切替時にStopして再decodeさせている");
            final List<CapturingNetwork.Sent> initial = forPlayer(net, player);
            helper.assertTrue(initial.size() == 2
                            && initial.get(0).payload() instanceof SpeakerSetPayload
                            && initial.get(1).payload() instanceof PlayDiscPayload,
                    "source圏外・speaker圏内のplayerへSet→Playを1回ずつ送っていない");
            final SpeakerSetPayload firstSet = (SpeakerSetPayload) initial.get(0).payload();
            helper.assertTrue(firstSet.speakers().size() == 1
                            && firstSet.speakers().get(0).pos().equals(speakerPos),
                    "ロード済みspeaker全件をSetへ載せていない");

            net.clear();
            remainingSpeaker.linkTo(level, sourcePos);
            tick(level, source);
            assertSetOnly(helper, net, player, "speaker集合追加");
            net.clear();
            speaker.clearLink();
            tick(level, source);
            final List<CapturingNetwork.Sent> removedNearSpeaker = forPlayer(net, player);
            helper.assertTrue(removedNearSpeaker.size() == 1
                            && removedNearSpeaker.get(0).payload() instanceof SpeakerSetPayload,
                    "近傍speaker解除で保持中voiceへSetだけを送っていない");
            net.clear();
            speaker.linkTo(level, sourcePos);
            tick(level, source);
            assertSetOnly(helper, net, player, "近傍speakerの再接続");
            remainingSpeaker.clearLink();
            level.setBlock(remainingSpeakerPos, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(remainingSpeakerPos.south(), Blocks.AIR.defaultBlockState(), 3);

            net.clear();
            source.seekTo(5_000L);
            assertSetThenPlay(helper, net, player, "seek再配送");

            staleLegacyPlayer.teleportTo(speakerPos.getX() + 0.5D, speakerPos.getY() + 0.5D,
                    speakerPos.getZ() + 0.5D);
            source.resendTo(staleLegacyPlayer);
            net.clear();
            speaker.setVolumePercent(37);
            source.resendTo(staleLegacyPlayer);
            final List<CapturingNetwork.Sent> freshResend = forPlayer(net, staleLegacyPlayer);
            helper.assertTrue(freshResend.size() == 2
                            && freshResend.get(0).payload() instanceof SpeakerSetPayload set
                            && set.speakers().get(0).volumePercent() == 37
                            && freshResend.get(1).payload() instanceof PlayDiscPayload,
                    "個別resendが最新speaker設定のSet→Playになっていない");
            net.clear();
            tick(level, source);
            assertSetOnly(helper, net, player, "speaker音量変更");
            final SpeakerSetPayload volumeSet = (SpeakerSetPayload) forPlayer(net, player).get(0).payload();
            helper.assertTrue(volumeSet.speakers().get(0).volumePercent() == 37,
                    "speaker音量係数をSetへ反映していない");

            net.clear();
            level.setBlock(speakerPos.east(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            tick(level, source);
            assertSetOnly(helper, net, player, "speaker mute変更");
            final SpeakerSetPayload muteSet = (SpeakerSetPayload) forPlayer(net, player).get(0).payload();
            helper.assertTrue(muteSet.speakers().get(0).muted(), "mute状態をSetへ反映していない");

            net.clear();
            level.setBlock(speakerPos.east(), Blocks.AIR.defaultBlockState(), 3);
            tick(level, source);
            assertSetOnly(helper, net, player, "speaker unmute変更");

            net.clear();
            player.teleportTo(speakerPos.getX() + 18.0D, speakerPos.getY(), speakerPos.getZ());
            tick(level, source);
            helper.assertTrue(forPlayer(net, player).isEmpty(),
                    "範囲端を少し越えただけでvoiceをStopして再decodeを必要にしている");

            net.clear();
            player.teleportTo(speakerPos.getX() + 0.5D, speakerPos.getY() + 0.5D,
                    speakerPos.getZ() + 0.5D);
            tick(level, source);
            helper.assertTrue(forPlayer(net, player).isEmpty(),
                    "保持中のvoiceが範囲へ戻る時にSet→Playを送り直している");

            net.clear();
            player.teleportTo(speakerPos.getX() + 100.0D, speakerPos.getY(), speakerPos.getZ());
            tick(level, source);
            helper.assertTrue(forPlayer(net, player).stream()
                            .filter(sent -> sent.payload() instanceof StopDiscPayload).count() == 1,
                    "全聴取点の範囲外へ出たplayerへStopを1回送っていない");

            net.clear();
            player.teleportTo(speakerPos.getX() + 0.5D, speakerPos.getY() + 0.5D,
                    speakerPos.getZ() + 0.5D);
            tick(level, source);
            assertSetThenPlay(helper, net, player, "speaker圏内への再入域");

            net.clear();
            speaker.clearLink();
            tick(level, source);
            final List<CapturingNetwork.Sent> distantClear = forPlayer(net, player);
            helper.assertTrue(distantClear.size() == 2
                            && distantClear.get(0).payload() instanceof SpeakerSetPayload set
                            && set.speakers().isEmpty()
                            && distantClear.get(1).payload() instanceof StopDiscPayload,
                    "最後のspeaker解除時に遠方playerへSet(empty)→Stopを送っていない");

            speaker.linkTo(level, sourcePos);
            player.teleportTo(sourcePos.getX() + 0.5D, sourcePos.getY() + 0.5D, sourcePos.getZ() + 0.5D);
            net.clear();
            tick(level, source);
            assertSetThenPlay(helper, net, player, "元音源圏内への再入域");
            net.clear();
            speaker.clearLink();
            tick(level, source);
            final List<CapturingNetwork.Sent> localClear = forPlayer(net, player);
            helper.assertTrue(localClear.size() == 1
                            && localClear.get(0).payload() instanceof SpeakerSetPayload set
                            && set.speakers().isEmpty(),
                    "最後のspeaker解除時に元音源圏内playerへSet(empty)以外を送っている");

            speaker.linkTo(level, sourcePos);
            net.clear();
            tick(level, source);
            net.clear();
            source.setRemoved();
            helper.assertTrue(forPlayer(net, player).stream()
                            .filter(sent -> sent.payload() instanceof StopDiscPayload).count() == 1,
                    "Golden unloadでlistenerのdecoderをStopしていない");
        } finally {
            Services.swapNetwork(previous);
            speaker.clearLink();
            remainingSpeaker.clearLink();
            player.discard();
            staleLegacyPlayer.discard();
            level.setBlock(speakerPos, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(speakerPos.south(), Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(remainingSpeakerPos, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(remainingSpeakerPos.south(), Blocks.AIR.defaultBlockState(), 3);
        }
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty64x3x8", timeoutTicks = 200, batch = "speakerPlayback")
    *///?}
    public static void vanillaUsesSharedListenersForMigrationResendAndTrackSwitch(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos sourceRel = new BlockPos(1, 1, 1);
        helper.setBlock(sourceRel, ModBlocks.GOLDEN_JUKEBOX.get());
        //? if <1.21.11 {
        /*final GoldenJukeboxBlockEntity source = helper.getBlockEntity(sourceRel);
        *///?} else {
        final GoldenJukeboxBlockEntity source = GameTestBlockEntities.get(helper, sourceRel, GoldenJukeboxBlockEntity.class);
        //?}
        helper.assertTrue(source != null, "Golden sourceを生成できていない");
        final BlockPos sourcePos = source.getBlockPos();
        final BlockPos speakerPos = sourcePos.offset(40, 0, 0);
        level.setBlock(speakerPos.south(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(speakerPos, ModBlocks.SPEAKER.get().defaultBlockState(), 3);
        final SpeakerBlockEntity speaker = (SpeakerBlockEntity) level.getBlockEntity(speakerPos);
        helper.assertTrue(speaker != null, "遠方speakerを生成できていない");

        final ServerPlayer first = helper.makeMockServerPlayerInLevel();
        first.teleportTo(speakerPos.getX() + 0.5D, speakerPos.getY() + 0.5D, speakerPos.getZ() + 0.5D);
        final ServerPlayer second = helper.makeMockServerPlayerInLevel();
        second.teleportTo(speakerPos.getX() + 2.5D, speakerPos.getY() + 0.5D, speakerPos.getZ() + 0.5D);
        final ServerPlayer outsideEnvelope = helper.makeMockServerPlayerInLevel();
        outsideEnvelope.teleportTo(sourcePos.getX() + 0.5D, sourcePos.getY() + 0.5D,
                sourcePos.getZ() + 30.5D);
        final ServerPlayer afterRemoval = helper.makeMockServerPlayerInLevel();
        afterRemoval.teleportTo(sourcePos.getX() + 100.5D, sourcePos.getY() + 0.5D,
                sourcePos.getZ() + 100.5D);

        final CapturingNetwork net = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(net);
        try {
            source.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, new ItemStack(Items.MUSIC_DISC_13));
            helper.assertTrue(source.isVanillaPlaying(), "speaker無しのvanilla盤が盤の再生状態に入っていない");
            // 2026-10-10 から金ジュークの通常盤は常に管理再生 (SpeakerPlayback#requiresManagedVanilla)。
            assertSetThenVanilla(helper, net, first, source, "speaker無しのvanilla盤の開始(first)");

            net.clear();
            // 再生途中に speaker を足し、範囲を16へ。既に聴いている player へは Set だけを送る。
            source.setRangeBlocks(16);
            speaker.linkTo(level, sourcePos);
            tick(level, source);
            assertSetOnly(helper, net, first, "再生途中のspeaker追加(first)");
            assertSetOnly(helper, net, second, "再生途中のspeaker追加(second)");
            helper.assertTrue(forPlayer(net, outsideEnvelope).stream()
                            .filter(sent -> sent.payload() instanceof StopDiscPayload).count() == 1,
                    "native 64-block圏内だがspeaker envelope外のplayerへStopを送っていない");

            net.clear();
            source.resendTo(first);
            assertSetThenVanilla(helper, net, first, source, "vanilla途中参加resend");
            helper.assertTrue(forPlayer(net, second).isEmpty(), "個別resendを別playerへも送っている");

            first.teleportTo(sourcePos.getX() + 0.5D, sourcePos.getY() + 0.5D, sourcePos.getZ() + 0.5D);
            net.clear();
            speaker.clearLink();
            tick(level, source);
            final List<CapturingNetwork.Sent> existingAtSource = forPlayer(net, first);
            helper.assertTrue(existingAtSource.size() == 1
                            && existingAtSource.get(0).payload() instanceof SpeakerSetPayload set
                            && set.speakers().isEmpty(),
                    "最後のspeaker解除でsource圏内の既存voiceをSet(empty)だけで戻していない");
            helper.assertTrue(forPlayer(net, second).stream()
                            .filter(sent -> sent.payload() instanceof StopDiscPayload).count() == 1,
                    "最後のspeaker解除でsource圏外listenerを停止していない");

            net.clear();
            afterRemoval.teleportTo(sourcePos.getX() + 1.5D, sourcePos.getY() + 0.5D,
                    sourcePos.getZ() + 0.5D);
            source.resendTo(afterRemoval);
            assertSetThenVanilla(helper, net, afterRemoval, source, "speaker解除後のlate join");
            helper.assertTrue(forPlayer(net, first).isEmpty(),
                    "speaker解除後のlate joinで既存voiceへPlayを再送している");

            speaker.linkTo(level, sourcePos);
            second.teleportTo(speakerPos.getX() + 0.5D, speakerPos.getY() + 0.5D,
                    speakerPos.getZ() + 0.5D);
            net.clear();
            tick(level, source);

            net.clear();
            source.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, customDisc(helper));
            assertSetThenPlay(helper, net, first, "vanilla→custom切替(first)");
            assertSetThenPlay(helper, net, second, "vanilla→custom切替(second)");

            net.clear();
            source.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, new ItemStack(Items.MUSIC_DISC_13));
            assertSetThenVanilla(helper, net, first, source, "custom→vanilla切替(first)");
            assertSetThenVanilla(helper, net, second, source, "custom→vanilla切替(second)");

            net.clear();
            source.clearContent();
            helper.assertTrue(forPlayer(net, first).stream()
                            .filter(sent -> sent.payload() instanceof StopDiscPayload).count() == 1,
                    "vanilla停止をfirst listenerへ送っていない");
            helper.assertTrue(forPlayer(net, second).stream()
                            .filter(sent -> sent.payload() instanceof StopDiscPayload).count() == 1,
                    "vanilla停止をsecond listenerへ送っていない");
        } finally {
            Services.swapNetwork(previous);
            speaker.clearLink();
            first.discard();
            second.discard();
            outsideEnvelope.discard();
            afterRemoval.discard();
            level.setBlock(speakerPos, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(speakerPos.south(), Blocks.AIR.defaultBlockState(), 3);
        }
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty64x3x8", timeoutTicks = 100, batch = "speakerPlayback")
    *///?}
    public static void vanillaInitialMutedSpeakerStopsNativeWithoutManagedPlay(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos sourceRel = new BlockPos(1, 1, 1);
        helper.setBlock(sourceRel, ModBlocks.GOLDEN_JUKEBOX.get());
        //? if <1.21.11 {
        /*final GoldenJukeboxBlockEntity source = helper.getBlockEntity(sourceRel);
        *///?} else {
        final GoldenJukeboxBlockEntity source = GameTestBlockEntities.get(helper, sourceRel, GoldenJukeboxBlockEntity.class);
        //?}
        helper.assertTrue(source != null, "Golden sourceを生成できていない");
        source.setRangeBlocks(10);
        final BlockPos sourcePos = source.getBlockPos();
        final BlockPos speakerPos = sourcePos.offset(30, 0, 0);
        level.setBlock(speakerPos.south(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(speakerPos, ModBlocks.SPEAKER.get().defaultBlockState(), 3);
        level.setBlock(speakerPos.east(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        final SpeakerBlockEntity speaker = (SpeakerBlockEntity) level.getBlockEntity(speakerPos);
        helper.assertTrue(speaker != null && speaker.isMuted(), "給電したspeakerがmuteになっていない");
        speaker.linkTo(level, sourcePos);
        final ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.teleportTo(speakerPos.getX() + 0.5D, speakerPos.getY() + 0.5D, speakerPos.getZ() + 0.5D);

        final CapturingNetwork net = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(net);
        try {
            source.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, new ItemStack(Items.MUSIC_DISC_13));
            final List<CapturingNetwork.Sent> sent = forPlayer(net, player);
            helper.assertTrue(sent.stream().filter(entry -> entry.payload() instanceof StopDiscPayload).count() == 1,
                    "mute speakerだけの圏内playerへ元native音のStopを送っていない: " + sent);
            helper.assertTrue(sent.stream().noneMatch(entry -> entry.payload() instanceof PlayVanillaDiscPayload),
                    "mute speakerだけの圏内playerへmanaged vanilla音を開始している: " + sent);
        } finally {
            Services.swapNetwork(previous);
            speaker.clearLink();
            player.discard();
            level.setBlock(speakerPos.east(), Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(speakerPos, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(speakerPos.south(), Blocks.AIR.defaultBlockState(), 3);
        }
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100, batch = "speakerPlayback")
    *///?}
    public static void vanillaNaturalEndStopsSpeakerListener(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos sourceRel = new BlockPos(1, 1, 1);
        helper.setBlock(sourceRel, ModBlocks.GOLDEN_JUKEBOX.get());
        //? if <1.21.11 {
        /*final GoldenJukeboxBlockEntity source = helper.getBlockEntity(sourceRel);
        *///?} else {
        final GoldenJukeboxBlockEntity source = GameTestBlockEntities.get(helper, sourceRel, GoldenJukeboxBlockEntity.class);
        //?}
        helper.assertTrue(source != null, "Golden sourceを生成できていない");
        final BlockPos speakerPos = source.getBlockPos().offset(2, 0, 0);
        level.setBlock(speakerPos.south(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(speakerPos, ModBlocks.SPEAKER.get().defaultBlockState(), 3);
        final SpeakerBlockEntity speaker = (SpeakerBlockEntity) level.getBlockEntity(speakerPos);
        helper.assertTrue(speaker != null, "speakerを生成できていない");
        speaker.linkTo(level, source.getBlockPos());
        final ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.teleportTo(speakerPos.getX() + 0.5D, speakerPos.getY() + 0.5D, speakerPos.getZ() + 0.5D);

        final CapturingNetwork net = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(net);
        try {
            source.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, shortVanillaDisc(helper));
            assertSetThenVanilla(helper, net, player, source, "短尺vanilla開始");
            net.clear();
            try {
                Thread.sleep(100L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("短尺vanilla終了待機がinterruptされた", interrupted);
            }
            tick(level, source);
            helper.assertTrue(source.isStopped(), "短尺vanilla盤が自然終了していない");
            helper.assertTrue(forPlayer(net, player).stream()
                            .filter(sent -> sent.payload() instanceof StopDiscPayload).count() == 1,
                    "短尺vanilla自然終了をlistenerへ送っていない");
        } finally {
            Services.swapNetwork(previous);
            speaker.clearLink();
            player.discard();
            // 短尺曲は Holder.direct の非バインド holder であり NBT 化できない (RegistryFixedCodec)。
            // テスト撤去時の chunk 保存で BE ごと落とされないよう、スロットを空に戻す。
            source.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, ItemStack.EMPTY);
            level.setBlock(speakerPos, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(speakerPos.south(), Blocks.AIR.defaultBlockState(), 3);
        }
        helper.succeed();
    }

    private static void tick(ServerLevel level, GoldenJukeboxBlockEntity source) {
        GoldenJukeboxBlockEntity.serverTick(level, source.getBlockPos(), source.getBlockState(), source);
    }

    private static List<CapturingNetwork.Sent> forPlayer(CapturingNetwork net, ServerPlayer player) {
        return net.sent().stream().filter(sent -> sent.player() == player).toList();
    }

    private static void assertSetOnly(GameTestHelper helper, CapturingNetwork net, ServerPlayer player,
            String operation) {
        final List<CapturingNetwork.Sent> sent = forPlayer(net, player);
        helper.assertTrue(sent.size() == 1 && sent.get(0).payload() instanceof SpeakerSetPayload,
                operation + "でSet以外のPlay/Stopを送っている: " + sent);
    }

    private static void assertSetThenPlay(GameTestHelper helper, CapturingNetwork net, ServerPlayer player,
            String operation) {
        final List<CapturingNetwork.Sent> sent = forPlayer(net, player);
        helper.assertTrue(sent.size() == 2
                        && sent.get(0).payload() instanceof SpeakerSetPayload
                        && sent.get(1).payload() instanceof PlayDiscPayload,
                operation + "でSet→Playを1回ずつ送っていない: " + sent);
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100, batch = "speakerPlayback")
    *///?}
    public static void movingVanillaWithoutSpeakersUsesManagedRoute(GameTestHelper helper) {
        final var level = helper.getLevel();
        final var relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, ModBlocks.GOLDEN_JUKEBOX.get());
        final var source = (GoldenJukeboxBlockEntity) level.getBlockEntity(helper.absolutePos(relative));
        final var player = helper.makeMockServerPlayerInLevel();
        final var pos = source.getBlockPos();
        player.teleportTo(pos.getX()+0.5, pos.getY()+0.5, pos.getZ()+0.5);
        final var net = new CapturingNetwork();
        final var previous = Services.swapNetwork(net);
        try {
            // A fixed source plays vanilla records on the managed route from the start (2026-10-10), so
            // later setting changes only resend the speaker set.
            source.setItem(0, new ItemStack(Items.MUSIC_DISC_13));
            assertSetThenVanilla(helper, net, player, source, "fixed vanilla start");
            net.clear();
            source.setRangeBlocks(32);
            com.kuronami.musicdiscmaker.event.SpeakerPlayback.tick(source);
            assertSetOnly(helper, net, player, "fixed vanilla range change");
            final var configuredSet = (SpeakerSetPayload) forPlayer(net, player).get(0).payload();
            helper.assertTrue(configuredSet.rangeBlocks() == 32,
                    "Fixed vanilla range was not carried by the managed payload");

            net.clear();
            source.setVolumePercent(42);
            source.setDirectional(false);
            com.kuronami.musicdiscmaker.event.SpeakerPlayback.tick(source);
            assertSetOnly(helper, net, player, "fixed vanilla live setting change");
            final var liveSet = (SpeakerSetPayload) forPlayer(net, player).get(0).payload();
            helper.assertTrue(liveSet.volumePercent() == 42 && !liveSet.directional(),
                    "Fixed vanilla live volume/directional settings were not resent");

            final var latePlayer = helper.makeMockServerPlayerInLevel();
            latePlayer.teleportTo(pos.getX()+0.5, pos.getY()+0.5, pos.getZ()+0.5);
            net.clear();
            source.resendTo(latePlayer);
            assertSetThenVanilla(helper, net, latePlayer, source, "fixed vanilla late join");

            net.clear();
            source.setRangeBlocks(GoldenJukeboxBlockEntity.RANGE_DEFAULT);
            source.setVolumePercent(GoldenJukeboxBlockEntity.VOLUME_DEFAULT);
            source.setDirectional(GoldenJukeboxBlockEntity.DIRECTIONAL_DEFAULT);
            com.kuronami.musicdiscmaker.event.SpeakerPlayback.tick(source);
            assertSetOnly(helper, net, player, "managed fixed vanilla reset to defaults");
            net.clear();
            source.resendTo(latePlayer);
            assertSetThenVanilla(helper, net, latePlayer, source, "managed fixed vanilla late join after reset");
            latePlayer.discard();

            com.kuronami.musicdiscmaker.event.SpeakerPlayback.remove(source);
            source.setItem(0, ItemStack.EMPTY);
            source.setItem(0, new ItemStack(Items.MUSIC_DISC_13));
            // 固定音源としての開始 (管理再生) は上で確かめた。state の無い所からの移動音源の開始だけを見る。
            com.kuronami.musicdiscmaker.event.SpeakerPlayback.remove(source);
            net.clear();
            helper.assertTrue(com.kuronami.musicdiscmaker.event.SpeakerPlayback.playMovingVanilla(source),
                    "Moving vanilla source without speakers was not managed");
            assertSetThenVanilla(helper, net, player, source, "moving vanilla start");
            final var set = (SpeakerSetPayload) forPlayer(net, player).get(0).payload();
            helper.assertTrue(set.speakers().isEmpty(), "Test unexpectedly used a fixed speaker");
            net.clear();
            com.kuronami.musicdiscmaker.event.SpeakerPlayback.tick(source);
            helper.assertTrue(forPlayer(net, player).isEmpty(), "Unchanged moving vanilla tick restarted playback");
            player.teleportTo(pos.getX()+1000, pos.getY()+0.5, pos.getZ()+0.5);
            com.kuronami.musicdiscmaker.event.SpeakerPlayback.tick(source);
            helper.assertTrue(forPlayer(net, player).stream().anyMatch(sent -> sent.payload() instanceof StopDiscPayload),
                    "Moving vanilla source did not stop the listener outside its range");
        } finally {
            com.kuronami.musicdiscmaker.event.SpeakerPlayback.remove(source);
            Services.swapNetwork(previous);
            player.discard();
        }
        helper.succeed();
    }

    /**
     * 金ジュークの通常盤は管理再生で鳴らす。シーク・一時停止からの再開は途中から、後から範囲に入った
     * player にも現在位置から届く。2026-10-10 Mac 開発クライアントで、バニラの再生 event で始めた音を直後に
     * 止めると止め損ねて壊しても鳴り続けること、再開が頭からになることを再現した (SpeakerPlayback#managesVanilla)。
     */
    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100, batch = "speakerPlayback")
    *///?}
    public static void fixedVanillaSeekAndResumeUseManagedRoute(GameTestHelper helper) {
        final var level = helper.getLevel();
        final var relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, ModBlocks.GOLDEN_JUKEBOX.get());
        final var source = (GoldenJukeboxBlockEntity) level.getBlockEntity(helper.absolutePos(relative));
        final var player = helper.makeMockServerPlayerInLevel();
        final var pos = source.getBlockPos();
        player.teleportTo(pos.getX()+0.5, pos.getY()+0.5, pos.getZ()+0.5);
        final var net = new CapturingNetwork();
        final var previous = Services.swapNetwork(net);
        try {
            source.setItem(0, new ItemStack(Items.MUSIC_DISC_13));
            assertSetThenVanilla(helper, net, player, source, "既定設定の通常盤の開始");
            net.clear();
            helper.assertTrue(source.isSeekable(), "通常盤がシークできない");
            source.seekTo(60_000L);
            assertSetThenVanilla(helper, net, player, source, "通常盤のシーク");
            final var seek = (PlayVanillaDiscPayload) forPlayer(net, player).get(1).payload();
            helper.assertTrue(seek.startOffsetMs() == 60_000L, "シーク位置から鳴らしていない: " + seek.startOffsetMs());
            helper.assertTrue(source.isVanillaPlaying(), "管理再生の間に盤の再生状態 (粒子・コンパレータ) が落ちた");

            net.clear();
            source.setPaused(true);
            helper.assertTrue(forPlayer(net, player).stream().anyMatch(sent -> sent.payload() instanceof StopDiscPayload),
                    "一時停止で止めていない");
            net.clear();
            source.setPaused(false);
            assertSetThenVanilla(helper, net, player, source, "通常盤の再開");
            final var resume = (PlayVanillaDiscPayload) forPlayer(net, player).get(1).payload();
            helper.assertTrue(resume.startOffsetMs() >= 60_000L, "再開が頭からになっている: " + resume.startOffsetMs());

            // 範囲の外にいた player が後から入ると、現在位置から届く。
            final var late = helper.makeMockServerPlayerInLevel();
            late.teleportTo(pos.getX() + 500.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            com.kuronami.musicdiscmaker.event.SpeakerPlayback.tick(source);
            net.clear();
            late.teleportTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
            com.kuronami.musicdiscmaker.event.SpeakerPlayback.tick(source);
            final var lateSent = forPlayer(net, late);
            helper.assertTrue(lateSent.stream().anyMatch(sent -> sent.payload() instanceof PlayVanillaDiscPayload p
                            && p.startOffsetMs() >= 60_000L),
                    "後から範囲に入った player へ通常盤を現在位置から送っていない: " + lateSent);
            late.discard();

            net.clear();
            source.onBlockRemoved();
            helper.assertTrue(forPlayer(net, player).stream().anyMatch(sent -> sent.payload() instanceof StopDiscPayload),
                    "壊した時に止めていない");
        } finally {
            com.kuronami.musicdiscmaker.event.SpeakerPlayback.remove(source);
            Services.swapNetwork(previous);
            player.discard();
        }
        helper.succeed();
    }

    /** 製作機の出力枠にある作ったばかりの盤だけ、画面を開いている player が曲名を付け直せる (2026-10-10 本人要望)。 */
    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100, batch = "speakerPlayback")
    *///?}
    public static void makerRenamesItsFreshOutputOnly(GameTestHelper helper) {
        final var relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, ModBlocks.MUSIC_DISC_MAKER.get());
        final var maker = (com.kuronami.musicdiscmaker.block.MusicDiscMakerBlockEntity)
                helper.getLevel().getBlockEntity(helper.absolutePos(relative));
        final int output = com.kuronami.musicdiscmaker.block.MusicDiscMakerBlockEntity.SLOT_OUTPUT;
        maker.setItem(output, customDisc(helper));
        final var player = helper.makeMockServerPlayerInLevel();
        final var pos = maker.getBlockPos();
        player.teleportTo(pos.getX()+0.5, pos.getY()+0.5, pos.getZ()+0.5);
        try {
            com.kuronami.musicdiscmaker.network.ModNetwork.handleRenameDisc(
                    new com.kuronami.musicdiscmaker.network.RenameDiscPayload(pos, "Speaker Playback", "Wrong"), player);
            helper.assertValueEqual(trackOf(maker.getItem(output)).title(), "Speaker Playback",
                    "製作機の画面を開いていない player の付け直しを通した");
            // その製作機の画面を開いている player の付け直しは通す。
            player.containerMenu = new com.kuronami.musicdiscmaker.menu.MusicDiscMakerMenu(1, player.getInventory(), maker);
            com.kuronami.musicdiscmaker.network.ModNetwork.handleRenameDisc(
                    new com.kuronami.musicdiscmaker.network.RenameDiscPayload(pos, "Old Title", "Stale"), player);
            helper.assertValueEqual(trackOf(maker.getItem(output)).title(), "Speaker Playback",
                    "画面が見ていた曲名と違う盤 (入れ替わった盤) を付け直した");
            com.kuronami.musicdiscmaker.network.ModNetwork.handleRenameDisc(
                    new com.kuronami.musicdiscmaker.network.RenameDiscPayload(pos, "Speaker Playback", "  Fixed Title  "), player);
            player.containerMenu = player.inventoryMenu;
            final CustomTrackData renamed = trackOf(maker.getItem(output));
            helper.assertValueEqual(renamed.title(), "Fixed Title", "付け直した曲名");
            helper.assertValueEqual(renamed.author(), "tester", "付け直しでアーティストが変わった");
            helper.assertValueEqual(renamed.url(), "https://example.invalid/speaker-playback", "付け直しで URL が変わった");
            helper.assertValueEqual(renamed.durationMs(), 120_000L, "付け直しで尺が変わった");
            maker.renameOutput("Fixed Title", "   ");
            helper.assertValueEqual(trackOf(maker.getItem(output)).title(), "Fixed Title", "空の名前で曲名を消した");
        } finally {
            maker.setItem(output, ItemStack.EMPTY);
            player.discard();
        }
        helper.succeed();
    }

    private static CustomTrackData trackOf(ItemStack disc) {
        return com.kuronami.musicdiscmaker.item.CustomMusicDiscItem.getTrack(disc);
    }

    private static void assertSetThenVanilla(GameTestHelper helper, CapturingNetwork net, ServerPlayer player,
            GoldenJukeboxBlockEntity source, String operation) {
        final List<CapturingNetwork.Sent> sent = forPlayer(net, player);
        helper.assertTrue(sent.size() == 2
                        && sent.get(0).payload() instanceof SpeakerSetPayload
                        && sent.get(1).payload() instanceof PlayVanillaDiscPayload,
                operation + "でSet→PlayVanillaを1回ずつ送っていない: " + sent);
        final PlayVanillaDiscPayload play = (PlayVanillaDiscPayload) sent.get(1).payload();
        helper.assertTrue("minecraft:music_disc.13".equals(play.track().soundEventId()),
                operation + "のSoundEvent識別子が違う: " + play.track().soundEventId());
        helper.assertTrue(play.startOffsetMs() >= 0L, operation + "のoffsetが負: " + play.startOffsetMs());
        helper.assertTrue(play.generation() == source.playbackCursor().generation(),
                operation + "のgenerationがserver cursorと不一致");
    }

    private static ItemStack customDisc(GameTestHelper helper) {
        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        final CustomTrackData track = new CustomTrackData("https://example.invalid/speaker-playback",
                "Speaker Playback", "tester", 120_000L, "", false);
        disc.set(ModDataComponents.CUSTOM_TRACK.get(), track);
        //? if <1.21.11 {
        /*disc.set(DataComponents.JUKEBOX_PLAYABLE,
                new JukeboxPlayable(new EitherHolder<>(SilentSongs.pick(track.durationMs())), false));
        *///?} else {
        helper.getLevel().registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG)
                .get(SilentSongs.pick(track.durationMs()))
                //? if >=26.1 {
                .ifPresent(holder -> disc.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(holder)));
                //?} else {
                /*.ifPresent(holder -> disc.set(DataComponents.JUKEBOX_PLAYABLE,
                        new JukeboxPlayable(new EitherHolder<>(holder))));
                *///?}
        //?}
        return disc;
    }

    /** wall-clock自然終了を待てる、登録不要の50ms JukeboxSong fixture。 */
    private static ItemStack shortVanillaDisc(GameTestHelper helper) {
        final Holder<JukeboxSong> original = helper.getLevel().registryAccess()
                .lookupOrThrow(Registries.JUKEBOX_SONG).get(JukeboxSongs.THIRTEEN).orElseThrow();
        final JukeboxSong shortSong = new JukeboxSong(original.value().soundEvent(),
                original.value().description(), 0.05F, original.value().comparatorOutput());
        final ItemStack disc = new ItemStack(Items.STICK);
        //? if >=26.1 {
        disc.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(Holder.direct(shortSong)));
        //?} elif >=1.21.11 {
        /*disc.set(DataComponents.JUKEBOX_PLAYABLE,
                new JukeboxPlayable(new EitherHolder<>(Holder.direct(shortSong))));
        *///?} else {
        /*disc.set(DataComponents.JUKEBOX_PLAYABLE,
                new JukeboxPlayable(new EitherHolder<>(Optional.of(Holder.direct(shortSong)),
                        JukeboxSongs.THIRTEEN), false));
        *///?}
        return disc;
    }
}
