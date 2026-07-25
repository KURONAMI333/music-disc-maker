package com.kuronami.musicdiscmaker.client.audio;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 「1 論理再生 + マルチアンカー聴取」の<b>選択則</b>だけを取り出した純関数。
 *
 * <p>{@link MultiSpeakerAnchor} から分けてあるのは、あちらが {@code Minecraft} を掴んでいて dedicated
 * server では読めない = headless テストに載らないため。境界条件（どの点が選ばれるか）は聴こえる/
 * 聴こえないを直接決めるのに、実音でしか確かめられない場所に置いておくと機械ゲートを素通りする。
 *
 * <p>規則: <b>可聴範囲の内側にある候補（音源本体と各スピーカー）の中から listener に最も近い 1 点</b>。
 * 範囲内の候補が 1 つも無ければ音源へ縮退する（そのときは減衰で無音になるのが正しい挙動）。
 * 音源を無条件の候補にすると「音源の方が近いが音源の範囲外・スピーカーの範囲内」の座標が
 * 音源に吸われて無音になる（音源 64 / スピーカー 128 のような、スライダーを 1 本上げるだけで作れる配置）。
 */
public final class SpeakerSelection {

    /**
     * 最近傍の切り替えに要する距離差（ブロック）。等距離付近で毎 tick 選択が反転すると、範囲が違う
     * 候補間で減衰半径のチャンネル書き込みが毎 tick 走るため、現在の選択をこの分だけ優遇する。
     */
    public static final double SWITCH_MARGIN = 0.5;

    /** 候補 1 点。{@code pos} が {@code null} なら音源本体。 */
    public record Candidate(@Nullable BlockPos pos, Vec3 center, int volumePercent, int rangeBlocks) {
    }

    /** 選ばれた点。{@code pos} が {@code null} なら音源本体。 */
    public record Choice(@Nullable BlockPos pos, int volumePercent, int rangeBlocks) {
    }

    private SpeakerSelection() {
    }

    /**
     * @param ear      listener（OpenAL の listener と同じ 1 点）
     * @param source   音源本体の候補（常に縮退先になる）
     * @param speakers 有効スピーカー（ミュート済み・撤去済みは呼び出し側で除外済み）
     * @param current  前 tick に選ばれていた点（{@code null} = 音源）。ヒステリシスの基準
     */
    public static Choice pick(Vec3 ear, Candidate source, List<Candidate> speakers,
            @Nullable BlockPos current) {
        Candidate best = null;
        double bestDistance = Double.MAX_VALUE;

        Candidate held = null;
        double heldDistance = Double.MAX_VALUE;

        final double sourceDistance = ear.distanceTo(source.center());
        if (inRange(sourceDistance, source)) {
            best = source;
            bestDistance = sourceDistance;
            if (current == null) {
                held = source;
                heldDistance = sourceDistance;
            }
        }

        for (final Candidate speaker : speakers) {
            final double distance = ear.distanceTo(speaker.center());
            if (!inRange(distance, speaker)) {
                continue; // このスピーカーの可聴範囲の外
            }
            if (speaker.pos() != null && speaker.pos().equals(current)) {
                held = speaker;
                heldDistance = distance;
            }
            if (distance < bestDistance) {
                best = speaker;
                bestDistance = distance;
            }
        }

        // 前回の選択がまだ範囲内なら、切り替え余裕のぶんだけ優遇して据え置く。
        if (held != null && heldDistance <= bestDistance + SWITCH_MARGIN) {
            return new Choice(held.pos(), held.volumePercent(), held.rangeBlocks());
        }
        if (best == null) {
            // 範囲内の候補が無い = どこからも聴こえない位置。音源へ縮退し、減衰に任せて無音にする。
            return new Choice(null, source.volumePercent(), source.rangeBlocks());
        }
        return new Choice(best.pos(), best.volumePercent(), best.rangeBlocks());
    }

    /**
     * 可聴範囲の内側か。{@code rangeBlocks <= 0} は「per-block 設定なし」の sentinel
     * （バニラジューク経路）で、範囲を自分では持たないので候補にならない = 常に音源へ縮退する。
     */
    private static boolean inRange(double distance, Candidate candidate) {
        return candidate.rangeBlocks() > 0 && distance <= candidate.rangeBlocks();
    }
}
