package com.kuronami.musicdiscmaker.depend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * production の枝を丸ごと (locate → walk → 展開 → classloader 登録) headless で走らせる。
 *
 * <p>{@link JarFoldersTest} が固定しているのは「jar の中を見つけられるか」までで、
 * <b>見つけた後もずっと jar の FileSystem が開いていないと展開できない</b>ことは固定できない。
 * {@code install(...)} を try-with-resources の外へ出しても JarFoldersTest は全部緑のまま、
 * production だけが {@link java.nio.file.ClosedFileSystemException} で落ちる。ここがその番。
 *
 * <p>loader は無い環境なので {@code pathLocator} を直に差し込む。ServiceLoader 経由にすると
 * どの実装が先に来るかが classpath の並びに依存して、テストの意図が並び順に化ける。
 */
class DependencyManagerProductionPathTest {

    @TempDir
    Path temp;

    @Test
    void unpacksFromInsideTheModJar() throws Exception {
        final Path jar = temp.resolve("music_disc_maker-test.jar");
        final byte[] first = "first packed jar".getBytes(StandardCharsets.UTF_8);
        final byte[] second = "second packed jar, a bit longer".getBytes(StandardCharsets.UTF_8);
        try (OutputStream raw = Files.newOutputStream(jar);
                ZipOutputStream zip = new ZipOutputStream(raw)) {
            write(zip, "dependencies/a.jar.packed", first);
            write(zip, "dependencies/b.jar.packed", second);
        }

        installLocator((modid, folder) -> JarFolders.open(jar, folder)
                .orElseThrow(() -> new IOException("mod jar 内に " + folder + " フォルダが見つからない")));

        final Path extractRoot = Files.createDirectories(temp.resolve("extract-root"));
        final String previousTmp = System.getProperty("java.io.tmpdir");
        System.setProperty("java.io.tmpdir", extractRoot.toString());
        try {
            DependencyManager.load();
        } finally {
            if (previousTmp == null) {
                System.clearProperty("java.io.tmpdir");
            } else {
                System.setProperty("java.io.tmpdir", previousTmp);
            }
        }

        final URL[] urls = DependencyManager.CLASSLOADER.getURLs();
        final List<String> names = Arrays.stream(urls)
                .map(url -> Paths.get(url.getPath().replaceFirst("^/", "")).getFileName().toString())
                .sorted()
                .collect(Collectors.toList());
        assertEquals(List.of("a.jar.packed", "b.jar.packed"), names,
                "展開した 2 本が classloader に載っていること");

        final Path extracted = Paths.get(urls[0].toURI()).getParent();
        assertTrue(extracted.startsWith(extractRoot), "展開先が指定した root の下にあること");
        assertEquals(first.length, Files.size(extracted.resolve("a.jar.packed")));
        assertEquals(second.length, Files.size(extracted.resolve("b.jar.packed")));
        assertEquals("first packed jar",
                new String(Files.readAllBytes(extracted.resolve("a.jar.packed")), StandardCharsets.UTF_8));
    }

    private static void write(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static void installLocator(DependencyManager.PathLocator locator) throws Exception {
        final Field field = DependencyManager.class.getDeclaredField("pathLocator");
        field.setAccessible(true);
        field.set(null, locator);
    }
}
