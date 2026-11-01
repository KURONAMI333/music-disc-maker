package com.kuronami.musicdiscmaker.client.audio;

import java.util.UUID;

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
     * @param serverDecodes      サーバーが RESTORE を復号できる (版が一致する)。旧サーバーは例外で接続を切る
     * @param inFlight           同じ hash の上げ直しが走っている
     * @param sinceLastAttemptMs 前回の試行からの経過。未試行は {@link Long#MAX_VALUE}
     * @param retryMillis        同じ hash を再度試すまでの間隔
     * @return 上げ直しを始めてよければ {@code true}
     */
    static boolean shouldRestore(boolean serverSaidMissing, boolean verifiedCopy,
                                 boolean serverDecodes, boolean inFlight,
                                 long sinceLastAttemptMs, long retryMillis) {
        return serverSaidMissing && verifiedCopy && serverDecodes && !inFlight && sinceLastAttemptMs >= retryMillis;
    }

    /**
     * 上げ直しの応答を、この転送のものとして受けるか。
     *
     * <p>転送が始まる前の id はこちらが振った {@code request} だが、サーバーは RESTORE_READY に
     * <b>自分が store に開いた id</b> を載せる。そこで<b>最初の RESTORE_READY だけは id を見ずに受ける</b>
     * (hash・接続世代・まだ transfer id が無いことは呼び出し側が先に確かめている)。受けた時点で
     * その id を transfer id として覚え、以後の ACK・DONE・ERROR はその id とだけ照合する。
     * id を照合し続けると READY を捨ててしまい、サーバーは枠と予約容量を 60 秒塞いだ後に
     * expired で解放する (client はそれを繰り返す)。
     *
     * @param ready      応答が RESTORE_READY か
     * @param transferId 覚えている transfer id。未確定なら {@code null}
     * @param request    こちらが振った request id
     * @param packetId   届いた包の id
     * @return この転送の応答として扱うなら {@code true}
     */
    static boolean acceptsRestoreReply(boolean ready, UUID transferId, UUID request, UUID packetId) {
        if (packetId == null) return false;
        if (ready && transferId == null) return true;
        return (transferId != null ? transferId : request).equals(packetId);
    }

    private LocalRestoreDecisions() {}
}
