package com.kuronami.musicdiscmaker.gametest;

//? if <1.21.11 {
/*import com.kuronami.musicdiscmaker.MusicDiscMaker;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
*///?}

import java.util.List;

import com.kuronami.musicdiscmaker.item.AlbumItem;
import com.kuronami.musicdiscmaker.register.ModCreativeTab;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * クリエイティブタブの表示順: 無色の Album の直後に 16 色、無色の Golden Album の直後に 16 色。
 * 色は item 側にだけ持たせ、品名は無色と同じ翻訳のままにする。
 */
//? if <1.21.11 {
/*@GameTestHolder(MusicDiscMaker.MODID)
*///?}
public final class CreativeTabOrderGameTests {
    private static final String TEMPLATE = "empty8x3x8";

    private CreativeTabOrderGameTests() {
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    *///?}
    public static void coloredAlbumsFollowUncoloredInDyeOrder(GameTestHelper helper) {
        final List<ItemStack> stacks = ModCreativeTab.displayStacks();

        final int album = indexOfUncolored(stacks, ModItems.ALBUM.get());
        final int golden = indexOfUncolored(stacks, ModItems.GOLDEN_ALBUM.get());
        helper.assertTrue(album >= 0, "無色の Album が表示順に無い");
        helper.assertTrue(golden >= 0, "無色の Golden Album が表示順に無い");

        final DyeColor[] colors = DyeColor.values();
        helper.assertTrue(colors.length == 16, "DyeColor が 16 色ではない");
        helper.assertTrue(album + 1 + colors.length <= stacks.size(), "Album の 16 色が表示順に収まらない");
        helper.assertTrue(golden + 1 + colors.length <= stacks.size(), "Golden Album の 16 色が表示順に収まらない");

        for (int i = 0; i < colors.length; i++) {
            final ItemStack albumColor = stacks.get(album + 1 + i);
            helper.assertTrue(albumColor.is(ModItems.ALBUM.get()), "Album の " + i + " 番目が Album でない");
            helper.assertTrue(AlbumItem.getColor(albumColor) == colors[i],
                    "Album の色順が DyeColor の定義順と違う: " + i);

            final ItemStack goldenColor = stacks.get(golden + 1 + i);
            helper.assertTrue(goldenColor.is(ModItems.GOLDEN_ALBUM.get()),
                    "Golden Album の " + i + " 番目が Golden Album でない");
            helper.assertTrue(AlbumItem.getColor(goldenColor) == colors[i],
                    "Golden Album の色順が DyeColor の定義順と違う: " + i);
        }

        // 10 個の通常項目 + 16 色 x 2。無色の直後以外に色付きが混ざっていないことも兼ねる。
        helper.assertTrue(stacks.size() == 10 + colors.length * 2,
                "表示順の総数が想定と違う: " + stacks.size());
        helper.assertTrue(stacks.get(album).getHoverName().equals(stacks.get(album + 1).getHoverName()),
                "色付き Album の名前が無色と違う");
        helper.assertTrue(stacks.get(golden).getHoverName().equals(stacks.get(golden + 1).getHoverName()),
                "色付き Golden Album の名前が無色と違う");
        helper.succeed();
    }

    private static int indexOfUncolored(List<ItemStack> stacks, Item item) {
        for (int i = 0; i < stacks.size(); i++) {
            final ItemStack stack = stacks.get(i);
            if (stack.is(item) && AlbumItem.getColor(stack) == null) {
                return i;
            }
        }
        return -1;
    }
}
