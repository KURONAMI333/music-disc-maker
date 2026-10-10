package com.kuronami.musicdiscmaker.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * client → server: maker(pos) の出力枠にある作ったばかりの盤の曲名を付け直す。
 * {@code expectedTitle} は画面が見ていた元の曲名で、その間に盤が入れ替わっていたら付け直さない。
 */
public record RenameDiscPayload(BlockPos pos, String expectedTitle, String title) implements ModPayload {

    public static final String PATH = "rename_disc";

    /** 曲名の上限 ({@code CustomTrackData} の表示文字列の上限と同じ)。 */
    public static final int MAX_TITLE = 256;

    public static RenameDiscPayload read(FriendlyByteBuf buf) {
        return new RenameDiscPayload(BlockPos.of(buf.readLong()), buf.readUtf(MAX_TITLE), buf.readUtf(MAX_TITLE));
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeLong(pos.asLong());
        buf.writeUtf(expectedTitle, MAX_TITLE);
        buf.writeUtf(title, MAX_TITLE);
    }
}
