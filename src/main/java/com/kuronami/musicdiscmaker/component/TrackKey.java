package com.kuronami.musicdiscmaker.component;

import java.util.Locale;

/**
 * 曲名+アーティストを正規化して disc の texture variant を決める。
 *
 * <p>サービス横断で「同じ曲＝同じ variant」に寄せるためのベストエフォート正規化:
 * 小文字化 → 括弧内 (Official Video) 等を除去 → ノイズ語除去 → 英数字/日本語以外を全削除
 * (空白も除去) してハッシュ。{@link String#hashCode()} は仕様上安定なので全 client で一致する。
 *
 * <p>限界: リマスター/カバー/ライブ版/大きな表記揺れは別 variant になりうる (完全保証は不可能)。
 * variant 数は {@code tools/gen_disc_textures.py} の VARIANTS と一致させること。
 */
public final class TrackKey {

    public static final int VARIANTS = 12;

    private TrackKey() {
    }

    /** track から決定的に variant index (0..VARIANTS-1) を返す。 */
    public static int variantIndex(CustomTrackData track) {
        if (track == null || track.isEmpty()) {
            return 0;
        }
        final String author = norm(track.author());
        String title = norm(track.title());
        // YouTube は "アーティスト - 曲名"、SoundCloud は "曲名" だけ等のズレを吸収するため、
        // タイトルに含まれるアーティスト名 (前後) を剥がして揃える。
        if (!author.isEmpty() && title.length() > author.length()) {
            if (title.startsWith(author)) {
                title = title.substring(author.length());
            } else if (title.endsWith(author)) {
                title = title.substring(0, title.length() - author.length());
            }
        }
        final String key = title + "|" + author;
        if (key.equals("|")) {
            return 0;
        }
        return Math.floorMod(key.hashCode(), VARIANTS);
    }

    static String norm(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String t = raw.toLowerCase(Locale.ROOT);
        // 括弧/角括弧/波括弧の中身を除去 ((Official Video) [HD] {Remix} 等)
        t = t.replaceAll("\\([^)]*\\)", " ");
        t = t.replaceAll("\\[[^\\]]*\\]", " ");
        t = t.replaceAll("\\{[^}]*\\}", " ");
        // よくあるノイズ語
        t = t.replaceAll(
                "\\b(official|video|audio|lyrics?|lyric|visualizer|mv|hd|hq|4k|8k|remaster(ed)?|feat|ft|prod|explicit|topic)\\b",
                " ");
        // チャンネル接尾辞
        t = t.replace("vevo", " ");
        // 英数字 + 日本語 (ひらがな/カタカナ/漢字) 以外を全部落とす (空白も)
        final StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            final char c = t.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
