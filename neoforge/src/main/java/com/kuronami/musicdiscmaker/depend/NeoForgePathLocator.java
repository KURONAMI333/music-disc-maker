package com.kuronami.musicdiscmaker.depend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.locating.IModFile;

/**
 * NeoForge での mod jar 内フォルダ特定。{@code META-INF/services} で {@link DependencyManager.PathLocator}
 * として登録される。
 *
 * <p>26.1: {@code IModFile.findResource} は廃止。{@code getContents().getContentRoots()} (jar 内
 * ファイルシステムのルート) に対し folder を解決して walkable な {@link Path} を返す。
 */
public class NeoForgePathLocator implements DependencyManager.PathLocator {

    @Override
    public Path locate(String modid, String folder) throws IOException, IllegalStateException {
        final IModFile modFile = ModList.get().getModFileById(modid).getFile();
        for (final Path root : modFile.getContents().getContentRoots()) {
            final Path candidate = root.resolve(folder);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IOException("mod jar 内に " + folder + " フォルダが見つからない");
    }
}
