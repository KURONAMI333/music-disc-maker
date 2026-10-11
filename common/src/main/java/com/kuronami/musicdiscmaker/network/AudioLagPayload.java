package com.kuronami.musicdiscmaker.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * client → server: この client で曲が実際に鳴り始めた時、server の再生位置からどれだけ遅れていたか。
 * server は聞いている人の中でいちばん遅い人が鳴り終わるまで、アルバムの次の曲・1曲リピートの頭出しを待つ
 * (URL の曲は読み込みのぶん遅れて鳴り始めるので、待たないと前の曲の最後が切れる)。
 *
 * @param pos      設置した金のジュークボックスの位置 ({@code entityId} が負の時)
 * @param entityId 金のジュークボックス付きトロッコの entity id。設置物なら {@code -1}
 * @param identity 鳴らし始めた再生の印。曲が変わった後の古い報告は server が捨てる
 * @param lagMs    遅れ (ms)。server は上限で丸める
 */
public record AudioLagPayload(BlockPos pos, int entityId, PlaybackSourceStamp identity, long lagMs)
        implements ModPayload {

    public static final String PATH = "audio_lag";

    public static AudioLagPayload read(FriendlyByteBuf buf) {
        return new AudioLagPayload(BlockPos.of(buf.readLong()), buf.readVarInt(), PlaybackSourceStamp.read(buf),
                buf.readVarLong());
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeLong(pos.asLong());
        buf.writeVarInt(entityId);
        identity.write(buf);
        buf.writeVarLong(lagMs);
    }
}
