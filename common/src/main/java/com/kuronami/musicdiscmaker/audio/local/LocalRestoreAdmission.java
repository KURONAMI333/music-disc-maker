package com.kuronami.musicdiscmaker.audio.local;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 上げ直しの受け入れ順。Minecraft に依存しないので、失敗した時に「原本が無い」の記録が
 * 残るかどうかを実物の store で確かめられる。
 *
 * <p><b>順序に意味がある。</b>容量 (quota) と転送枠を先に確かめ、store の枠を開いてから
 * 「原本が無い」と答えた記録を消費する。記録を先に消費すると、枠や容量で断った時に通知だけが
 * 消え、client は次の再生で missing を受け直すまで上げ直せない (記録は 1 回で消費する設計)。
 */
final class LocalRestoreAdmission {

    /** 「原本が無い」と答えた記録を 1 回消費する。無い・古いなら {@code false}。 */
    @FunctionalInterface
    interface Notice {
        boolean consume();
    }

    /**
     * 上げ直しの枠を開く。
     *
     * @param store   原本の置き場
     * @param quota   player ごとの容量
     * @param player  上げ直す player
     * @param hash    原本の hash
     * @param size    原本の大きさ
     * @param now     単調時計 (ms)
     * @param notice  記録の消費。枠を開いた後にだけ呼ぶ
     * @return 受け入れるなら store の transfer id。記録が無ければ枠を閉じて {@code null}
     * @throws IOException 容量・枠が足りない、hash とサイズが不正など
     */
    static UUID open(LocalAudioStore store, LocalAudioQuota quota, UUID player, String hash, long size,
                     long now, Notice notice) throws IOException {
        quota.check(player, hash, size);
        final UUID storeId = store.begin(player, hash, size, now);
        if (notice.consume()) return storeId;
        // 記録が無い (古い・別の再生) ので受け入れない。開いた枠だけ閉じる。
        store.cancel(player, storeId);
        return null;
    }

    /**
     * 上げ直しが済んだ hash について「原本が無い」と答えて待たせている player を取り出す (記録は消す)。
     * TTL を過ぎた記録は通知せず捨てる。呼び出し側は同期して呼ぶ。
     */
    static List<UUID> takeWaiting(Map<UUID, Map<String, Long>> missing, String hash, long now, long ttlMs) {
        final List<UUID> waiting = new ArrayList<>();
        for (Map.Entry<UUID, Map<String, Long>> e : missing.entrySet()) {
            final Long noticed = e.getValue().remove(hash);
            if (noticed != null && now - noticed <= ttlMs) waiting.add(e.getKey());
        }
        return waiting;
    }

    private LocalRestoreAdmission() {}
}
