package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.MusicDiscMaker;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** client → server: maker(pos) の URL を解決して曲情報を取得させる。 */
public record ResolveUrlPayload(BlockPos pos, String url) implements ModPayload {

    public static final ResourceLocation ID = new ResourceLocation(MusicDiscMaker.MODID, "resolve_url");

    public static ResolveUrlPayload read(FriendlyByteBuf buf) {
        return new ResolveUrlPayload(buf.readBlockPos(), buf.readUtf(2048));
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeUtf(url, 2048);
    }
}
