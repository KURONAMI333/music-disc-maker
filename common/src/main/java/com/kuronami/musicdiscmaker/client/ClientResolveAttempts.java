package com.kuronami.musicdiscmaker.client;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 同じ (製作機, URL) を client が何度も引き直さないための記録 (MC 非依存の純粋な器)。
 *
 * <p>失敗した URL を毎 tick 引き直すと回線と待ち時間を無駄にするので、一度試した組は覚えておく。
 * ただし覚えたままにすると、次に同じ URL で失敗した時に二度と引き直せなくなる。そこで
 * <ul>
 *   <li>1 つの組は {@link #MAX_ATTEMPTS_PER_KEY} 回まで。同じ失敗を何度も繰り返しても結果は
 *       変わらないので、そこで止める。回数は {@link #release(long)} / {@link #clear()} で戻る</li>
 *   <li>取得中 ({@link #claim} して {@link #finish} の前) は引き直さない。同じ URL を並行して
 *       引かない</li>
 *   <li>取得が<b>終わった</b>時刻から {@link #RETRY_AFTER_MS} は引き直さない。client 側の取得が
 *       失敗した時や取得中に画面を閉じた時は release が届かないため、時間でも切る</li>
 * </ul>
 * 記録は (製作機の位置, URL) 単位。次元・サーバーが変われば位置は別の製作機を指すので、
 * 接続が変わったら {@link #clear()} で全部捨てる。
 */
final class ClientResolveAttempts {

    /** 記録の上限。超えたら古い順に捨てる (長時間のプレイで増え続けない)。 */
    static final int MAX_ATTEMPTS = 64;

    /**
     * 同じ (製作機, URL) を引き直す回数の上限。同じ失敗が続く時に画面を開いたままでも
     * 取得を繰り返さないための歯止めで、焼けた・URL が変わった ({@link #release(long)}) か
     * 接続が変わった ({@link #clear()}) 時に戻る。
     */
    static final int MAX_ATTEMPTS_PER_KEY = 3;

    /**
     * 取得が終わってから次を試すまでの間隔。短くすると失敗が続く間ずっと取得を繰り返す。
     */
    static final long RETRY_AFTER_MS = 60_000L;

    /** 1 つの (製作機, URL) の状態。 */
    private static final class Record {

        /** 引き直した回数。{@code release} で記録ごと消えるので 0 に戻る。 */
        int attempts;

        /** 取得中か (並行して同じ URL を引かない)。 */
        boolean running;

        /** 取得が終わった時刻 (ms)。まだ終わっていなければ {@code -1}。 */
        long doneAtMs = -1L;
    }

    /** 試した (製作機, URL) → 状態。古い順に捨てるために挿入順 (= 最後に試した順) を保つ。 */
    private final Map<String, Record> records = new LinkedHashMap<>();

    /** 製作機ごとに、今記録している鍵 (release で戻すため)。 */
    private final Map<Long, String> byPos = new HashMap<>();

    /**
     * この (製作機, URL) を今試してよい時だけ true。true を返したら、その試行は {@link #finish} で
     * 閉じる (閉じるまで次の試行は始まらない)。
     *
     * @param pos   製作機の位置 ({@code BlockPos#asLong()})
     * @param url   引き直そうとしている URL
     * @param nowMs 現在時刻 (ms)。時間でも記録を切るため呼び出し側から渡す
     */
    synchronized boolean claim(long pos, String url, long nowMs) {
        final String key = key(pos, url);
        final Record record = records.get(key);
        if (record != null) {
            if (record.running) {
                return false;
            }
            if (record.attempts >= MAX_ATTEMPTS_PER_KEY) {
                return false;
            }
            if (record.doneAtMs >= 0L && nowMs - record.doneAtMs < RETRY_AFTER_MS) {
                return false;
            }
        }
        final String previous = byPos.get(pos);
        if (previous != null && !previous.equals(key)) {
            // 同じ製作機の URL が変わった。古い URL の記録はもう使わない (製作機ごとに 1 件)。
            records.remove(previous);
        }
        // 追い出し順を「最後に試した順」に保つため、同じ鍵も消してから入れ直す
        // (put だけだと挿入順が元のままで、試し直した組が先に捨てられる)。
        records.remove(key);
        final Record next = record != null ? record : new Record();
        next.attempts++;
        next.running = true;
        records.put(key, next);
        byPos.put(pos, key);
        evictOverflow();
        return true;
    }

    /**
     * 引き直しが終わった (成功・失敗どちらでも) ことを記録する。次を試すまでの間隔はここから数える。
     *
     * <p>{@link #release(long)} で先に記録が消えていたら何もしない (回数を戻したままにする)。
     *
     * @param nowMs 終わった時刻 (ms)
     */
    synchronized void finish(long pos, String url, long nowMs) {
        final Record record = records.get(key(pos, url));
        if (record == null) {
            return;
        }
        record.running = false;
        record.doneAtMs = nowMs;
    }

    /** 失敗表示が消えた製作機の記録を戻す (回数も戻るので、次に同じ URL で失敗したらまた試せる)。 */
    synchronized void release(long pos) {
        final String key = byPos.remove(pos);
        if (key != null) {
            records.remove(key);
        }
    }

    /** 接続・次元が変わった時に全部捨てる (回数も戻る)。 */
    synchronized void clear() {
        records.clear();
        byPos.clear();
    }

    /** 記録している組の数 (試験用)。 */
    synchronized int size() {
        return records.size();
    }

    /** 上限を超えた分を古い順に捨てる。入れた後に呼ぶ (入れ直しで末尾へ移った記録を残す)。 */
    private void evictOverflow() {
        final var oldest = records.keySet().iterator();
        while (records.size() > MAX_ATTEMPTS && oldest.hasNext()) {
            oldest.next();
            oldest.remove();
        }
        byPos.values().removeIf(key -> !records.containsKey(key));
    }

    private static String key(long pos, String url) {
        return pos + "|" + url;
    }
}
