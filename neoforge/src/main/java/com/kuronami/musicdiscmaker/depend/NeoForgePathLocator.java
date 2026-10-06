package com.kuronami.musicdiscmaker.depend;

import java.io.IOException;
import java.nio.file.Path;
//? if >=1.21.11 {
import java.util.Optional;
//?} else {
//?}

import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.locating.IModFile;

/**
 * NeoForge での mod jar 内フォルダ特定。{@code META-INF/services} で {@link DependencyManager.PathLocator}
 * として登録される。
 *
 * <p>1.21.11 以降は {@code IModFile.findResource} が無いので
 * {@code getContents().getContentRoots()} を使う。<b>content root は production では
 * mod jar そのもののパス</b>で、ディレクトリではない (dev だけが展開済みディレクトリ)。
 * その段差の吸収は {@link JarFolders} が持つ。
 *
 * <p>1.21.1 / 1.21.4 の帯には {@code findResource} が在るのでそちらを使う。返る {@link Path} の寿命は
 * loader が握っているので {@link LocatedFolder#borrowed} で借りるだけにする。
 */
public class NeoForgePathLocator implements DependencyManager.PathLocator {

    @Override
    public LocatedFolder locate(String modid, String folder) throws IOException, IllegalStateException {
        final IModFile modFile = ModList.get().getModFileById(modid).getFile();
        //? if >=1.21.11 {
        for (final Path root : modFile.getContents().getContentRoots()) {
            final Optional<LocatedFolder> found = JarFolders.open(root, folder);
            if (found.isPresent()) {
                return found.get();
            }
        }
        throw new IOException("Path " + folder + " not found inside the mod jar of " + modid);
        //?} else {
        /*return LocatedFolder.borrowed(modFile.findResource(folder));
        *///?}
    }
}
