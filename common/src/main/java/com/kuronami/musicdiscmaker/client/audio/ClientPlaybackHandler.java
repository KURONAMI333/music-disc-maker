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
        if (ClientPlaybackManager.get().holdUntilNaturalEnd(payload.jukeboxPos(), payload.identity().generation(),
                "play", false, () -> vanillaPlay(payload))) return;
        if (!claimWorld(payload.jukeboxPos(), payload.identity())) return;
        ClientPlaybackManager.get().stopPlaybackBeforeNative(payload.identity(), payload.jukeboxPos(),
                () -> VanillaSpeakerPlayback.play(payload));
    }

    public static void play(PlayDiscPayload payload) {
        if (protocolMismatch) {
            return; // 版不一致: 再生を始めない (黙って進ませない)
        }
        // 曲の終わりの停止を待っている間に届いた次の曲は、前の音が鳴り終わってから始める (NaturalEndHandoff)。
        // 同じ世代の送り直し (chunk の追跡し直し・Speaker の範囲に入った時) は server の位置を載せているので、
        // 最初の1本だけ残す。それで開くと、待った分だけ次の曲の頭が欠ける。
        if (ClientPlaybackManager.get().holdUntilNaturalEnd(payload.jukeboxPos(), payload.identity().generation(),
                "play", false, () -> play(payload))) return;
        if (!claimWorld(payload.jukeboxPos(), payload.identity())) return;
        VanillaSpeakerPlayback.stop(payload.jukeboxPos());
        ClientPlaybackManager.get().startPlaybackWithIdentity(payload.jukeboxPos(), payload.track(), payload.startOffsetMs(),
                payload.rangeBlocks(), payload.volumePercent(), payload.directional(), payload.identity());
    }

    public static void stop(StopDiscPayload payload) {
        final ClientPlaybackManager manager = ClientPlaybackManager.get();
        if (manager.waitingForNaturalEnd(payload.jukeboxPos())) {
            if (payload.naturalEnd()) {
                // 同じ曲の終わりが別の経路でも届いた / 鳴らし切った盤が抜かれた。待ちがまとめて止める。
                // 預かった次の曲の終わりなら、その曲は server でもう終わっているので預かりを捨てる。
                manager.naturalEndArrivedWhileWaiting(payload.jukeboxPos(), payload.identity().generation());
                return;
            }
            manager.cancelNaturalEndWait(payload.jukeboxPos()); // 手動の停止: 前の音もすぐ止める
        } else if (payload.naturalEnd() && manager.deferNaturalEndStop(payload.jukeboxPos(),
                payload.identity().generation(), () -> stopNow(payload))) {
            // 曲の終わりによる停止は、この client の音が鳴り終わるまで待つ (読み込みの遅れ・途中の詰まりで
            // 最後が切れないように)。
            return;
        }
        stopNow(payload);
    }

    private static void stopNow(StopDiscPayload payload) {
        if (payload.naturalEnd()) {
            // 曲の終わりの停止の直後には次の曲が来る。温めた先読み (1.21〜1.21.1) を捨てない。
            ClientPlaybackManager.get().runKeepingPrefetch(payload.jukeboxPos(), () -> stopNowInner(payload));
            return;
        }
        stopNowInner(payload);
    }

    private static void stopNowInner(StopDiscPayload payload) {
        if (payload.identity().sourceId() != null) {
            // Native record sound starts through the vanilla level event without an ownership lease.
            // Preserve its stamped stop independently; managed AV cleanup remains route/generation guarded.
            VanillaSpeakerPlayback.stopVanilla(payload.jukeboxPos());
            final WorldRoute route = new WorldRoute(payload.jukeboxPos().immutable());
            SOURCE_OWNERS.stop(payload.identity().sourceId(), payload.identity().generation(), route);
            return;
        }
        VanillaSpeakerPlayback.stopVanilla(payload.jukeboxPos());
        VanillaSpeakerPlayback.stop(payload.jukeboxPos());
        ClientPlaybackManager.get().stopPlayback(payload.jukeboxPos());
    }

    public static void clearSourceOwnership() {
        SOURCE_OWNERS.clear();
    }

    static boolean ownsSourceRoute(com.kuronami.musicdiscmaker.network.PlaybackSourceStamp identity,
            net.minecraft.core.BlockPos pos) {
        return identity != null && identity.sourceId() != null && pos != null
                && SOURCE_OWNERS.owns(identity.sourceId(), identity.generation(), new WorldRoute(pos.immutable()));
    }

    /** client受信: 固定speaker集合のライブ更新。音源は読み直さない。 */
    public static void speakerSet(SpeakerSetPayload payload) {
        if (!protocolMismatch) {
            if (ClientPlaybackManager.get().holdUntilNaturalEnd(payload.sourcePos(), payload.identity().generation(),
                    "set", true, () -> speakerSet(payload))) return;
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
        BoomboxClientPlayback.stop(payload.boomboxId(), payload.naturalEnd());
    }

    /**
     * client 受信: 金ジューク cart の再生 / keep-alive。
     *
     * @param payload 再生要求
     */
    public static void goldenCartPlay(com.kuronami.musicdiscmaker.network.GoldenCartPlayPayload payload) {
        if (protocolMismatch) {
            return; // 版不一致: 再生を始めない (黙って進ませない)
        }
        GoldenCartClientPlayback.play(payload);
    }

    /**
     * client 受信: 金ジューク cart の停止。
     *
     * @param payload 停止要求
     */
    public static void goldenCartStop(com.kuronami.musicdiscmaker.network.GoldenCartStopPayload payload) {
        GoldenCartClientPlayback.stop(payload);
    }

    /** client 受信: 金のジュークボックス付きトロッコが載せた金のジュークボックスの状態 (GUI の表示用)。 */
    public static void goldenCartSync(com.kuronami.musicdiscmaker.network.GoldenCartSyncPayload payload) {
        final var level = Minecraft.getInstance().level;
        if (!protocolMismatch && level != null && level.getEntity(payload.entityId())
                instanceof com.kuronami.musicdiscmaker.entity.GoldenJukeboxMinecartEntity cart) {
            cart.applySyncedState(payload.state());
        }
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
     * 現在の play 接続が終了した時にだけ、接続単位の版不一致状態を破棄する。
     * stopAll() や一致する VersionPayload の受信では解除しない。
     */
    public static void resetProtocolMismatchOnDisconnect() {
        protocolMismatch = false;
        protocolConfirmed = false;
    }

    /** この接続でサーバーから自分と同じ wire 版が届いた後 true。接続が変わるまで戻さない。 */
    private static volatile boolean protocolConfirmed;

    /**
     * 3.1.3 で増えた opcode (上げ直しの RESTORE) をサーバーが復号できると分かっているか。
     *
     * <p>NeoForge と Forge は login 段で版の違う相手を弾くので、繋がっていれば同じ版。
     * Fabric には login 段の版ゲートが無く、旧サーバーは新 opcode を復号できずに接続を切る。
     * しかも 3.1.2 までの Fabric 1.20.1 サーバーは版を送ってこない (JOIN の時点では相手の
     * channel 宣言が届いておらず {@code canSend} が偽になる)。版が届かないことを「一致」と
     * みなさず、同じ版が届いたと確かめられた時だけ送る。
     */
    public static boolean serverDecodesCurrentProtocol() {
        if (protocolMismatch) return false;
        if (!"Fabric".equals(com.kuronami.musicdiscmaker.platform.Services.PLATFORM.getPlatformName())) return true;
        return protocolConfirmed;
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
            protocolConfirmed = true;
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
