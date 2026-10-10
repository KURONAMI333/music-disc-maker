package com.kuronami.musicdiscmaker.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * client → server: maker(pos) の出力枠にある作ったばかりの盤を取りやめ、空のディスクに戻して入力枠へ返す。
 * {@code expectedTitle} は画面が見ていた曲名で、その間に盤が入れ替わっていたら何もしない。
 */
public record CancelDiscPayload(BlockPos pos, String expectedTitle) implements ModPayload {

    public static final String PATH = "cancel_disc";

    public static CancelDiscPayload read(FriendlyByteBuf buf) {
        return new CancelDiscPayload(BlockPos.of(buf.readLong()), buf.readUtf(RenameDiscPayload.MAX_TITLE));
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeLong(pos.asLong());
        buf.writeUtf(expectedTitle, RenameDiscPayload.MAX_TITLE);
    }
}
