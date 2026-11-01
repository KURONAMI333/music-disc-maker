package com.kuronami.musicdiscmaker.lavaplayer;

import java.util.Locale;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Apple Music / Deezer の<b>単曲リンク</b>から曲名・アーティスト・尺を読み、YouTube 検索の語にする。
 *
 * <p>プレイヤーが求めているのは「その曲が流れること」で、リンクの対応表ではない。Spotify を
 * 通すのに Apple Music のリンクだけ「非対応」で返すのは、URL の綴りが違うだけで盤が作れない
 * という同じ失敗を、理由だけ変えて繰り返している。ここでは Spotify と同じ
 * 「サービス=メタ情報、YouTube=再生」方式を、単曲リンクを持つサービスへ広げる。
 *
 * <h2>なぜ曲ページの HTML ではなく、サービスの照会口を読むか</h2>
 * Spotify は {@code og:title} をソーシャルクローラー向けに SSR するが、相手が変われば同じ手が
 * 効く保証は無い。<b>両サービスの曲ページが何を返すかは確かめていない</b> (この環境から実ページを
 * 取りに行かない方針) ので、確かめていない HTML の og タグに賭けるより、<b>返る形の決まっている
 * 照会口</b> (iTunes Lookup API / Deezer API。どちらも認証不要で、曲 ID から曲名・アーティスト・
 * 尺を返す) を読む。ID は URL から取り出した数字だけで、URL そのものを問い合わせ先へ混ぜない。
 *
 * <h2>なぜホストの許可リストで縛るか</h2>
 * 任意のサイトの HTML を読みに行くと、プレイヤーが入力した文字列がそのまま取得先になる。
 * ここは<b>ホスト名の完全一致</b> (前方一致・末尾一致にしない) と<b>単曲のパス形</b>の両方が
 * 通った時だけ照会先を組み立てる。アルバム・プレイリスト・アーティストの URL は従来どおり
 * 対象外 (盤 1 枚 = 曲 1 つで、選ぶ手が足りないため)。
 *
 * <p>loader は隔離 classloader で動くので mod 側の {@code UrlGuard} を呼べない。取得の直前に
 * 通す口 ({@code UrlGate}) は {@code MusicLoaderImpl} が持っているので、実際の検査は
 * そちらへ任せる (この class は URL を作るところまで)。
 */
final class MusicServiceLookup {

    private static final Logger LOGGER = LoggerFactory.getLogger(MusicServiceLookup.class);

    /** 照会先を持つサービス。ログと失敗の文面に出す名前だけを持つ。 */
    enum Service {
        APPLE_MUSIC("Apple Music"),
        DEEZER("Deezer");

        private final String label;

        Service(String label) {
            this.label = label;
        }

        /** ログ・失敗の詳細に出す表示名。 */
        String label() {
            return label;
        }
    }

    /**
     * 単曲リンク 1 本。
     *
     * @param service        読む相手
     * @param pageUrl        プレイヤーが入れた URL (ログ用)
     * @param lookupUrl      曲名・アーティスト・尺を返す照会先 (この class が組み立てた固定の形)
     * @param retryLookupUrl 国コード付きの照会が 0 件だった時に 1 回だけ引き直す先。
     *                       国コードが無い URL では {@code null}
     */
    record Link(Service service, String pageUrl, String lookupUrl, String retryLookupUrl) {

        Link(Service service, String pageUrl, String lookupUrl) {
            this(service, pageUrl, lookupUrl, null);
        }
    }

    /**
     * サービスから読んだ曲の情報。
     *
     * @param title      曲名
     * @param artist     アーティスト (空のことがある)
     * @param durationMs 尺 (ms)。分からなければ {@code 0}
     */
    record Meta(String title, String artist, long durationMs) {
    }

    /** 照会 1 回分の結末。 */
    enum ReadStatus {
        /** 曲名・アーティストを読めた。 */
        FOUND,
        /** 相手が「その ID の曲は無い」と確定して答えた。取り直しても変わらない。 */
        NO_TRACK,
        /** 届かない・読めない応答。時間を置けば変わりうる。 */
        UNREADABLE
    }

    /**
     * 照会 1 回分の結果。
     *
     * @param status 結末
     * @param meta   {@link ReadStatus#FOUND} の時だけ曲の情報
     */
    record ReadResult(ReadStatus status, Meta meta) {

        static ReadResult found(Meta meta) {
            return new ReadResult(ReadStatus.FOUND, meta);
        }

        static ReadResult noTrack() {
            return new ReadResult(ReadStatus.NO_TRACK, null);
        }

        static ReadResult unreadable() {
            return new ReadResult(ReadStatus.UNREADABLE, null);
        }
    }

    /** {@code https://music.apple.com} と {@code https://geo.music.apple.com} だけ。 */
    private static final Pattern APPLE_ROOT = Pattern.compile(
            "(?i)^https?://(?:geo\\.)?music\\.apple\\.com/");
    /**
     * 国コードの段 ({@code /us/}, {@code /jp/} 等) は任意。付かない形も実在する。
     * 大文字で書かれたリンクも同じ国として扱う。
     */
    private static final Pattern LOCALE_SEGMENT = Pattern.compile("^([A-Za-z]{2})/");
    /** {@code /song/<slug>/<trackId>}。 */
    private static final Pattern APPLE_SONG = Pattern.compile(
            "(?i)^song/(?:[^?#]*/)?(\\d+)(?:[?#].*)?$");
    /** {@code /album/<slug>/<albumId>?i=<trackId>}。{@code i} が無ければアルバム全体で対象外。 */
    private static final Pattern APPLE_ALBUM = Pattern.compile("(?i)^album/");
    private static final Pattern TRACK_PARAM = Pattern.compile("(?i)[?&]i=(\\d+)");

    /** {@code https://deezer.com} の {@code www} / {@code m} だけ。 */
    private static final Pattern DEEZER_ROOT = Pattern.compile(
            "(?i)^https?://(?:www\\.|m\\.)?deezer\\.com/");
    /** {@code /track/<id>}。アルバム・プレイリスト・アーティストはここへ来ない。 */
    private static final Pattern DEEZER_TRACK = Pattern.compile("(?i)^track/(\\d+)(?:[/?#].*)?$");

    /** Deezer の {@code duration} は秒。ms へ直して尺の照合に使う。 */
    private static final long SECONDS_TO_MS = 1000L;

    /** 「その ID の曲が無い」。空の {@code results} を曲名の空文字として扱わないための目印。 */
    private static final Pattern APPLE_NO_RESULT = Pattern.compile("\"resultCount\"\\s*:\\s*0");
    /** Deezer の失敗応答 ({@code {"error":...}})。曲名の欄を持たない。 */
    private static final Pattern DEEZER_ERROR = Pattern.compile("\"error\"");

    private MusicServiceLookup() {
    }

    /**
     * その URL が照会できる単曲リンクかを判定し、照会先を組み立てる。
     *
     * <p><b>ホスト名は完全一致</b>で見る。{@code www.deezer.com.evil.example} や
     * {@code music.apple.com@evil.example} のような紛らわしい文字列を通さないため、
     * 「このホストで始まる」ではなく「スキーム + ホスト + スラッシュまでを丸ごと一致させて
     * 残りをパスとして読む」形にしてある。
     *
     * @param url プレイヤーが入れた URL ({@code null} 可)
     * @return 照会できる単曲リンク。対象外・単曲でない・形が読めない時は {@code null}
     */
    static Link match(String url) {
        if (url == null) {
            return null;
        }
        final String trimmed = url.trim();
        final Matcher apple = APPLE_ROOT.matcher(trimmed);
        if (apple.find()) {
            final String path = trimmed.substring(apple.end());
            final Matcher locale = LOCALE_SEGMENT.matcher(path);
            final String country;
            final String rest;
            if (locale.find()) {
                country = locale.group(1).toLowerCase(Locale.ROOT);
                rest = path.substring(locale.end());
            } else {
                country = null;
                rest = path;
            }
            final String trackId = appleTrackId(rest, trimmed);
            return trackId == null ? null
                    : new Link(Service.APPLE_MUSIC, trimmed, appleLookupUrl(trackId, country),
                            country == null ? null : appleLookupUrl(trackId, null));
        }
        final Matcher deezer = DEEZER_ROOT.matcher(trimmed);
        if (deezer.find()) {
            final String path = trimmed.substring(deezer.end());
            final Matcher locale = LOCALE_SEGMENT.matcher(path);
            final String rest = locale.find() ? path.substring(locale.end()) : path;
            final Matcher track = DEEZER_TRACK.matcher(rest);
            return track.matches() ? new Link(Service.DEEZER, trimmed, deezerLookupUrl(track.group(1)))
                    : null;
        }
        return null;
    }

    /**
     * Apple Music の残り (ホストと国コードを剥がしたもの) から曲の ID を取り出す。
     *
     * @param rest    ホスト以降のパス
     * @param fullUrl パラメータを読む時の元 URL ({@code ?i=})
     * @return 曲の ID。単曲でなければ {@code null}
     */
    private static String appleTrackId(String rest, String fullUrl) {
        final Matcher song = APPLE_SONG.matcher(rest);
        if (song.matches()) {
            return song.group(1);
        }
        if (!APPLE_ALBUM.matcher(rest).find()) {
            return null;
        }
        // アルバムページは ?i=<曲 ID> が付いている時だけ単曲として扱う。
        final Matcher param = TRACK_PARAM.matcher(fullUrl);
        return param.find() ? param.group(1) : null;
    }

    /**
     * iTunes Lookup API。曲 ID は Apple Music と同じ採番。
     *
     * <p>国コードが分かる時は {@code country} を付ける。同じ曲 ID でも国によって扱いが
     * 変わる (配信停止・別版) ため、リンクの国をそのまま照会へ渡す。
     *
     * @param trackId 曲 ID
     * @param country 小文字の国コード。分からなければ {@code null}
     */
    private static String appleLookupUrl(String trackId, String country) {
        final String base = "https://itunes.apple.com/lookup?id=" + trackId + "&entity=song";
        return country == null ? base : base + "&country=" + country;
    }

    /** Deezer の公開 API。トラック照会は認証不要。 */
    private static String deezerLookupUrl(String trackId) {
        return "https://api.deezer.com/track/" + trackId;
    }

    /**
     * 照会先を読み、曲の情報を返す。取り直し・締切は {@link PageMeta} に任せる。
     *
     * @param link    照会できる単曲リンク
     * @param fetcher 1 回分の取得 (テストは差し替えてネットワークに出ない)
     * @param clockMs 経過を測る時計
     * @param sleeper 試行の間の待ち
     * @return 読めた曲の情報。読めなければ {@code null}
     */
    static Meta read(Link link, PageMeta.Fetcher fetcher, LongSupplier clockMs,
            PageMeta.Sleeper sleeper) {
        return readTrack(link, fetcher, clockMs, sleeper).meta();
    }

    /**
     * 照会先を読み、<b>曲が無いと確定したのか、届かなかったのか</b>まで返す。
     *
     * <p>iTunes の {@code resultCount:0} と Deezer の {@code error} は「その ID の曲が無い」の
     * 確定回答で、取り直しても同じものが返る。これを通信失敗と同じ扱いで 3 回叩くと、存在
     * しない ID に無駄な往復を重ねたうえ、利用者へは「接続できなかった」と嘘の理由を返す。
     * 逆に、空の本文・曲名の欠けた応答は取り直す価値があるので {@link ReadStatus#UNREADABLE}
     * のまま {@link PageMeta#fetchHtml} に再試行させる。
     *
     * <p>Apple Music のリンクに国コードが付いている時は、その国で 0 件だった時だけ国コード
     * 無しの照会を 1 回試す (国によって配信状況が違い、URL の国で引けない曲がある)。
     *
     * @param link    照会できる単曲リンク
     * @param fetcher 1 回分の取得 (テストは差し替えてネットワークに出ない)
     * @param clockMs 経過を測る時計
     * @param sleeper 試行の間の待ち
     * @return 照会の結末
     */
    static ReadResult readTrack(Link link, PageMeta.Fetcher fetcher, LongSupplier clockMs,
            PageMeta.Sleeper sleeper) {
        ReadResult result = fetch(link, link.lookupUrl(), fetcher, clockMs, sleeper);
        if (result.status() == ReadStatus.NO_TRACK && link.retryLookupUrl() != null) {
            LOGGER.info("No {} track in the link's country; retrying without it ({})",
                    link.service().label(), link.pageUrl());
            result = fetch(link, link.retryLookupUrl(), fetcher, clockMs, sleeper);
        }
        if (result.status() != ReadStatus.FOUND) {
            LOGGER.info("Could not read the {} track metadata ({}) [{}]",
                    link.service().label(), link.pageUrl(), result.status());
        }
        return result;
    }

    /** 1 つの照会先を、確定回答が返るまで試す。 */
    private static ReadResult fetch(Link link, String url, PageMeta.Fetcher fetcher,
            LongSupplier clockMs, PageMeta.Sleeper sleeper) {
        final String body = PageMeta.fetchHtml(url, link.service().label(),
                text -> parseOutcome(link.service(), text).status() != ReadStatus.UNREADABLE,
                fetcher, clockMs, sleeper);
        return body == null ? ReadResult.unreadable() : parseOutcome(link.service(), body);
    }

    /**
     * サービスの応答を {@link Meta} にする。
     *
     * @param service 読む相手 (応答の形が違う)
     * @param body    応答の本文
     * @return 曲名が読めなければ {@code null}
     */
    static Meta parse(Service service, String body) {
        return parseOutcome(service, body).meta();
    }

    /** 応答を、読めた・曲が無い・読めないの 3 つに分ける。 */
    private static ReadResult parseOutcome(Service service, String body) {
        if (body == null || body.isBlank()) {
            return ReadResult.unreadable();
        }
        return switch (service) {
            case APPLE_MUSIC -> APPLE_NO_RESULT.matcher(body).find()
                    ? ReadResult.noTrack() : parseApple(body);
            case DEEZER -> DEEZER_ERROR.matcher(body).find()
                    ? ReadResult.noTrack() : parseDeezer(body);
        };
    }

    /**
     * iTunes Lookup API の応答。
     *
     * <p>形は {@code {"resultCount":1,"results":[{"trackName":...,"artistName":...,
     * "trackTimeMillis":...}]}}。{@code resultCount} が {@code 0} の時は「その ID の曲が
     * 無い」= 読めなかった時と同じ扱いにする (曲名を作れない)。
     */
    private static ReadResult parseApple(String body) {
        final String title = jsonString(body, "trackName");
        if (title == null || title.isBlank()) {
            return ReadResult.unreadable();
        }
        final String artist = jsonString(body, "artistName");
        // trackTimeMillis が無い版もある。0 のままにすると尺の照合をしない (従来の Spotify と同じ)。
        return ReadResult.found(new Meta(title.trim(), artist == null ? "" : artist.trim(),
                jsonLong(body, "trackTimeMillis")));
    }

    /**
     * Deezer API の応答。
     *
     * <p>Deezer は {@code album} の中にも {@code title} を持つので、<b>アルバムの手前まで</b>だけを
     * 読む。そうしないと曲名の欄にアルバム名が入り、検索語が別物になる。
     * {@code duration} は<b>秒</b>なので ms へ直す (尺の照合は ms で揃っている)。
     */
    private static ReadResult parseDeezer(String body) {
        final int album = body.indexOf("\"album\"");
        final String head = album > 0 ? body.substring(0, album) : body;
        final String title = jsonString(head, "title");
        if (title == null || title.isBlank()) {
            return ReadResult.unreadable();
        }
        final String artist = artistName(head);
        final long seconds = jsonLong(head, "duration");
        return ReadResult.found(new Meta(title.trim(), artist == null ? "" : artist.trim(),
                seconds * SECONDS_TO_MS));
    }

    /**
     * Deezer の {@code "artist":{"id":...,"name":"..."}} から名前を取る。
     *
     * <p>トップレベルにも {@code name} を持つ応答 (アーティスト照会) があるので、
     * {@code "artist"} の後ろだけを見る。
     */
    private static String artistName(String head) {
        final int index = head.indexOf("\"artist\"");
        if (index < 0) {
            return null;
        }
        return jsonString(head.substring(index), "name");
    }

    /**
     * JSON の {@code "<field>":"..."} を 1 つ取り出す。
     *
     * <p>jsoup も JSON パーサも持ち込まない (隔離 classloader に同梱する jar を増やさない) ので、
     * 必要な欄だけを正規表現で拾う。値の中の {@code \"} を終端と取り違えないよう、
     * エスケープを 1 文字分だけ読み飛ばす形にしてある。
     *
     * <p>取り出した値は<b>ここで 1 回だけ</b> {@link #unescapeJson} に通す。呼び出し側でも
     * 戻すと、値の中の本物のバックスラッシュがもう一段崩れる。
     */
    private static String jsonString(String json, String field) {
        final Matcher m = Pattern.compile("\"" + Pattern.quote(field)
                + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(json);
        return m.find() ? unescapeJson(m.group(1)) : null;
    }

    /** JSON の {@code "<field>":123} を 1 つ取り出す。無ければ {@code 0}。 */
    private static long jsonLong(String json, String field) {
        final Matcher m = Pattern.compile("\"" + Pattern.quote(field)
                + "\"\\s*:\\s*(-?\\d+)").matcher(json);
        return m.find() ? Math.max(0L, Long.parseLong(m.group(1))) : 0L;
    }

    /**
     * JSON 文字列のエスケープを戻す。曲名に {@code \u0026} 等が残ると検索語が別物になる。
     *
     * @param value 生の値 (引用符の内側)
     * @return 人が読む形の値
     */
    static String unescapeJson(String value) {
        if (value == null || value.indexOf('\\') < 0) {
            return value;
        }
        final StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                out.append(c);
                continue;
            }
            final char escaped = value.charAt(++i);
            switch (escaped) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    if (i + 4 < value.length()) {
                        final int code = hex(value, i + 1);
                        if (code >= 0) {
                            out.append((char) code);
                            i += 4;
                            break;
                        }
                    }
                    out.append("\\u");
                }
                // \" \\ \/ はその文字自身。
                default -> out.append(escaped);
            }
        }
        return out.toString();
    }

    /** 4 桁の 16 進を読む。読めなければ {@code -1}。 */
    private static int hex(String value, int from) {
        int result = 0;
        for (int i = from; i < from + 4 && i < value.length(); i++) {
            final int digit = Character.digit(value.charAt(i), 16);
            if (digit < 0) {
                return -1;
            }
            result = result * 16 + digit;
        }
        return result;
    }
}
