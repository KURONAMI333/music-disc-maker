package com.kuronami.musicdiscmaker.beat;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.Config;

/**
 * 1 音源ぶんの「再生位置 → コンパレータ出力 (0..15)」。強化版ジュークボックスの BE が 1 個持ち、
 * server tick ごとに {@link #update} を呼ぶ。
 *
 * <p>量子化・平滑化・閾値は全部ここ (実行時) にある。{@link BeatMap} には生の dBFS しか入っていないので、
 * config を触ればその場で効き、再解析もキャッシュの作り直しも要らない。kura の実機チューニング帯が
 * ここに集約される。
 */
public final class BeatOutput {

    /** 1 server tick の実時間 (ms)。IIR の刻みと max プーリングの窓幅。 */
    private static final long TICK_MS = 50L;

    /** 平滑化後の強度 0..1。 */
    private double envelope;
    /** 直近に確定した出力 0..15 (ヒステリシスの基準)。 */
    private int value;
    /** onset モードでパルスを保持している残り tick 数。 */
    private int pulseTicksLeft;

    public int value() {
        return value;
    }

    /** 停止・一時停止・ディスク交換で呼ぶ。 */
    public void reset() {
        envelope = 0.0;
        value = 0;
        pulseTicksLeft = 0;
    }

    /**
     * 出力を 1 tick ぶん進める。
     *
     * @param map      この曲のビートマップ。{@code null} = 未解析 (まだキャッシュが無い)
     * @param audioMs  いま鳴っているはずの再生位置 (ms)。client の実鳴動時刻で校正済みの値
     * @return コンパレータへ出す 0..15
     */
    public int update(@Nullable BeatMap map, long audioMs) {
        if (map == null) {
            return decayToSilence();
        }
        // 正のオフセット = 遅らせる / 負 = 先に出す。負のときは先読みになるので、Create の
        // 機械遅延 (リピータ・ピストン・回転の慣性) を相殺して「音より早く」動かせる。
        final long readPos = audioMs - Config.beatOffsetMs();
        if (readPos < 0L) {
            return decayToSilence();
        }
        return Config.beatOnsetMode()
                ? updateOnset(map, readPos)
                : updateEnvelope(map, readPos);
    }

    private int updateEnvelope(BeatMap map, long readPos) {
        final BeatBand band = Config.beatBand();
        final double db = map.peakDb(band, readPos, readPos + TICK_MS);
        if (Double.isNaN(db)) {
            // 解析が再生位置に追いついていない (初回のその場解析中) → 0 を出す。
            return decayToSilence();
        }
        final double target = normalize(db, map.referenceDb(band)) * Config.beatSensitivity();
        applySmoothing(clamp01(target));
        return commit((int) Math.round(envelope * 15.0));
    }

    private int updateOnset(BeatMap map, long readPos) {
        final double fluxDb = map.peakFluxDb(readPos, readPos + TICK_MS);
        if (Double.isNaN(fluxDb)) {
            return decayToSilence();
        }
        final double strength = clamp01(normalize(fluxDb, map.referenceFluxDb()));
        if (strength >= Config.beatOnsetThreshold()) {
            pulseTicksLeft = Config.beatOnsetPulseTicks();
        }
        if (pulseTicksLeft > 0) {
            pulseTicksLeft--;
            envelope = 1.0;
            return commit(15);
        }
        envelope = 0.0;
        return commit(0);
    }

    /**
     * dBFS を 0..1 へ。基準は曲全体の 95 パーセンタイル ({@code referenceDb}) なので、
     * 静かな曲と大音量の曲が同じ config で同じように動く。
     * {@code beatFloorDb} は「基準から何 dB 下を 0 とみなすか」。
     */
    private static double normalize(double db, double referenceDb) {
        final double floorDb = Config.beatFloorDb();
        if (floorDb >= 0.0) {
            return db >= referenceDb ? 1.0 : 0.0;
        }
        return (db - referenceDb - floorDb) / -floorDb;
    }

    /** 立ち上がりは速く・落ちはゆっくり (非対称 1 次 IIR)。落ち方が Create の慣性の見え方を決める。 */
    private void applySmoothing(double target) {
        if (target >= envelope) {
            final int attackMs = Config.beatAttackMs();
            envelope = attackMs <= 0
                    ? target
                    : envelope + (target - envelope) * (1.0 - Math.exp(-(double) TICK_MS / attackMs));
        } else {
            final int releaseMs = Config.beatReleaseMs();
            final double coefficient = releaseMs <= 0 ? 0.0 : Math.exp(-(double) TICK_MS / releaseMs);
            envelope = Math.max(target, envelope * coefficient);
        }
    }

    private int decayToSilence() {
        pulseTicksLeft = 0;
        envelope = 0.0;
        return commit(0);
    }

    /**
     * ヒステリシス。±{@code beatHysteresis} 以内の揺れは据え置いて、レッドストーンの更新
     * (と comparator 連鎖の再評価) を間引く。0 との出入りは必ず通す — 「停止したのに信号が残る」
     * のは挙動として許されないため。
     */
    private int commit(int candidate) {
        if (candidate != 0 && value != 0 && Math.abs(candidate - value) <= Config.beatHysteresis()) {
            return value;
        }
        value = candidate;
        return value;
    }

    private static double clamp01(double v) {
        return v < 0.0 ? 0.0 : Math.min(v, 1.0);
    }
}
