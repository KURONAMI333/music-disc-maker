package com.kuronami.musicdiscmaker.platform;

import com.kuronami.musicdiscmaker.platform.services.IPlatformHelper;
import com.kuronami.musicdiscmaker.platform.services.ModBlockEntitySupplier;

import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
//? if >=1.21.2 {
//?} else {
/*import net.fabricmc.loader.api.FabricLoader;
*/
//?}
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
//? if >=1.21.2 {
import net.fabricmc.loader.api.FabricLoader;
//?} else {
//?}

public class FabricPlatformHelper implements IPlatformHelper {

    //? if <1.21 {
    /*@Override
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
    *///?}

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
