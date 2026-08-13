package com.kuronami.musicdiscmaker.client.audio;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「いま有効な再生要求はどれか」を鍵ごとの世代番号で追う純ロジック (MC 非依存)。
 *
 * <h2>これが要る理由</h2>
 * 再生ボタンは<b>ロードが終わる前に</b>もう一度押せる。バックパック系の compat は
 * 「別 thread で URL を開き、完了してから main thread で音源を登録する」形なので、
 * 登録されるまでの間は「止める対象」がどこにも無い。世代を持たないと:
 *
 * <ul>
 *   <li>再生 → すぐ停止: 停止が空振りし、ロードが終わった瞬間に鳴り始める</li>
 *   <li>再生を素早く 2 回: 両方のロードが完了して 2 音源が重なり、止められるのは後の 1 件だけ</li>
 * </ul>
 *
 * <p>再生と停止のどちらでも世代を進め、ロードは<b>開始時点の世代を捕まえて</b>おく。
 * 完了時に世代が変わっていたら、その音源は鳴らさずに閉じる。
 *
 * <p>{@code Minecraft} を掴まないので headless テストに載る ({@link PlaybackFailure} と同じ
 * seam の切り方)。
 *
 * @param <K> 再生をまとめる鍵。同じ鍵の再生は互いに後勝ちで打ち消し合う
 *            (バックパックなら storageUuid、画面ごとに 1 音源しか持たない経路なら固定の鍵)
 */
public final class PlaybackGenerations<K> {

    /** 鍵ごとの現在世代。値は 1 から始まり、単調に増える (0 は「まだ一度も無い」)。 */
    private final Map<K, Integer> current = new ConcurrentHashMap<>();

    /**
     * 新しい再生を宣言し、その世代を返す。ロード側はこの値を持ち回り、完了時に
     * {@link #isCurrent} で照合する。
     *
     * @param key 再生をまとめる鍵
     * @return この再生の世代
     */
    public int begin(K key) {
        return current.merge(key, 1, Integer::sum);
    }

    /**
     * 進行中の再生を無効化する (停止ボタン)。世代を進めるだけなので、まだロード中の要求も
     * 完了時点で捨てられる。
     *
     * @param key 再生をまとめる鍵
     */
    public void invalidate(K key) {
        begin(key);
    }

    /**
     * その世代がまだ最新か。
     *
     * @param key        再生をまとめる鍵
     * @param generation {@link #begin} が返した世代
     * @return 最新なら {@code true} (偽なら、その音源は鳴らさずに閉じること)
     */
    public boolean isCurrent(K key, int generation) {
        final Integer now = current.get(key);
        return now != null && now.intValue() == generation;
    }
}
