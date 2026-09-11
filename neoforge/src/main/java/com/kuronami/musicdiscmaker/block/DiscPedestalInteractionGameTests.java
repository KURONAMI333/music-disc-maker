package com.kuronami.musicdiscmaker.block;

import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
//? if <1.21.2 {
/*import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
*///?}
public final class DiscPedestalInteractionGameTests {
    private DiscPedestalInteractionGameTests() { }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100, batch = "discPedestalInteraction")
    *///?}
    public static void survivalSwapConsumesOneAndReturnsPrevious(GameTestHelper helper) {
        final Fixture fixture = fixture(helper, "pedestal-survival");
        try {
            final ItemStack previous = disc("previous");
            final ItemStack replacement = disc("replacement");
            fixture.pedestal().setStored(previous);
            replacement.setCount(2);
            fixture.player().setItemInHand(InteractionHand.MAIN_HAND, replacement);

            final InteractionResult result = use(fixture, replacement);
            helper.assertTrue(result.consumesAction(), "別ディスクでの台座交換が成功扱いにならない");
            helper.assertTrue("replacement".equals(fixture.pedestal().storedTitle()),
                    "台座が新しいディスクへ交換されていない");
            helper.assertTrue(replacement.getCount() == 1, "サバイバル交換が手持ちを1枚だけ消費しない");
            helper.assertTrue(countTitle(fixture.player(), "previous") == 1,
                    "交換前のディスクがinventoryへ1枚戻っていない");
            helper.assertTrue(countTitle(fixture.player(), "replacement") == 1,
                    "手持ちに残る交換用ディスクの枚数が変わった");
        } finally {
            fixture.cleanup();
        }
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100, batch = "discPedestalInteraction")
    *///?}
    public static void fullInventoryDropsPreviousExactlyOnce(GameTestHelper helper) {
        final Fixture fixture = fixture(helper, "pedestal-full");
        try {
            fixture.pedestal().setStored(disc("previous-full"));
            for (int slot = 0; slot < fixture.player().getInventory().getContainerSize(); slot++) {
                fixture.player().getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
            }
            final ItemStack replacement = disc("replacement-full");
            replacement.setCount(2);
            fixture.player().setItemInHand(InteractionHand.MAIN_HAND, replacement);

            use(fixture, replacement);
            helper.assertTrue("replacement-full".equals(fixture.pedestal().storedTitle()),
                    "満杯inventoryで台座が新しいディスクへ交換されていない");
            helper.assertTrue(replacement.getCount() == 1, "満杯inventoryの交換で手持ち消費数が1でない");
            helper.assertTrue(countTitle(fixture.player(), "previous-full") == 0,
                    "満杯inventoryへ旧ディスクが不正に挿入された");
            helper.assertTrue(countDropped(fixture, "previous-full") == 1,
                    "満杯inventoryで旧ディスクがexactly-once dropされない");
        } finally {
            fixture.cleanup();
        }
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100, batch = "discPedestalInteraction")
    *///?}
    public static void emptyHandAndCreativeKeepExistingSemantics(GameTestHelper helper) {
        final Fixture fixture = fixture(helper, "pedestal-creative");
        try {
            fixture.pedestal().setStored(disc("empty-hand"));
            fixture.player().setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            use(fixture, ItemStack.EMPTY);
            helper.assertTrue(fixture.pedestal().getStored().isEmpty(), "空手回収後も台座にディスクが残る");
            helper.assertTrue(countTitle(fixture.player(), "empty-hand") == 1,
                    "空手回収でディスクがinventoryへ戻らない");

            fixture.pedestal().setStored(disc("creative-previous"));
            final ItemStack creativeReplacement = disc("creative-replacement");
            creativeReplacement.setCount(2);
            fixture.player().getAbilities().instabuild = true;
            fixture.player().setItemInHand(InteractionHand.MAIN_HAND, creativeReplacement);
            use(fixture, creativeReplacement);
            helper.assertTrue("creative-replacement".equals(fixture.pedestal().storedTitle()),
                    "クリエイティブ交換で台座が新しいディスクにならない");
            helper.assertTrue(creativeReplacement.getCount() == 2,
                    "クリエイティブ交換が手持ちディスクを消費した");
            helper.assertTrue(countTitle(fixture.player(), "creative-previous") == 1,
                    "クリエイティブ交換で旧ディスクが返却されない");
        } finally {
            fixture.cleanup();
        }
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100, batch = "discPedestalInteraction")
    *///?}
    public static void selectionOutlineIsWholeAndTrackMetadataFollowsSwap(GameTestHelper helper) {
        final Fixture fixture = fixture(helper, "pedestal-outline-metadata");
        try {
            final ServerLevel level = fixture.helper().getLevel();
            final BlockPos absolutePos = fixture.helper().absolutePos(fixture.relativePos());
            final DiscPedestalBlock block = (DiscPedestalBlock) ModBlocks.DISC_PEDESTAL.get();
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                final var state = block.defaultBlockState().setValue(DiscPedestalBlock.FACING, facing);
                final VoxelShape selection = block.getShape(state, level, absolutePos, CollisionContext.empty());
                final VoxelShape collision = block.getCollisionShape(state, level, absolutePos, CollisionContext.empty());
                helper.assertTrue(selection.toAabbs().size() == 1,
                        "台座の選択枠が " + facing + " で一体の箱にならない");
                helper.assertTrue(collision.toAabbs().size() > 1,
                        "台座の " + facing + " の物理当たり判定まで単純な箱へ変わった");
            }

            fixture.pedestal().setStored(disc("first-title", "First Artist"));
            helper.assertTrue("first-title".equals(fixture.pedestal().storedTitle())
                            && "First Artist".equals(fixture.pedestal().storedAuthor()),
                    "台座が保存済みディスクの曲名とアーティスト名を両方読めない");

            final ItemStack replacement = disc("second-title", "Second Artist");
            fixture.player().setItemInHand(InteractionHand.MAIN_HAND, replacement);
            use(fixture, replacement);
            helper.assertTrue("second-title".equals(fixture.pedestal().storedTitle())
                            && "Second Artist".equals(fixture.pedestal().storedAuthor()),
                    "ディスク交換後の曲名またはアーティスト名が古いまま残る");

            fixture.pedestal().setStored(disc("title-without-author", ""));
            helper.assertTrue("title-without-author".equals(fixture.pedestal().storedTitle())
                            && fixture.pedestal().storedAuthor().isEmpty(),
                    "アーティスト名の無い曲へ架空の作者名を出す");
        } finally {
            fixture.cleanup();
        }
        helper.succeed();
    }

    private static Fixture fixture(GameTestHelper helper, String name) {
        final BlockPos relativePos = new BlockPos(2, 1, 2);
        helper.setBlock(relativePos, ModBlocks.DISC_PEDESTAL.get().defaultBlockState());
        final DiscPedestalBlockEntity pedestal = (DiscPedestalBlockEntity) helper.getLevel()
                .getBlockEntity(helper.absolutePos(relativePos));
        if (pedestal == null) {
            helper.fail("Disc Pedestal BEを生成できない", relativePos);
        }
        final FakePlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), name));
        final BlockPos absolutePos = helper.absolutePos(relativePos);
        player.setPos(absolutePos.getX() + 0.5, absolutePos.getY() + 1.0, absolutePos.getZ() + 1.5);
        return new Fixture(helper, relativePos, pedestal, player);
    }

    private static InteractionResult use(Fixture fixture, ItemStack held) {
        final ServerLevel level = fixture.helper().getLevel();
        final BlockPos absolutePos = fixture.helper().absolutePos(fixture.relativePos());
        final var state = level.getBlockState(absolutePos);
        final DiscPedestalBlock block = (DiscPedestalBlock) state.getBlock();
        final BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolutePos), Direction.UP,
                absolutePos, false);
        //? if >=1.21 {
        return block.useWithoutItem(state, level, absolutePos, fixture.player(), hit);
        //?} else {
        /*return block.use(state, level, absolutePos, fixture.player(), InteractionHand.MAIN_HAND, hit);
        *///?}
    }

    private static ItemStack disc(String title) {
        return disc(title, "tester");
    }

    private static ItemStack disc(String title, String author) {
        final ItemStack stack = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        stack.set(ModDataComponents.CUSTOM_TRACK.get(), new CustomTrackData(
                "https://example.invalid/" + title, title, author, 1_000L, "", false));
        return stack;
    }

    private static int countTitle(FakePlayer player, String title) {
        int count = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            final ItemStack stack = player.getInventory().getItem(slot);
            if (title.equals(DiscPedestalBlockEntity.titleOf(stack))) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int countDropped(Fixture fixture, String title) {
        return fixture.helper().getLevel().getEntitiesOfClass(ItemEntity.class,
                fixture.player().getBoundingBox().inflate(2.0),
                entity -> title.equals(DiscPedestalBlockEntity.titleOf(entity.getItem())))
                .stream().mapToInt(entity -> entity.getItem().getCount()).sum();
    }

    private record Fixture(GameTestHelper helper, BlockPos relativePos, DiscPedestalBlockEntity pedestal,
            FakePlayer player) {
        void cleanup() {
            final BlockPos absolutePos = helper.absolutePos(relativePos);
            helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(absolutePos).inflate(2.0))
                    .forEach(ItemEntity::discard);
            helper.getLevel().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(2.0))
                    .forEach(ItemEntity::discard);
            player.discard();
            helper.setBlock(relativePos, Blocks.AIR.defaultBlockState());
        }
    }
}
