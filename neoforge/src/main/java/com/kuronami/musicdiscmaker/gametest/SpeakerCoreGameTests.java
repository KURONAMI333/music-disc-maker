package com.kuronami.musicdiscmaker.gametest;

//? if >=1.21.2 {
import com.kuronami.musicdiscmaker.block.SpeakerBlock;
import com.kuronami.musicdiscmaker.block.SpeakerBlockEntity;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
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
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;
//?} else {
//?}

/** 固定スピーカーの registry 経由の接続、解除、mute と支持状態を確認する。 */
public final class SpeakerCoreGameTests {

    private SpeakerCoreGameTests() {
    }

    //? if >=1.21.2 {
    public static void fenceSupportsPlacementAndRemovalStillDropsSpeaker(GameTestHelper helper) {
        final BlockPos support = new BlockPos(4, 1, 4);
        final BlockPos speakerPos = support.above();
        helper.setBlock(support, Blocks.OAK_FENCE.defaultBlockState());
        final Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.SPEAKER.get()));
        ModItems.SPEAKER.get().useOn(new UseOnContext(player, InteractionHand.MAIN_HAND,
                hit(helper.absolutePos(support), Direction.UP)));
        helper.assertTrue(helper.getBlockEntity(speakerPos, SpeakerBlockEntity.class) != null,
                "Speaker cannot be placed on the center of a fence");
        helper.assertTrue(player.getMainHandItem().isEmpty(), "Placed speaker was not consumed");
        helper.setBlock(support, Blocks.AIR.defaultBlockState());
        helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(speakerPos)).isAir(),
                "Speaker floated after its fence support was removed");
        helper.succeed();
    }

    public static void linksClearsAndMutesWithoutChangingTheSource(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos source = helper.absolutePos(new BlockPos(1, 1, 1));
        final BlockPos rel = new BlockPos(3, 1, 3);
        final BlockPos pos = helper.absolutePos(rel);
        helper.setBlock(rel, ModBlocks.SPEAKER.get().defaultBlockState());
        final SpeakerBlockEntity speaker = helper.getBlockEntity(rel, SpeakerBlockEntity.class);
        if (speaker == null) {
            helper.fail("speaker BlockEntity が registry 経由で生成されていない", rel);
            return;
        }
        speaker.linkTo(level, source);
        helper.assertTrue(speaker.hasLinkedSource(), "Golden 音源へのリンクが保存されていない");
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, source).contains(speaker),
                "ロード済み speaker が server 索引に載っていない");
        level.setBlock(pos.relative(Direction.EAST), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
        helper.assertTrue(speaker.isMuted(), "隣接レッドストーンで mute にならない");
        speaker.clearLink();
        helper.assertFalse(speaker.hasLinkedSource(), "リンク解除後も BlockEntity に音源が残る");
        helper.assertFalse(SpeakerNetwork.findLinkedSpeakers(level, source).contains(speaker),
                "リンク解除後も server 索引に残る");
        speaker.linkTo(level, source);
        speaker.setRemoved();
        helper.assertFalse(SpeakerNetwork.findLinkedSpeakers(level, source).contains(speaker),
                "chunk unload 相当の BlockEntity remove 後も server 索引に残る");
        speaker.clearRemoved();
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, source).contains(speaker),
                "chunk reload 相当の clearRemoved 後に server 索引へ戻らない");
        level.destroyBlock(pos, false);
        helper.assertFalse(SpeakerNetwork.findLinkedSpeakers(level, source).contains(speaker),
                "speaker 破壊後も server 索引に残る");
        helper.succeed();
    }

    public static void exposesAllFaceAndFacingStates(GameTestHelper helper) {
        final var state = ModBlocks.SPEAKER.get().defaultBlockState()
                .setValue(SpeakerBlock.FACE, AttachFace.CEILING)
                .setValue(SpeakerBlock.FACING, Direction.WEST)
                .setValue(SpeakerBlock.HORN_TURN, SpeakerBlock.HornTurn.LEFT);
        helper.assertTrue(state.hasProperty(SpeakerBlock.FACE) && state.hasProperty(SpeakerBlock.FACING)
                        && state.hasProperty(SpeakerBlock.HORN_TURN),
                "speaker state に FACE/FACING/HORN_TURN が登録されていない");
        helper.assertTrue(state.getValue(SpeakerBlock.FACE) == AttachFace.CEILING
                        && state.getValue(SpeakerBlock.FACING) == Direction.WEST
                        && state.getValue(SpeakerBlock.HORN_TURN) == SpeakerBlock.HornTurn.LEFT,
                "床・壁・天井とホーンの水平振りを独立して表現できない");
        helper.assertTrue(SpeakerBlock.HornTurn.CENTER.next() == SpeakerBlock.HornTurn.LEFT
                        && SpeakerBlock.HornTurn.LEFT.next() == SpeakerBlock.HornTurn.RIGHT
                        && SpeakerBlock.HornTurn.RIGHT.next() == SpeakerBlock.HornTurn.CENTER,
                "ホーンの水平振りが中央→左→右→中央で循環しない");
        assertPlacementFacesPlayer(helper, new BlockPos(5, 0, 5), Direction.UP, AttachFace.FLOOR,
                new BlockPos(5, 1, 7), Direction.SOUTH, "床");
        assertPlacementFacesPlayer(helper, new BlockPos(7, 3, 5), Direction.DOWN, AttachFace.CEILING,
                new BlockPos(7, 1, 7), Direction.SOUTH, "天井");
        assertPlacementFacesPlayer(helper, new BlockPos(9, 1, 5), Direction.NORTH, AttachFace.WALL,
                new BlockPos(9, 1, 3), Direction.NORTH, "壁");
        assertDiagonalFloorPlacement(helper);
        helper.succeed();
    }

    /** 最後の 1 個でも、BlockItem が消費する前に捕捉した Golden リンクを設置先へ渡す。 */
    public static void lastSpeakerItemKeepsGoldenLinkWhenPlaced(GameTestHelper helper) {
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
        final SpeakerBlockEntity speaker = helper.getBlockEntity(speakerRel, SpeakerBlockEntity.class);
        if (speaker == null) {
            helper.fail("最後の speaker item が設置されていない", speakerRel);
            return;
        }
        helper.assertTrue(onlySpeaker.isEmpty(), "最後の speaker item が消費されていない");
        helper.assertTrue(speaker.hasLinkedSource(), "最後の speaker item が消費された後に Golden リンクを失った");
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, helper.absolutePos(sourceRel)).contains(speaker),
                "設置された最後の speaker が Golden の索引に載っていない");
        helper.succeed();
    }

    /** 別次元で選んだ最後の 1 個は、無接続 block に化けず item と link を保持する。 */
    public static void wrongDimensionLinkRejectsPlacementWithoutConsumingItem(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos supportRel = new BlockPos(4, 0, 5);
        final BlockPos speakerRel = supportRel.above();
        helper.setBlock(supportRel, Blocks.STONE.defaultBlockState());
        final Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        final ItemStack onlySpeaker = new ItemStack(ModItems.SPEAKER.get(), 1);
        final String currentDimension = level.dimension().identifier().toString();
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

    public static void linkAndVolumeSurviveBlockEntityRoundTrip(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos rel = new BlockPos(6, 1, 6);
        final BlockPos source = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.setBlock(rel, ModBlocks.SPEAKER.get().defaultBlockState());
        final SpeakerBlockEntity speaker = helper.getBlockEntity(rel, SpeakerBlockEntity.class);
        if (speaker == null) {
            helper.fail("保存試験の speaker が生成されていない", rel);
            return;
        }
        speaker.linkTo(level, source);
        speaker.setVolumePercent(999);
        final var saved = speaker.saveCustomOnly(level.registryAccess());
        final SpeakerBlockEntity restored = new SpeakerBlockEntity(helper.absolutePos(rel), speaker.getBlockState());
        restored.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), saved));
        helper.assertTrue(restored.getLinkedSource().equals(speaker.getLinkedSource()), "音源リンクが保存往復で失われた");
        helper.assertTrue(restored.getVolumePercent() == SpeakerBlockEntity.VOLUME_MAX,
                "音量clamp値が保存往復で失われた");
        helper.succeed();
    }

    public static void sourceIdentitySurvivesItemAndSpeakerRoundTrips(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos sourceRel = new BlockPos(1, 1, 1);
        final BlockPos speakerRel = new BlockPos(4, 1, 4);
        helper.setBlock(sourceRel, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        helper.setBlock(speakerRel.below(), Blocks.STONE.defaultBlockState());
        helper.setBlock(speakerRel, ModBlocks.SPEAKER.get().defaultBlockState());
        final GoldenJukeboxBlockEntity golden = helper.getBlockEntity(sourceRel, GoldenJukeboxBlockEntity.class);
        final SpeakerBlockEntity speaker = helper.getBlockEntity(speakerRel, SpeakerBlockEntity.class);
        if (golden == null || speaker == null) {
            helper.fail("UUID link試験のBlockEntityが生成されていない");
            return;
        }

        final BlockPos sourcePos = helper.absolutePos(sourceRel);
        final UUID sourceId = golden.sourceIdentity();
        final SpeakerLink expected = new SpeakerLink(
                level.dimension().identifier().toString(), sourcePos.asLong(), sourceId);
        final ItemStack linkedItem = new ItemStack(ModItems.SPEAKER.get());
        SpeakerItem.writeLink(linkedItem, expected);
        final SpeakerLink itemLink = SpeakerItem.linkOf(linkedItem).orElse(null);
        helper.assertTrue(expected.equals(itemLink), "speaker itemのUUID付きlinkが保存往復で変化した");

        speaker.linkTo(level, sourcePos);
        helper.assertTrue(speaker.getLinkedSource().filter(expected::equals).isPresent(),
                "loaded GoldenへのlinkToがsource IDを保持しない");
        speaker.clearLink();
        speaker.replaceLinkedSource(itemLink);
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, sourcePos).contains(speaker),
                "UUID付きlinkが位置索引から検索できない");

        final CompoundTag saved = speaker.saveCustomOnly(level.registryAccess());
        final SpeakerBlockEntity restored = new SpeakerBlockEntity(speaker.getBlockPos(), speaker.getBlockState());
        restored.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), saved));
        helper.assertTrue(restored.getLinkedSource().filter(expected::equals).isPresent(),
                "speaker BEのUUID付きlinkが保存往復で変化した");

        final SpeakerLink legacy = new SpeakerLink(expected.dimensionId(), expected.packedPos());
        final ItemStack legacyItem = new ItemStack(ModItems.SPEAKER.get());
        SpeakerItem.writeLink(legacyItem, legacy);
        helper.assertTrue(SpeakerItem.linkOf(legacyItem).filter(legacy::equals).isPresent(),
                "source IDの無い旧item linkが保存往復で失われた");

        final ItemStack malformedItem = malformedLinkItem(expected.dimensionId(), expected.packedPos());
        final SpeakerLink recoveredItem = SpeakerItem.linkOf(malformedItem).orElse(null);
        helper.assertTrue(recoveredItem != null && recoveredItem.positionKey().equals(legacy)
                        && recoveredItem.sourceId() == null,
                "itemの不正source IDが位置linkまで失わせた");

        final CompoundTag malformedSaved = saved.copy();
        malformedSaved.putString("source_id", "invalid-uuid");
        final SpeakerBlockEntity recovered = new SpeakerBlockEntity(speaker.getBlockPos(), speaker.getBlockState());
        recovered.loadWithComponents(TagValueInput.create(
                ProblemReporter.DISCARDING, level.registryAccess(), malformedSaved));
        helper.assertTrue(recovered.getLinkedSource().filter(legacy::equals).isPresent(),
                "BEの不正source IDが位置linkまで失わせた");
        helper.succeed();
    }

    public static void sourceIdentityIndexExcludesOtherAndLegacyLinks(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos speakerRel = new BlockPos(5, 1, 5);
        final BlockPos sourcePos = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.setBlock(speakerRel.below(), Blocks.STONE.defaultBlockState());
        helper.setBlock(speakerRel, ModBlocks.SPEAKER.get().defaultBlockState());
        final SpeakerBlockEntity speaker = helper.getBlockEntity(speakerRel, SpeakerBlockEntity.class);
        if (speaker == null) {
            helper.fail("UUID索引試験のspeakerが生成されていない", speakerRel);
            return;
        }

        final UUID sourceId = UUID.randomUUID();
        final UUID otherId = UUID.randomUUID();
        final String dimension = level.dimension().identifier().toString();
        final String otherDimension = dimension.equals("minecraft:the_nether")
                ? "minecraft:overworld" : "minecraft:the_nether";
        speaker.replaceLinkedSource(new SpeakerLink(otherDimension, sourcePos.asLong(), sourceId));
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, sourceId).isEmpty(),
                "別次元の同じsource IDが現在次元のUUID索引へ混入した");

        speaker.replaceLinkedSource(new SpeakerLink(dimension, sourcePos.asLong(), sourceId));
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, sourceId).contains(speaker),
                "同じsource IDのspeakerをUUID索引で取得できない");
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, otherId).isEmpty(),
                "別source IDの検索がspeakerを返した");

        speaker.replaceLinkedSource(new SpeakerLink(dimension, sourcePos.asLong(), otherId));
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, sourceId).isEmpty(),
                "source ID差し替え後も旧UUID索引にspeakerが残った");
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, otherId).contains(speaker),
                "source ID差し替え後に新UUID索引へspeakerが載らない");
        speaker.setRemoved();
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, otherId).isEmpty(),
                "removed speakerがUUID索引から除外されない");
        speaker.clearRemoved();
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, otherId).contains(speaker),
                "clearRemoved後にUUID索引へspeakerが戻らない");

        speaker.clearLink();
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, otherId).isEmpty(),
                "clearLink後もUUID索引にspeakerが残った");
        speaker.replaceLinkedSource(new SpeakerLink(dimension, sourcePos.asLong()));
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, sourceId).isEmpty(),
                "source IDの無い旧位置linkがUUID索引へ混入した");
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, sourcePos).contains(speaker),
                "UUID索引追加で旧位置link検索が壊れた");
        speaker.clearLink();
        helper.succeed();
    }

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
        final GoldenJukeboxBlockEntity original =
                helper.getBlockEntity(oldSourceRel, GoldenJukeboxBlockEntity.class);
        final SpeakerBlockEntity legacySpeaker =
                helper.getBlockEntity(legacySpeakerRel, SpeakerBlockEntity.class);
        final SpeakerBlockEntity identifiedSpeaker =
                helper.getBlockEntity(identifiedSpeakerRel, SpeakerBlockEntity.class);
        if (original == null || legacySpeaker == null || identifiedSpeaker == null) {
            helper.fail("source-aware lookup試験の実world BEを生成できていない");
            return;
        }

        final String dimension = level.dimension().identifier().toString();
        final BlockPos oldSourcePos = helper.absolutePos(oldSourceRel);
        final UUID sourceId = original.sourceIdentity();
        legacySpeaker.replaceLinkedSource(new SpeakerLink(dimension, oldSourcePos.asLong()));
        identifiedSpeaker.replaceLinkedSource(new SpeakerLink(dimension, oldSourcePos.asLong(), sourceId));
        final var initial = SpeakerNetwork.findLinkedSpeakers(level, original);
        helper.assertTrue(initial.contains(legacySpeaker) && initial.contains(identifiedSpeaker),
                "現位置の旧linkとUUID linkをsource-aware検索で取得できない");
        helper.assertTrue(legacySpeaker.getLinkedSource().filter(link -> sourceId.equals(link.sourceId())
                        && link.packedPos() == oldSourcePos.asLong()).isPresent(),
                "UUID無し旧linkを現在source IDへ昇格していない");

        final CompoundTag sharedState = original.saveCustomOnly(level.registryAccess());
        helper.setBlock(movedSourceRel, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        final GoldenJukeboxBlockEntity moved =
                helper.getBlockEntity(movedSourceRel, GoldenJukeboxBlockEntity.class);
        if (moved == null) {
            helper.fail("移設先Goldenを実worldへ生成できていない");
            return;
        }
        loadGoldenState(level, moved, sharedState);
        original.setRemoved();

        helper.setBlock(oldSourceRel, Blocks.AIR.defaultBlockState());
        helper.setBlock(oldSourceRel, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        final GoldenJukeboxBlockEntity replacement =
                helper.getBlockEntity(oldSourceRel, GoldenJukeboxBlockEntity.class);
        if (replacement == null) {
            helper.fail("旧座標の別UUID Goldenを生成できていない");
            return;
        }
        final UUID replacementId = replacement.sourceIdentity();
        helper.assertFalse(sourceId.equals(replacementId), "旧座標の置換Goldenが別UUIDになっていない");
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, replacement).isEmpty(),
                "旧座標を使う別UUID Goldenが既存UUID linkを引き継いだ");

        final BlockPos movedSourcePos = helper.absolutePos(movedSourceRel);
        final var afterMove = SpeakerNetwork.findLinkedSpeakers(level, moved);
        helper.assertTrue(afterMove.contains(legacySpeaker) && afterMove.contains(identifiedSpeaker),
                "同じUUIDの移設先Goldenがlink済みspeakerを解決できない");
        helper.assertTrue(legacySpeaker.getLinkedSource().filter(link -> sourceId.equals(link.sourceId())
                        && link.packedPos() == movedSourcePos.asLong()).isPresent()
                        && identifiedSpeaker.getLinkedSource().filter(link -> sourceId.equals(link.sourceId())
                        && link.packedPos() == movedSourcePos.asLong()).isPresent(),
                "移設後も同UUID linkの保存位置が旧座標のまま残った");

        helper.setBlock(duplicateSourceRel, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        final GoldenJukeboxBlockEntity duplicate =
                helper.getBlockEntity(duplicateSourceRel, GoldenJukeboxBlockEntity.class);
        if (duplicate == null) {
            helper.fail("重複UUID Goldenを実worldへ生成できていない");
            return;
        }
        loadGoldenState(level, duplicate, sharedState);
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, moved).isEmpty(),
                "同一UUID Goldenが2台あるCONFLICT中にspeakerを解決した");
        helper.succeed();
    }

    private static void loadGoldenState(ServerLevel level, GoldenJukeboxBlockEntity target, CompoundTag state) {
        target.loadWithComponents(TagValueInput.create(
                ProblemReporter.DISCARDING, level.registryAccess(), state));
    }

    public static void destroyedSourceRelinksReplacementAtSamePosition(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos sourceRel = new BlockPos(1, 1, 1);
        final BlockPos speakerRel = new BlockPos(3, 1, 3);
        helper.setBlock(sourceRel, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        helper.setBlock(speakerRel.below(), Blocks.STONE.defaultBlockState());
        helper.setBlock(speakerRel, ModBlocks.SPEAKER.get().defaultBlockState());
        final GoldenJukeboxBlockEntity source =
                helper.getBlockEntity(sourceRel, GoldenJukeboxBlockEntity.class);
        final SpeakerBlockEntity speaker = helper.getBlockEntity(speakerRel, SpeakerBlockEntity.class);
        if (source == null || speaker == null) {
            helper.fail("実破壊復帰試験のworld BEを生成できていない");
            return;
        }
        final BlockPos sourcePos = helper.absolutePos(sourceRel);
        final UUID oldId = source.sourceIdentity();
        speaker.linkTo(level, sourcePos);
        level.destroyBlock(sourcePos, false);
        helper.assertTrue(GoldenSourceRetirements.get(level).removedAt(sourcePos).contains(oldId),
                "実Golden破壊をretirementへ記録していない");

        helper.setBlock(sourceRel, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        final GoldenJukeboxBlockEntity replacement =
                helper.getBlockEntity(sourceRel, GoldenJukeboxBlockEntity.class);
        if (replacement == null) {
            helper.fail("同位置の復帰Goldenを生成できていない");
            return;
        }
        final UUID replacementId = replacement.sourceIdentity();
        helper.assertFalse(oldId.equals(replacementId), "同位置の新Goldenが旧UUIDを再利用した");
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, replacement).contains(speaker),
                "retired sourceの同位置復帰でspeakerを引き継がない");
        helper.assertTrue(speaker.getLinkedSource().filter(link -> replacementId.equals(link.sourceId())
                        && link.packedPos() == sourcePos.asLong()).isPresent(),
                "同位置復帰後のspeaker linkを新UUIDへ更新していない");
        helper.succeed();
    }

    public static void removedSourceDoesNotRelinkAndRevivalClearsRetirement(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos oldRel = new BlockPos(1, 1, 1);
        final BlockPos movedRel = new BlockPos(4, 1, 1);
        final BlockPos revivedRel = new BlockPos(6, 1, 1);
        final BlockPos speakerRel = new BlockPos(3, 1, 4);
        helper.setBlock(oldRel, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        helper.setBlock(speakerRel.below(), Blocks.STONE.defaultBlockState());
        helper.setBlock(speakerRel, ModBlocks.SPEAKER.get().defaultBlockState());
        final GoldenJukeboxBlockEntity original =
                helper.getBlockEntity(oldRel, GoldenJukeboxBlockEntity.class);
        final SpeakerBlockEntity speaker = helper.getBlockEntity(speakerRel, SpeakerBlockEntity.class);
        if (original == null || speaker == null) {
            helper.fail("非破壊移設試験のworld BEを生成できていない");
            return;
        }
        final BlockPos oldPos = helper.absolutePos(oldRel);
        final UUID identity = original.sourceIdentity();
        final CompoundTag sourceState = original.saveCustomOnly(level.registryAccess());
        speaker.linkTo(level, oldPos);

        original.setRemoved();
        level.removeBlockEntity(oldPos);
        helper.assertFalse(GoldenSourceRetirements.get(level).removedAt(oldPos).contains(identity),
                "removeBlockEntityだけのCreate順序を実破壊として記録した");
        helper.setBlock(oldRel, Blocks.AIR.defaultBlockState());
        helper.setBlock(oldRel, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        final GoldenJukeboxBlockEntity unrelated =
                helper.getBlockEntity(oldRel, GoldenJukeboxBlockEntity.class);
        if (unrelated == null) {
            helper.fail("旧位置の別Goldenを生成できていない");
            return;
        }
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, unrelated).isEmpty(),
                "retirementの無い旧位置へ別UUID Goldenがspeakerを引き継いだ");

        helper.setBlock(movedRel, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        final GoldenJukeboxBlockEntity moved =
                helper.getBlockEntity(movedRel, GoldenJukeboxBlockEntity.class);
        if (moved == null) {
            helper.fail("同UUIDの移設Goldenを生成できていない");
            return;
        }
        loadGoldenState(level, moved, sourceState);
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, moved).contains(speaker),
                "別位置に復元した同UUID Goldenがspeakerを解決できない");
        final BlockPos movedPos = helper.absolutePos(movedRel);
        level.destroyBlock(movedPos, false);
        helper.assertTrue(GoldenSourceRetirements.get(level).removedAt(movedPos).contains(identity),
                "移設Goldenの実破壊をretirementへ記録していない");

        helper.setBlock(revivedRel, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        final GoldenJukeboxBlockEntity revived =
                helper.getBlockEntity(revivedRel, GoldenJukeboxBlockEntity.class);
        if (revived == null) {
            helper.fail("別位置の同UUID復活Goldenを生成できていない");
            return;
        }
        loadGoldenState(level, revived, sourceState);
        final var resolution = GoldenSourceRegistry.lookup(level, identity);
        helper.assertTrue(resolution.status() == GoldenSourceRegistry.Status.UNIQUE
                        && resolution.source() == revived,
                "別位置へ復活した同UUID GoldenをUNIQUEに解決できない");
        helper.assertFalse(GoldenSourceRetirements.get(level).removedAt(movedPos).contains(identity),
                "同UUID Golden復活後も旧retirementが残った");
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, unrelated).isEmpty(),
                "同UUID復活後に旧位置の別Goldenへspeakerを渡した");
        helper.setBlock(movedRel, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        final GoldenJukeboxBlockEntity movedReplacement =
                helper.getBlockEntity(movedRel, GoldenJukeboxBlockEntity.class);
        helper.assertTrue(movedReplacement != null
                        && SpeakerNetwork.findLinkedSpeakers(level, movedReplacement).isEmpty(),
                "forget後もretirement旧位置の新Goldenへspeakerを渡した");
        helper.succeed();
    }

    public static void lateLegacySpeakerFollowsClaimedSuccessorNotOldPosition(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos oldSourceRel = new BlockPos(1, 1, 1);
        final BlockPos movedSourceRel = new BlockPos(6, 1, 1);
        final BlockPos onlineSpeakerRel = new BlockPos(2, 1, 4);
        final BlockPos offlineSpeakerRel = new BlockPos(4, 1, 4);
        helper.setBlock(oldSourceRel, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        helper.setBlock(onlineSpeakerRel.below(), Blocks.STONE.defaultBlockState());
        helper.setBlock(offlineSpeakerRel.below(), Blocks.STONE.defaultBlockState());
        helper.setBlock(onlineSpeakerRel, ModBlocks.SPEAKER.get().defaultBlockState());
        helper.setBlock(offlineSpeakerRel, ModBlocks.SPEAKER.get().defaultBlockState());
        final GoldenJukeboxBlockEntity original =
                helper.getBlockEntity(oldSourceRel, GoldenJukeboxBlockEntity.class);
        final SpeakerBlockEntity onlineSpeaker =
                helper.getBlockEntity(onlineSpeakerRel, SpeakerBlockEntity.class);
        final SpeakerBlockEntity offlineSpeaker =
                helper.getBlockEntity(offlineSpeakerRel, SpeakerBlockEntity.class);
        if (original == null || onlineSpeaker == null || offlineSpeaker == null) {
            helper.fail("late speaker successor試験のworld BEを生成できていない");
            return;
        }

        final BlockPos oldSourcePos = helper.absolutePos(oldSourceRel);
        final UUID originalId = original.sourceIdentity();
        onlineSpeaker.linkTo(level, oldSourcePos);
        offlineSpeaker.linkTo(level, oldSourcePos);
        offlineSpeaker.setRemoved();
        level.destroyBlock(oldSourcePos, false);
        helper.assertTrue(GoldenSourceRetirements.get(level).removedAt(oldSourcePos).contains(originalId),
                "late speaker試験の旧source破壊を記録していない");

        helper.setBlock(oldSourceRel, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        final GoldenJukeboxBlockEntity successorAtOldPosition =
                helper.getBlockEntity(oldSourceRel, GoldenJukeboxBlockEntity.class);
        if (successorAtOldPosition == null) {
            helper.fail("旧位置のsuccessor Goldenを生成できていない");
            return;
        }
        final UUID successorId = successorAtOldPosition.sourceIdentity();
        final var initiallyClaimed = SpeakerNetwork.findLinkedSpeakers(level, successorAtOldPosition);
        helper.assertTrue(initiallyClaimed.contains(onlineSpeaker) && !initiallyClaimed.contains(offlineSpeaker),
                "successorの初回claimがロード済みspeakerだけを取得していない");
        final CompoundTag successorState = successorAtOldPosition.saveCustomOnly(level.registryAccess());

        successorAtOldPosition.setRemoved();
        level.removeBlockEntity(oldSourcePos);
        helper.setBlock(oldSourceRel, Blocks.AIR.defaultBlockState());
        helper.setBlock(movedSourceRel, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        final GoldenJukeboxBlockEntity movedSuccessor =
                helper.getBlockEntity(movedSourceRel, GoldenJukeboxBlockEntity.class);
        if (movedSuccessor == null) {
            helper.fail("移設先successor Goldenを生成できていない");
            return;
        }
        loadGoldenState(level, movedSuccessor, successorState);
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, movedSuccessor).contains(onlineSpeaker),
                "移設先successorが既にclaimしたspeakerを維持しない");

        helper.setBlock(oldSourceRel, ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState());
        final GoldenJukeboxBlockEntity unrelatedAtOldPosition =
                helper.getBlockEntity(oldSourceRel, GoldenJukeboxBlockEntity.class);
        if (unrelatedAtOldPosition == null) {
            helper.fail("旧位置の無関係Goldenを生成できていない");
            return;
        }
        offlineSpeaker.clearRemoved();
        final var unrelatedListeners = SpeakerNetwork.findLinkedSpeakers(level, unrelatedAtOldPosition);
        helper.assertFalse(unrelatedListeners.contains(offlineSpeaker),
                "後からロードされた旧UUID speakerを旧位置の無関係Goldenへ誤移行した");
        final var successorListeners = SpeakerNetwork.findLinkedSpeakers(level, movedSuccessor);
        final BlockPos movedSourcePos = helper.absolutePos(movedSourceRel);
        helper.assertTrue(successorListeners.contains(offlineSpeaker)
                        && offlineSpeaker.getLinkedSource().filter(link -> successorId.equals(link.sourceId())
                        && link.packedPos() == movedSourcePos.asLong()).isPresent(),
                "後からロードされた旧UUID speakerをclaim済みsuccessorへ復帰できない");
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

    public static void breakingEverySupportFaceRemovesSpeakerAndIndex(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos source = helper.absolutePos(new BlockPos(1, 1, 1));
        assertSupportBreak(helper, level, source, new BlockPos(7, 1, 7), AttachFace.FLOOR, Direction.NORTH,
                Direction.DOWN, "床");
        assertSupportBreak(helper, level, source, new BlockPos(9, 1, 7), AttachFace.WALL, Direction.NORTH,
                Direction.SOUTH, "壁");
        assertSupportBreak(helper, level, source, new BlockPos(11, 1, 7), AttachFace.CEILING, Direction.NORTH,
                Direction.UP, "天井");
        helper.succeed();
    }

    public static void staleBlockEntityAtTheSamePositionIsExcluded(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos rel = new BlockPos(9, 1, 9);
        final BlockPos source = helper.absolutePos(new BlockPos(1, 1, 1));
        helper.setBlock(rel, ModBlocks.SPEAKER.get().defaultBlockState());
        final SpeakerBlockEntity live = helper.getBlockEntity(rel, SpeakerBlockEntity.class);
        if (live == null) {
            helper.fail("stale 試験の speaker が生成されていない", rel);
            return;
        }
        live.linkTo(level, source);
        final SpeakerBlockEntity stale = new SpeakerBlockEntity(helper.absolutePos(rel), live.getBlockState());
        stale.setLevel(level);
        stale.linkTo(level, source);
        final var linked = SpeakerNetwork.findLinkedSpeakers(level, source);
        helper.assertTrue(linked.contains(live), "world 上の speaker が索引から消えた");
        helper.assertFalse(linked.contains(stale), "同座標で差し替わった古い BlockEntity が索引に残る");
        helper.succeed();
    }

    public static void unloadedSpeakerIsDroppedWithoutLoadingItsChunk(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos source = helper.absolutePos(new BlockPos(1, 1, 1));
        final BlockPos unloaded = new BlockPos(30_000_000, 80, 30_000_000);
        helper.assertFalse(level.hasChunkAt(unloaded), "前提: 遠方 chunk が既にロードされている");
        final SpeakerBlockEntity stale = new SpeakerBlockEntity(unloaded, ModBlocks.SPEAKER.get().defaultBlockState());
        stale.setLevel(level);
        stale.linkTo(level, source);
        helper.assertFalse(SpeakerNetwork.findLinkedSpeakers(level, source).contains(stale),
                "未ロード chunk の speaker を索引が返した");
        helper.assertFalse(level.hasChunkAt(unloaded), "索引検索が未ロード chunk を強制ロードした");
        helper.succeed();
    }

    public static void reloadReindexesLinkAndDefaultsMissingVolume(GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final BlockPos rel = new BlockPos(12, 1, 12);
        final BlockPos sourceA = helper.absolutePos(new BlockPos(1, 1, 1));
        final BlockPos sourceB = helper.absolutePos(new BlockPos(2, 1, 1));
        helper.setBlock(rel, ModBlocks.SPEAKER.get().defaultBlockState());
        final SpeakerBlockEntity speaker = helper.getBlockEntity(rel, SpeakerBlockEntity.class);
        if (speaker == null) {
            helper.fail("reload 試験の speaker が生成されていない", rel);
            return;
        }
        speaker.linkTo(level, sourceA);
        speaker.setVolumePercent(37);
        final var savedA = speaker.saveCustomOnly(level.registryAccess());
        speaker.linkTo(level, sourceB);
        speaker.setVolumePercent(73);
        final var savedB = speaker.saveCustomOnly(level.registryAccess());
        speaker.clearLink();
        final var savedEmpty = speaker.saveCustomOnly(level.registryAccess());

        speaker.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), savedA));
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, sourceA).contains(speaker),
                "同一BEへのreload Aで索引に登録されない");
        helper.assertFalse(SpeakerNetwork.findLinkedSpeakers(level, sourceB).contains(speaker),
                "同一BEへのreload Aで古いB索引が残る");
        speaker.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), savedB));
        helper.assertFalse(SpeakerNetwork.findLinkedSpeakers(level, sourceA).contains(speaker),
                "同一BEへのreload BでA索引が残る");
        helper.assertTrue(SpeakerNetwork.findLinkedSpeakers(level, sourceB).contains(speaker),
                "同一BEへのreload Bで索引に登録されない");
        speaker.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), savedEmpty));
        helper.assertFalse(SpeakerNetwork.findLinkedSpeakers(level, sourceB).contains(speaker),
                "空リンクのreload後にB索引が残る");

        final var missingVolume = savedA.copy();
        missingVolume.remove("volume");
        speaker.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), missingVolume));
        helper.assertTrue(speaker.getVolumePercent() == SpeakerBlockEntity.VOLUME_DEFAULT,
                "volumeキーが無い旧保存で既定100へ戻らない");
        helper.succeed();
    }

    private static void assertSupportBreak(GameTestHelper helper, ServerLevel level, BlockPos source, BlockPos rel,
            AttachFace face, Direction facing, Direction supportDirection, String label) {
        final BlockPos supportRel = rel.relative(supportDirection);
        helper.setBlock(supportRel, Blocks.STONE.defaultBlockState());
        helper.setBlock(rel, ModBlocks.SPEAKER.get().defaultBlockState()
                .setValue(SpeakerBlock.FACE, face).setValue(SpeakerBlock.FACING, facing));
        final SpeakerBlockEntity speaker = helper.getBlockEntity(rel, SpeakerBlockEntity.class);
        if (speaker == null) {
            helper.fail(label + "支持面試験の speaker が生成されていない", rel);
            return;
        }
        speaker.linkTo(level, source);
        level.destroyBlock(helper.absolutePos(supportRel), false);
        helper.assertTrue(level.getBlockState(helper.absolutePos(rel)).isAir(), label + "支持面を壊しても speaker が残る");
        helper.assertFalse(SpeakerNetwork.findLinkedSpeakers(level, source).contains(speaker),
                label + "支持面破壊後に speaker が索引へ残る");
    }

    private static void assertPlacementFacesPlayer(GameTestHelper helper, BlockPos supportRel, Direction hitFace,
            AttachFace expectedFace, BlockPos playerRel, Direction expectedFacing, String label) {
        helper.setBlock(supportRel, Blocks.STONE.defaultBlockState());
        final Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        final BlockPos playerPos = helper.absolutePos(playerRel);
        player.setPos(playerPos.getX() + 0.5D, playerPos.getY(), playerPos.getZ() + 0.5D);
        // A real player standing south of a target looks north to place it. Mock players default to yaw=0
        // (south) regardless of their position, which was testing an impossible body/placement relation.
        player.setYRot(yawLookingAt(expectedFacing));
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.SPEAKER.get()));
        final BlockPos supportPos = helper.absolutePos(supportRel);
        final BlockPlaceContext context = new BlockPlaceContext(new UseOnContext(player, InteractionHand.MAIN_HAND,
                hit(supportPos, hitFace)));
        final var placed = ModBlocks.SPEAKER.get().getStateForPlacement(context);
        helper.assertTrue(placed != null && placed.getValue(SpeakerBlock.FACE) == expectedFace
                        && placed.getValue(SpeakerBlock.FACING) == expectedFacing,
                label + "設置でホーン口が設置者側を向かない");
    }

    /** Regression seam for the actual BlockPlaceContext path: diagonals must not collapse back to cardinal facing. */
    private static void assertDiagonalFloorPlacement(GameTestHelper helper) {
        final BlockPos supportRel = new BlockPos(11, 0, 5);
        helper.setBlock(supportRel, Blocks.STONE.defaultBlockState());
        final Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        final BlockPos playerPos = helper.absolutePos(new BlockPos(13, 1, 3));
        player.setPos(playerPos.getX() + 0.5D, playerPos.getY(), playerPos.getZ() + 0.5D);
        player.setYRot(45.0F);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(ModItems.SPEAKER.get()));
        final BlockPos supportPos = helper.absolutePos(supportRel);
        final var placed = ModBlocks.SPEAKER.get().getStateForPlacement(new BlockPlaceContext(
                new UseOnContext(player, InteractionHand.MAIN_HAND, hit(supportPos, Direction.UP))));
        helper.assertTrue(placed != null && placed.getValue(SpeakerBlock.FACE) == AttachFace.FLOOR
                        && placed.getValue(SpeakerBlock.FACING) == Direction.NORTH
                        && placed.getValue(SpeakerBlock.HORN_TURN) == SpeakerBlock.HornTurn.RIGHT,
                "床設置の斜めyawがFACING/HORN_TURNへ保持されない");
    }

    private static float yawLookingAt(Direction playerSide) {
        return switch (playerSide) {
            case SOUTH -> 180.0F;
            case WEST -> 90.0F;
            case NORTH -> 0.0F;
            case EAST -> 270.0F;
            default -> throw new IllegalArgumentException("Player side must be horizontal: " + playerSide);
        };
    }

    private static BlockHitResult hit(BlockPos pos, Direction face) {
        return new BlockHitResult(Vec3.atCenterOf(pos), face, pos, false);
    }
    //?} else {
    //?}
}
