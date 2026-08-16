package com.kuronami.musicdiscmaker.client.audio;

import com.kuronami.musicdiscmaker.register.ModBlocks;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 固定ブロック位置に張り付く従来アンカー。vanilla jukebox・強化版ジュークボックス、および
 * {@link com.kuronami.musicdiscmaker.event.AlbumPlaybackMirror} 経由で音を出す第三者ブロック
 * (例: [Let's Do] Furniture の蓄音機) を担う。
 *
 * <p>{@link #isValid()} は chunk 未ロード時は停止しない (air 誤読による遠距離の誤消音を防ぐ)。ロード済み
 * で air になっている時 (撤去・破壊・爆発・ピストン・コマンド) にだけ {@code false} を返す。
 *
 * <p><b>ブロック種別では判定しない。</b> ここは client 側の保険であって主たる停止経路ではない
 * (撤去は server が {@code StopDiscPayload} を送って止める)。host になりうるブロックは
 * {@code AlbumPlaybackMirror} が汎用 API である以上いくらでも増えるので、種別の白リストにすると
 * 新しい音源ブロックが出るたびに「鳴っているのに最初の tick で自己停止する」形で無音になる。
 * 保険が要るのは「そこに何も無くなった」場合だけなので、それだけを見る。
 */
public final class StaticAnchor implements DiscAnchor {

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
