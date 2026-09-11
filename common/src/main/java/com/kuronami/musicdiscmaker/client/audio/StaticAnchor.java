package com.kuronami.musicdiscmaker.client.audio;


import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

//? if >=1.21.2 {
/**
 * 固定ブロック位置に張り付く従来アンカー。vanilla jukebox・強化版ジュークボックス、および
 * {@link com.kuronami.musicdiscmaker.event.ExternalPlaybackMirror} 経由で音を出す第三者ブロック
 * (例: [Let's Do] Furniture の蓄音機) を担う。
 *
 * <p>{@link #isValid()} は chunk 未ロード時は停止しない (air 誤読による遠距離の誤消音を防ぐ)。ロード済み
 * で air になっている時 (撤去・破壊・爆発・ピストン・コマンド) にだけ {@code false} を返す。
 *
 * <p><b>ブロック種別では判定しない。</b> ここは client 側の保険であって主たる停止経路ではない
 * (撤去は server が {@code StopDiscPayload} を送って止める)。host になりうるブロックは
 * {@code ExternalPlaybackMirror} が汎用 API である以上いくらでも増えるので、種別の白リストにすると
 * 新しい音源ブロックが出るたびに「鳴っているのに最初の tick で自己停止する」形で無音になる。
 * 保険が要るのは「そこに何も無くなった」場合だけなので、それだけを見る。
 *
 * <p>原ブロックが client world に実在するため {@link LiveConfigAnchor} を実装し、強化版ジュークボックス
 * の音量/範囲を毎 tick ライブ再読させる。
 */
public final class StaticAnchor implements DiscAnchor, LiveConfigAnchor {
//?} elif >=1.21 {
/*/^*
 * 固定ブロック位置に張り付く従来アンカー。vanilla jukebox・強化版ジュークボックス、および
 * {@link com.kuronami.musicdiscmaker.event.ExternalPlaybackMirror} 経由で音を出す第三者ブロック
 * (例: [Let's Do] Furniture の蓄音機) を担う。
 *
 * <p>{@link #isValid()} は chunk 未ロード時は停止しない (air 誤読による遠距離の誤消音を防ぐ)。ロード済み
 * で air になっている時 (撤去・破壊・爆発・ピストン・コマンド) にだけ {@code false} を返す。
 *
 * <p><b>ブロック種別では判定しない。</b> ここは client 側の保険であって主たる停止経路ではない
 * (撤去は server が {@code StopDiscPayload} を送って止める)。host になりうるブロックは
 * {@code ExternalPlaybackMirror} が汎用 API である以上いくらでも増えるので、種別の白リストにすると
 * 新しい音源ブロックが出るたびに「鳴っているのに最初の tick で自己停止する」形で無音になる。
 * 保険が要るのは「そこに何も無くなった」場合だけなので、それだけを見る。
 ^/
public final class StaticAnchor implements DiscAnchor, LiveConfigAnchor {
*///?} else {
/*/^*
 * 固定ブロック位置に張り付く従来アンカー。vanilla jukebox・強化版ジュークボックス、および音源 host
 * になりうる第三者ブロックを担う。
 *
 * <p>{@link #isValid()} は chunk 未ロード時は停止しない (air 誤読による遠距離の誤消音を防ぐ)。ロード済み
 * で air になっている時 (撤去・破壊・爆発・ピストン・コマンド) にだけ {@code false} を返す。
 *
 * <p><b>ブロック種別では判定しない。</b> 第三者ブロックに挿した custom disc が host のブロック種別を
 * 白リスト判定 (vanilla jukebox / 強化版のみ) にしていると、鳴っているのに最初の tick で自己停止する。
 * host になりうるブロックは音源が汎用化する以上いくらでも増えるので、保険が要るのは「そこに何も
 * 無くなった」場合だけに絞る。
 *
 * <p>VS2 船に載った jukebox もブロックは shipyard 座標に実在し続けるため {@link #isValid()} は通る。
 * 音源座標だけが shipyard 座標のままでズレるので、そこは互換アダプタが別アンカーで補正する。
 ^/
public final class StaticAnchor implements DiscAnchor, LiveConfigAnchor {
*///?}

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
        return !state.isAir();
    }

    @Override
    public Vec3 worldPos(float partialTicks) {
        return center; // jukebox は動かない
    }
}
