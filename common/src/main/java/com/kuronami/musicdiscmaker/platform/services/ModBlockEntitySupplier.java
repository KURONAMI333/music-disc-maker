package com.kuronami.musicdiscmaker.platform.services;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * loader 非依存の BlockEntity ファクトリ。vanilla の {@code BlockEntityType.BlockEntitySupplier} は
 * package-private で common から参照できないため、共通の関数型 interface で受け渡し、
 * 各ローダーが自前の builder に適応する。
 */
@FunctionalInterface
public interface ModBlockEntitySupplier<T extends BlockEntity> {

    T create(BlockPos pos, BlockState state);
}
