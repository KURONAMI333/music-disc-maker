package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.block.MusicDiscMakerBlockEntity;
import com.kuronami.musicdiscmaker.block.SpeakerBlockEntity;
import com.kuronami.musicdiscmaker.client.audio.ClientPlaybackHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * loader 非依存の payload ハンドラ本体。payload 型の登録・受信配線は各ローダーの entry が行い、
 * 受信時に (main thread で) これらのハンドラへ委譲する。
 *
 * <p>GUI はボタンレスなので client は URL をコミットするだけ ({@link ResolveUrlPayload})、
 * 解決と disc 生成は {@code DiscFabrication} が「URL + 空ディスク」が揃ったら自動で行う。
 * S→C 再生 packet の client ハンドラは {@link ClientPlaybackHandler} 経由で client のみロードされる。
 */
public final class ModNetwork {

    private static final double MAX_REACH_SQR = 64.0;

    private ModNetwork() {
    }

    /** server 受信: client → server の URL コミット。 */
    public static void handleResolveUrl(ResolveUrlPayload payload, ServerPlayer player) {
        final BlockPos pos = payload.pos();
        if (!player.level().isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_REACH_SQR) {
            return;
        }
        if (player.level().getBlockEntity(pos) instanceof MusicDiscMakerBlockEntity maker) {
            maker.setCurrentUrl(payload.url()); // setCurrentUrl が DiscFabrication.process を呼ぶ
        }
    }

    /** server 受信: 強化版ジュークボックスの設定適用。 */
    public static void handleConfigureJukebox(ConfigureJukeboxPayload payload, ServerPlayer player) {
        final BlockPos pos = payload.pos();
        if (!player.level().isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_REACH_SQR) {
            return;
        }
        if (player.level().getBlockEntity(pos) instanceof GoldenJukeboxBlockEntity jukebox) {
            // 値の保存を先に (range/volume/repeat) → 最後に paused の再生調停 (最新値で resume させる)。
            jukebox.setRangeBlocks(payload.rangeBlocks());
            jukebox.setVolumePercent(payload.volumePercent());
            jukebox.setRepeat(payload.repeat());
            jukebox.setPaused(payload.paused());
        }
    }

    /** server 受信: 強化版ジュークボックスのシークバー頭出し。 */
    public static void handleSeekJukebox(SeekJukeboxPayload payload, ServerPlayer player) {
        final BlockPos pos = payload.pos();
        if (!player.level().isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_REACH_SQR) {
            return;
        }
        if (player.level().getBlockEntity(pos) instanceof GoldenJukeboxBlockEntity jukebox) {
            jukebox.seekTo(payload.offsetMs());
        }
    }

    /** server 受信: スピーカーの音量・可聴範囲の適用。 */
    public static void handleConfigureSpeaker(SpeakerConfigPayload payload, ServerPlayer player) {
        final BlockPos pos = payload.pos();
        if (!player.level().isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_REACH_SQR) {
            return;
        }
        if (player.level().getBlockEntity(pos) instanceof SpeakerBlockEntity speaker) {
            speaker.setVolumePercent(payload.volumePercent());
            speaker.setRangeBlocks(payload.rangeBlocks());
            // スピーカーの chunk をロードしていない listener はスナップショットで鳴っているので、
            // 集合を送り直して新しい値を届ける (BE ライブ再読が届かない距離の救済)。
            speaker.notifySourceChanged();
        }
    }

    /** client 受信: 再生開始 (client 専用経路。dedicated server では never-load)。 */
    public static void handlePlay(PlayDiscPayload payload) {
        ClientPlaybackHandler.play(payload);
    }

    /** client 受信: 再生停止 (client 専用経路)。 */
    public static void handleStop(StopDiscPayload payload) {
        ClientPlaybackHandler.stop(payload);
    }

    /** client 受信: 有効スピーカー集合の更新 (client 専用経路)。 */
    public static void handleSpeakerSet(SpeakerSetPayload payload) {
        ClientPlaybackHandler.speakers(payload);
    }
}
