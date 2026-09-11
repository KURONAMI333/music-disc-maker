package com.kuronami.musicdiscmaker.client.audio;

import org.jetbrains.annotations.Nullable;

/**
 * 「その再生要求でロードを起こすか」の判断だけを取り出した純ロジック (MC 非依存)。
 *
 * <h2>これが要る理由</h2>
 * server はブームボックスの再生を <b>1 秒ごとの keep-alive</b> として撃ち続ける (late-join と
 * 生存確認を兼ねるため)。判断材料が「今鳴っているインスタンス」だけだと、<b>ロード中は毎回
 * 「鳴っていない」に見える</b>ので、心拍 1 発ごとに前のロードを捨てて新しいロードを始めてしまう。
 * ストリーム確立が 1 秒を超える音源 (ネットワーク経由はほぼ該当) では、これが永久に繰り返されて
 * <b>一度も音が立たない</b>。ログも出ない。
 *
 * <p>{@link PlaybackSessions} の世代トークンはこの問題を解けない。あれは「複数のロードが完走した
 * ときにどれを採るか」= 孤児インスタンスを作らないための機構で、「そもそもロードを起こすか」は
 * 見ていない。<b>2 つは別の関心で、両方要る。</b>
 *
 * <p>金ジューク起点の再生 ({@link ClientPlaybackManager}) が同じ形でも壊れていないのは、あちらの
 * 再送が chunk 再入・シークという<b>イベント駆動</b>で、ロード中の窓に入る確率が低いため。
 * <b>定期再送を持つ経路は必ずここを通す。</b>
 */
public final class PlaybackRequestGate {

    /** 再生要求 1 通に対して取る行動。 */
    public enum Decision {
        /** 新しくロードを起こす。 */
        START,
        /** すでに同じ曲が鳴っている。ストリームには触らず、生存時刻と設定だけ更新する。 */
        REFRESH,
        /** すでに同じ曲をロード中。何もしない (ここで START にすると永久に鳴らない)。 */
        IGNORE
    }

    private PlaybackRequestGate() {
    }

    /**
     * @param activeUrl     いま設置済みのインスタンスが鳴らしている URL (無ければ {@code null})
     * @param activeStopped そのインスタンスが停止済みか (自然終了・停止要求)
     * @param pendingUrl    いまロード中の要求の URL (無ければ {@code null})
     * @param requestedUrl  届いた要求の URL
     * @return 取るべき行動
     */
    public static Decision decide(@Nullable String activeUrl, boolean activeStopped,
            @Nullable String pendingUrl, String requestedUrl) {
        if (activeUrl != null && !activeStopped && activeUrl.equals(requestedUrl)) {
            return Decision.REFRESH;
        }
        // 設置済みが停止している (自然終了・止めた) 場合はここへ落ちる = 同じ曲でも鳴らし直す。
        if (pendingUrl != null && pendingUrl.equals(requestedUrl)) {
            return Decision.IGNORE;
        }
        return Decision.START;
    }
    /** 同じURLの別位置・seekとheartbeatを音声世代で区別する。 */
    public static Decision decide(@Nullable String activeUrl, boolean activeStopped,
            @Nullable String pendingUrl, String requestedUrl, @Nullable Long previousGeneration,
            long requestedGeneration) {
        if (previousGeneration == null || previousGeneration.longValue() != requestedGeneration) {
            return Decision.START;
        }
        return decide(activeUrl, activeStopped, pendingUrl, requestedUrl);
    }
}
