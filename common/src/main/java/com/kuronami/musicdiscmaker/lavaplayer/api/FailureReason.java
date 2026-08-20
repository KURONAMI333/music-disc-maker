package com.kuronami.musicdiscmaker.lavaplayer.api;

/**
 * URL 解決が失敗した理由。GUI に理由別メッセージを出すために impl (隔離 classloader) から
 * mod 側へ返す。api パッケージなので bridge classloader が両側に同一クラスを渡す。
 *
 * <p>enum 名は BlockEntity の同期 NBT にそのまま書かれるので、リネームは互換を壊す。
 */
public enum FailureReason {

    /**
     * 分類できない一般的な失敗。<b>分類器の既定値</b>でもある。
     *
     * <p>ここに「回線が落ちている」等の推測を混ぜないこと。分類器がどれにも当てられなかった
     * ことだけを表す。{@link #CONNECTION_FAILED} と分けているのは、根拠なく回線を疑わせる
     * 文面を利用者に出さないため。
     */
    UNKNOWN,
    /** 対応していないサービス / URL 形式 (どの source manager も一致しない)。 */
    UNSUPPORTED_URL,
    /** 動画が非公開・削除済み・存在しない。 */
    PRIVATE_OR_REMOVED,
    /** 地域制限で再生できない。 */
    REGION_LOCKED,
    /** 年齢制限で再生できない。 */
    AGE_RESTRICTED,
    /**
     * ホストへ到達できなかった (DNS 解決失敗・接続拒否・タイムアウト・切断)。
     *
     * <p><b>例外の型で積極的に判別した時だけ</b>ここへ落とす ({@code FailureClassifier} の
     * {@code hasNetworkCause})。既定値ではないので、この理由が出ている時は「この端末とホストの
     * 間で通信が成立しなかった」と言い切ってよい。
     */
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
