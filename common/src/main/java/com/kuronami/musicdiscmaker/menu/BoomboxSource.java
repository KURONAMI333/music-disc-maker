package com.kuronami.musicdiscmaker.menu;

//? if >=1.21 {
import io.netty.buffer.ByteBuf;
//?}
import net.minecraft.core.BlockPos;
//? if >=1.21 {
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
//?} else {
/*import net.minecraft.network.FriendlyByteBuf;
*///?}
import net.minecraft.world.InteractionHand;

/**
 * GUI を開いたブームボックスがどれかを client へ運ぶ値。
 *
 * <p>ブームボックスは<b>持ったままでも置いてからでも同じ GUI が開く</b> (KURONAMI333 裁定 2026-09-07) ので、
 * 既存 3 ブロックの menu のように {@link BlockPos} 1 本では足りない。
 *
 * <ul>
 *   <li>{@code hand < 0} — 置いてある機体。{@code pos} がその位置</li>
 *   <li>{@code hand == 0} / {@code 1} — 手に持っている機体 (メイン / オフ)。{@code pos} は使わない</li>
 * </ul>
 *
 * <p>手持ちを<b>インベントリのスロット番号でなく手で</b> 指しているのは、スロット番号の取り方が
 * 帯で割れる (26.x は {@code Inventory#getSelectedSlot}・1.21.1 は {@code selected} フィールド) のに対し、
 * {@link InteractionHand} は全帯で同じだから。GUI を開けるのは手に持っている時だけなので、
 * いま必要な情報はこれで足りる。
 *
 * <p><b>「インベントリのどこに在るか」が要るのは再生側</b> (在れば鳴る・複数台同時) で、そちらは
 * この値ではなく機体ごとの識別子で解く。第 1 スライスでは未実装。
 */
public record BoomboxSource(BlockPos pos, int hand) {
    //? if >=1.21 {

    public static final StreamCodec<ByteBuf, BoomboxSource> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, BoomboxSource::pos,
            ByteBufCodecs.VAR_INT, BoomboxSource::hand,
            BoomboxSource::new);
    //?} else {
    /*
    /^*
     * buf へ書き出す。1.20.1 に {@code BlockPos.STREAM_CODEC} は無いので、extended menu の
     * open データは生の {@link FriendlyByteBuf} で運ぶ ({@code CustomTrackData} と同じ流儀)。
     *
     * @param buf 書き出し先
     ^/
    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeVarInt(hand);
    }

    /^*
     * buf から復元する。
     *
     * @param buf 読み出し元
     * @return 復元した値
     ^/
    public static BoomboxSource read(FriendlyByteBuf buf) {
        return new BoomboxSource(buf.readBlockPos(), buf.readVarInt());
    }
    *///?}

    /** 置いてある機体。 */
    public static BoomboxSource placed(BlockPos pos) {
        return new BoomboxSource(pos, -1);
    }

    /** 手に持っている機体。 */
    public static BoomboxSource held(InteractionHand hand) {
        return new BoomboxSource(BlockPos.ZERO, hand == InteractionHand.OFF_HAND ? 1 : 0);
    }

    public boolean isPlaced() {
        return hand < 0;
    }

    /** 手持ちの時の手。置いてある機体に対して呼ばない。 */
    public InteractionHand heldHand() {
        return hand == 1 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }
}
