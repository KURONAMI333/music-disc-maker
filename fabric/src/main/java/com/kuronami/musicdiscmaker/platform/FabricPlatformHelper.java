package com.kuronami.musicdiscmaker.platform;

import com.kuronami.musicdiscmaker.platform.services.IPlatformHelper;
import com.kuronami.musicdiscmaker.platform.services.ModBlockEntitySupplier;

import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

public class FabricPlatformHelper implements IPlatformHelper {

    @Override
    public <T extends BlockEntity> BlockEntityType<T> createBlockEntityType(ModBlockEntitySupplier<T> supplier, Block block) {

        return FabricBlockEntityTypeBuilder.<T>create((pos, state) -> supplier.create(pos, state), block).build();
    }

    @Override
    public String getPlatformName() {

        return "Fabric";
    }

    @Override
    public boolean isModLoaded(String modId) {

        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {

        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }
}
