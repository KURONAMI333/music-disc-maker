package com.kuronami.musicdiscmaker.client.audio;

import com.kuronami.musicdiscmaker.register.ModBlocks;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 固定 jukebox 位置に張り付く従来アンカー。vanilla jukebox と強化版ジュークボックスの両方を担う。
 *
 * <p>{@link #isValid()} は chunk 未ロード時は停止しない (air 誤読による遠距離の誤消音を防ぐ)。ロード済み
 * で jukebox でも強化版でもない時 (撤去・破壊・爆発・ピストン・コマンド) にだけ {@code false} を返す。
 * これは anchor 抽象化前の {@code DiscSoundInstance.tick()} の存在チェックと同一の判定。
 *
 * <p>VS2 船に載った jukebox もブロックは shipyard 座標に実在し続けるため {@link #isValid()} は通る。
 * 音源座標だけが shipyard 座標のままでズレるので、そこは互換アダプタが別アンカーで補正する。
 */
public final class StaticAnchor implements DiscAnchor, LiveConfigAnchor {

    private final BlockPos pos;
    private final Vec3 center;

    public StaticAnchor(BlockPos pos) {
        this.pos = pos.immutable();
        this.center = new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    /** このアンカーの jukebox 位置。強化版の音量/範囲 live 再読で BE を引くのに使う。 */
    public BlockPos pos() {
        return pos;
    }

    /** 固定ジュークは pos がそのまま client world 上の BE 位置。 */
    @Override
    public BlockPos configPos() {
        return pos;
    }

    @Override
    public boolean isValid() {
        final Level level = Minecraft.getInstance().level;
        // level 未生成 / chunk 未ロードでは判定を保留 (停止しない)。
        if (level == null || !level.isLoaded(pos)) {
            return true;
        }
        final BlockState state = level.getBlockState(pos);
        return state.is(Blocks.JUKEBOX) || state.is(ModBlocks.GOLDEN_JUKEBOX.get());
    }

    @Override
    public Vec3 worldPos(float partialTicks) {
        return center; // jukebox は動かない
    }
}
