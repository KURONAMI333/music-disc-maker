package com.kuronami.musicdiscmaker.network;

import java.util.UUID;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * server → client: ブームボックス {@code boomboxId} の再生。
 *
 * <p><b>キーはアイテム個体の UUID であって持ち主でも座標でもない。</b> 携帯プレイヤーは持ち替えても
 * インベントリの中を動いても鳴り続けるので、スロットや entity では音源を指せない。同じ URL の
 * ブームボックスが 2 台あっても独立に鳴り独立に止まる、という要件もこのキーでしか満たせない。
 * {@code ownerEntityId} は「どこから鳴っているか」= 聴取アンカーの取り付け先で、別の軸。
 *
 * <p>ブロックと違い chunk を持たないので、宛先は「その entity を追跡中の player + 本人」。
 * server は再生中のあいだ一定間隔で撃ち続ける (keep-alive)。これが 3 役を兼ねる:
 * ① 再生開始 ② 後から近づいた player への late-join ③ client 側の生存確認 — 一定時間届かなければ
 * 「持ち主が落とした / しまった」とみなして自己停止する。
 *
 * <p>音量・指向性を毎回載せているのは、GUI の変更を<b>鳴らし直さずに</b>反映させるため。client は
 * 同じ URL の keep-alive でストリームには触らず、値だけをライブアンカーへ書き込む。
 */
public record BoomboxPlayPayload(UUID boomboxId, int ownerEntityId, CustomTrackData track,
        long startOffsetMs, int volumePercent, boolean directional) implements CustomPacketPayload {

    public static final Type<BoomboxPlayPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "boombox_play"));

    public static final StreamCodec<ByteBuf, BoomboxPlayPayload> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, BoomboxPlayPayload::boomboxId,
            ByteBufCodecs.VAR_INT, BoomboxPlayPayload::ownerEntityId,
            CustomTrackData.STREAM_CODEC, BoomboxPlayPayload::track,
            ByteBufCodecs.VAR_LONG, BoomboxPlayPayload::startOffsetMs,
            ByteBufCodecs.VAR_INT, BoomboxPlayPayload::volumePercent,
            ByteBufCodecs.BOOL, BoomboxPlayPayload::directional,
            BoomboxPlayPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
