package com.kuronami.musicdiscmaker.platform;

import com.kuronami.musicdiscmaker.platform.services.IPlatformHelper;
import com.kuronami.musicdiscmaker.platform.services.ModBlockEntitySupplier;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;

public class NeoForgePlatformHelper implements IPlatformHelper {

    @Override
    public <T extends BlockEntity> BlockEntityType<T> createBlockEntityType(ModBlockEntitySupplier<T> supplier, Block block) {
        return new BlockEntityType<>(supplier::create, java.util.Set.of(block));
    }

    @Override
    public String getPlatformName() {
        return "NeoForge";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLEnvironment.isProduction();
    }
}
