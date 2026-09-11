package com.kuronami.musicdiscmaker.depend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * production の content root は<b>mod jar そのもののパス</b>で渡ってくる、という状況を再現する。
 *
 * <p>dev では content root が展開済みディレクトリなので {@code root.resolve("dependencies")} が
 * 通ってしまい、この段差は実 jar でしか踏めない。ここはその実 jar 側だけを切り出したもので、
 * {@link JarFolders} を素朴な {@code resolve} に戻すと {@link #findsTheFolderWhenTheRootIsTheJarItself()}
 * と {@link #readsTheEntriesFromInsideTheJar()} が落ちる。
 */
class JarFoldersTest {

    @TempDir
    Path temp;

    private Path jarWithDependencies(String... names) throws IOException {
        final Path jar = temp.resolve("music_disc_maker-test.jar");
        try (OutputStream raw = Files.newOutputStream(jar);
                ZipOutputStream zip = new ZipOutputStream(raw)) {
            zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            zip.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (final String name : names) {
                zip.putNextEntry(new ZipEntry("dependencies/" + name));
                zip.write(("payload of " + name).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return jar;
    }

    /**
     * 不具合そのものの形。jar ファイルのパスに resolve した「フォルダ」は存在しえない。
     * {@code Files.exists} は例外ではなく false を返すので、素朴な実装は黙って空振りする。
     */
    @Test
    void resolvingAFolderOnAJarPathNeverExists() throws IOException {
        final Path jar = jarWithDependencies("lavaplayer.jar.packed");
        assertTrue(Files.isRegularFile(jar), "テストの前提: content root は jar ファイル");
        assertFalse(Files.exists(jar.resolve("dependencies")),
                "jar のパスに resolve したフォルダが存在してはならない (存在するならテストの前提が壊れている)");
    }

    @Test
    void findsTheFolderWhenTheRootIsTheJarItself() throws IOException {
        final Path jar = jarWithDependencies("a.jar.packed", "b.jar.packed");
        try (LocatedFolder located = JarFolders.open(jar, "dependencies").orElseThrow()) {
            assertTrue(Files.isDirectory(located.path()));
        }
    }

    @Test
    void readsTheEntriesFromInsideTheJar() throws IOException {
        final Path jar = jarWithDependencies("a.jar.packed", "b.jar.packed", "notes.txt");
        try (LocatedFolder located = JarFolders.open(jar, "dependencies").orElseThrow()) {
            final List<String> packed;
            try (Stream<Path> walk = Files.walk(located.path())) {
                packed = walk.filter(p -> p.toString().endsWith(".jar.packed"))
                        .map(p -> p.getFileName().toString())
                        .sorted()
                        .collect(Collectors.toList());
            }
            assertEquals(List.of("a.jar.packed", "b.jar.packed"), packed);

            final Path first = located.path().resolve("a.jar.packed");
            assertEquals("payload of a.jar.packed",
                    new String(Files.readAllBytes(first), StandardCharsets.UTF_8));
            assertEquals("payload of a.jar.packed".length(), Files.size(first));
        }
    }

    /** dev の経路。content root が実ディレクトリのままでも通ること。 */
    @Test
    void stillWorksWhenTheRootIsARealDirectory() throws IOException {
        final Path root = Files.createDirectories(temp.resolve("build-dir"));
        final Path deps = Files.createDirectories(root.resolve("dependencies"));
        Files.write(deps.resolve("a.jar.packed"), "payload".getBytes(StandardCharsets.UTF_8));

        try (LocatedFolder located = JarFolders.open(root, "dependencies").orElseThrow()) {
            assertEquals(deps, located.path());
        }
        // 借りただけなので close 後も生きている。
        assertTrue(Files.exists(deps.resolve("a.jar.packed")));
    }

    /** close で jar の FileSystem まで閉じること (閉じ忘れの逆側を固定する)。 */
    @Test
    void closingReleasesTheJarFileSystem() throws IOException {
        final Path jar = jarWithDependencies("a.jar.packed");
        final Path inside;
        try (LocatedFolder located = JarFolders.open(jar, "dependencies").orElseThrow()) {
            inside = located.path().resolve("a.jar.packed");
            assertTrue(Files.exists(inside));
        }
        assertThrows(ClosedFileSystemException.class, () -> Files.exists(inside));

        // 閉じているので同じ jar をもう一度開ける (開きっぱなしなら
        // FileSystemAlreadyExistsException になる)。
        try (FileSystem reopened = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
            assertTrue(Files.exists(reopened.getPath("/dependencies/a.jar.packed")));
        }
    }

    /** 目当てのフォルダが無い jar では、開いた FileSystem を残さないこと。 */
    @Test
    void doesNotLeakTheFileSystemWhenTheFolderIsMissing() throws IOException {
        final Path jar = jarWithDependencies();
        final Optional<LocatedFolder> found = JarFolders.open(jar, "dependencies");
        assertTrue(found.isEmpty());

        try (FileSystem reopened = FileSystems.newFileSystem(jar, (ClassLoader) null)) {
            assertTrue(Files.exists(reopened.getPath("/META-INF/MANIFEST.MF")));
        }
    }
}
