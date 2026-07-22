package com.kuronami.musicdiscmaker.network;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * SSRF ガード。プレイヤーが与えた URL を LavaPlayer に渡す前に検査し、内部ネットワークへの
 * リクエスト誘導 (クラウドメタデータ・LAN 機器・ループバック等) を遮断する。
 *
 * <p>方式は <b>IP ベース遮断</b> (ドメイン allowlist ではない): ラジオ機能で任意の公開
 * HTTP ライブストリーム URL を許す必要があるため、ホストの allowlist は使えない。代わりに
 * <ol>
 *   <li>scheme を {@code http}/{@code https} に限定する ({@code file}/{@code ftp}/{@code jar} 等を弾く)</li>
 *   <li>ホスト名を DNS 解決し、得られた IP がプライベート帯 (RFC1918) / ループバック / リンクローカル
 *       (= クラウドメタデータ 169.254.169.254 を含む) / CGNAT / ULA 等なら拒否する</li>
 * </ol>
 *
 * <p><b>既知の限界</b> (上位で追う):
 * <ul>
 *   <li><b>DNS rebinding (TOCTOU)</b>: ここでは解決した全 A/AAAA を検査するが、LavaPlayer の
 *       HttpClient は接続時に再解決するため、検査後に内部 IP へ振り替える窓が残る。解決 IP を
 *       接続層にピン留めするのは LavaPlayer の HttpClient を差し替える必要がある。</li>
 *   <li><b>リダイレクト追従</b>: LavaPlayer の {@code HttpAudioSourceManager} と Spotify メタ取得は
 *       3xx を追うため、{@code http://public/redirect → http://169.254.169.254/} は初回 URL の検査を
 *       すり抜ける。接続層でのリダイレクト先再検査が必要。</li>
 * </ul>
 * どちらも「解決 IP へ接続する」形にできれば塞げるが、LavaPlayer の接続層に手を入れる必要があるため、
 * 現状は初回 URL の解決 IP 検査で妥協する。
 */
public final class UrlGuard {

    /** 検査結果。拒否理由を区別できるようにして上位のメッセージ分岐に渡せる形にする。 */
    public enum Reason {
        /** 通過。 */
        OK,
        /** URL が空・null・解析不能。 */
        INVALID_URL,
        /** http/https 以外の scheme (file/ftp/jar/data/gopher 等)。 */
        DISALLOWED_SCHEME,
        /** ホストが内部・予約 IP に解決された (SSRF 遮断)。 */
        BLOCKED_HOST,
        /** ホストを DNS 解決できなかった。 */
        UNRESOLVABLE
    }

    /** {@code scheme://} (階層型 URL) を検出する。scheme は RFC3986 準拠 (英字始まり + 英数 . + -)。 */
    private static final Pattern SCHEME = Pattern.compile("^([a-zA-Z][a-zA-Z0-9+.\\-]*)://");

    /**
     * {@code scheme:opaque} (非階層型 = {@code data:} / {@code jar:} / {@code mailto:} 等) を検出する。
     * colon の直後が数字・スラッシュのものは除外する ({@code host:port} や {@code scheme://} と区別)。
     */
    private static final Pattern OPAQUE_SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.\\-]*:(?![0-9/])");

    private UrlGuard() {
    }

    /**
     * URL を検査して理由を返す (例外を投げない・DNS 解決を伴う)。
     * ホストを持たない検索クエリ ({@code ytsearch:} 等) は SSRF 対象外なので {@link Reason#OK}。
     */
    public static Reason inspect(String rawUrl) {
        if (rawUrl == null) {
            return Reason.INVALID_URL;
        }
        final String url = rawUrl.trim();
        if (url.isEmpty()) {
            return Reason.INVALID_URL;
        }
        // ytsearch: / scsearch: 等の検索クエリは任意ホストへ接続しない (サービス側検索に載るだけ)。
        if (isSearchQuery(url)) {
            return Reason.OK;
        }

        final String forParsing;
        final java.util.regex.Matcher schemeM = SCHEME.matcher(url);
        if (schemeM.find()) {
            final String scheme = schemeM.group(1).toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return Reason.DISALLOWED_SCHEME;
            }
            forParsing = url;
        } else if (OPAQUE_SCHEME.matcher(url).find()) {
            // data: / jar: / mailto: 等の非対応 scheme。host:port (colon 後が数字) はここに来ない。
            return Reason.DISALLOWED_SCHEME;
        } else {
            // scheme 省略 (例: "youtube.com/watch?v=...") は https とみなしてホストを取り出す。
            forParsing = "https://" + url;
        }

        final String host = extractHost(forParsing);
        if (host == null || host.isEmpty()) {
            return Reason.INVALID_URL; // fail-closed: ホストを特定できない URL は通さない
        }

        final InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (final UnknownHostException ex) {
            return Reason.UNRESOLVABLE;
        }
        for (final InetAddress addr : addresses) {
            if (isBlockedAddress(addr)) {
                return Reason.BLOCKED_HOST;
            }
        }
        return Reason.OK;
    }

    /**
     * URL を検査し、通過しなければ {@link UrlBlockedException} を投げる。
     * 各再生・解決の入口 (server 解決 / client 再生 / 携帯再生) から呼ぶ。
     */
    public static void enforce(String url) {
        final Reason reason = inspect(url);
        if (reason != Reason.OK) {
            throw new UrlBlockedException(reason, url);
        }
    }

    /** scheme が {@code *search} (ytsearch, ytmsearch, scsearch, spsearch...) か。 */
    private static boolean isSearchQuery(String s) {
        final int colon = s.indexOf(':');
        if (colon <= 0) {
            return false;
        }
        // "://" を含む本物の URL は検索クエリではない。
        if (s.regionMatches(colon, "://", 0, 3)) {
            return false;
        }
        final String scheme = s.substring(0, colon).toLowerCase(Locale.ROOT);
        return scheme.endsWith("search");
    }

    /**
     * URL からホスト名だけを取り出す (DNS 解決はしない・純粋関数)。
     * IPv6 リテラルの角括弧は剥がす。{@code URI.getHost()} が null を返すホスト (下線を含む等) は
     * authority から手動抽出する。取り出せなければ null (fail-closed)。
     */
    static String extractHost(String urlWithScheme) {
        String host;
        try {
            final URI uri = new URI(urlWithScheme);
            host = uri.getHost();
            if (host == null) {
                host = hostFromAuthority(uri.getAuthority());
            }
        } catch (final URISyntaxException ex) {
            return null;
        }
        if (host == null) {
            return null;
        }
        // IPv6 リテラルの角括弧を剥がす (InetAddress は "::1" 形式を期待)。
        if (host.length() >= 2 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']') {
            host = host.substring(1, host.length() - 1);
        }
        return host;
    }

    /** {@code user:pass@host:port} 形式の authority からホストだけを取り出す。 */
    private static String hostFromAuthority(String authority) {
        if (authority == null || authority.isEmpty()) {
            return null;
        }
        String auth = authority;
        final int at = auth.lastIndexOf('@');
        if (at >= 0) {
            auth = auth.substring(at + 1);
        }
        if (auth.startsWith("[")) {
            final int end = auth.indexOf(']');
            return end > 0 ? auth.substring(0, end + 1) : auth;
        }
        final int colon = auth.indexOf(':');
        return colon >= 0 ? auth.substring(0, colon) : auth;
    }

    /**
     * IP が内部・予約帯なら true (純粋関数・DNS しない)。IPv4/IPv6 の両方を見る。
     *
     * <p>{@link InetAddress} 標準判定でループバック (127/8, ::1) / リンクローカル (169.254/16 = メタデータ,
     * fe80::/10) / サイトローカル (10/8, 172.16/12, 192.168/16) / any-local (0.0.0.0, ::) / マルチキャストを
     * 弾き、標準判定が漏らす CGNAT (100.64/10) / ブロードキャスト / "this network" (0/8) / IPv6 ULA (fc00::/7) /
     * IPv4-mapped IPv6 (::ffff:x.x.x.x) を手動で補う。
     */
    static boolean isBlockedAddress(InetAddress addr) {
        if (addr.isAnyLocalAddress()
                || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isMulticastAddress()) {
            return true;
        }
        final byte[] b = addr.getAddress();
        if (b.length == 4) {
            return isBlockedV4(b);
        }
        if (b.length == 16) {
            // IPv4-mapped ::ffff:a.b.c.d は展開して IPv4 判定 (通常は Inet4Address になるが保険)。
            if (isV4Mapped(b)) {
                return isBlockedV4(new byte[] {b[12], b[13], b[14], b[15]});
            }
            final int b0 = b[0] & 0xFF;
            // fc00::/7 unique local address。
            if ((b0 & 0xFE) == 0xFC) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlockedV4(byte[] b) {
        final int b0 = b[0] & 0xFF;
        final int b1 = b[1] & 0xFF;
        // 0.0.0.0/8 "this network"。
        if (b0 == 0) {
            return true;
        }
        // 100.64.0.0/10 CGNAT (RFC6598)。
        if (b0 == 100 && b1 >= 64 && b1 <= 127) {
            return true;
        }
        // 255.255.255.255 limited broadcast。
        if (b0 == 255 && b1 == 255 && (b[2] & 0xFF) == 255 && (b[3] & 0xFF) == 255) {
            return true;
        }
        return false;
    }

    private static boolean isV4Mapped(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return (b[10] & 0xFF) == 0xFF && (b[11] & 0xFF) == 0xFF;
    }
}
