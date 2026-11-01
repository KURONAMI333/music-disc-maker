package com.kuronami.musicdiscmaker.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/**
 * client → server: server の回線で URL を解決できなかった時、その client (自宅回線) が取った
 * 曲名・アーティスト・尺を渡す。server は焼く直前に値と URL を検め、通れば元の URL のまま盤を作る。
 *
 * <p>なぜ client が取るか: 専用サーバーの回線が YouTube に bot 判定されると、同じ URL でも
 * プレイヤーの回線では取れることがある。盤の尺は server が焼くので、ここで尺が渡らないと
 * 長さ 0 (= ラジオ扱い) の盤しか作れない。
 *
 * <p>{@code url} は client が解決した後の住所 (解決結果が入力と同じなら入力そのもの)。server は
 * これを焼かず、<b>元の URL と同じ動画を指しているか</b>の照合にだけ使う。
 *
 * @param pos        製作機の位置
 * @param url        client が解決した住所
 * @param title      表示用の曲名
 * @param author     表示用のアーティスト
 * @param durationMs 尺 (ms)。0 以下は「分からない」なので server は焼かない
 */
public record ClientResolvedTrackPayload(BlockPos pos, String url, String title, String author, long durationMs)
        implements ModPayload {

    public static final String PATH = "client_resolved_track";

    /** URL の上限 ({@code ResolveUrlPayload} と同じ)。 */
    public static final int MAX_URL = 2048;

    /** 曲名・アーティストの上限 ({@code CustomTrackData} の表示文字列の上限と同じ)。 */
    public static final int MAX_TEXT = 256;

    public static ClientResolvedTrackPayload read(FriendlyByteBuf buf) {
        return new ClientResolvedTrackPayload(BlockPos.of(buf.readLong()), buf.readUtf(MAX_URL),
                buf.readUtf(MAX_TEXT), buf.readUtf(MAX_TEXT), buf.readVarLong());
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeLong(pos.asLong());
        buf.writeUtf(url, MAX_URL);
        buf.writeUtf(title, MAX_TEXT);
        buf.writeUtf(author, MAX_TEXT);
        buf.writeVarLong(durationMs);
    }
}
