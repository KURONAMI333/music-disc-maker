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
 *   <li>失敗表示が消えた製作機の記録は {@link #release(long)} で戻す (焼けた・URL が変わった)</li>
 *   <li>戻し損ねた記録も {@link #RETRY_AFTER_MS} を過ぎればまた試せる。client 側の取得が
 *       失敗した時や取得中に画面を閉じた時は release が届かないため、時間でも切る</li>
 * </ul>
 * 記録は (製作機の位置, URL) 単位。次元・サーバーが変われば位置は別の製作機を指すので、
 * 接続が変わったら {@link #clear()} で全部捨てる。
 */
final class ClientResolveAttempts {

    /** 記録の上限。超えたら古い順に捨てる (長時間のプレイで増え続けない)。 */
    static final int MAX_ATTEMPTS = 64;

    /**
     * 一度試した組を覚えておく時間。client 側の取得失敗は release が届かないので、これを過ぎたら
     * 画面を開いたままでも再試行する。短くすると失敗が続く間ずっと取得を繰り返す。
     */
    static final long RETRY_AFTER_MS = 60_000L;

    /** 試した (製作機, URL) → 試した時刻。古い順に捨てるために挿入順を保つ。 */
    private final Map<String, Long> attempted = new LinkedHashMap<>();

    /** 製作機ごとに、今記録している鍵 (release で戻すため)。 */
    private final Map<Long, String> byPos = new HashMap<>();

    /**
     * この (製作機, URL) を今試してよい時だけ true。
     *
     * @param pos   製作機の位置 ({@code BlockPos#asLong()})
     * @param url   引き直そうとしている URL
     * @param nowMs 現在時刻 (ms)。時間でも記録を切るため呼び出し側から渡す
     */
    synchronized boolean claim(long pos, String url, long nowMs) {
        final String key = key(pos, url);
        final Long triedAt = attempted.get(key);
        if (triedAt != null && nowMs - triedAt < RETRY_AFTER_MS) {
            return false;
        }
        final String previous = byPos.get(pos);
        if (attempted.size() >= MAX_ATTEMPTS) {
            evictOldest();
        }
        if (previous != null && !previous.equals(key)) {
            // 同じ製作機の URL が変わった。古い URL の記録はもう使わない (製作機ごとに 1 件)。
            attempted.remove(previous);
        }
        attempted.put(key, nowMs);
        byPos.put(pos, key);
        return true;
    }

    /** 失敗表示が消えた製作機の記録を戻す (次に同じ URL で失敗したらまた試せる)。 */
    synchronized void release(long pos) {
        final String key = byPos.remove(pos);
        if (key != null) {
            attempted.remove(key);
        }
    }

    /** 接続・次元が変わった時に全部捨てる。 */
    synchronized void clear() {
        attempted.clear();
        byPos.clear();
    }

    /** 記録している組の数 (試験用)。 */
    synchronized int size() {
        return attempted.size();
    }

    private void evictOldest() {
        final var oldest = attempted.keySet().iterator();
        if (!oldest.hasNext()) {
            return;
        }
        final String dropped = oldest.next();
        oldest.remove();
        byPos.values().removeIf(dropped::equals);
    }

    private static String key(long pos, String url) {
        return pos + "|" + url;
    }
}
