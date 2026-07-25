package com.kuronami.musicdiscmaker.client.audio;

import com.kuronami.musicdiscmaker.network.BoomboxPlayPayload;
import com.kuronami.musicdiscmaker.network.BoomboxStopPayload;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.SpeakerSetPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;

/**
 * client 側: jukebox の再生/停止 packet を受けて {@link ClientPlaybackManager} を駆動する。
 * client 専用 (ModNetwork から lambda 経由でのみ参照されるので server ではロードされない)。
 */
public final class ClientPlaybackHandler {

    private ClientPlaybackHandler() {
    }

    public static void play(PlayDiscPayload payload) {
        ClientPlaybackManager.get().startPlayback(payload.jukeboxPos(), payload.track(), payload.startOffsetMs(),
                payload.rangeBlocks(), payload.volumePercent(), payload.playbackId());
    }

    public static void stop(StopDiscPayload payload) {
        ClientPlaybackManager.get().stopPlayback(payload.jukeboxPos());
        // 停止した音源のスピーカー集合は保持しない (再生開始時に必ず再送される)。
        ClientPlaybackManager.get().forgetSpeakers(payload.jukeboxPos());
    }

    /** 聴取モデルの更新 (音源にぶら下がる有効スピーカー集合 + 指向性)。 */
    public static void speakers(SpeakerSetPayload payload) {
        ClientPlaybackManager.get().updateSpeakers(
                payload.sourcePos(), payload.directional(), payload.speakers());
    }

    /** 手持ちブームボックスの再生 / keep-alive。 */
    public static void boomboxPlay(BoomboxPlayPayload payload) {
        BoomboxClientPlayback.play(payload.entityId(), payload.track(), payload.startOffsetMs(),
                payload.rangeBlocks(), payload.volumePercent(), payload.directional());
    }

    /** 手持ちブームボックスの停止。 */
    public static void boomboxStop(BoomboxStopPayload payload) {
        BoomboxClientPlayback.stop(payload.entityId());
    }
}
