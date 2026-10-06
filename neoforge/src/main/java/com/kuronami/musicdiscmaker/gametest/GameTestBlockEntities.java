package com.kuronami.musicdiscmaker.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Loader-version bridge for GameTestHelper's typed block-entity lookup. */
public final class GameTestBlockEntities {
    private GameTestBlockEntities() { }

    public static <T extends BlockEntity> T get(GameTestHelper helper, BlockPos relativePos, Class<T> type) {
        //? if >=1.21.11 {
        return helper.getBlockEntity(relativePos, type);
        //?} else {
        /*return type.cast(helper.getBlockEntity(relativePos));
        *///?}
    }
}
