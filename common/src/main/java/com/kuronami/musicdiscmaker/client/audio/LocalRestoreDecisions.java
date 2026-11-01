package com.kuronami.musicdiscmaker.client.audio;

/**
 * 手元の原本をサーバーへ上げ直してよいかの、Minecraft に依存しない判定。
 * 再生スレッドが missing を受けた時にだけ呼ばれ、条件がそろわなければ何も送らない。
 */
final class LocalRestoreDecisions {

    /**
     * この hash をサーバーへ上げ直し始めてよいか。
     *
     * <p>条件はすべて必要:
     * <ul>
     *   <li>サーバーがこの再生で「原本が無い」と答えた — こちらから任意の hash を持ち込まない</li>
     *   <li>手元のキャッシュに hash と一致する検証済み原本がある — 送る中身がある</li>
     *   <li>同じ hash の上げ直しが走っていない — 1 本だけ</li>
     *   <li>直前に試して失敗していない — missing のたびに同じ 32MiB を送らない</li>
     * </ul>
     *
     * @param serverSaidMissing  この再生でサーバーが missing を返した
     * @param verifiedCopy       キャッシュに hash 一致の原本がある
     * @param inFlight           同じ hash の上げ直しが走っている
     * @param sinceLastAttemptMs 前回の試行からの経過。未試行は {@link Long#MAX_VALUE}
     * @param retryMillis        同じ hash を再度試すまでの間隔
     * @return 上げ直しを始めてよければ {@code true}
     */
    static boolean shouldRestore(boolean serverSaidMissing, boolean verifiedCopy, boolean inFlight,
                                 long sinceLastAttemptMs, long retryMillis) {
        return serverSaidMissing && verifiedCopy && !inFlight && sinceLastAttemptMs >= retryMillis;
    }

    private LocalRestoreDecisions() {}
}
