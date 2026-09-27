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
 * <p>ブームボックスは<b>持ったままでも置いてからでも同じ GUI が開く</b> (設計上の決定 2026-09-07) ので、
 * 既存 3 ブロックの menu のように {@link BlockPos} 1 本では足りない。
 *
 * <ul>
 *   <li>{@code hand < 0} — 置いてある機体。{@code pos} がその位置</li>
 *   <li>{@code hand == 0} / {@code 1} — 手に持っている機体 (メイン / オフ)。{@code pos} は使わない</li>
 *   <li>{@code hand == 2} — Curios の機能スロットに装備している機体。
 *       {@code equipmentSlot} / {@code equipmentIndex} / {@code expectedId} を server で再検証する</li>
 * </ul>
 *
 * <p>手持ちを<b>インベントリのスロット番号でなく手で</b> 指しているのは、スロット番号の取り方が
 * 帯で割れる (26.x は {@code Inventory#getSelectedSlot}・1.21.1 は {@code selected} フィールド) のに対し、
 * {@link InteractionHand} は全帯で同じだから。GUI を開けるのは手に持っている時だけなので、
 * いま必要な情報はこれで足りる。
 *
 * <p><b>「インベントリのどこに在るか」が要るのは再生側</b> (在れば鳴る・複数台同時) で、そちらは
 * この値ではなく機体ごとの識別子で解く。
 */
public record BoomboxSource(BlockPos pos, int hand, String equipmentSlot, int equipmentIndex,
        long expectedId) {
    private static final int EQUIPPED = 2;
    private static final int MAX_SLOT_ID_LENGTH = 64;

    //? if >=1.21 {

    public static final StreamCodec<ByteBuf, BoomboxSource> STREAM_CODEC = StreamCodec.of(
            (buf, source) -> {
                BlockPos.STREAM_CODEC.encode(buf, source.pos());
                ByteBufCodecs.VAR_INT.encode(buf, source.hand());
                ByteBufCodecs.STRING_UTF8.encode(buf, source.equipmentSlot());
                ByteBufCodecs.VAR_INT.encode(buf, source.equipmentIndex());
                ByteBufCodecs.VAR_LONG.encode(buf, source.expectedId());
            },
            buf -> new BoomboxSource(
                    BlockPos.STREAM_CODEC.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.VAR_INT.decode(buf),
                    ByteBufCodecs.VAR_LONG.decode(buf)));
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
        buf.writeUtf(equipmentSlot, MAX_SLOT_ID_LENGTH);
        buf.writeVarInt(equipmentIndex);
        buf.writeVarLong(expectedId);
    }

    /^*
     * buf から復元する。
     *
     * @param buf 読み出し元
     * @return 復元した値
     ^/
    public static BoomboxSource read(FriendlyByteBuf buf) {
        return new BoomboxSource(buf.readBlockPos(), buf.readVarInt(),
                buf.readUtf(MAX_SLOT_ID_LENGTH), buf.readVarInt(), buf.readVarLong());
    }
    *///?}

    /** 置いてある機体。 */
    public static BoomboxSource placed(BlockPos pos) {
        return new BoomboxSource(pos, -1, "", -1, 0L);
    }

    /** 手に持っている機体。 */
    public static BoomboxSource held(InteractionHand hand) {
        return new BoomboxSource(BlockPos.ZERO, hand == InteractionHand.OFF_HAND ? 1 : 0, "", -1, 0L);
    }

    /** Curios の機能スロットに装備している機体。 */
    public static BoomboxSource equipped(String slotId, int index, long expectedId) {
        if (slotId == null || slotId.length() > MAX_SLOT_ID_LENGTH) {
            throw new IllegalArgumentException("Invalid Curios slot id");
        }
        return new BoomboxSource(BlockPos.ZERO, EQUIPPED, slotId, index, expectedId);
    }

    public boolean isPlaced() {
        return hand < 0;
    }

    public boolean isHeld() {
        return hand == 0 || hand == 1;
    }

    public boolean isEquipped() {
        return hand == EQUIPPED;
    }

    /** 手持ちの時の手。手持ち以外の機体に対して呼ばない。 */
    public InteractionHand heldHand() {
        if (!isHeld()) throw new IllegalStateException("Boombox source is not held");
        return hand == 1 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
    }
}
