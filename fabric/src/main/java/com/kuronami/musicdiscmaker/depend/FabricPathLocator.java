package com.kuronami.musicdiscmaker.depend;

import java.nio.file.Path;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Fabric での mod jar 内フォルダ特定。{@code META-INF/services} で {@link DependencyManager.PathLocator}
 * として登録される。
 *
 * <p>{@code ModContainer.findPath} は jar の中を指す {@link Path} を返すが、その
 * FileSystem は Fabric loader が mod の寿命ぶん開いたままにしている。こちらが閉じると
 * loader 側の Path まで死ぬので {@link LocatedFolder#borrowed} で借りるだけにする。
 */
public class FabricPathLocator implements DependencyManager.PathLocator {

    @Override
    public LocatedFolder locate(String modid, String folder) {
        final Path path = FabricLoader.getInstance()
                .getModContainer(modid)
                //? if >=1.21.2 {
                .orElseThrow(() -> new IllegalStateException("Mod container not found: " + modid))
                //?} elif >=1.21 {
                /*.orElseThrow(() -> new IllegalStateException("Mod container not found: " + modid))
                *///?} else {
                /*.orElseThrow(() -> new IllegalStateException("Mod container not found: " + modid))
                *///?}
                .findPath(folder)
                //? if >=1.21.2 {
                .orElseThrow(() -> new IllegalStateException("Path " + folder + " not found inside the mod jar of " + modid));
                //?} elif >=1.21 {
                /*.orElseThrow(() -> new IllegalStateException("Path " + folder + " not found inside the mod jar of " + modid));
                *///?} else {
                /*.orElseThrow(() -> new IllegalStateException("Path " + folder + " not found inside the mod jar of " + modid));
                *///?}
        return LocatedFolder.borrowed(path);
    }
}
