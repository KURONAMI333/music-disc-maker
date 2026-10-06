package com.kuronami.musicdiscmaker.client.audio;

import java.io.InterruptedIOException;

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
                // "could not publish the normalized file" (hard link 不可・disk full 等の
                // 発行側 I/O 失敗) は入力の非対応ではないので import_failed へ。
                if (d.contains("output") || d.contains("parent directory") || d.contains("normalization")
                        || d.contains("i/o failed") || d.contains("encoding failed") || d.contains("publish"))
                    return "import_failed";
                return "unsupported";
            }
        }
        return "import_failed";
    }

    private LocalImportDecisions() {}
}
