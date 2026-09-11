package com.kuronami.musicdiscmaker.depend;

import java.io.IOException;
import java.nio.file.Path;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.locating.IModFile;

/**
 * Forge での mod jar 内フォルダ特定。{@code META-INF/services} で {@link DependencyManager.PathLocator}
 * として登録される。
 *
 * <p>{@code IModFile.findResource} が返す {@link Path} の寿命は loader が握っているので
 * {@link LocatedFolder#borrowed} で借りるだけにする。
 */
public class ForgePathLocator implements DependencyManager.PathLocator {

    @Override
    public LocatedFolder locate(String modid, String folder) throws IOException, IllegalStateException {
        final IModFile modFile = ModList.get().getModFileById(modid).getFile();
        return LocatedFolder.borrowed(modFile.findResource(folder));
    }
}
