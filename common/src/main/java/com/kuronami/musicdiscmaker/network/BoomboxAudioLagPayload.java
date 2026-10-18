package com.kuronami.musicdiscmaker.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * client → server: ブームボックスの曲がこの client で実際に鳴り始めた時、server の再生位置からどれだけ
 * 遅れていたか。server は聞いている人の中でいちばん遅い人が鳴り終わるまで、次の曲・頭出し・停止を待つ
 * (金のジュークボックスの {@link AudioLagPayload} と同じ考え方)。以前は報告が無く、固定の 8 秒を待っていた。
 *
 * @param boomboxId       鳴らしている個体の id ({@link BoomboxPlayPayload#boomboxId})
 * @param audioGeneration 鳴らし始めた再生の印 ({@link BoomboxPlayPayload#audioGeneration})。曲が変わった後の
 *                        古い報告は server が捨てる
 * @param lagMs           遅れ (ms)。server は上限で丸める
 */
public record BoomboxAudioLagPayload(long boomboxId, long audioGeneration, long lagMs) implements ModPayload {

    public static final String PATH = "boombox_audio_lag";

    public static BoomboxAudioLagPayload read(FriendlyByteBuf buf) {
        return new BoomboxAudioLagPayload(buf.readLong(), buf.readVarLong(), buf.readVarLong());
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeLong(boomboxId);
        buf.writeVarLong(audioGeneration);
        buf.writeVarLong(lagMs);
    }
}
