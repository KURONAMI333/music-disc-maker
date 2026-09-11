package com.kuronami.musicdiscmaker.gametest;

//? if >=1.21.2 {
//?} else {
/*import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.block.SpeakerBlock;
import com.kuronami.musicdiscmaker.block.SpeakerBlockEntity;
import com.kuronami.musicdiscmaker.event.SpeakerNetwork;
import com.kuronami.musicdiscmaker.event.GoldenSourceRegistry;
import com.kuronami.musicdiscmaker.event.GoldenSourceRetirements;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModItems;
import com.kuronami.musicdiscmaker.item.SpeakerItem;
import com.kuronami.musicdiscmaker.speaker.SpeakerLink;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
public final class SpeakerCoreLegacyGameTests {
    private static final String TEMPLATE = "empty8x3x8";

    private SpeakerCoreLegacyGameTests() {
    }

    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = "speakerLegacyCore")
    public static void linkVolumeAndEverySupportFace(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos source = helper.absolutePos(new BlockPos(1, 1, 1));
        assertSupportBreak(helper, level, source, new BlockPos(3, 1, 3), AttachFace.FLOOR, Direction.NORTH,
                Direction.DOWN, "床");
        assertSupportBreak(helper, level, source, new BlockPos(5, 1, 3), AttachFace.WALL, Direction.NORTH,
                Direction.SOUTH, "壁");
        assertSupportBreak(helper, level, source, new BlockPos(7, 1, 3), AttachFace.CEILING, Direction.NORTH,
                Direction.UP, "天井");
        helper.succeed();
    }

    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = "speakerLegacyLastItem")
    public static void lastItemCarriesGoldenLink(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos sourceRel = new BlockPos(1, 1, 1);
        final BlockPos supportRel = new BlockPos(4, 0, 4);
        final BlockPos speakerRel = supportRel.above();
        helper.setBlock(sourceRel, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        helper.setBlock(supportRel, Blocks.STONE.defaultBlockState());
        final Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        final ItemStack onlySpeaker = new ItemStack(ModItems.SPEAKER.get(), 1);
        player.setItemInHand(InteractionHand.MAIN_HAND, onlySpeaker);
        player.setShiftKeyDown(true);
        ModItems.SPEAKER.get().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                hit(helper.absolutePos(sourceRel), Direction.UP)));
        player.setShiftKeyDown(false);
        ModItems.SPEAKER.get().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                hit(helper.absolutePos(supportRel), Direction.UP)));
        final SpeakerBlockEntity speaker = helper.getBlockEntity(speakerRel);
        helper.assertTrue(speaker != null && speaker.hasLinkedSource(), "最後の item 設置で Golden リンクを失った");
        helper.assertTrue(onlySpeaker.isEmpty(), "最後の speaker item が消費されていない");
        helper.succeed();
    }

    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = "speakerLegacyCrossDimension")
    public static void wrongDimensionLinkRejectsPlacementWithoutConsumingItem(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos supportRel = new BlockPos(4, 0, 5);
        final BlockPos speakerRel = supportRel.above();
        helper.setBlock(supportRel, Blocks.STONE.defaultBlockState());
        final Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        final ItemStack onlySpeaker = new ItemStack(ModItems.SPEAKER.get(), 1);
        final String currentDimension = level.dimension().location().toString();
        final String otherDimension = currentDimension.equals("minecraft:the_nether")
                ? "minecraft:overworld" : "minecraft:the_nether";
        final SpeakerLink selected = new SpeakerLink(otherDimension, new BlockPos(9, 70, 9).asLong());
        SpeakerItem.writeLink(onlySpeaker, selected);
        player.setItemInHand(InteractionHand.MAIN_HAND, onlySpeaker);

        final var result = ModItems.SPEAKER.get().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                hit(helper.absolutePos(supportRel), Direction.UP)));
        helper.assertFalse(result.consumesAction(), "別次元linkの設置が成功扱いになった");
        helper.assertTrue(level.getBlockState(helper.absolutePos(speakerRel)).isAir(),
                "別次元linkが無接続speakerとして設置された");
        helper.assertTrue(onlySpeaker.getCount() == 1, "別次元linkの最後のspeaker itemが消費された");
        helper.assertTrue(SpeakerItem.linkOf(onlySpeaker).filter(selected::equals).isPresent(),
                "設置拒否で選択済みlinkが失われた");
        helper.succeed();
    }

    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = "speakerLegacyIdentity")
    public static void sourceIdentitySurvivesItemAndSpeakerRoundTrips(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos sourceRel = new BlockPos(1, 1, 1);
        final BlockPos speakerRel = new BlockPos(4, 1, 4);
        helper.setBlock(sourceRel, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        helper.setBlock(speakerRel.below(), Blocks.STONE.defaultBlockState());
        helper.setBlock(speakerRel, ModBlocks.SPEAKER.get().defaultBlockState());
        final GoldenJukeboxBlockEntity golden = helper.getBlockEntity(sourceRel);
        final SpeakerBlockEntity speaker = helper.getBlockEntity(speakerRel);
        helper.assertTrue(golden != null && speaker != null,
                "legacy UUID link試験のBlockEntityが生成されていない");

        final BlockPos sourcePos = helper.absolutePos(sourceRel);
        final java.util.UUID sourceId = golden.sourceIdentity();
        final SpeakerLink expected = new SpeakerLink(
                level.dimension().location().toString(), sourcePos.asLong(), sourceId);
        final ItemStack linkedItem = new ItemStack(ModItems.SPEAKER.get());
        SpeakerItem.writeLink(linkedItem, expected);
        final SpeakerLink itemLink = SpeakerItem.linkOf(linkedItem).orElse(null);
        helper.assertTrue(expected.equals(itemLink), "legacy speaker itemのUUID付きlinkが保存往復で変化した");

        speaker.linkTo(level, sourcePos);
        helper.assertTrue(speaker.getLinkedSource().filter(expected::equals).isPresent(),
                "legacy loaded GoldenへのlinkToがsource IDを保持しない");
        speaker.clearLink();
        speaker.replaceLinkedSource(itemLink);
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, sourcePos).contains(speaker),
                "legacy UUID付きlinkが位置索引から検索できない");

        final CompoundTag saved = speaker.saveWithFullMetadata(level.registryAccess());
        final SpeakerBlockEntity restored = (SpeakerBlockEntity) BlockEntity.loadStatic(
                speaker.getBlockPos(), speaker.getBlockState(), saved, level.registryAccess());
        helper.assertTrue(restored != null && restored.getLinkedSource().filter(expected::equals).isPresent(),
                "legacy speaker BEのUUID付きlinkが保存往復で変化した");

        final SpeakerLink oldLink = new SpeakerLink(expected.dimensionId(), expected.packedPos());
        final ItemStack oldItem = new ItemStack(ModItems.SPEAKER.get());
        SpeakerItem.writeLink(oldItem, oldLink);
        helper.assertTrue(SpeakerItem.linkOf(oldItem).filter(oldLink::equals).isPresent(),
                "source IDの無いlegacy item linkが保存往復で失われた");

        final SpeakerLink recoveredItem = SpeakerItem.linkOf(
                malformedLinkItem(expected.dimensionId(), expected.packedPos())).orElse(null);
        helper.assertTrue(recoveredItem != null && recoveredItem.positionKey().equals(oldLink)
                        && recoveredItem.sourceId() == null,
                "legacy itemの不正source IDが位置linkまで失わせた");

        final CompoundTag malformedSaved = saved.copy();
        malformedSaved.putString("source_id", "invalid-uuid");
        final SpeakerBlockEntity recovered = (SpeakerBlockEntity) BlockEntity.loadStatic(
                speaker.getBlockPos(), speaker.getBlockState(), malformedSaved, level.registryAccess());
        helper.assertTrue(recovered != null && recovered.getLinkedSource().filter(oldLink::equals).isPresent(),
                "legacy BEの不正source IDが位置linkまで失わせた");
        helper.succeed();
    }

    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = "speakerLegacyIdentityIndex")
    public static void sourceIdentityIndexExcludesOtherAndLegacyLinks(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos speakerRel = new BlockPos(5, 1, 5);
        final BlockPos sourcePos = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.setBlock(speakerRel.below(), Blocks.STONE.defaultBlockState());
        helper.setBlock(speakerRel, ModBlocks.SPEAKER.get().defaultBlockState());
        final SpeakerBlockEntity speaker = helper.getBlockEntity(speakerRel);
        helper.assertTrue(speaker != null, "legacy UUID索引試験のspeakerが生成されていない");

        final java.util.UUID sourceId = java.util.UUID.randomUUID();
        final java.util.UUID otherId = java.util.UUID.randomUUID();
        final String dimension = level.dimension().location().toString();
        final String otherDimension = dimension.equals("minecraft:the_nether")
                ? "minecraft:overworld" : "minecraft:the_nether";
        speaker.replaceLinkedSource(new SpeakerLink(otherDimension, sourcePos.asLong(), sourceId));
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, sourceId).isEmpty(),
                "legacy別次元の同じsource IDが現在次元のUUID索引へ混入した");

        speaker.replaceLinkedSource(new SpeakerLink(dimension, sourcePos.asLong(), sourceId));
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, sourceId).contains(speaker),
                "legacy同じsource IDのspeakerをUUID索引で取得できない");
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, otherId).isEmpty(),
                "legacy別source IDの検索がspeakerを返した");

        speaker.replaceLinkedSource(new SpeakerLink(dimension, sourcePos.asLong(), otherId));
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, sourceId).isEmpty(),
                "legacy source ID差し替え後も旧UUID索引にspeakerが残った");
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, otherId).contains(speaker),
                "legacy source ID差し替え後に新UUID索引へspeakerが載らない");
        speaker.setRemoved();
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, otherId).isEmpty(),
                "legacy removed speakerがUUID索引から除外されない");
        speaker.clearRemoved();
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, otherId).contains(speaker),
                "legacy clearRemoved後にUUID索引へspeakerが戻らない");

        speaker.clearLink();
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, otherId).isEmpty(),
                "legacy clearLink後もUUID索引にspeakerが残った");
        speaker.replaceLinkedSource(new SpeakerLink(dimension, sourcePos.asLong()));
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, sourceId).isEmpty(),
                "source IDの無いlegacy位置linkがUUID索引へ混入した");
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, sourcePos).contains(speaker),
                "legacy UUID索引追加で旧位置link検索が壊れた");
        speaker.clearLink();
        helper.succeed();
    }

    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = "speakerLegacySourceAware")
    public static void sourceAwareLookupPromotesMovesRejectsReuseAndConflict(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos oldSourceRel = new BlockPos(1, 1, 1);
        final BlockPos movedSourceRel = new BlockPos(5, 1, 1);
        final BlockPos duplicateSourceRel = new BlockPos(6, 1, 2);
        final BlockPos legacySpeakerRel = new BlockPos(2, 1, 4);
        final BlockPos identifiedSpeakerRel = new BlockPos(4, 1, 4);
        helper.setBlock(oldSourceRel, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        helper.setBlock(legacySpeakerRel.below(), Blocks.STONE.defaultBlockState());
        helper.setBlock(identifiedSpeakerRel.below(), Blocks.STONE.defaultBlockState());
        helper.setBlock(legacySpeakerRel, ModBlocks.SPEAKER.get().defaultBlockState());
        helper.setBlock(identifiedSpeakerRel, ModBlocks.SPEAKER.get().defaultBlockState());
        final GoldenJukeboxBlockEntity original = helper.getBlockEntity(oldSourceRel);
        final SpeakerBlockEntity legacySpeaker = helper.getBlockEntity(legacySpeakerRel);
        final SpeakerBlockEntity identifiedSpeaker = helper.getBlockEntity(identifiedSpeakerRel);
        helper.assertTrue(original != null && legacySpeaker != null && identifiedSpeaker != null,
                "legacy source-aware lookup試験の実world BEを生成できていない");

        final String dimension = level.dimension().location().toString();
        final BlockPos oldSourcePos = helper.absolutePos(oldSourceRel);
        final java.util.UUID sourceId = original.sourceIdentity();
        legacySpeaker.replaceLinkedSource(new SpeakerLink(dimension, oldSourcePos.asLong()));
        identifiedSpeaker.replaceLinkedSource(new SpeakerLink(dimension, oldSourcePos.asLong(), sourceId));
        final var initial = SpeakerNetwork.findLinkedSpeakers(level, original);
        helper.assertTrue(initial.contains(legacySpeaker) && initial.contains(identifiedSpeaker),
                "legacy現位置の旧linkとUUID linkをsource-aware検索で取得できない");
        helper.assertTrue(legacySpeaker.getLinkedSource().filter(link -> sourceId.equals(link.sourceId())
                        && link.packedPos() == oldSourcePos.asLong()).isPresent(),
                "legacy UUID無し旧linkを現在source IDへ昇格していない");

        final CompoundTag sharedState = original.saveCustomOnly(level.registryAccess());
        helper.setBlock(movedSourceRel, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        final GoldenJukeboxBlockEntity moved = helper.getBlockEntity(movedSourceRel);
        helper.assertTrue(moved != null, "legacy移設先Goldenを実worldへ生成できていない");
        moved.loadWithComponents(sharedState, level.registryAccess());
        original.setRemoved();

        helper.setBlock(oldSourceRel, Blocks.AIR.defaultBlockState());
        helper.setBlock(oldSourceRel, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        final GoldenJukeboxBlockEntity replacement = helper.getBlockEntity(oldSourceRel);
        helper.assertTrue(replacement != null, "legacy旧座標の別UUID Goldenを生成できていない");
        final java.util.UUID replacementId = replacement.sourceIdentity();
        helper.assertFalse(sourceId.equals(replacementId), "legacy旧座標の置換Goldenが別UUIDになっていない");
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, replacement).isEmpty(),
                "legacy旧座標を使う別UUID Goldenが既存UUID linkを引き継いだ");

        final BlockPos movedSourcePos = helper.absolutePos(movedSourceRel);
        final var afterMove = SpeakerNetwork.findLinkedSpeakers(level, moved);
        helper.assertTrue(afterMove.contains(legacySpeaker) && afterMove.contains(identifiedSpeaker),
                "legacy同じUUIDの移設先Goldenがlink済みspeakerを解決できない");
        helper.assertTrue(legacySpeaker.getLinkedSource().filter(link -> sourceId.equals(link.sourceId())
                        && link.packedPos() == movedSourcePos.asLong()).isPresent()
                        && identifiedSpeaker.getLinkedSource().filter(link -> sourceId.equals(link.sourceId())
                        && link.packedPos() == movedSourcePos.asLong()).isPresent(),
                "legacy移設後も同UUID linkの保存位置が旧座標のまま残った");

        helper.setBlock(duplicateSourceRel, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        final GoldenJukeboxBlockEntity duplicate = helper.getBlockEntity(duplicateSourceRel);
        helper.assertTrue(duplicate != null, "legacy重複UUID Goldenを実worldへ生成できていない");
        duplicate.loadWithComponents(sharedState, level.registryAccess());
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, moved).isEmpty(),
                "legacy同一UUID Goldenが2台あるCONFLICT中にspeakerを解決した");
        helper.succeed();
    }

    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = "speakerLegacyDestroyedRecovery")
    public static void destroyedSourceRelinksReplacementAtSamePosition(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos sourceRel = new BlockPos(1, 1, 1);
        final BlockPos speakerRel = new BlockPos(3, 1, 3);
        helper.setBlock(sourceRel, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        helper.setBlock(speakerRel.below(), Blocks.STONE.defaultBlockState());
        helper.setBlock(speakerRel, ModBlocks.SPEAKER.get().defaultBlockState());
        final GoldenJukeboxBlockEntity source = helper.getBlockEntity(sourceRel);
        final SpeakerBlockEntity speaker = helper.getBlockEntity(speakerRel);
        helper.assertTrue(source != null && speaker != null,
                "legacy実破壊復帰試験のworld BEを生成できていない");
        final BlockPos sourcePos = helper.absolutePos(sourceRel);
        final java.util.UUID oldId = source.sourceIdentity();
        speaker.linkTo(level, sourcePos);
        level.destroyBlock(sourcePos, false);
        helper.assertTrue(GoldenSourceRetirements.get(level).removedAt(sourcePos).contains(oldId),
                "legacy実Golden破壊をretirementへ記録していない");

        helper.setBlock(sourceRel, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        final GoldenJukeboxBlockEntity replacement = helper.getBlockEntity(sourceRel);
        helper.assertTrue(replacement != null, "legacy同位置の復帰Goldenを生成できていない");
        final java.util.UUID replacementId = replacement.sourceIdentity();
        helper.assertFalse(oldId.equals(replacementId), "legacy同位置の新Goldenが旧UUIDを再利用した");
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, replacement).contains(speaker),
                "legacy retired sourceの同位置復帰でspeakerを引き継がない");
        helper.assertTrue(speaker.getLinkedSource().filter(link -> replacementId.equals(link.sourceId())
                        && link.packedPos() == sourcePos.asLong()).isPresent(),
                "legacy同位置復帰後のspeaker linkを新UUIDへ更新していない");
        helper.succeed();
    }

    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = "speakerLegacyRemovedRevival")
    public static void removedSourceDoesNotRelinkAndRevivalClearsRetirement(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos oldRel = new BlockPos(1, 1, 1);
        final BlockPos movedRel = new BlockPos(4, 1, 1);
        final BlockPos revivedRel = new BlockPos(6, 1, 1);
        final BlockPos speakerRel = new BlockPos(3, 1, 4);
        helper.setBlock(oldRel, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        helper.setBlock(speakerRel.below(), Blocks.STONE.defaultBlockState());
        helper.setBlock(speakerRel, ModBlocks.SPEAKER.get().defaultBlockState());
        final GoldenJukeboxBlockEntity original = helper.getBlockEntity(oldRel);
        final SpeakerBlockEntity speaker = helper.getBlockEntity(speakerRel);
        helper.assertTrue(original != null && speaker != null,
                "legacy非破壊移設試験のworld BEを生成できていない");
        final BlockPos oldPos = helper.absolutePos(oldRel);
        final java.util.UUID identity = original.sourceIdentity();
        final CompoundTag sourceState = original.saveCustomOnly(level.registryAccess());
        speaker.linkTo(level, oldPos);

        original.setRemoved();
        level.removeBlockEntity(oldPos);
        helper.assertFalse(GoldenSourceRetirements.get(level).removedAt(oldPos).contains(identity),
                "legacy setRemovedだけのCreate順序を実破壊として記録した");
        helper.setBlock(oldRel, Blocks.AIR.defaultBlockState());
        helper.setBlock(oldRel, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        final GoldenJukeboxBlockEntity unrelated = helper.getBlockEntity(oldRel);
        helper.assertTrue(unrelated != null, "legacy旧位置の別Goldenを生成できていない");
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, unrelated).isEmpty(),
                "legacy retirementの無い旧位置へ別UUID Goldenがspeakerを引き継いだ");

        helper.setBlock(movedRel, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        final GoldenJukeboxBlockEntity moved = helper.getBlockEntity(movedRel);
        helper.assertTrue(moved != null, "legacy同UUIDの移設Goldenを生成できていない");
        moved.loadWithComponents(sourceState, level.registryAccess());
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, moved).contains(speaker),
                "legacy別位置に復元した同UUID Goldenがspeakerを解決できない");
        final BlockPos movedPos = helper.absolutePos(movedRel);
        level.destroyBlock(movedPos, false);
        helper.assertTrue(GoldenSourceRetirements.get(level).removedAt(movedPos).contains(identity),
                "legacy移設Goldenの実破壊をretirementへ記録していない");

        helper.setBlock(revivedRel, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        final GoldenJukeboxBlockEntity revived = helper.getBlockEntity(revivedRel);
        helper.assertTrue(revived != null, "legacy別位置の同UUID復活Goldenを生成できていない");
        revived.loadWithComponents(sourceState, level.registryAccess());
        final var resolution = GoldenSourceRegistry.lookup(level, identity);
        helper.assertTrue(resolution.status() == GoldenSourceRegistry.Status.UNIQUE
                        && resolution.source() == revived,
                "legacy別位置へ復活した同UUID GoldenをUNIQUEに解決できない");
        helper.assertFalse(GoldenSourceRetirements.get(level).removedAt(movedPos).contains(identity),
                "legacy同UUID Golden復活後も旧retirementが残った");
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, unrelated).isEmpty(),
                "legacy同UUID復活後に旧位置の別Goldenへspeakerを渡した");
        helper.setBlock(movedRel, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        final GoldenJukeboxBlockEntity movedReplacement = helper.getBlockEntity(movedRel);
        helper.assertTrue(movedReplacement != null
                        && SpeakerNetwork.findLinkedSpeakers(level, movedReplacement).isEmpty(),
                "legacy forget後もretirement旧位置の新Goldenへspeakerを渡した");
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
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(root));
        return stack;
    }

    private static void assertSupportBreak(GameTestHelper helper, ServerLevel level, BlockPos source, BlockPos rel,
            AttachFace face, Direction facing, Direction supportDirection, String label) {
        final BlockPos support = rel.relative(supportDirection);
        helper.setBlock(support, Blocks.STONE.defaultBlockState());
        helper.setBlock(rel, ModBlocks.SPEAKER.get().defaultBlockState()
                .setValue(SpeakerBlock.FACE, face).setValue(SpeakerBlock.FACING, facing));
        final SpeakerBlockEntity speaker = helper.getBlockEntity(rel);
        if (speaker == null) {
            helper.fail(label + "支持面の speaker が生成されていない", rel);
            return;
        }
        speaker.linkTo(level, source);
        speaker.setVolumePercent(999);
        helper.assertTrue(speaker.getVolumePercent() == SpeakerBlockEntity.VOLUME_MAX,
                label + " speaker の音量clampが効かない");
        level.destroyBlock(helper.absolutePos(support), false);
        helper.assertTrue(level.getBlockState(helper.absolutePos(rel)).isAir(), label + "支持面破壊で speaker が消えない");
        helper.assertFalse(SpeakerNetwork.findLinkedSpeakers(level, source).contains(speaker),
                label + "支持面破壊後に索引が残る");
    }

    private static BlockHitResult hit(BlockPos pos, Direction face) {
        return new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false);
    }
}
*/
//?}
