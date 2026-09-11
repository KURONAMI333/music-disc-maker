package com.kuronami.musicdiscmaker.platform;

import com.kuronami.musicdiscmaker.platform.services.IPlatformHelper;
import com.kuronami.musicdiscmaker.platform.services.ModBlockEntitySupplier;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import net.neoforged.fml.ModList;
//? if >=1.21.2 {
import net.neoforged.fml.loading.FMLEnvironment;
//?} else {
/*import net.neoforged.fml.loading.FMLLoader;
*/
//?}

public class NeoForgePlatformHelper implements IPlatformHelper {

    @Override
    public <T extends BlockEntity> BlockEntityType<T> createBlockEntityType(ModBlockEntitySupplier<T> supplier, Block block) {
        //? if >=1.21.2 {
        return new BlockEntityType<>(supplier::create, java.util.Set.of(block));
        //?} else {
/*        return BlockEntityType.Builder.<T>of(supplier::create, block).build(null);
        */
        //?}
    }

    @Override
    public net.minecraft.world.phys.Vec3 audioSourcePosition(net.minecraft.server.level.ServerLevel level,
            net.minecraft.core.BlockPos pos) {
        //? if <1.21.2 {
        /*if (isModLoaded("sable")) {
            return com.kuronami.musicdiscmaker.compat.aeronautics.SableServerAudio.sourcePosition(level, pos);
        }
        *///?}
        return net.minecraft.world.phys.Vec3.atCenterOf(pos);
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
        //? if >=1.21.2 {
        return !FMLEnvironment.isProduction();
        //?} else {
/*        return !FMLLoader.isProduction();
        */
        //?}
    }
}

