package com.kuronami.musicdiscmaker.client.audio;

import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;

/**
 * client 側: jukebox の再生/停止 packet を受けて {@link ClientPlaybackManager} を駆動する。
 * client 専用 (ModNetwork から lambda 経由でのみ参照されるので server ではロードされない)。
 */
public final class ClientPlaybackHandler {

    private ClientPlaybackHandler() {
    }

    public static void play(PlayDiscPayload payload) {
        ClientPlaybackManager.get().startPlayback(payload.jukeboxPos(), payload.track());
    }

    public static void stop(StopDiscPayload payload) {
        ClientPlaybackManager.get().stopPlayback(payload.jukeboxPos());
    }
}
