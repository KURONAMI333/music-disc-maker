package com.kuronami.musicdiscmaker.lavaplayer.api;

/**
 * URL 解決が失敗した理由。GUI に理由別メッセージを出すために impl (隔離 classloader) から
 * mod 側へ返す。api パッケージなので bridge classloader が両側に同一クラスを渡す。
 *
 * <p>enum 名は BlockEntity の同期 NBT にそのまま書かれるので、リネームは互換を壊す。
 */
public enum FailureReason {

    /** 分類できない一般的な失敗。 */
    UNKNOWN,
    /** 対応していないサービス / URL 形式 (どの source manager も一致しない)。 */
    UNSUPPORTED_URL,
    /** 動画が非公開・削除済み・存在しない。 */
    PRIVATE_OR_REMOVED,
    /** 地域制限で再生できない。 */
    REGION_LOCKED,
    /** 年齢制限で再生できない。 */
    AGE_RESTRICTED,
    /** ネットワーク接続失敗・タイムアウト・一時的な障害。 */
    CONNECTION_FAILED,
    /** SSRF ガードが URL を拒否した。 */
    BLOCKED_URL,
    /** YouTube の bot 判定でログインを要求された (datacenter IP でよく起きる。接続失敗ではない)。 */
    BOT_CHECK;

    /** NBT / 同期から安全に復元する (未知の名前は UNKNOWN)。 */
    public static FailureReason fromName(String name) {
        if (name == null || name.isBlank()) {
            return UNKNOWN;
        }
        try {
            return FailureReason.valueOf(name);
        } catch (final IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}
