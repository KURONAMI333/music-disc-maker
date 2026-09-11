package com.kuronami.musicdiscmaker.depend;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ProviderNotFoundException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * mod の content root (実ディレクトリ or jar ファイル) の中のフォルダを開く。
 *
 * <p><b>なぜ要るか。</b> loader が渡してくる content root は dev では展開済みディレクトリだが、
 * production では<b>jar ファイルそのもののパス</b>になる。ディレクトリのつもりで
 * {@code root.resolve("dependencies")} を書くと、production では
 * {@code ....jar/dependencies} という<b>存在しえないパス</b>が出来上がり、
 * {@link Files#exists} が黙って false を返す。dev だけ通って production では
 * 一度も通らないので、実機に出るまで誰も気づかない。ここはその段差を一箇所に閉じ込める。
 *
 * <p>jar の場合は zip の {@link FileSystem} を開いて中を指す {@link Path} を返す。その
 * {@link Path} は FileSystem が開いている間しか有効でないので、寿命は
 * {@link LocatedFolder} に持たせて呼び出し側の try-with-resources に預ける。
 *
 * <p>loader の API に依存しないのは意図的で、こうしておくと「jar を root として渡された」
 * 状況を JUnit でそのまま再現できる ({@code JarFoldersTest})。loader を跨いだ実機でしか
 * 踏めない不具合を、テストで固定できる場所へ引き寄せている。
 */
public final class JarFolders {

    private static final Logger LOGGER = LoggerFactory.getLogger("music_disc_maker/deps");

    private JarFolders() {
    }

    /**
     * content root の中の {@code folder} を開く。
     *
     * @param root   実ディレクトリ、または jar ファイルのパス
     * @param folder jar/ディレクトリ直下のフォルダ名
     * @return 見つかれば開いた {@link LocatedFolder}、無ければ {@link Optional#empty()}
     * @throws IOException jar を開けなかった場合
     */
    public static Optional<LocatedFolder> open(Path root, String folder) throws IOException {
        if (root == null) {
            return Optional.empty();
        }
        if (Files.isDirectory(root)) {
            final Path candidate = root.resolve(folder);
            return Files.exists(candidate)
                    ? Optional.of(LocatedFolder.borrowed(candidate))
                    : Optional.empty();
        }
        if (!Files.isRegularFile(root)) {
            return Optional.empty();
        }
        return openInsideArchive(root, folder);
    }

    private static Optional<LocatedFolder> openInsideArchive(Path archive, String folder) throws IOException {
        final Optional<OpenArchive> opened = openArchive(archive);
        if (opened.isEmpty()) {
            return Optional.empty();
        }
        final OpenArchive open = opened.get();
        try {
            final Path candidate = open.fs().getPath("/" + folder);
            if (!Files.exists(candidate)) {
                open.closeIfOurs();
                return Optional.empty();
            }
            return Optional.of(open.ours()
                    ? LocatedFolder.owning(candidate, open.fs())
                    : LocatedFolder.borrowed(candidate));
        } catch (final RuntimeException ex) {
            open.closeIfOurs();
            throw ex;
        }
    }

    private static Optional<OpenArchive> openArchive(Path archive) throws IOException {
        try {
            return Optional.of(new OpenArchive(FileSystems.newFileSystem(archive, (ClassLoader) null), true));
        } catch (final FileSystemAlreadyExistsException alreadyOpen) {
            // 同じ jar を誰かが既に zipfs で開いている (zipfs provider は実パスで共有する)。
            // 借りるだけにして閉じない。こちらが閉じると相手の Path まで死ぬ。
            return Optional.of(new OpenArchive(existing(archive, alreadyOpen), false));
        } catch (final ProviderNotFoundException notAnArchive) {
            // zip として開けないファイルは content root として無関係。ただし黙って空を返すと、
            // 呼び出し側は「フォルダが無い」としか言えなくなる。それはこの不具合そのものの
            // 形 (実際の原因と違うものを探させる) なので、本当の理由をここで残す。
            LOGGER.warn("Cannot read {} as an archive; treating it as an unrelated content root", archive,
                    notAnArchive);
            return Optional.empty();
        }
    }

    /** 開いた zip の FileSystem と、それを閉じる責任がこちらにあるか。 */
    private record OpenArchive(FileSystem fs, boolean ours) {

        void closeIfOurs() {
            if (!ours) {
                return;
            }
            try {
                fs.close();
            } catch (final IOException ignored) {
                // 開けたが使わなかっただけ。閉じ損ねても本流を止める理由にならない。
            }
        }
    }

    private static FileSystem existing(Path archive, FileSystemAlreadyExistsException cause) throws IOException {
        try {
            return FileSystems.getFileSystem(URI.create("jar:" + archive.toUri()));
        } catch (final FileSystemNotFoundException | IllegalArgumentException ex) {
            // 「既にある」と言われた直後に見つからない = 別スレッドが閉じた等。原因を繋いで返す。
            final IOException failure = new IOException("Cannot reuse the already open file system of " + archive, ex);
            failure.addSuppressed(cause);
            throw failure;
        }
    }
}
