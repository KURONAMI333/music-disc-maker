package com.kuronami.musicdiscmaker.gametest;

//? if >=1.21 {
import java.util.Arrays;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.MusicDiscMakerBlockEntity;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.menu.MusicDiscMakerMenu;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
//? if >=1.21.2 {
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
//?} else {
//?}
//? if <1.21.2 {
/*import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
*/
//?}

/** Music Disc Maker が通常の空ディスクから単曲盤だけを作る契約を固定する。 */
public final class MusicDiscMakerSingleDiscGameTests {
    private static final BlockPos MAKER_POS = new BlockPos(3, 1, 3);
    private static final BlockPos NEIGHBOR_MAKER_POS = new BlockPos(4, 1, 3);

    private MusicDiscMakerSingleDiscGameTests() {
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    */
    //?}
    public static void createsOnlyCanonicalSingleDiscAndKeepsInputsAtomic(GameTestHelper helper) {
        final MusicDiscMakerBlockEntity maker = maker(helper);
        final CustomTrackData expected = track(1);
        helper.assertTrue(MusicDiscMakerBlockEntity.isBlankDisc(new ItemStack(ModItems.BLANK_DISC.get()))
                        && !MusicDiscMakerBlockEntity.isBlankDisc(new ItemStack(Items.STONE)),
                "Makerの入力判定が通常の空ディスクだけを受け入れない");

        maker.setResolvedTrack(expected, "cache-only");
        maker.setItem(MusicDiscMakerBlockEntity.SLOT_INPUT, new ItemStack(ModItems.BLANK_DISC.get(), 2));
        helper.assertTrue(maker.createDisc(), "通常の空ディスクから単曲盤を作成できない");
        final ItemStack output = maker.getItem(MusicDiscMakerBlockEntity.SLOT_OUTPUT);
        helper.assertTrue(output.is(ModItems.CUSTOM_MUSIC_DISC.get())
                        && expected.equals(output.get(ModDataComponents.CUSTOM_TRACK.get())),
                "完成盤に正準の単曲データが入っていない");
        helper.assertTrue(!output.has(ModDataComponents.PLAYLIST_TRACKS.get()),
                "新規の通常盤へ旧playlist_tracks componentを書いた");
        helper.assertTrue(maker.getItem(MusicDiscMakerBlockEntity.SLOT_INPUT).getCount() == 1,
                "作成成功時に空ディスクをちょうど1枚消費していない");

        maker.clearContent();
        maker.setResolvedTrack(track(2), "blocked-output");
        maker.setItem(MusicDiscMakerBlockEntity.SLOT_INPUT, new ItemStack(ModItems.BLANK_DISC.get(), 2));
        maker.setItem(MusicDiscMakerBlockEntity.SLOT_OUTPUT, new ItemStack(Items.STONE));
        helper.assertFalse(maker.createDisc(), "出力詰まりでも作成を受理した");
        helper.assertTrue(maker.getItem(MusicDiscMakerBlockEntity.SLOT_INPUT).getCount() == 2,
                "出力詰まりで入力ディスクを消費した");
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    */
    //?}
    public static void menuOnlyExposesTwoMachineSlotsAndRecoversLegacyMaterialOnce(GameTestHelper helper) {
        final Fixture fixture = fixture(helper, "single-disc-menu");
        try {
            final MusicDiscMakerBlockEntity maker = fixture.maker();
            final FakePlayer player = fixture.player();
            final MusicDiscMakerMenu menu = new MusicDiscMakerMenu(71, player.getInventory(), maker);

            helper.assertTrue(menu.slots.size() == 38, "Maker GUIが機械2枠+inventory36枠になっていない");
            helper.assertTrue(menu.getSlot(0).mayPlace(new ItemStack(ModItems.BLANK_DISC.get())),
                    "手動入力slotが通常の空ディスクを受け入れない");
            helper.assertFalse(menu.getSlot(0).mayPlace(new ItemStack(Items.STONE)), "手動入力slotが石を受け入れた");
            helper.assertTrue(Arrays.equals(new int[] { 0, 1 }, maker.getSlotsForFace(Direction.DOWN)),
                    "自動化へ旧slot2を公開している");
            helper.assertFalse(maker.canPlaceItem(2, new ItemStack(ModItems.BLANK_DISC.get())),
                    "旧slot2へ新規入力できる");

            player.getInventory().setItem(9, new ItemStack(ModItems.BLANK_DISC.get()));
            helper.assertFalse(menu.quickMoveStack(player, 2).isEmpty(), "shift-clickで空ディスクを入力へ送れない");
            helper.assertTrue(maker.getItem(MusicDiscMakerBlockEntity.SLOT_INPUT).is(ModItems.BLANK_DISC.get()),
                    "shift-clickが空ディスクを入力slotへ置かない");
            maker.clearContent();

            maker.setItem(2, new ItemStack(Items.REDSTONE, 2));
            new MusicDiscMakerMenu(72, player.getInventory(), maker);
            helper.assertTrue(maker.getItem(2).isEmpty() && count(player, Items.REDSTONE) == 2,
                    "旧slot材料を最初のmenu生成でplayerへ一度返せない");
            new MusicDiscMakerMenu(73, player.getInventory(), maker);
            helper.assertTrue(count(player, Items.REDSTONE) == 2, "旧slot材料をmenu再生成で重複返却した");

            fillInventory(player);
            maker.setItem(2, new ItemStack(Items.REDSTONE, 3));
            new MusicDiscMakerMenu(74, player.getInventory(), maker);
            helper.assertTrue(maker.getItem(2).isEmpty() && dropped(helper, player, Items.REDSTONE) == 3,
                    "満杯inventoryで旧slot材料をvoidせずdropへ回さない");
        } finally {
            fixture.cleanup();
        }
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    */
    //?}
    public static void destroyingMakerDropsEveryStoredStackExactlyOnce(GameTestHelper helper) {
        final MusicDiscMakerBlockEntity maker = maker(helper);
        helper.setBlock(NEIGHBOR_MAKER_POS, ModBlocks.MUSIC_DISC_MAKER.get().defaultBlockState());
        final MusicDiscMakerBlockEntity neighbor = (MusicDiscMakerBlockEntity) helper.getLevel()
                .getBlockEntity(helper.absolutePos(NEIGHBOR_MAKER_POS));
        final BlockPos makerPos = helper.absolutePos(MAKER_POS);
        final CustomTrackData expectedTrack = track(90);
        final ItemStack output = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        output.set(ModDataComponents.CUSTOM_TRACK.get(), expectedTrack);
        try {
            helper.assertTrue(neighbor != null, "破壊番兵の隣Maker BEを生成できていない");
            neighbor.setItem(MusicDiscMakerBlockEntity.SLOT_INPUT, new ItemStack(ModItems.BLANK_DISC.get()));
            maker.setItem(MusicDiscMakerBlockEntity.SLOT_INPUT, new ItemStack(ModItems.BLANK_DISC.get(), 2));
            maker.setItem(MusicDiscMakerBlockEntity.SLOT_OUTPUT, output);
            maker.setItem(2, new ItemStack(Items.REDSTONE, 3));

            helper.assertTrue(helper.getLevel().destroyBlock(makerPos, true), "Makerを通常破壊できない");
            helper.assertTrue(neighbor.getItem(MusicDiscMakerBlockEntity.SLOT_INPUT).is(ModItems.BLANK_DISC.get()),
                    "Maker破壊が隣のMakerの内容に波及した");
            helper.assertTrue(droppedAt(helper, makerPos, ModItems.BLANK_DISC.get()) == 2,
                    "破壊時に入力blank_discを一度ずつdropしない");
            helper.assertTrue(droppedTrackAt(helper, makerPos, expectedTrack) == 1,
                    "破壊時に出力custom discのmetadataを一度だけ維持してdropしない");
            helper.assertTrue(droppedAt(helper, makerPos, Items.REDSTONE) == 3,
                    "破壊時に隠しlegacy redstoneを一度ずつdropしない");
        } finally {
            helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(makerPos).inflate(3.0D))
                    .forEach(ItemEntity::discard);
            if (neighbor != null) neighbor.clearContent();
            helper.setBlock(MAKER_POS, Blocks.AIR.defaultBlockState());
            helper.setBlock(NEIGHBOR_MAKER_POS, Blocks.AIR.defaultBlockState());
        }
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8")
    */
    //?}
    public static void singleDiscAndResolvedCacheSurviveSaveReload(GameTestHelper helper) {
        final MusicDiscMakerBlockEntity maker = maker(helper);
        final CustomTrackData expected = track(10);
        maker.setResolvedTrack(expected, "cache-only");
        maker.setItem(MusicDiscMakerBlockEntity.SLOT_INPUT, new ItemStack(ModItems.BLANK_DISC.get()));
        helper.assertTrue(maker.createDisc(), "保存前の単曲盤を作成できない");

        final ServerLevel level = helper.getLevel();
        final CompoundTag saved = maker.saveCustomOnly(level.registryAccess());
        final MusicDiscMakerBlockEntity restored = new MusicDiscMakerBlockEntity(maker.getBlockPos(), maker.getBlockState());
        //? if >=1.21.2 {
        restored.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), saved));
        //?} else {
        /*restored.loadWithComponents(saved, level.registryAccess());
        */
        //?}
        final ItemStack restoredDisc = restored.getItem(MusicDiscMakerBlockEntity.SLOT_OUTPUT);
        helper.assertTrue(expected.equals(restoredDisc.get(ModDataComponents.CUSTOM_TRACK.get()))
                        && !restoredDisc.has(ModDataComponents.PLAYLIST_TRACKS.get()),
                "保存再読込で単曲盤が変化した、または旧playlist componentが増えた");
        helper.assertTrue(expected.equals(restored.getResolvedTrack())
                        && "cache-only".equals(restored.getResolvedForUrl()),
                "保存再読込で解決済み単曲cacheが変化した");

        final CompoundTag trialCache = saved.copy();
        trialCache.putString("url", "https://example.invalid/playlist");
        trialCache.putString("resolvedForUrl", "https://example.invalid/playlist");
        trialCache.put("tracks", CustomTrackData.CODEC.listOf()
                .encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, java.util.List.of(expected, track(11)))
                .result().orElseThrow());
        //? if >=1.21.2 {
        restored.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), trialCache));
        //?} else {
        /*restored.loadWithComponents(trialCache, level.registryAccess());
        */
        //?}
        restored.setItem(MusicDiscMakerBlockEntity.SLOT_OUTPUT, ItemStack.EMPTY);
        restored.setItem(MusicDiscMakerBlockEntity.SLOT_INPUT, new ItemStack(ModItems.BLANK_DISC.get()));
        helper.assertTrue(!restored.hasResolvedTrack() && restored.getResolvedForUrl().isEmpty()
                        && !restored.createDisc()
                        && restored.getItem(MusicDiscMakerBlockEntity.SLOT_INPUT).getCount() == 1
                        && restored.getItem(MusicDiscMakerBlockEntity.SLOT_OUTPUT).isEmpty(),
                "旧多曲cacheが単曲解決を迂回して空ディスクを消費した");
        helper.succeed();
    }

    private static MusicDiscMakerBlockEntity maker(GameTestHelper helper) {
        helper.setBlock(MAKER_POS, ModBlocks.MUSIC_DISC_MAKER.get().defaultBlockState());
        final MusicDiscMakerBlockEntity maker = (MusicDiscMakerBlockEntity) helper.getLevel()
                .getBlockEntity(helper.absolutePos(MAKER_POS));
        helper.assertTrue(maker != null, "Music Disc Maker BEを生成できていない");
        return maker;
    }

    private static CustomTrackData track(int index) {
        return new CustomTrackData("https://example.invalid/" + index, "Track " + index,
                "MDM", 60_000L, "", false);
    }

    private static void fillInventory(FakePlayer player) {
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            player.getInventory().setItem(slot, new ItemStack(Items.STONE, Items.STONE.getDefaultMaxStackSize()));
        }
    }

    private static int count(FakePlayer player, Item item) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            final ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    private static int dropped(GameTestHelper helper, FakePlayer player, Item item) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new AABB(player.blockPosition()).inflate(4.0D), entity -> entity.getItem().is(item))
                .stream().mapToInt(entity -> entity.getItem().getCount()).sum();
    }

    private static int droppedAt(GameTestHelper helper, BlockPos pos, Item item) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2.0D),
                entity -> entity.getItem().is(item)).stream().mapToInt(entity -> entity.getItem().getCount()).sum();
    }

    private static int droppedTrackAt(GameTestHelper helper, BlockPos pos, CustomTrackData track) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2.0D),
                entity -> track.equals(entity.getItem().get(ModDataComponents.CUSTOM_TRACK.get())))
                .stream().mapToInt(entity -> entity.getItem().getCount()).sum();
    }

    private static Fixture fixture(GameTestHelper helper, String name) {
        final MusicDiscMakerBlockEntity maker = maker(helper);
        final FakePlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(UUID.randomUUID(), name));
        final BlockPos absolutePos = helper.absolutePos(MAKER_POS);
        player.setPos(absolutePos.getX() + 0.5D, absolutePos.getY() + 0.5D, absolutePos.getZ() + 1.5D);
        return new Fixture(helper, maker, player);
    }

    private record Fixture(GameTestHelper helper, MusicDiscMakerBlockEntity maker, FakePlayer player) {
        void cleanup() {
            helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                    new AABB(helper.absolutePos(MAKER_POS)).inflate(2.0D)).forEach(ItemEntity::discard);
            helper.getLevel().getEntitiesOfClass(ItemEntity.class, player.getBoundingBox().inflate(2.0D))
                    .forEach(ItemEntity::discard);
            player.discard();
            helper.setBlock(MAKER_POS, Blocks.AIR.defaultBlockState());
        }
    }
}
//?} else {
//?}
