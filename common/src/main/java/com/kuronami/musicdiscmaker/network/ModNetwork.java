package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.block.MusicDiscMakerBlockEntity;
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

    /**
     * payload の並びの版。<b>どれか 1 つでも wire 形式が変われば上げること。</b>
     *
     * <p>NeoForge はこの文字列を接続交渉に使い、一致しない client を接続時に弾く。上げ忘れると
     * 「古い client と新しい server が同じ版だと合意し、その後で食い違うバイト列を読む」形になる
     * (症状は接続エラーではなく、再生の取り違え・意味不明な範囲/音量・切断)。<b>接続を断る方が
     * 壊れたバイト列を読むより安全</b>なので、上げるのを渋らない。
     *
     * <p>実際に食い違いを起こすのはここでなく各 payload の {@code STREAM_CODEC} なので、
     * 「codec を触ったのに版を上げていない」は人の注意力ではなく
     * {@code NetworkProtocolGameTests} の wire 指紋が機械で捕まえる。
     *
     * <p>版の履歴: {@code "1"} = 2.1.0 まで / {@code "2"} = {@code PlayDiscPayload} と
     * {@code ConfigureJukeboxPayload} に指向性 (BOOL) が増えた 2.2.0 以降。
     *
     * <p>Fabric には同等の交渉が無い (payload 型登録に版を渡す口が無い) ので、この定数が効くのは
     * NeoForge 側だけ。
     */
    public static final String PROTOCOL_VERSION = "2";

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
            // 値の保存を先に (range/volume/repeat/directional) → 最後に paused の再生調停
            // (最新値で resume させる = 再生 payload に最新の指向性が載る)。
            jukebox.setRangeBlocks(payload.rangeBlocks());
            jukebox.setVolumePercent(payload.volumePercent());
            jukebox.setRepeat(payload.repeat());
            jukebox.setDirectional(payload.directional());
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

    /** client 受信: 再生開始 (client 専用経路。dedicated server では never-load)。 */
    public static void handlePlay(PlayDiscPayload payload) {
        ClientPlaybackHandler.play(payload);
    }

    /** client 受信: 再生停止 (client 専用経路)。 */
    public static void handleStop(StopDiscPayload payload) {
        ClientPlaybackHandler.stop(payload);
    }
}
