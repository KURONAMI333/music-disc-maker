package com.kuronami.musicdiscmaker.network;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.VanillaTrackData;

import net.minecraft.network.FriendlyByteBuf;

/**
 * server → client: 金ジューク付きトロッコ ({@code entityId}) の再生開始 / keep-alive。
 * {@link com.kuronami.musicdiscmaker.compat.create.ContraptionPlayDiscPayload} と同型だが
 * {@code localPos} と {@code MovingSpeakerState} を持たない (cart は entity そのものが鳴るので
 * 追従先は {@code entityId} だけで足りる。speaker 連携は未実装)。
 *
 * <h2>鍵と追従先</h2>
 * 音源の同一性は {@link PlaybackSourceStamp#sourceId()} (機体の永続識別子) と
 * {@code generation}。{@code entityId} はどこに追従して鳴らすかの軸 — 拾い直しで
 * entity が作り直されても、同じ {@code sourceId} の新しい世代として届く。
 *
 * <h2>keep-alive / 停止</h2>
 * server は再生中に 1 秒ごとに撃ち続ける。client は {@link com.kuronami.musicdiscmaker.client.audio.CartAnchor}
 * の最終受信時刻で途絶を検知して自己停止する。{@link #isStop()} は明示停止
 * (media 取り出し・pause・破壊) で使う。
 */
public record GoldenCartPlayPayload(int entityId, CustomTrackData track, @Nullable VanillaTrackData vanilla,
        long startOffsetMs, int rangeBlocks, int volumePercent, boolean directional,
        PlaybackSourceStamp identity) implements ModPayload {

    public static final String PATH = "golden_cart_play";

    /** 停止形。media も vanilla 曲も持たない空の payload を「鳴っていない」として送る。 */
    public static GoldenCartPlayPayload stop(int entityId, PlaybackSourceStamp identity) {
        return new GoldenCartPlayPayload(entityId, CustomTrackData.EMPTY, null, 0L, 0, 0, true, identity);
    }

    public boolean isStop() {
        return track.isEmpty() && vanilla == null;
    }

    public static GoldenCartPlayPayload read(FriendlyByteBuf buf) {
        final int entityId = buf.readVarInt();
        final CustomTrackData track = CustomTrackData.read(buf);
        final VanillaTrackData vanilla = buf.readBoolean()
                ? new VanillaTrackData(buf.readUtf(256), buf.readVarLong()) : null;
        return new GoldenCartPlayPayload(entityId, track, vanilla, buf.readVarLong(),
                buf.readVarInt(), buf.readVarInt(), buf.readBoolean(), PlaybackSourceStamp.read(buf));
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        track.write(buf);
        buf.writeBoolean(vanilla != null);
        if (vanilla != null) {
            buf.writeUtf(vanilla.soundEventId(), 256);
            buf.writeVarLong(vanilla.durationMs());
        }
        buf.writeVarLong(startOffsetMs);
        buf.writeVarInt(rangeBlocks);
        buf.writeVarInt(volumePercent);
        buf.writeBoolean(directional);
        identity.write(buf);
    }
}
