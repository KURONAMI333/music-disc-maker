package com.kuronami.musicdiscmaker.component;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    /** バニラ染料16色と1対1で対応する自動アクセント数。 */
    public static final int AUTO_DYE_VARIANTS = 16;

    /**
     * 曲データごとの結果キャッシュ上限。表示中は tint / item model property が
     * 毎フレーム・毎quad呼ばれるため、正規化 (replaceAll) をそこで回さない。
     */
    private static final int INDEX_CACHE_LIMIT = 2048;
    private static final Map<CustomTrackData, Integer> VARIANT_INDEX_CACHE = new ConcurrentHashMap<>();
    private static final Map<CustomTrackData, Integer> DYE_INDEX_CACHE = new ConcurrentHashMap<>();

    private TrackKey() {
    }

    /** track から決定的に variant index (0..VARIANTS-1) を返す。 */
    public static int variantIndex(CustomTrackData track) {
        if (track == null || track.isEmpty()) {
            return 0;
        }
        final Integer cached = VARIANT_INDEX_CACHE.get(track);
        if (cached != null) {
            return cached;
        }
        final int index = variantIndex(track.title(), track.author());
        remember(VARIANT_INDEX_CACHE, track, index);
        return index;
    }

    /** track から決定的に自動アクセント染料の index (0..15) を返す。 */
    public static int automaticDyeIndex(CustomTrackData track) {
        if (track == null || track.isEmpty()) {
            return 0;
        }
        final Integer cached = DYE_INDEX_CACHE.get(track);
        if (cached != null) {
            return cached;
        }
        final int index = automaticDyeIndex(track.title(), track.author());
        remember(DYE_INDEX_CACHE, track, index);
        return index;
    }

    private static void remember(Map<CustomTrackData, Integer> cache, CustomTrackData track, int index) {
        if (cache.size() >= INDEX_CACHE_LIMIT) {
            cache.clear();
        }
        cache.put(track, index);
    }

    static int variantIndex(String title, String author) {
        return index(title, author, VARIANTS);
    }

    static int automaticDyeIndex(String title, String author) {
        return index(title, author, AUTO_DYE_VARIANTS);
    }

    /**
     * 旧12variantと新16染料で正規化とhashを共用する。modulusだけを変えるため、
     * {@link #variantIndex} の既存結果は変わらない。
     */
    private static int index(String rawTitle, String rawAuthor, int variants) {
        final String author = norm(rawAuthor);
        String title = norm(rawTitle);
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
        return Math.floorMod(key.hashCode(), variants);
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
