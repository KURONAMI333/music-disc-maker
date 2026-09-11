package com.kuronami.musicdiscmaker.client;

import com.kuronami.musicdiscmaker.item.AlbumItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;

/** Cover and spine share one dye; the sleeves have no tint index. */
public final class AlbumDyeTint {
    public static final int LAYERS = 2;
    private static final int DEFAULT_COVER = 0x765640;

    private AlbumDyeTint() { }

    public static int color(ItemStack stack, int index) {
        if (index < 0 || index >= LAYERS) return 0xFFFFFFFF;
        final DyeColor dye = AlbumItem.getColor(stack);
        final int rgb = dye == null ? DEFAULT_COVER : dye.getTextColor();
        if (index == 0) return 0xFF000000 | rgb;
        // A darker spine preserves the same hue without a second dye input.
        final int r = ((rgb >> 16) & 255) * 3 / 4;
        final int g = ((rgb >> 8) & 255) * 3 / 4;
        final int b = (rgb & 255) * 3 / 4;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
