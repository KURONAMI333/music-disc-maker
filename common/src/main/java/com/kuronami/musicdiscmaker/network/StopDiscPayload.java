package com.kuronami.musicdiscmaker.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * server → client: jukebox(pos) の再生停止 (disc 取り出し / ブロック破壊 / 曲の終わり)。
 *
 * <p>{@code naturalEnd} は曲を最後まで鳴らし切ったことによる停止。client は自分の音が読み込みの遅れで
 * server より後ろにいる時、鳴り終わるまで止めるのを待つ。取り出し・一時停止などはすぐ止める。
 */
public record StopDiscPayload(BlockPos jukeboxPos, PlaybackSourceStamp identity, boolean naturalEnd)
        implements ModPayload {

    public StopDiscPayload(BlockPos pos) { this(pos, PlaybackSourceStamp.NONE, false); }

    public StopDiscPayload(BlockPos pos, PlaybackSourceStamp identity) { this(pos, identity, false); }

    public static final String PATH = "stop_disc";

    public static StopDiscPayload read(FriendlyByteBuf buf) {
        return new StopDiscPayload(BlockPos.of(buf.readLong()), PlaybackSourceStamp.read(buf), buf.readBoolean());
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeLong(jukeboxPos.asLong());
        identity.write(buf);
        buf.writeBoolean(naturalEnd);
    }
}
