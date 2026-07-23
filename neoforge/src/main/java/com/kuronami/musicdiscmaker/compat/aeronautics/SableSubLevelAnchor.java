package com.kuronami.musicdiscmaker.compat.aeronautics;

import com.kuronami.musicdiscmaker.client.audio.DiscAnchor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * 【骨格・未実装 TODO】Create Aeronautics (物理エンジン = 旧 Sable) の変換式 sub-level に載った音源の
 * アンカー。変換式は捕獲式 (Create 本家) と違いブロックが実在し tick し続けるため、golden jukebox BE は
 * sub-level 内で通常どおり再生を続ける。問題は座標のみ: 原 world セルが空になり、固定 {@code StaticAnchor}
 * が自己消音する。修正は「原 pos 固定判定を捨て、sub-level 剛体 pose で local→world を毎 tick 変換する
 * 変換式アンカー」= このクラスが埋めるべきスロット。
 *
 * <h2>なぜ未実装で止めたか (2026-07-23 実測)</h2>
 * <ul>
 *   <li><b>API drift</b>: spike ({@code _research/SPIKE_MDM_CREATE_VS2.md}) が記載した物理 API
 *       ({@code dev.ryanhcode.sable.Sable.HELPER.getContaining(level,pos)} →
 *       {@code SubLevel.logicalPose().transformPosition(local)}) は、実際の配布 (create-aeronautics
 *       1.3.0+mc1.21.1, NeoForge) では物理コアが <b>{@code dev.simulated_team.simulated}</b> へ改名・
 *       再構成されており、この名前の公開 API は存在しない。jarjar 3 モジュール構成
 *       ({@code aeronautics} / {@code offroad} / {@code simulated}=物理コア) で、{@code simulated} の
 *       {@code api/} には {@code getContaining} 相当の sub-level 座標変換の公開エントリが見当たらない
 *       (実測: {@code api/sound/*} 等はあるが SubLevel helper は非公開/別名)。</li>
 *   <li><b>トリガー経路の未確認</b>: 変換式では BE が sub-level (別 Level) 内で tick する。その BE が
 *       {@code PlayDiscPayload} を「どの座標系で」「どの client へ」broadcast するか、client がその
 *       sub-level ブロックの world 座標を解決する経路が spike でも未確認 (推論のみ)。捕獲式 (Create) の
 *       server 主導 payload とは別設計になる。</li>
 *   <li>ライセンスが NOASSERTION (カスタム) = Aeronautics コードを MDM に取り込まない方針。compileOnly
 *       soft-dep で API に当てる必要があるが、上記の通り当てるべき API 名が未確定。</li>
 * </ul>
 *
 * <h2>実装再開時の手順 (次セッションへの引き継ぎ)</h2>
 * <ol>
 *   <li>{@code dev.simulated_team.simulated} (nested jar: create-aeronautics-bundled →
 *       {@code META-INF/jarjar/dev.simulated_team.simulated.simulated-neoforge-1.21.1-*.jar}) を javap し、
 *       「world pos ↔ sub-level local pos」を変換する公開 API と、ある BlockPos を含む sub-level を
 *       引く helper を特定する (旧 {@code getContaining} / {@code logicalPose().transformPosition} の後継)。</li>
 *   <li>{@link #worldPos} をその API に配線する (毎 tick local→world)。{@link #isValid} は sub-level が
 *       生きている間 true。</li>
 *   <li>トリガー: sub-level 内 BE の再生開始/継続をどう client へ届けるか決める。捕獲式と違い変換式は
 *       BE が実在するので、原 world セルが空になった時点を検出して変換式アンカーへ切り替える payload を
 *       送るのが素直 (compat/create の server 主導 payload を雛形に)。</li>
 * </ol>
 *
 * <p>現状は安全な no-op: {@link #isValid} が false を返すのでこのアンカーは決して再生を保持しない
 * (誤って配線しても無音になるだけでクラッシュしない)。Aeronautics 型は一切 import していないため
 * compileOnly jar 無しでコンパイルできる。
 */
public final class SableSubLevelAnchor implements DiscAnchor {

    private final BlockPos localPos;

    public SableSubLevelAnchor(BlockPos localPos) {
        this.localPos = localPos.immutable();
    }

    @Override
    public boolean isValid() {
        // TODO(aeronautics): sub-level が生きている間 true。未実装のため安全側 (再生を保持しない)。
        return false;
    }

    @Override
    public Vec3 worldPos(float partialTicks) {
        // TODO(aeronautics): dev.simulated_team.simulated の sub-level 剛体 pose で local→world 変換する。
        return Vec3.atCenterOf(localPos);
    }
}
