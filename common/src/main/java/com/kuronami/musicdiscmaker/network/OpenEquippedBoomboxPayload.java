package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.compat.curios.CuriosEquipmentAccess;

import net.minecraft.network.FriendlyByteBuf;

/**
 * client → server: Curios の機能スロットにあるブームボックスの GUI を開く要求。
 *
 * <p>client が送る slot/index/id は参照候補にすぎない。server は現在の Curios handler を引き直し、
 * 専用 slot・index・機体 ID が全て一致した時だけ menu を開く。
 */
public record OpenEquippedBoomboxPayload(String slotId, int slotIndex, long boomboxId) implements ModPayload {
    public static final String PATH = "open_equipped_boombox";
    private static final int MAX_SLOT_ID_LENGTH = 64;

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(slotId, MAX_SLOT_ID_LENGTH);
        buf.writeVarInt(slotIndex);
        buf.writeVarLong(boomboxId);
    }

    public static OpenEquippedBoomboxPayload read(FriendlyByteBuf buf) {
        return new OpenEquippedBoomboxPayload(
                buf.readUtf(MAX_SLOT_ID_LENGTH), buf.readVarInt(), buf.readVarLong());
    }

    public boolean isDedicatedSlot() {
        // boomboxId は mintId() が負値も返し得るので符号で絞らない。
        // 値の正当性は handler が「現在の実機体の id と一致するか」で決める。
        return CuriosEquipmentAccess.BOOMBOX_SLOT.equals(slotId)
                && slotIndex == CuriosEquipmentAccess.BOOMBOX_INDEX;
    }
}
