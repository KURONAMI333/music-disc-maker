package com.kuronami.musicdiscmaker.platform.services;

public interface IPlatformHelper {
    default boolean isMovingAudioSource(net.minecraft.server.level.ServerLevel level,
            net.minecraft.core.BlockPos pos) {
        return false;
    }

    /** 保存座標と異なる空間にある音源の、server上の可聴距離判定に使う実座標。 */
    default net.minecraft.world.phys.Vec3 audioSourcePosition(net.minecraft.server.level.ServerLevel level,
            net.minecraft.core.BlockPos pos) {
        return new net.minecraft.world.phys.Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    /** BlockEntityType generation delegated to loader (vanilla BlockEntitySupplier is package-private). */
    <T extends net.minecraft.world.level.block.entity.BlockEntity> net.minecraft.world.level.block.entity.BlockEntityType<T> createBlockEntityType(ModBlockEntitySupplier<T> supplier, net.minecraft.world.level.block.Block block);

    /**
     * Gets the name of the current platform
     *
     * @return The name of the current platform.
     */
    String getPlatformName();

    /**
     * Checks if a mod with the given id is loaded.
     *
     * @param modId The mod to check if it is loaded.
     * @return True if the mod is loaded, false otherwise.
     */
    boolean isModLoaded(String modId);

    /**
     * Check if the game is currently in a development environment.
     *
     * @return True if in a development environment, false otherwise.
     */
    boolean isDevelopmentEnvironment();

    /**
     * Gets the name of the environment type as a string.
     *
     * @return The name of the environment type.
     */
    default String getEnvironmentName() {

        return isDevelopmentEnvironment() ? "development" : "production";
    }
}
