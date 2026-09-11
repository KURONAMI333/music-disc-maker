package com.kuronami.musicdiscmaker.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/** client → server: maker(pos) の URL を解決して曲情報を取得させる。 */
public record ResolveUrlPayload(BlockPos pos, String url) implements ModPayload {

    public static final String PATH = "resolve_url";

    /** URL の上限。細工された巨大文字列で読み取り側を膨らませないための cap。 */
    public static final int MAX_URL = 2048;

    public static ResolveUrlPayload read(FriendlyByteBuf buf) {
        return new ResolveUrlPayload(BlockPos.of(buf.readLong()), buf.readUtf(MAX_URL));
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeLong(pos.asLong());
        buf.writeUtf(url, MAX_URL);
    }
}
