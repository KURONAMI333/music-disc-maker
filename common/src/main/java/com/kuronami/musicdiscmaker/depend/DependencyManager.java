package com.kuronami.musicdiscmaker.depend;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kuronami.musicdiscmaker.depend.ExtractionPlan.Entry;

/**
 * mod jar に同梱した LavaPlayer 一式 ({@code dependencies/*.jar.packed}) をディスクに展開し、
 * 隔離 {@link DependencyClassLoader} に登録する。
 *
 * <p>dev (runClient 等) では {@code -Dmusicdiscmaker.dev=<libs>;<dependencies>} を見て
 * build ディレクトリから直接読む (mod jar 未経由)。production では {@link PathLocator} で
 * mod jar 内の {@code dependencies/} を特定する。
 *
 * <p><b>展開は同梱 jar 一式の指紋で決まる固定の場所に置き、揃っていれば丸ごと飛ばす。</b>
 * 起動のたびに 34MB を書き直すのは失敗の窓を毎回開けるのと同じで、そこが実際に
 * 「どのリンクを貼っても Failed」の入口になっていた。判定と場所決めは
 * {@link ExtractionPlan}、一度だけ成功させる掛け金は {@link DependencyLoadGate} が持つ。
 *
 * <p>temp が使えない環境 (権限・空き・ウイルス対策の隔離) 向けに、JVM の作業ディレクトリ
 * 配下へ退避する経路がある。両方落ちたら、両方の原因を繋いだ例外で止まる。
 */
public final class DependencyManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("music_disc_maker/deps");
    private static final String FILE_ENDING = ".jar.packed";

    /** 展開先の親ディレクトリ名 (temp 側・退避先側で共通)。 */
    private static final String EXTRACT_DIR = "music_disc_maker-deps";

    /** 全ファイルの設置が済んだ後にだけ書かれる完了マーカー。 */
    private static final String MARKER = ".complete";

    /** 展開の再試行の上限。使い切った後は原因つきで投げ直すだけで、仕事はしない。 */
    private static final int MAX_ATTEMPTS = 3;

    /** これだけ触られていない展開先は古い版のものとみなして掃除する。 */
    private static final long STALE_AFTER_MS = java.util.concurrent.TimeUnit.DAYS.toMillis(1);

    /** LavaPlayer 一式を抱える隔離 classloader。impl は {@code Class.forName(.., この loader)} で取る。 */
    public static final DependencyClassLoader CLASSLOADER = new DependencyClassLoader();

    private static final DependencyLoadGate GATE = new DependencyLoadGate(MAX_ATTEMPTS);

    private static volatile PathLocator pathLocator;

    private DependencyManager() {
    }

    /**
     * 同梱依存を展開して classloader に登録する。成功するまで何度でも呼んでよい
     * ({@link #MAX_ATTEMPTS} 回まで実際に試み、その後は覚えた原因を投げ直す)。
     */
    public static void load() {
        GATE.runOnce(DependencyManager::doLoad);
    }

    private static void doLoad() throws IOException {
        final String searchedIn;
        final Set<Path> packed;
        final String devPath = System.getProperty("musicdiscmaker.dev");
        if (devPath != null && !devPath.isBlank()) {
            searchedIn = "the dev paths (" + devPath + ")";
            final List<Path> roots = Stream.of(devPath.split(";"))
                    .filter(s -> s != null && !s.isBlank())
                    .map(Paths::get)
                    .toList();
            final Set<Path> found = new LinkedHashSet<>();
            for (final Path root : roots) {
                found.addAll(walkPacked(root));
            }
            packed = found;
        } else {
            final Path root = locateInJar("dependencies");
            searchedIn = "the mod jar (" + root + ")";
            packed = walkPacked(root);
        }

        final List<Entry> expected = ExtractionPlan.requireNonEmpty(describe(packed), searchedIn);
        final String fingerprint = ExtractionPlan.fingerprint(expected);

        final Path tempRoot = Paths.get(System.getProperty("java.io.tmpdir", "."));
        Path dir;
        try {
            dir = prepare(tempRoot, fingerprint, packed, expected);
        } catch (final IOException | RuntimeException tempFailure) {
            // 一番ありがちな失敗の塊がここ (temp の権限・空き・ウイルス対策の隔離)。
            // MC の作業ディレクトリ配下へ退避してもう一度だけ試す。
            final Path fallbackRoot = Paths.get("").toAbsolutePath();
            LOGGER.warn("Could not unpack the LavaPlayer dependencies under the temp directory ({}); "
                    + "retrying under the working directory ({})",
                    tempRoot.toAbsolutePath(), fallbackRoot, tempFailure);
            try {
                dir = prepare(fallbackRoot, fingerprint, packed, expected);
            } catch (final IOException | RuntimeException fallbackFailure) {
                fallbackFailure.addSuppressed(tempFailure);
                throw new IOException(
                        "Failed to unpack the bundled LavaPlayer dependencies in both "
                                + tempRoot.toAbsolutePath() + " and " + fallbackRoot
                                + " (see the cause and its suppressed exception for both failures)",
                        fallbackFailure);
            }
        }

        // 全ファイルが揃ったことを確かめた後にだけ classloader へ登録する。
        // 途中まで登録して失敗すると、再試行で同じ URL を二重に積むことになる。
        for (final Entry entry : expected) {
            CLASSLOADER.addURL(toUrl(dir.resolve(entry.name())));
        }
        LOGGER.info("Loaded LavaPlayer dependencies ({} jars) from {}", expected.size(), dir);
    }

    /**
     * 指紋で決まる展開先を用意して返す。既に揃っていれば展開はしない。
     *
     * @return 展開先ディレクトリ
     */
    private static Path prepare(Path root, String fingerprint, Set<Path> packed, List<Entry> expected)
            throws IOException {
        final Path base = root.resolve(EXTRACT_DIR);
        final Path dir = base.resolve(fingerprint);
        Files.createDirectories(dir);

        final Path marker = dir.resolve(MARKER);
        if (ExtractionPlan.canReuse(expected, probe(dir, marker))) {
            LOGGER.info("Reusing the already extracted LavaPlayer dependencies in {}", dir);
            touch(dir);
            pruneStale(base, fingerprint);
            return dir;
        }

        LOGGER.info("Extracting {} LavaPlayer dependency jars into {}", expected.size(), dir);
        for (final Path source : packed) {
            extractAtomically(dir, source);
        }
        for (final Entry entry : expected) {
            final long actual = sizeOrAbsent(dir.resolve(entry.name()));
            if (actual != entry.size()) {
                throw new IOException("Extracted dependency " + entry.name() + " has size " + actual
                        + " but " + entry.size() + " was expected (target directory: " + dir + ")");
            }
        }
        writeAtomically(marker, ExtractionPlan.renderMarker(expected).getBytes(StandardCharsets.UTF_8));
        pruneStale(base, fingerprint);
        return dir;
    }

    private static ExtractionPlan.DirectoryProbe probe(Path dir, Path marker) {
        return new ExtractionPlan.DirectoryProbe() {

            @Override
            public boolean markerPresent() {
                return Files.isRegularFile(marker);
            }

            @Override
            public long sizeOf(String name) {
                return sizeOrAbsent(dir.resolve(name));
            }
        };
    }

    /**
     * 1 ファイルを一時名で書いてから最終名へ原子的に移す。
     *
     * <p>一時ファイルを最終名と同じディレクトリに作るのは、{@code ATOMIC_MOVE} が
     * ファイルシステムをまたげないため。これで<b>複数のインスタンスが同時に起動しても</b>、
     * 片方が書いている途中のファイルをもう片方が最終名で掴むことはない。
     */
    private static void extractAtomically(Path dir, Path source) throws IOException {
        final String name = source.getFileName().toString();
        final Path target = dir.resolve(name);
        final long expectedSize = Files.size(source);
        // 既存ファイルがサイズだけ合っていても書き直す。ここへ来ている時点で
        // 完了マーカーが無い = その展開は「完了した」と確認されたことがなく、
        // 中身が壊れていないと信じる根拠が無い。
        final Path tmp = dir.resolve(name + ".tmp-" + UUID.randomUUID());
        try {
            try (InputStream in = Files.newInputStream(source);
                    OutputStream out = Files.newOutputStream(tmp,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                in.transferTo(out);
            }
            move(tmp, target, expectedSize);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (final IOException ignored) {
                // 一時ファイルの後始末は本流を止める理由にならない。
            }
        }
    }

    private static void writeAtomically(Path target, byte[] content) throws IOException {
        final Path tmp = target.resolveSibling(target.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.write(tmp, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            move(tmp, target, content.length);
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (final IOException ignored) {
                // 同上。
            }
        }
    }

    private static void move(Path tmp, Path target, long expectedSize) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (final AtomicMoveNotSupportedException ex) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException ex) {
            // Windows では、別のインスタンスが同じ jar を classloader 越しに開いていると
            // 上書きが AccessDeniedException になる。中身が既に正しいなら成功と同じ。
            if (sizeOrAbsent(target) != expectedSize) {
                throw ex;
            }
        }
    }

    /** 存在すればサイズ、しなければ {@code -1}。 */
    private static long sizeOrAbsent(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : -1L;
        } catch (final IOException ex) {
            return -1L;
        }
    }

    /**
     * 古い指紋の展開を片付ける。<b>失敗しても本流は止めない</b> — 掃除できないこと
     * (別インスタンスが掴んでいる等) は、依存をロードできない理由にはならない。
     *
     * <p>最近触られたディレクトリは残す。別バージョンのインスタンスが同時に起動していると、
     * <b>相手が今まさに書き込んでいるディレクトリを消してしまう</b> (実測: 6 並列起動で、
     * 消された側が書き込み中の一時ファイルを {@code NoSuchFileException} で見失った)。
     * 使用中のディレクトリは再利用のたびに {@link #touch} で更新されるので、この足切りで
     * 生きているものと本当に古いものを分けられる。
     */
    private static void pruneStale(Path base, String keep) {
        final long cutoff = System.currentTimeMillis() - STALE_AFTER_MS;
        try (Stream<Path> children = Files.list(base)) {
            children.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().equals(keep))
                    .filter(p -> lastModified(p) < cutoff)
                    .forEach(DependencyManager::deleteRecursive);
        } catch (final IOException | RuntimeException ignored) {
            // 掃除は best effort。
        }
    }

    /** 「今も使われている」印を付ける。付けられなくても実害は無い。 */
    private static void touch(Path dir) {
        try {
            Files.setLastModifiedTime(dir, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        } catch (final IOException | RuntimeException ignored) {
            // 読み取り専用の環境等。掃除が少し遅れるだけ。
        }
    }

    /** 最終更新時刻。読めなければ「今」扱いにして掃除の対象から外す (消しすぎない側に倒す)。 */
    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (final IOException | RuntimeException ex) {
            return System.currentTimeMillis();
        }
    }

    private static void deleteRecursive(Path path) {
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (final IOException ignored) {
                    // 残っても実害は無い。
                }
            });
        } catch (final IOException | RuntimeException ignored) {
            // 同上。
        }
    }

    /** 展開すべきファイルの一覧 (名前とサイズ)。指紋と再利用判定の両方がこれを使う。 */
    private static List<Entry> describe(Set<Path> packed) throws IOException {
        final List<Entry> entries = new ArrayList<>(packed.size());
        for (final Path path : packed) {
            entries.add(new Entry(path.getFileName().toString(), Files.size(path)));
        }
        return entries;
    }

    /**
     * {@code *.jar.packed} を探す。
     *
     * <p>元の実装は {@link IOException} を握り潰して空集合を返しており、<b>例外すら出ずに
     * 空の classloader ができる</b>経路になっていた。読めなかったことは呼び出し側へ返す。
     */
    private static Set<Path> walkPacked(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            // dev では複数の root を渡され、片方だけ存在しないことがある。
            // 全部足して 0 件だった場合は requireNonEmpty が止める。
            return Set.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(f -> f.toString().endsWith(FILE_ENDING))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (final UncheckedIOException ex) {
            throw new IOException("Failed to scan for packed jars under " + root, ex.getCause());
        } catch (final IOException ex) {
            throw new IOException("Failed to scan for packed jars under " + root, ex);
        }
    }

    private static URL toUrl(Path path) {
        try {
            return path.toUri().toURL();
        } catch (final MalformedURLException ex) {
            throw new IllegalStateException("Cannot turn " + path + " into a URL", ex);
        }
    }

    private static Path locateInJar(String folder) {
        try {
            return pathLocator().locate("music_disc_maker", folder);
        } catch (final Exception ex) {
            throw new IllegalStateException("Cannot locate " + folder + " inside the mod jar", ex);
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
