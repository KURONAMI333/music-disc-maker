package com.kuronami.musicdiscmaker.lavaplayer;

import java.util.ArrayList;
import java.util.List;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureClassifier;
import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;

import dev.lavalink.youtube.AllClientsFailedException;
import dev.lavalink.youtube.ClientException;

/**
 * youtube-source が client ごとに持っている失敗理由を取り出す。<b>隔離 classloader 側に置く</b> —
 * {@code AllClientsFailedException} / {@code ClientException} は youtube-source の型なので、
 * mod 側 (api パッケージ) からは参照できない。
 *
 * <h2>なぜ要るか — 一番外側の文面は診断に使えない</h2>
 * {@code AllClientsFailedException} のメッセージは
 * {@code "(yts.version: 1.18.2) All clients failed to load the item."} で始まり、各 client の
 * 理由はその後ろに<b>空行 2 つを挟んで</b>連結される (2026-08-18 実測。
 * {@code AllClientsFailedException.createMessage} のバイトコードと一致)。
 * 一方、境界の向こうへ渡す短い詳細は<b>先頭行しか採っていなかった</b>ので、
 * 利用者の画面にもログにも「全部の client が失敗した」としか出ず、<b>どう失敗したかが消えていた</b>。
 *
 * <p>この型は {@code getClientExceptions()} から構造として取り出す。連鎖 (cause) をたどっても
 * 届かない — {@code AllClientsFailedException} の cause は {@code null} 固定で、client ごとの
 * 例外は<b>リストにしか入っていない</b> (同じくバイトコードで確認)。
 *
 * <h2>短い詳細と長い詳細を分ける</h2>
 * チャットは 1 行なので {@link #shortDetail} を出す。client ごとのスタックトレースまで含む
 * {@link #verbose} は {@code latest.log} 側に出す (利用者がそのまま貼れる)。
 */
final class ClientFailureDetails {

    /** 原因のたどり方の上限 (循環した cause chain で回り続けないための歯止め)。 */
    private static final int MAX_CAUSE_DEPTH = 12;
    /** {@code ClientException} のメッセージ定型 {@code "Client [%s] failed: %s"} の後半の目印。 */
    private static final String CLIENT_FAILED_MARKER = "] failed: ";

    private ClientFailureDetails() {
    }

    /**
     * 連鎖の中から youtube-source の集約例外を探す。
     *
     * @param thrown 探したい例外 ({@code null} 可)
     * @return 見つかった集約例外、無ければ {@code null}
     */
    static AllClientsFailedException aggregate(Throwable thrown) {
        Throwable current = thrown;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof AllClientsFailedException all) {
                return all;
            }
            final Throwable next = current.getCause();
            current = next == current ? null : next;
        }
        return null;
    }

    /**
     * client ごとの理由を {@code "ANDROID_VR: This video requires login."} の形で 1 本ずつ返す。
     *
     * <p>{@code ClientException} のメッセージは {@code "Client [ANDROID_VR] failed: <理由>"} なので、
     * 定型の前半を落として identifier と理由だけにする (チャット 1 行に入れるため)。定型に
     * 一致しない場合はメッセージをそのまま使う (<b>推測で削らない</b>)。
     *
     * @param thrown 集約例外を含みうる例外 ({@code null} 可)
     * @return client ごとの 1 行。集約例外が無ければ空リスト
     */
    static List<String> clientReasons(Throwable thrown) {
        final AllClientsFailedException all = aggregate(thrown);
        final List<String> reasons = new ArrayList<>();
        if (all == null) {
            return reasons;
        }
        for (final ClientException each : all.getClientExceptions()) {
            if (each == null) {
                continue;
            }
            final String identifier = identifierOf(each);
            final String reason = reasonOf(each);
            reasons.add(identifier.isEmpty() ? reason : identifier + ": " + reason);
        }
        return reasons;
    }

    /**
     * チャットに載せる短い詳細。client が複数なら {@code " | "} で繋ぐ。
     *
     * <p>集約例外でない場合は<b>分類の根拠になった例外</b>の型名 + メッセージの先頭行を返す
     * ({@link FailureClassifier#blamed})。一番外側をそのまま採ると、lavaplayer の定型文
     * (「Connecting to the URL failed.」等) しか出ず、実際に落ちた場所の文面
     * (「UnknownHostException: ...」) が消える。
     *
     * @param thrown 詳細を採りたい例外 ({@code null} 可)
     * @return 1 行の技術詳細 ({@code null} は返さない)
     */
    static String shortDetail(Throwable thrown) {
        final List<String> reasons = clientReasons(thrown);
        if (reasons.isEmpty()) {
            return describe(FailureClassifier.blamed(thrown));
        }
        return String.join(" | ", reasons);
    }

    /**
     * ログに出す長い詳細。client ごとの {@code getFormattedMessage()} (理由 + 何段かの
     * スタックフレーム + {@code Caused by:}) をそのまま並べる。
     *
     * @param thrown 詳細を採りたい例外 ({@code null} 可)
     * @return 複数行の詳細。集約例外でなければ空文字
     */
    static String verbose(Throwable thrown) {
        final AllClientsFailedException all = aggregate(thrown);
        if (all == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        for (final ClientException each : all.getClientExceptions()) {
            if (each == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(System.lineSeparator());
            }
            sb.append(each.getFormattedMessage());
        }
        return sb.toString();
    }

    /**
     * 失敗を分類する。集約例外なら<b>client ごとに分類して、語句で判別できた最初のものを採る</b>。
     *
     * <h2>連結された 1 本の文字列に当てない理由</h2>
     * 集約例外のメッセージは全 client の理由を連結して持つので、そこへ語句一致を当てると
     * <b>どの client の理由かに関係なく</b>、語句の固定優先順位だけで分類が決まる
     * (どこか 1 つに bot 判定の語があれば、別の client の年齢制限や地域制限より先に採られる)。
     * client 単位に切ってから分類すれば、少なくとも「1 つの client の中では 1 つの理由」になる。
     *
     * <p>ステータス番号だけを根拠にした {@link FailureReason#SOURCE_REFUSED} は<b>いったん保留し、
     * どの client も語句を持たなかった時にだけ採る</b> ({@link FailureClassifier#isSpecific})。
     * 対等に扱うと、番号を返した client が先に並ぶだけで後ろの client の年齢制限や非公開が消える
     * = client 単位に切った意味が無くなる。
     *
     * <p>client 順は youtube-source が試した順そのもの。現行構成は {@code AndroidVr} 単独なので
     * リストは常に 1 件で、順序の選択そのものは出荷構成では効かない
     * ({@code MusicLoaderImpl#registerYoutube} の javadoc)。
     *
     * @param thrown 分類したい例外 ({@code null} 可)
     * @return 分類結果
     */
    static FailureReason classify(Throwable thrown) {
        FailureReason refused = null;
        for (final String reason : clientReasons(thrown)) {
            final FailureReason classified = FailureClassifier.classifyMessage(reason);
            if (FailureClassifier.isSpecific(classified)) {
                return classified;
            }
            if (classified == FailureReason.SOURCE_REFUSED && refused == null) {
                refused = classified;
            }
        }
        return refused != null ? refused : FailureClassifier.classify(thrown);
    }

    /**
     * 例外を 1 行の技術詳細に畳む (型名 + メッセージの先頭行)。
     *
     * @param thrown 畳みたい例外 ({@code null} 可)
     * @return 1 行の技術詳細
     */
    static String describe(Throwable thrown) {
        if (thrown == null) {
            return "";
        }
        final String message = thrown.getMessage();
        final String head = message == null ? "" : firstLine(message);
        return head.isEmpty() ? thrown.getClass().getSimpleName()
                : thrown.getClass().getSimpleName() + ": " + head;
    }

    /** 最初の非空行。 */
    static String firstLine(String text) {
        for (final String line : text.split("\\R")) {
            final String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return "";
    }

    private static String identifierOf(ClientException exception) {
        try {
            return exception.getClient() == null ? "" : exception.getClient().getIdentifier();
        } catch (final Throwable ignored) {
            // identifier が取れなくても理由本文は出す
            return "";
        }
    }

    /** {@code "Client [X] failed: "} を落とした理由本文 (定型でなければメッセージの先頭行)。 */
    private static String reasonOf(ClientException exception) {
        final String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return describe(exception.getCause());
        }
        final String head = firstLine(message);
        final int marker = head.indexOf(CLIENT_FAILED_MARKER);
        return marker < 0 ? head : head.substring(marker + CLIENT_FAILED_MARKER.length());
    }
}
