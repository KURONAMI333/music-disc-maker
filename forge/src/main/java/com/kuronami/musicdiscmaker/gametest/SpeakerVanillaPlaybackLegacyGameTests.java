package com.kuronami.musicdiscmaker.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.block.SpeakerBlockEntity;
import com.kuronami.musicdiscmaker.event.SpeakerNetwork;
import com.kuronami.musicdiscmaker.event.GoldenSourceRegistry;
import com.kuronami.musicdiscmaker.event.GoldenSourceRetirements;
import com.kuronami.musicdiscmaker.item.SpeakerItem;
import com.kuronami.musicdiscmaker.network.ModPayload;
import com.kuronami.musicdiscmaker.network.PlayVanillaDiscPayload;
import com.kuronami.musicdiscmaker.network.SpeakerSetPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.platform.services.INetworkHelper;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModItems;
import com.kuronami.musicdiscmaker.speaker.SpeakerLink;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Forge 1.20.1でもRecordItemの音源記述を共通Speaker配送へ載せる回帰試験。 */
@GameTestHolder(MusicDiscMaker.MODID)
@PrefixGameTestTemplate(false)
public final class SpeakerVanillaPlaybackLegacyGameTests {
    private SpeakerVanillaPlaybackLegacyGameTests() {
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void recordItemMigratesToSpeakerAndStops(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos sourceRel = new BlockPos(1, 1, 1);
        helper.setBlock(sourceRel, ModBlocks.GOLDEN_JUKEBOX.get());
        final GoldenJukeboxBlockEntity source = (GoldenJukeboxBlockEntity) helper.getBlockEntity(sourceRel);
        final BlockPos speakerPos = source.getBlockPos().offset(2, 0, 0);
        level.setBlock(speakerPos.south(), Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(speakerPos, ModBlocks.SPEAKER.get().defaultBlockState(), 3);
        final SpeakerBlockEntity speaker = (SpeakerBlockEntity) level.getBlockEntity(speakerPos);
        helper.assertTrue(speaker != null, "speaker block entity is missing");
        final ServerPlayer first = listener(level, speakerPos, 0.5D);
        final ServerPlayer second = listener(level, speakerPos, 1.5D);

        final CapturingNetwork net = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(net);
        try {
            source.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, new ItemStack(Items.MUSIC_DISC_13));
            helper.assertTrue(source.isVanillaPlaying(), "speakerなしのRecordItemがnative再生を開始していない");
            helper.assertTrue(net.of(PlayVanillaDiscPayload.class).isEmpty(),
                    "speakerなしのRecordItemへ追加payloadを送っている");

            speaker.linkTo(level, source.getBlockPos());
            net.clear();
            GoldenJukeboxBlockEntity.serverTick(level, source.getBlockPos(), source.getBlockState(), source);
            assertSetThenVanilla(helper, net, first, source);
            assertSetThenVanilla(helper, net, second, source);

            net.clear();
            source.clearContent();
            helper.assertTrue(net.forPlayer(first).stream()
                            .filter(sent -> sent.payload() instanceof StopDiscPayload).count() == 1,
                    "RecordItem停止をfirst listenerへ送っていない");
            helper.assertTrue(net.forPlayer(second).stream()
                            .filter(sent -> sent.payload() instanceof StopDiscPayload).count() == 1,
                    "RecordItem停止をsecond listenerへ送っていない");
        } finally {
            Services.swapNetwork(previous);
            speaker.clearLink();
            level.players().remove(first);
            level.players().remove(second);
            first.discard();
            second.discard();
            level.setBlock(speakerPos, Blocks.AIR.defaultBlockState(), 3);
            level.setBlock(speakerPos.south(), Blocks.AIR.defaultBlockState(), 3);
        }
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void movingRecordUsesManagedPlaybackWithoutExtraSpeakers(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, ModBlocks.GOLDEN_JUKEBOX.get());
        final GoldenJukeboxBlockEntity source = (GoldenJukeboxBlockEntity) helper.getBlockEntity(pos);
        final ServerPlayer player = listener(level, source.getBlockPos(), 0.5D);
        final CapturingNetwork net = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(net);
        try {
            helper.assertFalse(Services.PLATFORM.isMovingAudioSource(level, source.getBlockPos()),
                    "ordinary block was treated as a moving source without VS");
            source.setItem(0, new ItemStack(Items.MUSIC_DISC_13));
            net.clear();
            helper.assertTrue(com.kuronami.musicdiscmaker.event.SpeakerPlayback.playMovingVanilla(source),
                    "moving vanilla source with no speakers was rejected");
            assertSetThenVanilla(helper, net, player, source);
            net.clear();
            com.kuronami.musicdiscmaker.event.SpeakerPlayback.tick(source);
            helper.assertTrue(net.forPlayer(player).isEmpty(), "steady moving source restarted its listener");
            source.clearContent();
            helper.assertTrue(net.forPlayer(player).stream()
                    .filter(sent -> sent.payload() instanceof StopDiscPayload).count() == 1,
                    "moving vanilla source did not stop exactly once");
        } finally {
            com.kuronami.musicdiscmaker.event.SpeakerPlayback.remove(source);
            Services.swapNetwork(previous);
            level.players().remove(player);
            player.discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void sourceIdentitySurvivesItemAndSpeakerRoundTrips(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos sourceRel = new BlockPos(1, 1, 1);
        final BlockPos speakerRel = new BlockPos(4, 1, 4);
        helper.setBlock(sourceRel, ModBlocks.GOLDEN_JUKEBOX.get());
        helper.setBlock(speakerRel.below(), Blocks.STONE);
        helper.setBlock(speakerRel, ModBlocks.SPEAKER.get());
        final GoldenJukeboxBlockEntity golden = (GoldenJukeboxBlockEntity) helper.getBlockEntity(sourceRel);
        final SpeakerBlockEntity speaker = (SpeakerBlockEntity) helper.getBlockEntity(speakerRel);
        helper.assertTrue(golden != null && speaker != null,
                "Forge UUID link試験のBlockEntityが生成されていない");

        final BlockPos sourcePos = helper.absolutePos(sourceRel);
        final UUID sourceId = golden.sourceIdentity();
        final SpeakerLink expected = new SpeakerLink(
                level.dimension().location().toString(), sourcePos.asLong(), sourceId);
        final ItemStack linkedItem = new ItemStack(ModItems.SPEAKER.get());
        SpeakerItem.writeLink(linkedItem, expected);
        final SpeakerLink itemLink = SpeakerItem.linkOf(linkedItem).orElse(null);
        helper.assertTrue(expected.equals(itemLink), "Forge speaker itemのUUID付きlinkが保存往復で変化した");

        speaker.linkTo(level, sourcePos);
        helper.assertTrue(speaker.getLinkedSource().filter(expected::equals).isPresent(),
                "Forge loaded GoldenへのlinkToがsource IDを保持しない");
        speaker.clearLink();
        speaker.replaceLinkedSource(itemLink);
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, sourcePos).contains(speaker),
                "Forge UUID付きlinkが位置索引から検索できない");

        final CompoundTag saved = speaker.saveWithFullMetadata();
        final SpeakerBlockEntity restored = (SpeakerBlockEntity) BlockEntity.loadStatic(
                speaker.getBlockPos(), speaker.getBlockState(), saved);
        helper.assertTrue(restored != null && restored.getLinkedSource().filter(expected::equals).isPresent(),
                "Forge speaker BEのUUID付きlinkが保存往復で変化した");

        final SpeakerLink oldLink = new SpeakerLink(expected.dimensionId(), expected.packedPos());
        final ItemStack oldItem = new ItemStack(ModItems.SPEAKER.get());
        SpeakerItem.writeLink(oldItem, oldLink);
        helper.assertTrue(SpeakerItem.linkOf(oldItem).filter(oldLink::equals).isPresent(),
                "source IDの無いForge item linkが保存往復で失われた");

        final SpeakerLink recoveredItem = SpeakerItem.linkOf(
                malformedLinkItem(expected.dimensionId(), expected.packedPos())).orElse(null);
        helper.assertTrue(recoveredItem != null && recoveredItem.positionKey().equals(oldLink)
                        && recoveredItem.sourceId() == null,
                "Forge itemの不正source IDが位置linkまで失わせた");

        final CompoundTag malformedSaved = saved.copy();
        malformedSaved.putString("source_id", "invalid-uuid");
        final SpeakerBlockEntity recovered = (SpeakerBlockEntity) BlockEntity.loadStatic(
                speaker.getBlockPos(), speaker.getBlockState(), malformedSaved);
        helper.assertTrue(recovered != null && recovered.getLinkedSource().filter(oldLink::equals).isPresent(),
                "Forge BEの不正source IDが位置linkまで失わせた");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void sourceIdentityIndexExcludesOtherAndLegacyLinks(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos speakerRel = new BlockPos(5, 1, 5);
        final BlockPos sourcePos = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.setBlock(speakerRel.below(), Blocks.STONE);
        helper.setBlock(speakerRel, ModBlocks.SPEAKER.get());
        final SpeakerBlockEntity speaker = (SpeakerBlockEntity) helper.getBlockEntity(speakerRel);
        helper.assertTrue(speaker != null, "Forge UUID索引試験のspeakerが生成されていない");

        final UUID sourceId = UUID.randomUUID();
        final UUID otherId = UUID.randomUUID();
        final String dimension = level.dimension().location().toString();
        final String otherDimension = dimension.equals("minecraft:the_nether")
                ? "minecraft:overworld" : "minecraft:the_nether";
        speaker.replaceLinkedSource(new SpeakerLink(otherDimension, sourcePos.asLong(), sourceId));
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, sourceId).isEmpty(),
                "Forge別次元の同じsource IDが現在次元のUUID索引へ混入した");

        speaker.replaceLinkedSource(new SpeakerLink(dimension, sourcePos.asLong(), sourceId));
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, sourceId).contains(speaker),
                "Forge同じsource IDのspeakerをUUID索引で取得できない");
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, otherId).isEmpty(),
                "Forge別source IDの検索がspeakerを返した");

        speaker.replaceLinkedSource(new SpeakerLink(dimension, sourcePos.asLong(), otherId));
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, sourceId).isEmpty(),
                "Forge source ID差し替え後も旧UUID索引にspeakerが残った");
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, otherId).contains(speaker),
                "Forge source ID差し替え後に新UUID索引へspeakerが載らない");
        speaker.setRemoved();
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, otherId).isEmpty(),
                "Forge removed speakerがUUID索引から除外されない");
        speaker.clearRemoved();
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, otherId).contains(speaker),
                "Forge clearRemoved後にUUID索引へspeakerが戻らない");

        speaker.clearLink();
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, otherId).isEmpty(),
                "Forge clearLink後もUUID索引にspeakerが残った");
        speaker.replaceLinkedSource(new SpeakerLink(dimension, sourcePos.asLong()));
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, sourceId).isEmpty(),
                "source IDの無いForge位置linkがUUID索引へ混入した");
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, sourcePos).contains(speaker),
                "Forge UUID索引追加で旧位置link検索が壊れた");
        speaker.clearLink();
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void sourceAwareLookupPromotesMovesRejectsReuseAndConflict(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos oldSourceRel = new BlockPos(1, 1, 1);
        final BlockPos movedSourceRel = new BlockPos(5, 1, 1);
        final BlockPos duplicateSourceRel = new BlockPos(6, 1, 2);
        final BlockPos legacySpeakerRel = new BlockPos(2, 1, 4);
        final BlockPos identifiedSpeakerRel = new BlockPos(4, 1, 4);
        helper.setBlock(oldSourceRel, ModBlocks.GOLDEN_JUKEBOX.get());
        helper.setBlock(legacySpeakerRel.below(), Blocks.STONE);
        helper.setBlock(identifiedSpeakerRel.below(), Blocks.STONE);
        helper.setBlock(legacySpeakerRel, ModBlocks.SPEAKER.get());
        helper.setBlock(identifiedSpeakerRel, ModBlocks.SPEAKER.get());
        final GoldenJukeboxBlockEntity original =
                (GoldenJukeboxBlockEntity) helper.getBlockEntity(oldSourceRel);
        final SpeakerBlockEntity legacySpeaker =
                (SpeakerBlockEntity) helper.getBlockEntity(legacySpeakerRel);
        final SpeakerBlockEntity identifiedSpeaker =
                (SpeakerBlockEntity) helper.getBlockEntity(identifiedSpeakerRel);
        helper.assertTrue(original != null && legacySpeaker != null && identifiedSpeaker != null,
                "Forge source-aware lookup試験の実world BEを生成できていない");

        final String dimension = level.dimension().location().toString();
        final BlockPos oldSourcePos = helper.absolutePos(oldSourceRel);
        final UUID sourceId = original.sourceIdentity();
        legacySpeaker.replaceLinkedSource(new SpeakerLink(dimension, oldSourcePos.asLong()));
        identifiedSpeaker.replaceLinkedSource(new SpeakerLink(dimension, oldSourcePos.asLong(), sourceId));
        final var initial = SpeakerNetwork.findLinkedSpeakers(level, original);
        helper.assertTrue(initial.contains(legacySpeaker) && initial.contains(identifiedSpeaker),
                "Forge現位置の旧linkとUUID linkをsource-aware検索で取得できない");
        helper.assertTrue(legacySpeaker.getLinkedSource().filter(link -> sourceId.equals(link.sourceId())
                        && link.packedPos() == oldSourcePos.asLong()).isPresent(),
                "Forge UUID無し旧linkを現在source IDへ昇格していない");

        final CompoundTag sharedState = original.saveWithFullMetadata();
        helper.setBlock(movedSourceRel, ModBlocks.GOLDEN_JUKEBOX.get());
        final GoldenJukeboxBlockEntity moved =
                (GoldenJukeboxBlockEntity) helper.getBlockEntity(movedSourceRel);
        helper.assertTrue(moved != null, "Forge移設先Goldenを実worldへ生成できていない");
        moved.load(sharedState);
        original.setRemoved();

        helper.setBlock(oldSourceRel, Blocks.AIR);
        helper.setBlock(oldSourceRel, ModBlocks.GOLDEN_JUKEBOX.get());
        final GoldenJukeboxBlockEntity replacement =
                (GoldenJukeboxBlockEntity) helper.getBlockEntity(oldSourceRel);
        helper.assertTrue(replacement != null, "Forge旧座標の別UUID Goldenを生成できていない");
        final UUID replacementId = replacement.sourceIdentity();
        helper.assertFalse(sourceId.equals(replacementId), "Forge旧座標の置換Goldenが別UUIDになっていない");
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, replacement).isEmpty(),
                "Forge旧座標を使う別UUID Goldenが既存UUID linkを引き継いだ");

        final BlockPos movedSourcePos = helper.absolutePos(movedSourceRel);
        final var afterMove = SpeakerNetwork.findLinkedSpeakers(level, moved);
        helper.assertTrue(afterMove.contains(legacySpeaker) && afterMove.contains(identifiedSpeaker),
                "Forge同じUUIDの移設先Goldenがlink済みspeakerを解決できない");
        helper.assertTrue(legacySpeaker.getLinkedSource().filter(link -> sourceId.equals(link.sourceId())
                        && link.packedPos() == movedSourcePos.asLong()).isPresent()
                        && identifiedSpeaker.getLinkedSource().filter(link -> sourceId.equals(link.sourceId())
                        && link.packedPos() == movedSourcePos.asLong()).isPresent(),
                "Forge移設後も同UUID linkの保存位置が旧座標のまま残った");

        helper.setBlock(duplicateSourceRel, ModBlocks.GOLDEN_JUKEBOX.get());
        final GoldenJukeboxBlockEntity duplicate =
                (GoldenJukeboxBlockEntity) helper.getBlockEntity(duplicateSourceRel);
        helper.assertTrue(duplicate != null, "Forge重複UUID Goldenを実worldへ生成できていない");
        duplicate.load(sharedState);
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, moved).isEmpty(),
                "Forge同一UUID Goldenが2台あるCONFLICT中にspeakerを解決した");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void destroyedSourceRelinksReplacementAtSamePosition(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos sourceRel = new BlockPos(1, 1, 1);
        final BlockPos speakerRel = new BlockPos(3, 1, 3);
        helper.setBlock(sourceRel, ModBlocks.GOLDEN_JUKEBOX.get());
        helper.setBlock(speakerRel.below(), Blocks.STONE);
        helper.setBlock(speakerRel, ModBlocks.SPEAKER.get());
        final GoldenJukeboxBlockEntity source =
                (GoldenJukeboxBlockEntity) helper.getBlockEntity(sourceRel);
        final SpeakerBlockEntity speaker = (SpeakerBlockEntity) helper.getBlockEntity(speakerRel);
        helper.assertTrue(source != null && speaker != null,
                "Forge実破壊復帰試験のworld BEを生成できていない");
        final BlockPos sourcePos = helper.absolutePos(sourceRel);
        final UUID oldId = source.sourceIdentity();
        speaker.linkTo(level, sourcePos);
        level.destroyBlock(sourcePos, false);
        helper.assertTrue(GoldenSourceRetirements.get(level).removedAt(sourcePos).contains(oldId),
                "Forge実Golden破壊をretirementへ記録していない");

        helper.setBlock(sourceRel, ModBlocks.GOLDEN_JUKEBOX.get());
        final GoldenJukeboxBlockEntity replacement =
                (GoldenJukeboxBlockEntity) helper.getBlockEntity(sourceRel);
        helper.assertTrue(replacement != null, "Forge同位置の復帰Goldenを生成できていない");
        final UUID replacementId = replacement.sourceIdentity();
        helper.assertFalse(oldId.equals(replacementId), "Forge同位置の新Goldenが旧UUIDを再利用した");
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, replacement).contains(speaker),
                "Forge retired sourceの同位置復帰でspeakerを引き継がない");
        helper.assertTrue(speaker.getLinkedSource().filter(link -> replacementId.equals(link.sourceId())
                        && link.packedPos() == sourcePos.asLong()).isPresent(),
                "Forge同位置復帰後のspeaker linkを新UUIDへ更新していない");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void removedSourceDoesNotRelinkAndRevivalClearsRetirement(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos oldRel = new BlockPos(1, 1, 1);
        final BlockPos movedRel = new BlockPos(4, 1, 1);
        final BlockPos revivedRel = new BlockPos(6, 1, 1);
        final BlockPos speakerRel = new BlockPos(3, 1, 4);
        helper.setBlock(oldRel, ModBlocks.GOLDEN_JUKEBOX.get());
        helper.setBlock(speakerRel.below(), Blocks.STONE);
        helper.setBlock(speakerRel, ModBlocks.SPEAKER.get());
        final GoldenJukeboxBlockEntity original =
                (GoldenJukeboxBlockEntity) helper.getBlockEntity(oldRel);
        final SpeakerBlockEntity speaker = (SpeakerBlockEntity) helper.getBlockEntity(speakerRel);
        helper.assertTrue(original != null && speaker != null,
                "Forge非破壊移設試験のworld BEを生成できていない");
        final BlockPos oldPos = helper.absolutePos(oldRel);
        final UUID identity = original.sourceIdentity();
        final CompoundTag sourceState = original.saveWithFullMetadata();
        speaker.linkTo(level, oldPos);

        original.setRemoved();
        level.removeBlockEntity(oldPos);
        helper.assertFalse(GoldenSourceRetirements.get(level).removedAt(oldPos).contains(identity),
                "Forge setRemovedだけのCreate順序を実破壊として記録した");
        helper.setBlock(oldRel, Blocks.AIR);
        helper.setBlock(oldRel, ModBlocks.GOLDEN_JUKEBOX.get());
        final GoldenJukeboxBlockEntity unrelated =
                (GoldenJukeboxBlockEntity) helper.getBlockEntity(oldRel);
        helper.assertTrue(unrelated != null, "Forge旧位置の別Goldenを生成できていない");
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, unrelated).isEmpty(),
                "Forge retirementの無い旧位置へ別UUID Goldenがspeakerを引き継いだ");

        helper.setBlock(movedRel, ModBlocks.GOLDEN_JUKEBOX.get());
        final GoldenJukeboxBlockEntity moved =
                (GoldenJukeboxBlockEntity) helper.getBlockEntity(movedRel);
        helper.assertTrue(moved != null, "Forge同UUIDの移設Goldenを生成できていない");
        moved.load(sourceState);
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, moved).contains(speaker),
                "Forge別位置に復元した同UUID Goldenがspeakerを解決できない");
        final BlockPos movedPos = helper.absolutePos(movedRel);
        level.destroyBlock(movedPos, false);
        helper.assertTrue(GoldenSourceRetirements.get(level).removedAt(movedPos).contains(identity),
                "Forge移設Goldenの実破壊をretirementへ記録していない");

        helper.setBlock(revivedRel, ModBlocks.GOLDEN_JUKEBOX.get());
        final GoldenJukeboxBlockEntity revived =
                (GoldenJukeboxBlockEntity) helper.getBlockEntity(revivedRel);
        helper.assertTrue(revived != null, "Forge別位置の同UUID復活Goldenを生成できていない");
        revived.load(sourceState);
        final var resolution = GoldenSourceRegistry.lookup(level, identity);
        helper.assertTrue(resolution.status() == GoldenSourceRegistry.Status.UNIQUE
                        && resolution.source() == revived,
                "Forge別位置へ復活した同UUID GoldenをUNIQUEに解決できない");
        helper.assertFalse(GoldenSourceRetirements.get(level).removedAt(movedPos).contains(identity),
                "Forge同UUID Golden復活後も旧retirementが残った");
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, unrelated).isEmpty(),
                "Forge同UUID復活後に旧位置の別Goldenへspeakerを渡した");
        helper.setBlock(movedRel, ModBlocks.GOLDEN_JUKEBOX.get());
        final GoldenJukeboxBlockEntity movedReplacement =
                (GoldenJukeboxBlockEntity) helper.getBlockEntity(movedRel);
        helper.assertTrue(movedReplacement != null
                        && SpeakerNetwork.findLinkedSpeakers(level, movedReplacement).isEmpty(),
                "Forge forget後もretirement旧位置の新Goldenへspeakerを渡した");
        helper.succeed();
    }

    private static ItemStack malformedLinkItem(String dimensionId, long packedPos) {
        final CompoundTag link = new CompoundTag();
        link.putString("dimension", dimensionId);
        link.putLong("position", packedPos);
        link.putString("source_id", "invalid-uuid");
        final CompoundTag root = new CompoundTag();
        root.put("music_disc_maker_speaker_link", link);
        final ItemStack stack = new ItemStack(ModItems.SPEAKER.get());
        stack.setTag(root);
        return stack;
    }

    private static ServerPlayer listener(ServerLevel level, BlockPos position, double offsetX) {
        // Only the level's listener selection and MDM payload delivery are under test.
        // Vanilla's login helper supplies no Channel and cannot run Forge's login handshake.
        final ServerPlayer player = new ServerPlayer(level.getServer(), level,
                new GameProfile(UUID.randomUUID(), "speaker-listener"));
        player.setPos(position.getX() + offsetX, position.getY() + 0.5D, position.getZ() + 0.5D);
        level.players().add(player);
        return player;
    }

    private static void assertSetThenVanilla(GameTestHelper helper, CapturingNetwork net,
            ServerPlayer player, GoldenJukeboxBlockEntity source) {
        final List<Sent> sent = net.forPlayer(player);
        helper.assertTrue(sent.size() == 2
                        && sent.get(0).payload() instanceof SpeakerSetPayload
                        && sent.get(1).payload() instanceof PlayVanillaDiscPayload,
                "Set→PlayVanillaを送っていない: " + sent);
        final PlayVanillaDiscPayload play = (PlayVanillaDiscPayload) sent.get(1).payload();
        helper.assertTrue("minecraft:music_disc.13".equals(play.track().soundEventId()),
                "RecordItemのSoundEvent識別子が違う: " + play.track().soundEventId());
        helper.assertTrue(play.generation() == source.playbackCursor().generation(),
                "PlayVanilla generationがserver cursorと不一致");
    }

    private record Sent(ModPayload payload, ServerPlayer player) {
    }

    private static final class CapturingNetwork implements INetworkHelper {
        private final List<Sent> sent = new ArrayList<>();

        private List<Sent> of(Class<? extends ModPayload> type) {
            return sent.stream().filter(entry -> type.isInstance(entry.payload())).toList();
        }

        private List<Sent> forPlayer(ServerPlayer player) {
            return sent.stream().filter(entry -> entry.player() == player).toList();
        }

        private void clear() {
            sent.clear();
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
