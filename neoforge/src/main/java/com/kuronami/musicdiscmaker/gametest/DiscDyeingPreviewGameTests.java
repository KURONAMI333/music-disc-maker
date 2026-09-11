package com.kuronami.musicdiscmaker.gametest;

import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.DiscDyeingTableBlockEntity;
import com.kuronami.musicdiscmaker.color.DiscDye;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.DiscDyeData;
import com.kuronami.musicdiscmaker.menu.DiscDyeingTableMenu;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
//? if >=1.21.2 {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}
//? if <1.21.2 {
/*import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
*///?}
public final class DiscDyeingPreviewGameTests {
    private DiscDyeingPreviewGameTests() { }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100, batch = "discDyeingPreview")
    *///?}
    public static void previewUsesDyedResultWithoutChangingInputMetadata(GameTestHelper helper) {
        final BlockPos tablePos = new BlockPos(2, 1, 2);
        helper.setBlock(tablePos, ModBlocks.DISC_DYEING_TABLE.get());
        final DiscDyeingTableBlockEntity table = (DiscDyeingTableBlockEntity) helper.getLevel()
                .getBlockEntity(helper.absolutePos(tablePos));
        helper.assertTrue(table != null, "Disc Dyeing Table BEを生成できない");
        final FakePlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "dye-preview"));
        try {
            final CustomTrackData track = new CustomTrackData(
                    "https://example.invalid/dye-preview", "Preview", "tester", 120_000L, "", false);
            final DiscDyeData inputDye = new DiscDyeData(DiscDye.BLUE, DiscDye.PINK);
            final DiscDyeData resultDye = new DiscDyeData(DiscDye.MAGENTA, DiscDye.ORANGE);
            final ItemStack input = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
            input.set(ModDataComponents.CUSTOM_TRACK.get(), track);
            input.set(ModDataComponents.DISC_DYE.get(), inputDye);
            table.setItem(DiscDyeingTableBlockEntity.SLOT_INPUT, input);
            table.setItem(DiscDyeingTableBlockEntity.SLOT_DYE_BOARD, vanillaDye("magenta_dye"));
            table.setItem(DiscDyeingTableBlockEntity.SLOT_DYE_ACCENT, vanillaDye("orange_dye"));

            final DiscDyeingTableMenu menu = new DiscDyeingTableMenu(61, player.getInventory(), table);
            final ItemStack preview = menu.getPreviewStack();
            helper.assertTrue(resultDye.equals(preview.get(ModDataComponents.DISC_DYE.get())),
                    "中央previewが染色後の出力色を使わない");
            helper.assertTrue(track.equals(preview.get(ModDataComponents.CUSTOM_TRACK.get())),
                    "中央preview用の結果が曲metadataを変えた");
            helper.assertTrue(inputDye.equals(input.get(ModDataComponents.DISC_DYE.get()))
                            && track.equals(input.get(ModDataComponents.CUSTOM_TRACK.get())),
                    "preview生成が入力の色または曲metadataを書き換えた");
        } finally {
            player.discard();
            helper.setBlock(tablePos, Blocks.AIR.defaultBlockState());
        }
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100, batch = "discDyeingPreview")
    *///?}
    public static void eitherDyeChangesOnlyItsRegionAndConsumesIndependently(GameTestHelper helper) {
        final BlockPos tablePos = new BlockPos(2, 1, 2);
        helper.setBlock(tablePos, ModBlocks.DISC_DYEING_TABLE.get());
        final DiscDyeingTableBlockEntity table = (DiscDyeingTableBlockEntity) helper.getLevel()
                .getBlockEntity(helper.absolutePos(tablePos));
        helper.assertTrue(table != null, "Disc Dyeing Table BEを生成できない");
        try {
            final CustomTrackData track = new CustomTrackData(
                    "https://example.invalid/partial-dye", "Partial", "tester", 90_000L, "", false);
            final DiscDyeData originalDye = new DiscDyeData(DiscDye.BLUE, DiscDye.PINK);
            final ItemStack input = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get(), 2);
            input.set(ModDataComponents.CUSTOM_TRACK.get(), track);
            input.set(ModDataComponents.DISC_DYE.get(), originalDye);
            table.setItem(DiscDyeingTableBlockEntity.SLOT_INPUT, input);
            table.setItem(DiscDyeingTableBlockEntity.SLOT_DYE_BOARD, vanillaDye("magenta_dye", 2));

            ItemStack preview = table.getItem(DiscDyeingTableBlockEntity.SLOT_OUTPUT);
            helper.assertTrue(new DiscDyeData(DiscDye.MAGENTA, DiscDye.PINK)
                            .equals(preview.get(ModDataComponents.DISC_DYE.get())),
                    "左染料だけで盤面を替え、元のアクセントを保持できない");
            helper.assertTrue(track.equals(preview.get(ModDataComponents.CUSTOM_TRACK.get())),
                    "片側previewが曲metadataを失った");

            table.onResultTaken();
            helper.assertTrue(table.getItem(DiscDyeingTableBlockEntity.SLOT_INPUT).getCount() == 1,
                    "片側染色の取得で入力ディスクを1枚消費しない");
            helper.assertTrue(table.getItem(DiscDyeingTableBlockEntity.SLOT_DYE_BOARD).getCount() == 1,
                    "左染料を1個だけ消費しない");
            helper.assertTrue(table.getItem(DiscDyeingTableBlockEntity.SLOT_DYE_ACCENT).isEmpty(),
                    "空の右染料を消費した");

            table.setItem(DiscDyeingTableBlockEntity.SLOT_DYE_BOARD, ItemStack.EMPTY);
            table.setItem(DiscDyeingTableBlockEntity.SLOT_DYE_ACCENT, vanillaDye("orange_dye", 1));
            preview = table.getItem(DiscDyeingTableBlockEntity.SLOT_OUTPUT);
            helper.assertTrue(new DiscDyeData(DiscDye.BLUE, DiscDye.ORANGE)
                            .equals(preview.get(ModDataComponents.DISC_DYE.get())),
                    "右染料だけでアクセントを替え、元の盤面を保持できない");
            helper.assertTrue(originalDye.equals(input.get(ModDataComponents.DISC_DYE.get()))
                            && track.equals(input.get(ModDataComponents.CUSTOM_TRACK.get())),
                    "片側染色が入力ディスクを書き換えた");
        } finally {
            helper.setBlock(tablePos, Blocks.AIR.defaultBlockState());
        }
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100, batch = "discDyeingPreview")
    *///?}
    public static void undyedDiscKeepsTheMissingRegionAndShiftFillsBothDyeSlots(GameTestHelper helper) {
        final BlockPos tablePos = new BlockPos(2, 1, 2);
        helper.setBlock(tablePos, ModBlocks.DISC_DYEING_TABLE.get());
        final DiscDyeingTableBlockEntity table = (DiscDyeingTableBlockEntity) helper.getLevel()
                .getBlockEntity(helper.absolutePos(tablePos));
        helper.assertTrue(table != null, "Disc Dyeing Table BEを生成できない");
        final FakePlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "dye-partial-shift"));
        try {
            table.setItem(DiscDyeingTableBlockEntity.SLOT_INPUT,
                    new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get()));
            table.setItem(DiscDyeingTableBlockEntity.SLOT_DYE_ACCENT, vanillaDye("cyan_dye", 1));
            ItemStack preview = table.getItem(DiscDyeingTableBlockEntity.SLOT_OUTPUT);
            helper.assertTrue(new DiscDyeData(null, DiscDye.CYAN)
                            .equals(preview.get(ModDataComponents.DISC_DYE.get())),
                    "未染色盤へ右染料だけを適用した時、盤面を未指定のまま保持できない");

            table.setItem(DiscDyeingTableBlockEntity.SLOT_DYE_ACCENT, ItemStack.EMPTY);
            player.getInventory().setItem(9, vanillaDye("red_dye", 1));
            player.getInventory().setItem(10, vanillaDye("red_dye", 1));
            final DiscDyeingTableMenu menu = new DiscDyeingTableMenu(62, player.getInventory(), table);
            helper.assertTrue(!menu.quickMoveStack(player, 4).isEmpty(),
                    "1個目のShift投入が左染料枠へ入らない");
            helper.assertTrue(!menu.quickMoveStack(player, 5).isEmpty(),
                    "同色2個目のShift投入が右染料枠へ入らない");
            helper.assertTrue(table.getItem(DiscDyeingTableBlockEntity.SLOT_DYE_BOARD).getCount() == 1
                            && table.getItem(DiscDyeingTableBlockEntity.SLOT_DYE_ACCENT).getCount() == 1,
                    "Shift投入が空の右枠より左スタックへの合流を優先した");
        } finally {
            player.discard();
            helper.setBlock(tablePos, Blocks.AIR.defaultBlockState());
        }
        helper.succeed();
    }

    /** 26.2 は個別染料を {@code Items} のpublic定数に持たないため、vanilla item registryから取る。 */
    private static ItemStack vanillaDye(String path) {
        return vanillaDye(path, 1);
    }

    private static ItemStack vanillaDye(String path, int count) {
        //? if >=1.21.2 {
        final var item = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(path));
        //?} else {
        /*final var item = BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("minecraft", path));
        *///?}
        if (item == null) {
            throw new IllegalStateException("Missing vanilla dye: " + path);
        }
        return new ItemStack(item, count);
    }
}
