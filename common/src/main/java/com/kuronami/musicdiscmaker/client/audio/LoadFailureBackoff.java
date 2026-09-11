package com.kuronami.musicdiscmaker.client.audio;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

/**
 * 「同じ URL のロードに何度失敗したら諦めるか」の純ロジック (MC 非依存)。
 *
 * <h2>これが要る理由</h2>
 * 周期再送を持つ経路 (携帯ブームボックスの 1 秒 keep-alive) は、ロードに失敗すると
 * in-flight ガード ({@link PlaybackRequestGate}) が外れて<b>次の再送で必ず START し直す</b>。
 * リンク切れ URL を積んだブームボックスは、それだけで <b>1 Hz のネットワーク接続試行が
 * 持っている間ずっと続く</b> (聴取範囲の client 全員で)。rot した URL はこの MOD の常態なので、
 * 実地で踏む確率が高い。
 *
 * <p>金ジューク起点の再生は失敗を 1 回通知して畳むので、この歯止めを持たない
 * (server が再送しないため、畳めばそこで止まる)。周期再送を持つ経路にだけ要る。
 *
 * <h2>リセットの境界</h2>
 * 予算は {@code (key, url)} の組に紐づく。<b>曲が変われば予算は作り直される</b> (別の URL の記録は
 * 上書きされる) ので、「1 曲リンク切れだったせいでプレイリストの残りが鳴らない」にはならない。
 * 明示停止・ロード成功でも消す。予算はこの client セッション限りで、切断で {@link #clear()} する。
 *
 * @param <K> 経路ごとの actor キー (ブームボックス = 機体の識別子)
 */
public final class LoadFailureBackoff<K> {

    /** 打ち切るまでの失敗回数。 */
    public static final int MAX_ATTEMPTS = 2;

    private record Record(String url, int failures) {
    }

    private final Map<K, Record> records = new ConcurrentHashMap<>();

    /**
     * この URL のロードをもう起こしてよいか。
     *
     * @param key 対象
     * @param url ロードしようとしている URL
     * @return 予算を使い切っている = 起こしてはいけない
     */
    public boolean isExhausted(K key, String url) {
        final Record record = records.get(key);
        return record != null && record.url().equals(url) && record.failures() >= MAX_ATTEMPTS;
    }

    /**
     * 失敗を 1 回数える。別の URL の記録が残っていれば作り直す (曲が変わったら予算も新しい)。
     *
     * @param key 対象
     * @param url 失敗した URL
     * @return 打ち切りに達したか (呼び出し側が「これ以上鳴らせない」を 1 度だけ出せるように)
     */
    public boolean recordFailure(K key, String url) {
        final Record updated = records.compute(key, (ignored, previous) ->
                previous != null && previous.url().equals(url)
                        ? new Record(url, previous.failures() + 1)
                        : new Record(url, 1));
        return updated.failures() >= MAX_ATTEMPTS;
    }

    /**
     * ロード成功・明示停止で予算を返す。
     *
     * @param key 対象 ({@code null} 可)
     */
    public void reset(@Nullable K key) {
        if (key != null) {
            records.remove(key);
        }
    }

    /** 切断・ワールド退出。 */
    public void clear() {
        records.clear();
    }
}
