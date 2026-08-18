package com.kuronami.musicdiscmaker.depend;

import java.nio.file.Path;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Fabric での mod jar 内フォルダ特定。{@code META-INF/services} で {@link DependencyManager.PathLocator}
 * として登録される。
 */
public class FabricPathLocator implements DependencyManager.PathLocator {

    @Override
    public Path locate(String modid, String folder) {
        return FabricLoader.getInstance()
                .getModContainer(modid)
                .orElseThrow(() -> new IllegalStateException("Mod container not found: " + modid))
                .findPath(folder)
                .orElseThrow(() -> new IllegalStateException("Path " + folder + " not found inside the mod jar of " + modid));
    }
}
