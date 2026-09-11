package com.kuronami.musicdiscmaker.depend;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;

/**
 * mod jar の中で見つけたフォルダと、その {@link Path} を有効に保っている資源。
 *
 * <p>jar の中を指す {@link Path} は、その jar の {@link java.nio.file.FileSystem} が開いている
 * 間しか使えない (閉じた後に触ると {@link java.nio.file.ClosedFileSystemException})。
 * 「見つけた場所」だけを返す契約だと<b>誰がいつ閉じるのかが型から落ちる</b>ので、
 * 場所と持ち主を一緒に返す。
 *
 * <p>持ち主が居ない場合 ({@link #borrowed}) もある。実ディレクトリと、Fabric loader が自分で
 * 開いて mod の寿命ぶん保持している jar がそれで、こちらが閉じてはいけない。
 */
public final class LocatedFolder implements Closeable {

    private final Path path;
    private final Closeable owned;

    private LocatedFolder(Path path, Closeable owned) {
        this.path = path;
        this.owned = owned;
    }

    /** 他の誰かが寿命を握っている {@link Path}。{@link #close()} は何もしない。 */
    public static LocatedFolder borrowed(Path path) {
        return new LocatedFolder(path, null);
    }

    /** こちらが開いた資源と一組の {@link Path}。{@link #close()} でその資源を閉じる。 */
    public static LocatedFolder owning(Path path, Closeable owned) {
        return new LocatedFolder(path, owned);
    }

    /** 見つかったフォルダ。walk も read も、{@link #close()} を呼ぶまでの間だけ有効。 */
    public Path path() {
        return path;
    }

    @Override
    public void close() throws IOException {
        if (owned != null) {
            owned.close();
        }
    }

    @Override
    public String toString() {
        return path.toString();
    }
}
