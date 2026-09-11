package com.kuronami.musicdiscmaker.client.audio;

import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.SpeakerSetPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * client 側: jukebox の再生/停止 packet を受けて {@link ClientPlaybackManager} を駆動する。
 * client 専用 (ModNetwork から lambda 経由でのみ参照されるので server ではロードされない)。
 *
 * <p>B5/C12: play 段ハンドシェイク ({@code VersionPayload}) の比較もここで行う。
 * サーバーの wire 版が自分と食い違っていたら<b>警告を出して client 機能を無効化する</b>
 * (接続は切らない = Forge {@code acceptMissingOr} の思想の play 段版)。
 * 「警告のみ」にしないのは、半端に動いて「接続はできるのに何も鳴らない」に戻るから。
 */
public final class ClientPlaybackHandler {

    public static final SourcePlaybackOwnership<Object> SOURCE_OWNERS = new SourcePlaybackOwnership<>();

    private record WorldRoute(net.minecraft.core.BlockPos pos) {}

    private static boolean claimWorld(net.minecraft.core.BlockPos pos,
            com.kuronami.musicdiscmaker.network.PlaybackSourceStamp identity) {
        if (identity.sourceId() == null) return true;
        return SOURCE_OWNERS.claim(identity.sourceId(), identity.generation(), new WorldRoute(pos.immutable()), () -> {
            VanillaSpeakerPlayback.stopVanilla(pos);
            VanillaSpeakerPlayback.stop(pos);
            ClientPlaybackManager.get().stopPlayback(pos);
        }) != null;
    }

    private ClientPlaybackHandler() {
    }

    public static void vanillaPlay(com.kuronami.musicdiscmaker.network.PlayVanillaDiscPayload payload) {
        if (protocolMismatch) return;
        if (!claimWorld(payload.jukeboxPos(), payload.identity())) return;
        ClientPlaybackManager.get().stopPlayback(payload.jukeboxPos());
        VanillaSpeakerPlayback.play(payload);
    }

    public static void play(PlayDiscPayload payload) {
        if (protocolMismatch) {
            return; // 版不一致: 再生を始めない (黙って進ませない)
        }
        if (!claimWorld(payload.jukeboxPos(), payload.identity())) return;
        VanillaSpeakerPlayback.stop(payload.jukeboxPos());
        ClientPlaybackManager.get().startPlayback(payload.jukeboxPos(), payload.track(), payload.startOffsetMs(),
                payload.rangeBlocks(), payload.volumePercent(), payload.directional());
    }

    public static void stop(StopDiscPayload payload) {
        // nativeのレコード音は所有権表を通らず始まるため、圏外への停止もここで消す。
        VanillaSpeakerPlayback.stopVanilla(payload.jukeboxPos());
        if (payload.identity().sourceId() != null) {
            SOURCE_OWNERS.stop(payload.identity().sourceId(), payload.identity().generation(),
                    new WorldRoute(payload.jukeboxPos().immutable()));
            return;
        }
        VanillaSpeakerPlayback.stop(payload.jukeboxPos());
        ClientPlaybackManager.get().stopPlayback(payload.jukeboxPos());
    }

    /** client受信: 固定speaker集合のライブ更新。音源は読み直さない。 */
    public static void speakerSet(SpeakerSetPayload payload) {
        if (!protocolMismatch) {
            if (!claimWorld(payload.sourcePos(), payload.identity())) return;
            ClientPlaybackManager.get().updateSpeakerSet(payload);
            VanillaSpeakerPlayback.update(payload);
        }
    }

    /**
     * client 受信: ブームボックスの再生 / keep-alive。
     *
     * @param payload 再生要求
     */
    public static void boomboxPlay(com.kuronami.musicdiscmaker.network.BoomboxPlayPayload payload) {
        if (protocolMismatch) {
            return; // 版不一致: 再生を始めない (黙って進ませない)
        }
        BoomboxClientPlayback.play(payload);
    }

    /**
     * client 受信: ブームボックスの停止。
     *
     * @param payload 停止要求
     */
    public static void boomboxStop(com.kuronami.musicdiscmaker.network.BoomboxStopPayload payload) {
        BoomboxClientPlayback.stop(payload.boomboxId());
    }

    /** 版不一致で client 機能を無効化した後 true。一度立ったら接続が変わるまで戻さない。 */
    private static volatile boolean protocolMismatch;

    public static void boomboxState(com.kuronami.musicdiscmaker.network.BoomboxStatePayload payload) {
        com.kuronami.musicdiscmaker.network.BoomboxTrace.state("client-state-received", payload);
        final var player = Minecraft.getInstance().player;
        if (!protocolMismatch && player != null
                && player.containerMenu instanceof com.kuronami.musicdiscmaker.menu.BoomboxMenu menu
                && menu.containerId == payload.containerId()) {
            menu.applyPlaybackState(payload.state());
            com.kuronami.musicdiscmaker.network.BoomboxTrace.state("client-state-applied", payload);
        }
    }

    /** B5/C12: 版不一致で機能を無効化したか。画面の C2S 送出のガードにも使う。 */
    public static boolean isProtocolMismatch() {
        return protocolMismatch;
    }

    /**
     * サーバーから届いた wire 版を自分の版と比べる。食い違っていたら警告を出し、
     * 以後の再生開始と C2S 送出を止める。
     *
     * <p>一致したときは何もしない (毎回出すものではない)。
     *
     * @param serverVersion サーバー側の {@code ModNetwork.PROTOCOL_VERSION}
     */
    public static void handleServerVersion(String serverVersion) {
        final String mine = com.kuronami.musicdiscmaker.network.ModNetwork.PROTOCOL_VERSION;
        if (mine.equals(serverVersion)) {
            return;
        }
        protocolMismatch = true;
        final var player = Minecraft.getInstance().player;
        if (player != null) {
            //? if >=26.1 {
            player.sendSystemMessage(Component.translatable(
                    "music_disc_maker.protocol_mismatch", mine, serverVersion));
            //?} else {
/*            player.displayClientMessage(Component.translatable(
                    "music_disc_maker.protocol_mismatch", mine, serverVersion), false);
*/
            //?}
        }
        com.kuronami.musicdiscmaker.MusicDiscMaker.LOGGER.warn(
                "Protocol version mismatch: client={} server={} -- MDM client features disabled",
                mine, serverVersion);
    }
}
