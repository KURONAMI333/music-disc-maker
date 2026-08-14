package com.kuronami.musicdiscmaker.lavaplayer;

import java.util.function.LongSupplier;

/**
 * visitorId の入れ替え回数に付ける歯止め (token bucket)。
 *
 * <p>入れ替えは 1 回ごとに YouTube へ visitorId 取得の HTTP を投げる。失敗のたびに無条件で
 * 叩くと、それ自体が bot 判定を悪化させうるので上限を持たせる。burst 分は一気に使えて、
 * その後は {@code refillIntervalMs} ごとに 1 個ずつ戻る。
 *
 * <p>使い切ったら {@code false} を返すだけで待たない — 待つと MC のスレッドを塞ぐし、
 * 呼び出し側は入れ替え無しの再試行が無意味なことを知っている (対照 0/10) ので、
 * そこで諦めて分類つきの失敗を出すのが正しい。
 */
final class RollBudget {

    private final int burst;
    private final long refillIntervalMs;
    private final LongSupplier clockMs;

    private int tokens;
    private long lastRefillMs;
    private boolean started;

    RollBudget(int burst, long refillIntervalMs, LongSupplier clockMs) {
        this.burst = burst;
        this.refillIntervalMs = refillIntervalMs;
        this.clockMs = clockMs;
        this.tokens = burst;
    }

    /**
     * 1 回分使う。
     *
     * @return 使えたら {@code true}、余力が尽きていれば {@code false}
     */
    synchronized boolean take() {
        refill();
        if (tokens <= 0) {
            return false;
        }
        tokens--;
        return true;
    }

    /** 経過時間ぶんだけ補充する。 */
    private void refill() {
        final long now = clockMs.getAsLong();
        if (!started) {
            started = true;
            lastRefillMs = now;
            return;
        }
        final long elapsed = now - lastRefillMs;
        if (elapsed < refillIntervalMs) {
            return;
        }
        final long gained = elapsed / refillIntervalMs;
        tokens = (int) Math.min(burst, tokens + gained);
        lastRefillMs += gained * refillIntervalMs;
    }
}
