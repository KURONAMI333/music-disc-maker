package com.kuronami.musicdiscmaker.client.audio;

import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import com.kuronami.musicdiscmaker.lavaplayer.api.ResolveException;

/**
 * ローカル取込の純粋な判定部品 (picker 候補・表示名・失敗→status 写像)。
 * Minecraft 型を持たないので headless 試験が直接駆動できる。
 */
final class LocalImportDecisions {

    /**
     * picker に出す選択候補。内容はコンテナ probe で決めるので拡張子は絞り込みの目安でしかない。
     * 末尾の {@code *.*} で対応外形式も選べる = 選んだ時点で「なぜ駄目か」まで辿り着ける
     * (候補に出さないと、グレーアウトされたファイルの理由が利用者からは見えない)。
     */
    static final String[] PICKER_PATTERNS = {
            "*.mp3", "*.wav", "*.wave", "*.flac", "*.mka", "*.ogg", "*.opus", "*.oga",
            "*.aac", "*.adts", "*.m4a", "*.mp4", "*.m4v", "*.mov", "*.mkv", "*.webm", "*.*" };

    /** 表示名は入力ファイル名から末尾の拡張子 (種類を問わず 1 個) を外したもの。 */
    static String displayName(String fileName) {
        return fileName.replaceAll("\\.[A-Za-z0-9]{1,8}$", "");
    }

    /**
     * 選択されたパスから、取込中に左上の欄へ出せる名前を作る。
     * パスとして読めない時やファイル名が無い時は空 (= 欄を書き換えない)。
     */
    static String displayNameFor(String selected) {
        try {
            final Path name = Path.of(selected).getFileName();
            if (name == null) return "";
            final String display = displayName(name.toString()).replaceAll("[\\p{Cntrl}]", "");
            return display.substring(0, Math.min(256, display.length()));
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * サーバーの拒否で、選択済みの取込を捨てずに盤の待ちへ戻すか。盤の拒否 ("disc") で、
     * 本体をまだ 1 byte も送っていない時だけ。送信後・別の理由の拒否は従来どおり取込を終える。
     */
    static boolean waitsForDisc(String reason, long sent) {
        return "disc".equals(reason) && sent == 0;
    }

    /**
     * 画面が取り除かれた時に保持した取込を取り消すか。JEI・EMI のレシピ画面のように画面だけが替わり、
     * プレイヤーがまだその menu を開いている間は取り消さない (画面が戻れば tick が続きを進める)。
     * vanilla の onClose・サーバーの close・死亡は、画面を替える前に containerMenu を戻す。
     */
    static boolean cancelsOnScreenRemoved(Object screenMenu, Object playerMenu) {
        return playerMenu != screenMenu;
    }

    /**
     * cancel(menu) が保持中の取込を中止するか。自分の menu からの取消は常に中止。別の menu からの呼び出しは、
     * 保持した取込の menu がもう開かれていない (onClose を通らず閉じた) 時だけ中止する。
     */
    static boolean cancelAborts(Object uploadMenu, Object requestMenu, Object playerMenu) {
        return uploadMenu == requestMenu || uploadMenu != playerMenu;
    }

    /** サーバーの "busy" で、本体をまだ送っていないなら取込を保持して撃ち直す。 */
    static boolean retriesBusy(String reason, long sent) {
        return "busy".equals(reason) && sent == 0;
    }

    /**
     * 取込失敗を表示用 status 名へ畳む。{@link ResolveException} の detail は normalizer と
     * このクラスが投げる固定文言で、そこから利用者向けの粒度だけを選ぶ。
     */
    static String failureStatus(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof InterruptedException || t instanceof InterruptedIOException) return "select";
            if (t instanceof ResolveException failure) {
                final String d = failure.detail();
                if (d.contains("interrupted")) return "select";
                if (d.contains("duration limit")) return "too_long";
                if (d.contains("size limit")) return "too_large";
                if (d.contains("opus encoder") || d.contains("not supported here")) return "unavailable";
                // 読めない入力 (権限・消えたファイル等) と発行側 I/O 失敗
                // ("could not publish the normalized file" / disk full 等) は、
                // 入力形式の非対応ではないので import_failed へ。
                if (d.startsWith("input file is not readable") || d.contains("output")
                        || d.contains("parent directory")
                        || d.contains("normalization") || d.contains("i/o failed")
                        || d.contains("encoding failed") || d.contains("publish"))
                    return "import_failed";
                return "unsupported";
            }
        }
        return "import_failed";
    }

    /**
     * Linux で GUI セッションが無いか。GUI が無ければ TinyFileDialogs は端末向け backend に
     * 落ち、{@code tinyfd_openFileDialog} は null を返す (取消と区別できない)。
     * 呼ぶ前にここで弾いて理由を出す。
     *
     * <p>macOS / Windows では判定しない。Linux は X11 の {@code DISPLAY} だけを見る —
     * Wayland の {@code WAYLAND_DISPLAY} だけでは TinyFileDialogs の GUI は開かず、
     * これを「GUI あり」と誤判定すると押しても無反応になる。backend の有無は検査しない
     * (検査するなら 4 引数版)。
     *
     * @param osName      {@code os.name}
     * @param display     {@code DISPLAY} の値
     * @param wayland     {@code WAYLAND_DISPLAY} の値 (互換のため受けるが判定には使わない)
     * @return GUI のファイル選択を開けない環境なら {@code true}
     */
    static boolean pickerSessionUnavailable(String osName, String display, String wayland) {
        return pickerSessionUnavailable(osName, display, wayland, name -> true);
    }

    /**
     * backend の有無まで見る版。{@code onPath} は「その実行ファイルが PATH 上で起動できるか」を
     * 返す判定 (試験では predicate を差し替える)。{@code DISPLAY} があっても既知のダイアログ
     * 実装がどれも無ければ TinyFileDialogs は開けないので {@code true}。
     * {@code python3} / {@code python} は PATH にあるだけでは TinyFileDialogs の tkinter
     * backend と認めない (tkinter が無ければ実際には開けず null を返す) ので候補に含めない。
     *
     * @param onPath 実行ファイル名を受け取り、PATH 上で起動可能なら {@code true}
     * @return GUI のファイル選択を開けない環境なら {@code true}
     */
    static boolean pickerSessionUnavailable(String osName, String display, String wayland,
                                            Predicate<String> onPath) {
        if (osName == null || !osName.toLowerCase(java.util.Locale.ROOT).contains("linux")) {
            return false;
        }
        if (isBlank(display)) return true;
        return firstDialogBackendOnPath(onPath) == null;
    }

    /** TinyFileDialogs が使う可能性のある Linux のダイアログ実装 (診断用の名前)。 */
    private static final String[] LINUX_DIALOG_BACKENDS = {
            "zenity", "kdialog", "yad", "Xdialog", "xdialog", "qarma", "matedialog" };

    /**
     * PATH 上で最初に見つかったダイアログ実装の名前。見つからなければ {@code null}。
     * ログには名前だけを出し、PATH 全体や個人パスは出さない。
     */
    static String firstDialogBackendOnPath(Predicate<String> onPath) {
        for (String backend : LINUX_DIALOG_BACKENDS) {
            if (onPath.test(backend)) return backend;
        }
        return null;
    }

    /**
     * {@code executable} が PATH 上の起動可能なファイルか。
     *
     * <p>{@link Files#isExecutable(Path)} だけでは、PATH の要素に同名のディレクトリが
     * ある場合に「実行ファイルあり」と誤認する (ディレクトリにも実行ビットは立つ)。
     * 通常ファイルに限ることで、zenity 等の名前のディレクトリを backend と数えない。
     *
     * @param executable 実行ファイル名 (ディレクトリを含めない)
     * @param path       PATH の値 ({@code null}/空なら見つからない)
     * @return 起動可能な通常ファイルが最初に見つかれば {@code true}
     */
    static boolean isExecutableOnPath(String executable, String path) {
        if (path == null || path.isBlank()) return false;
        for (String directory : path.split(java.io.File.pathSeparator)) {
            if (directory.isBlank()) continue;
            try {
                final Path candidate = Path.of(directory, executable);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return true;
            } catch (RuntimeException invalidPath) {
                // 不正な PATH 要素は飛ばす。
            }
        }
        return false;
    }

    /**
     * 画面へドラッグ＆ドロップされた一覧から、取込に使う 1 本を選ぶ。
     *
     * <p>GLFW のドロップはフォルダも渡すので、フォルダは飛ばして最初の通常ファイルを使う。
     * 複数落とされた時は 2 本目以降を無視する (取込は 1 本ずつ、取込中は受け付けない)。
     * 拡張子では弾かない — 内容の判定は {@code LocalAudioClient#prepare} が行い、失敗理由を
     * 画面へ出す。ここで絞ると、対応外の入力に理由が出ないまま無反応になる。
     *
     * @param dropped ドロップされたパス (OS が渡した順)
     * @return 取込に使うパス文字列。通常ファイルが無ければ {@code null}
     */
    static String firstDroppedFile(List<? extends Path> dropped) {
        if (dropped == null) return null;
        for (Path path : dropped) {
            if (path == null) continue;
            try {
                if (Files.isDirectory(path)) continue;
            } catch (RuntimeException invalidPath) {
                continue; // パスとして読めない要素は取込へ回さない
            }
            return path.toString();
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private LocalImportDecisions() {}
}
