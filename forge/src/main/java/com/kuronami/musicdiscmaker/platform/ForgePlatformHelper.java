package com.kuronami.musicdiscmaker.platform;

import com.kuronami.musicdiscmaker.platform.services.IPlatformHelper;
import com.kuronami.musicdiscmaker.platform.services.ModBlockEntitySupplier;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;

public class ForgePlatformHelper implements IPlatformHelper {

    @Override
    public boolean isMovingAudioSource(net.minecraft.server.level.ServerLevel level,
            net.minecraft.core.BlockPos pos) {
        return isModLoaded("valkyrienskies")
                && com.kuronami.musicdiscmaker.compat.valkyrienskies.VS2ServerAudio.isMovingSource(level, pos);
    }

    @Override
    public net.minecraft.world.phys.Vec3 audioSourcePosition(net.minecraft.server.level.ServerLevel level,
            net.minecraft.core.BlockPos pos) {
        if (isModLoaded("valkyrienskies")) {
            return com.kuronami.musicdiscmaker.compat.valkyrienskies.VS2ServerAudio.worldPosition(level, pos);
        }
        return IPlatformHelper.super.audioSourcePosition(level, pos);
    }

    @Override
    public <T extends BlockEntity> BlockEntityType<T> createBlockEntityType(ModBlockEntitySupplier<T> supplier, Block block) {

        return BlockEntityType.Builder.<T>of(supplier::create, block).build(null);
    }

    @Override
    public String getPlatformName() {

        return "Forge";
    }

    @Override
    public boolean isModLoaded(String modId) {

        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {

        return !FMLLoader.isProduction();
    }
}
