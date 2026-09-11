package com.kuronami.musicdiscmaker.speaker;

import java.util.Collection;
import java.util.Objects;

/**
 * 複数の固定聴取点から一つを選ぶ時の、位置と実効gainの連続遷移。
 *
 * <p>decoderや音声streamには触らない。positional audioでは補間位置にOpenALの線形距離減衰が
 * もう一度掛かるため、両端で本来得るgainを補間し、補間位置の減衰で割った補正値も返す。
 * これにより「位置を動かした結果、距離減衰が二重に落ちる」谷を作らない。
 */
public final class SpeakerTransition {

    /** 20 tick/s環境で300 ms。最終値は実音の聴感試験で決める。 */
    public static final int TRANSITION_TICKS = 6;

    public record Frame(double x, double y, double z, double rangeBlocks, int volumePercent,
                        double gainMultiplier, String selectedId) {
    }

    private Frame frame;
    private Frame transitionFrom;
    private SpeakerSelection.Candidate target;
    private String selectedId;
    private int transitionStep;

    /** 1 client tickぶん進める。同じtickに複数回呼ばないこと。 */
    public Frame advance(SpeakerSelection.Listener listener,
            Collection<SpeakerSelection.Candidate> candidates, boolean directional) {
        final SpeakerSelection.Candidate next = SpeakerSelection.select(listener, candidates, selectedId)
                .orElse(null);
        final String nextId = next == null ? null : next.id();

        if (frame == null) {
            selectedId = nextId;
            target = next;
            frame = next == null ? new Frame(listener.x(), listener.y(), listener.z(), 0.0D, 0, 0.0D, null)
                    : exact(next);
            return frame;
        }

        if (!Objects.equals(selectedId, nextId)) {
            transitionFrom = frame;
            target = next;
            selectedId = nextId;
            transitionStep = 0;
        } else {
            // 同じ候補の音量・位置・rangeがpayloadで更新された場合は最新値を遷移先に使う。
            target = next;
        }

        if (transitionFrom != null && transitionStep < TRANSITION_TICKS) {
            transitionStep++;
            final double linear = transitionStep / (double) TRANSITION_TICKS;
            final double progress = linear * linear * (3.0D - 2.0D * linear);
            frame = interpolate(transitionFrom, target, listener, directional, progress, selectedId);
            if (transitionStep == TRANSITION_TICKS) {
                transitionFrom = null;
                frame = target == null
                        ? new Frame(frame.x(), frame.y(), frame.z(), frame.rangeBlocks(), 0, 0.0D, null)
                        : exact(target);
            }
            return frame;
        }

        frame = target == null
                ? new Frame(frame.x(), frame.y(), frame.z(), frame.rangeBlocks(), 0, 0.0D, null)
                : exact(target);
        return frame;
    }

    private static Frame exact(SpeakerSelection.Candidate candidate) {
        return new Frame(candidate.x(), candidate.y(), candidate.z(), candidate.rangeBlocks(),
                candidate.volumePercent(), candidate.gainMultiplier(),
                candidate.id());
    }

    private static Frame interpolate(Frame from, SpeakerSelection.Candidate to,
            SpeakerSelection.Listener listener, boolean directional, double progress, String selectedId) {
        final double targetX = to == null ? from.x() : to.x();
        final double targetY = to == null ? from.y() : to.y();
        final double targetZ = to == null ? from.z() : to.z();
        final int targetVolume = to == null ? 0 : to.volumePercent();
        final double targetRange = to == null ? from.rangeBlocks() : to.rangeBlocks();
        // 遷移中のOpenAL max distanceは狭い遷移先へ即座に縮めない。座標がまだ旧anchor寄りなら
        // 範囲外=0になり、補正gainでは戻せないため、両端の大きい方を遷移中の分母にも使う。
        final double transitionRange = Math.max(from.rangeBlocks(), targetRange);

        final double x = lerp(from.x(), targetX, progress);
        final double y = lerp(from.y(), targetY, progress);
        final double z = lerp(from.z(), targetZ, progress);
        // maxを土台にすると、補正gainは0..1に収まる。終点で土台が元設定へ戻っても積は連続する。
        final int baseVolume = Math.max(from.volumePercent(), targetVolume);
        final double fromAttenuation = directional
                ? attenuation(listener, from.x(), from.y(), from.z(), from.rangeBlocks())
                : 1.0D;
        final double targetAttenuation = to == null ? 0.0D
                : directional ? attenuation(listener, to.x(), to.y(), to.z(), to.rangeBlocks()) : 1.0D;
        final double targetGain = to == null ? 0.0D : to.gainMultiplier();
        final double wanted = lerp(from.volumePercent() * from.gainMultiplier() * fromAttenuation,
                targetVolume * targetGain * targetAttenuation, progress);
        final double atInterpolatedPosition = directional
                ? attenuation(listener, x, y, z, transitionRange > 0.0D ? transitionRange : 1.0D)
                : 1.0D;
        final double denominator = baseVolume * atInterpolatedPosition;
        final double correction = denominator <= 0.0D ? 0.0D
                : Math.max(0.0D, Math.min(1.0D, wanted / denominator));
        return new Frame(x, y, z, transitionRange, baseVolume, correction, selectedId);
    }

    private static double attenuation(SpeakerSelection.Listener listener,
            double x, double y, double z, double range) {
        if (!(range > 0.0D)) {
            return 0.0D;
        }
        final double distance = Math.hypot(Math.hypot(listener.x() - x, listener.y() - y), listener.z() - z);
        // Channel#linearAttenuation は AL_LINEAR_DISTANCE_CLAMPED, reference distance=1,
        // rolloff=1 で鳴らす。range をそのまま分母にする近似では、耳元 (distance <= 1) で
        // 本来 1.0 の gain を余計に落とし、遷移終端に段差を作る。
        if (range <= 1.0D) {
            return distance <= range ? 1.0D : 0.0D;
        }
        if (distance <= 1.0D) {
            return 1.0D;
        }
        if (distance >= range) {
            return 0.0D;
        }
        return (range - distance) / (range - 1.0D);
    }

    private static double lerp(double from, double to, double progress) {
        return from + (to - from) * progress;
    }
}
