package com.kuronami.musicdiscmaker.depend;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Comparator;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * mod jar に同梱した LavaPlayer 一式 ({@code dependencies/*.jar.packed}) を temp に展開し、
 * 隔離 {@link DependencyClassLoader} に登録する。
 *
 * <p>dev (runClient 等) では {@code -Dmusicdiscmaker.dev=<libs>;<dependencies>} を見て
 * build ディレクトリから直接読む (mod jar 未経由)。production では {@link PathLocator} で
 * mod jar 内の {@code dependencies/} を特定する。
 */
public final class DependencyManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("music_disc_maker/deps");
    private static final String FILE_ENDING = ".jar.packed";

    /** LavaPlayer 一式を抱える隔離 classloader。impl は {@code Class.forName(.., この loader)} で取る。 */
    public static final DependencyClassLoader CLASSLOADER = new DependencyClassLoader();

    private static volatile PathLocator pathLocator;
    private static boolean loaded = false;

    private DependencyManager() {
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        LOGGER.info("Loading LavaPlayer dependencies...");

        final Path tmp = createExtractDirectory();
        final String devPath = System.getProperty("musicdiscmaker.dev");

        final Set<Path> packed;
        if (devPath != null && !devPath.isBlank()) {
            packed = Stream.of(devPath.split(";"))
                    .filter(s -> s != null && !s.isBlank())
                    .map(Paths::get)
                    .flatMap(p -> walkPacked(p).stream())
                    .collect(Collectors.toUnmodifiableSet());
        } else {
            packed = walkPacked(locateInJar("dependencies"));
        }

        packed.stream()
                .map(p -> extractFile(tmp, p))
                .map(DependencyManager::toUrl)
                .forEach(CLASSLOADER::addURL);

        LOGGER.info("Loaded LavaPlayer dependencies ({} jars)", packed.size());
    }

    private static Path createExtractDirectory() {
        try {
            final Path base = Paths.get(System.getProperty("java.io.tmpdir", "."), "music_disc_maker-deps");
            final Path dir = base.resolve(Long.toString(System.currentTimeMillis()));
            try {
                deleteRecursive(base);
            } catch (final Exception ignored) {
                // 前回分が消せなくても続行
            }
            Files.createDirectories(dir);
            return dir;
        } catch (final IOException ex) {
            throw new RuntimeException("Failed to create the temp directory used for extraction", ex);
        }
    }

    private static void deleteRecursive(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (final IOException ignored) {
                }
            });
        }
    }

    private static Set<Path> walkPacked(Path root) {
        if (root == null || !Files.exists(root)) {
            return Collections.emptySet();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(f -> f.toString().endsWith(FILE_ENDING)).collect(Collectors.toSet());
        } catch (final IOException ex) {
            LOGGER.error("Failed to scan for packed jars under {}", root, ex);
            return Collections.emptySet();
        }
    }

    private static Path extractFile(Path extractDir, Path source) {
        final Path target = extractDir.resolve(source.getFileName().toString());
        try (InputStream in = Files.newInputStream(source);
                OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE)) {
            in.transferTo(out);
        } catch (final IOException ex) {
            throw new RuntimeException("Failed to extract a packed jar: " + source, ex);
        }
        return target;
    }

    private static URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (final MalformedURLException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static Path locateInJar(String folder) {
        try {
            return pathLocator().locate("music_disc_maker", folder);
        } catch (final Exception ex) {
            throw new RuntimeException("Cannot locate " + folder + " inside the mod jar", ex);
        }
    }

    private static PathLocator pathLocator() {
        PathLocator local = pathLocator;
        if (local == null) {
            synchronized (DependencyManager.class) {
                local = pathLocator;
                if (local == null) {
                    local = ServiceLoader.load(PathLocator.class, DependencyManager.class.getClassLoader())
                            .findFirst()
                            .orElseThrow(() -> new IllegalStateException("No PathLocator service is registered"));
                    pathLocator = local;
                }
            }
        }
        return local;
    }

    /** loader 別に mod jar 内のフォルダ位置を特定する (NeoForge 実装は ServiceLoader 登録)。 */
    public interface PathLocator {
        Path locate(String modid, String folder) throws IOException, IllegalStateException;
    }
}
