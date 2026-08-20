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
    /**
     * 相手は応答したが、要求を通さなかった (401/403/429 以外のステータス番号。400 / 404 / 410 / 5xx 等)。
     *
     * <p>{@link #CONNECTION_FAILED} と分けるのは、利用者に言うことが逆になるため。
     * こちらは<b>この端末とホストの間の通信そのものは成立している</b>ので、回線や DNS を
     * 疑わせる文面は嘘になる。401/403/429 だけは「この接続を名指しで拒んだ」なので
     * {@link #BOT_CHECK} に寄せる (MDM_DECISIONS D8)。
     *
     * <p>再試行は {@link #CONNECTION_FAILED} と同じく行う (5xx は開き直すと通ることがある)。
     * 代替ソース探しは行わない — 発火集合は MDM_DECISIONS D11 のまま変えない
     * ({@code MusicLoaderImpl#firesSubstitute})。
     */
    SOURCE_REFUSED,
    /** SSRF ガードが URL を拒否した (Track 1 が設定する)。 */
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
