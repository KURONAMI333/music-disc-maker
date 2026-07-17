package com.kuronami.musicdiscmaker.depend;

import java.io.IOException;
import java.nio.file.Path;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.forgespi.locating.IModFile;

/**
 * Forge での mod jar 内フォルダ特定。{@code META-INF/services} で {@link DependencyManager.PathLocator}
 * として登録される。
 */
public class ForgePathLocator implements DependencyManager.PathLocator {

    @Override
    public Path locate(String modid, String folder) throws IOException, IllegalStateException {
        final IModFile modFile = ModList.get().getModFileById(modid).getFile();
        return modFile.findResource(folder);
    }
}
