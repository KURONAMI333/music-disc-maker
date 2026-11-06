package com.kuronami.musicdiscmaker.lavaplayer.api;

import java.util.regex.Pattern;

/**
 * YouTube を相手にする URL かどうかの判定。
 *
 * <h2>なぜ api パッケージに 1 つだけ置くか</h2>
 * 同じ判定を 2 つの classloader 側が使う。loader (隔離 classloader) は visitorId の入れ替えと
 * 代替ソース探しを出す相手を決めるのに、mod 側は「時間を置いて開き直す」対象かを決めるのに使う。
 * 片方だけ直すと、loader は bot 判定として開き直すのに client は諦める (またはその逆) という
 * 食い違いが起きる。api パッケージは両方から同一クラスとして見えるので、ここに置けば
 * 実装は 1 つで済む。
 *
 * <p>api パッケージなので JDK 以外に依存しない ({@link OpenStreamResult} と同じ制約)。
 */
public final class YoutubeUrls {

    /**
     * host が YouTube 系か。scheme 省略と {@code www} / {@code m} / {@code music} の接頭辞を許す。
     *
     * <p>{@code ^} で始まるので {@code find()} でも実質は前方一致。URL の途中に
     * {@code youtube.com/} を含むだけの別ホストは拾わない。
     */
    private static final Pattern YOUTUBE_HOST =
            Pattern.compile("(?i)^(?:https?://)?(?:www\\.|m\\.|music\\.)?(?:youtube\\.com|youtu\\.be)/");

    /** 検索クエリの接頭辞。結果が YouTube 側の答えで決まる。 */
    private static final String[] SEARCH_PREFIXES = {"ytsearch:", "ytmsearch:"};

    private YoutubeUrls() {
    }

    /**
     * その URL は YouTube の URL か。検索クエリ ({@code ytsearch:} / {@code ytmsearch:}) も含む。
     *
     * <p>検索を含めるのは、検索も YouTube 側の答えで決まるため (bot 判定では 0 件で返る)。
     * loader が visitorId を入れ替えてやり直す集合と、mod 側が時間を置いて開き直す集合を
     * 同じにするのがこの関数の役目。
     *
     * @param url 判定する URL ({@code null} 可)
     * @return YouTube の URL か検索クエリなら {@code true}
     */
    public static boolean isYoutube(String url) {
        return url != null && (isSearchQuery(url) || isYoutubeHost(url));
    }

    /**
     * host が YouTube 系の URL か (検索クエリは {@code false})。
     *
     * <p>URL の正規化 ({@code MusicLoaderImpl#normalizeYoutubeUrl}) はこちらを使う。検索クエリは
     * 正規化の対象ではないので、{@link #isYoutube} とは分けてある。
     *
     * @param url 判定する URL ({@code null} 可)
     * @return host が YouTube 系なら {@code true}
     */
    public static boolean isYoutubeHost(String url) {
        return url != null && YOUTUBE_HOST.matcher(url).find();
    }

    /**
     * YouTube の検索クエリか ({@code ytsearch:} / {@code ytmsearch:} で始まる)。
     *
     * @param url 判定する URL ({@code null} 可)
     * @return 検索クエリなら {@code true}
     */
    public static boolean isSearchQuery(String url) {
        if (url == null) {
            return false;
        }
        for (final String prefix : SEARCH_PREFIXES) {
            if (url.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
