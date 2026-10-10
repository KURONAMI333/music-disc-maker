package com.kuronami.musicdiscmaker.network;

import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.menu.GoldenJukeboxMenu;
import com.kuronami.musicdiscmaker.menu.BoomboxMenu;
import com.kuronami.musicdiscmaker.block.BoomboxBlockEntity;
import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.compat.curios.CuriosEquipmentAccess;
import com.kuronami.musicdiscmaker.event.BoomboxCarry;
import com.kuronami.musicdiscmaker.event.BoomboxPlayback;
import com.kuronami.musicdiscmaker.menu.BoomboxSource;
import com.kuronami.musicdiscmaker.platform.Services;
import net.minecraft.server.level.ServerLevel;
import com.kuronami.musicdiscmaker.block.MusicDiscMakerBlockEntity;
import com.kuronami.musicdiscmaker.client.audio.ClientPlaybackHandler;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * loader 非依存の payload ハンドラ本体。payload 型の登録・受信配線は各ローダーの entry で行い
 * (NeoForge={@code NeoForgePayloads} / Fabric)、受信時 (main thread) にここへ委譲する。
 *
 * <p>GUI はボタンレスなので client は URL をコミットするだけ ({@link ResolveUrlPayload})、
 * 解決と disc 生成は {@code DiscFabrication} が「URL + 空ディスク」が揃ったら自動で行う。
 * S→C 再生 packet は {@link ClientPlaybackHandler} 経由で client のみロード。
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
     * <p>版の履歴: {@code "1"} = 指向性トグル追加前 / {@code "2"} = {@code PlayDiscPayload} と
     * {@code ConfigureJukeboxPayload} に指向性 (BOOL) が増えた版 / {@code "3"} = menuと世代を検証する曲送りを追加 / {@code "4"} = Boombox画面状態・操作・音声世代と音量を追加 / {@code "5"} = 固定スピーカーの聴取点同期を追加 / {@code "6"} = バニラ盤のスピーカー再生を追加 / {@code "7"} = スピーカー水平角と片側染色を追加。
     *
     * <p>Fabric には同等の交渉が無い (payload 型登録に版を渡す口が無い) ので、独自の参加時ハンドシェイクでこの定数を照合する。
     */
    // 8: Goldenの音源UUID・再生世代を通常再生/停止/Speaker更新へ追加。
    // 9: Createの再生payloadにも音源UUID・再生世代を追加。
    // 10: Sableの音源UUID・再生世代と専用停止payloadを追加。
    // 11: Createのバニラ盤を追加。12: 移動元のworld位置と固定Speakerの聴取点を追加。
    // 13: 染色Albumの新しいDataComponent/レシピ登録を持つ開発版。旧clientとの混在を防ぐ。
    // 14-mp3-dev: ローカルMP3持込の双方向転送 payload (local_audio) を追加した開発版。
    // 14-mp3-curios-dev: BoomboxSource に装備参照 (slot/index/機体id) を追加し、
    // 装備機体を開く C2S payload (open_equipped_boombox) を足した開発版。
    // 15-cart-dev: 金ジューク付きトロッコの menu 操作 (control_golden_cart) と
    // 再生/停止/状態 (golden_cart_play/stop/state) を追加した開発版。
    // 16-local-audio-check-dev: cache hit後、world原本の存在を検証する CHECK/AVAILABLE を追加。
    // 16: 上記をまとめた 3.1.0 の公開版。不一致時の案内に出るので開発用の接尾辞を付けない。
    // 17: stop_disc に曲の終わりの印 (naturalEnd) を追加。
    // 18: 金ジューク付きトロッコを金ジュークの menu・画面・操作 payload へ統合 (control_golden_cart と
    // golden_cart_state を廃止し golden_cart_sync を追加、golden_cart_stop に曲の終わりの印を追加)。
    // 19: boombox_play にバニラ・他 MOD の盤の曲を追加 (ブームボックスでどの盤でも鳴る)。
    public static final String PROTOCOL_VERSION = "19";

    private static final double MAX_REACH_SQR = 64.0;

    public static void handleLocalAudio(LocalAudioPayload payload, ServerPlayer player) {
        com.kuronami.musicdiscmaker.audio.local.LocalAudioServer.receive(payload, player);
    }
    public static void handleLocalAudioClient(LocalAudioPayload payload) {
        com.kuronami.musicdiscmaker.client.audio.LocalAudioClient.receive(payload);
    }

    private ModNetwork() {
    }

    // ── client → server ハンドラ (loader が ServerPlayer を渡す) ──

    public static void handleResolveUrl(ResolveUrlPayload payload, ServerPlayer player) {
        final BlockPos pos = payload.pos();
        if (!player.level().isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_REACH_SQR) {
            return;
        }
        if (player.level().getBlockEntity(pos) instanceof MusicDiscMakerBlockEntity maker) {
            maker.setCurrentUrl(payload.url()); // setCurrentUrl が DiscFabrication.process を呼ぶ
        }
    }

    /** 製作機の出力枠にある盤の曲名を付け直す。その製作機の画面を開いている player だけ。 */
    public static void handleRenameDisc(RenameDiscPayload payload, ServerPlayer player) {
        final BlockPos pos = payload.pos();
        if (!player.level().isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_REACH_SQR) {
            return;
        }
        if (player.containerMenu instanceof com.kuronami.musicdiscmaker.menu.MusicDiscMakerMenu menu
                && menu.getBlockEntity().getBlockPos().equals(pos)
                && player.level().getBlockEntity(pos) instanceof MusicDiscMakerBlockEntity maker) {
            maker.renameOutput(payload.title());
        }
    }

    public static void handleConfigureJukebox(ConfigureJukeboxPayload payload, ServerPlayer player) {
        if (opensMountedJukebox(player)) {
            final GoldenJukeboxBlockEntity mounted = mountedJukebox(player);
            if (mounted == null) return;
            mounted.setRangeBlocks(payload.rangeBlocks());
            mounted.setVolumePercent(payload.volumePercent());
            mounted.setRepeat(payload.repeat());
            mounted.setDirectional(payload.directional());
            if (mounted.isPaused() != payload.paused()) mounted.setPaused(payload.paused());
            return;
        }
        final BlockPos pos = payload.pos();
        if (!player.level().isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_REACH_SQR) {
            return;
        }
        //? if >=1.21.2 {
        if (player.level().getBlockEntity(pos) instanceof GoldenJukeboxBlockEntity be) {
            // 値の保存を先に (range/volume/repeat/directional) → 最後に paused の再生調停
            // (最新値で resume させる = 再生 payload に最新の指向性が載る)。
            be.setRangeBlocks(payload.rangeBlocks());
            be.setVolumePercent(payload.volumePercent());
            be.setRepeat(payload.repeat());
            be.setDirectional(payload.directional());
            if (be.isPaused() != payload.paused()) be.setPaused(payload.paused());
        //?} elif >=1.21 {
        /*if (player.level().getBlockEntity(pos) instanceof GoldenJukeboxBlockEntity jukebox) {
            // 値の保存を先に (range/volume/repeat/directional) → 最後に paused の再生調停
            // (最新値で resume させる = 再生 payload に最新の指向性が載る)。
            jukebox.setRangeBlocks(payload.rangeBlocks());
            jukebox.setVolumePercent(payload.volumePercent());
            jukebox.setRepeat(payload.repeat());
            jukebox.setDirectional(payload.directional());
            if (jukebox.isPaused() != payload.paused()) jukebox.setPaused(payload.paused());
        *///?} else {
        /*if (player.level().getBlockEntity(pos) instanceof GoldenJukeboxBlockEntity be) {
            be.setRangeBlocks(payload.rangeBlocks());
            be.setVolumePercent(payload.volumePercent());
            be.setRepeat(payload.repeat());
            be.setDirectional(payload.directional());
            if (be.isPaused() != payload.paused()) be.setPaused(payload.paused()); // 値保存を先に、最後に paused で再生調停 (最新値で resume させる)
        *///?}
        }
    }

    public static void handleSeekJukebox(SeekJukeboxPayload payload, ServerPlayer player) {
        if (opensMountedJukebox(player)) {
            final GoldenJukeboxBlockEntity mounted = mountedJukebox(player);
            if (mounted != null) mounted.seekTo(payload.offsetMs());
            return;
        }
        final BlockPos pos = payload.pos();
        if (!player.level().isLoaded(pos) || player.distanceToSqr(Vec3.atCenterOf(pos)) > MAX_REACH_SQR) {
            return;
        }
        //? if >=1.21.2 {
        if (player.level().getBlockEntity(pos) instanceof GoldenJukeboxBlockEntity be) {
            be.seekTo(payload.offsetMs());
        //?} elif >=1.21 {
        /*if (player.level().getBlockEntity(pos) instanceof GoldenJukeboxBlockEntity jukebox) {
            jukebox.seekTo(payload.offsetMs());
        *///?} else {
        /*if (player.level().getBlockEntity(pos) instanceof GoldenJukeboxBlockEntity be) {
            be.seekTo(payload.offsetMs());
        *///?}
        }
    }

    /** 遅れて届いた操作を、閉じたmenuや差し替わった媒体へ適用しない。 */
    public static void handleNavigateJukebox(NavigateJukeboxPayload payload, ServerPlayer player) {
        final GoldenJukeboxBlockEntity be = openedJukebox(payload.pos(), payload.containerId(), payload.generation(), player);
        if (be == null) return;
        if (payload.previous()) be.previousTrack(); else be.nextTrack();
    }

    public static void handleShuffleJukebox(ShuffleJukeboxPayload payload, ServerPlayer player) {
        final GoldenJukeboxBlockEntity be = openedJukebox(payload.pos(), payload.containerId(), payload.generation(), player);
        if (be != null) be.setShuffle(payload.enabled());
    }

    /**
     * Curios の機能スロットに装備された機体の GUI 要求。client の送った slot/index/id は
     * 参照候補でしかないので、server は現在の handler を引き直し、専用 slot・index・機体 ID が
     * 一致した実物だけを開く。Curios 未導入の loader では provider が無く resolve が空を返す。
     */
    public static void handleOpenEquippedBoombox(OpenEquippedBoomboxPayload payload, ServerPlayer player) {
        if (!payload.isDedicatedSlot()) return;
        final CuriosEquipmentAccess.Entry equipped = CuriosEquipmentAccess.resolve(
                player, payload.slotId(), payload.slotIndex());
        if (equipped == null) return;
        final var stack = equipped.stack();
        if (!BoomboxCarry.isBoombox(stack) || BoomboxCarry.idOf(stack) != payload.boomboxId()) return;

        final long id = payload.boomboxId() == BoomboxContents.UNASSIGNED
                ? BoomboxPlayback.identify(stack) : payload.boomboxId();
        final BoomboxSource source = BoomboxSource.equipped(payload.slotId(), payload.slotIndex(), id);
        // identify 後にも同じ slot/index が同じ実物を指すことを確認する。途中で差し替わっていれば
        // 別機体の menu を開かず、以後も Menu 側が参照同一性を継続して検査する。
        final CuriosEquipmentAccess.Entry current = CuriosEquipmentAccess.resolve(
                player, payload.slotId(), payload.slotIndex());
        if (current == null || current.stack() != stack || BoomboxCarry.idOf(current.stack()) != id) return;
        final var syncBudget = BoomboxMenu.inspectInitialMenuSync(player.getInventory(), source);
        if (!syncBudget.fits()) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    syncBudget.rejectionKey("message.music_disc_maker.boombox.open_too_large")), true);
            return;
        }
        Services.MENU.openBoomboxMenu(player, source);
    }

    private static GoldenJukeboxBlockEntity openedJukebox(BlockPos pos, int containerId, long generation, ServerPlayer player) {
        if (!(player.containerMenu instanceof GoldenJukeboxMenu menu)
                || menu.containerId != containerId || !menu.stillValid(player)) return null;
        final GoldenJukeboxBlockEntity be = menu.getBlockEntity();
        if (be.isMounted()) {
            // トロッコに載った金のジュークボックスは座標で指せない。開いている menu が対象。
            return be.playbackCursor().generation() == generation ? be : null;
        }
        return be.getBlockPos().equals(pos) && player.level().isLoaded(pos)
                && player.level().getBlockEntity(pos) == be
                && be.playbackCursor().generation() == generation ? be : null;
    }

    /**
     * 開いている GUI がトロッコに載った金のジュークボックスか。距離が離れて使えなくなっていても true で、
     * その時の操作を座標で引いた設置物 (座標は世界の原点) へ落とさない。
     */
    private static boolean opensMountedJukebox(ServerPlayer player) {
        return player.containerMenu instanceof GoldenJukeboxMenu menu && menu.getBlockEntity().isMounted();
    }

    /**
     * 開いている GUI がトロッコに載った金のジュークボックスなら、その BE。
     * 載せた BE は世界の座標に無いので、payload の座標ではなく開いている menu で対象を決める。
     */
    @org.jetbrains.annotations.Nullable
    private static GoldenJukeboxBlockEntity mountedJukebox(ServerPlayer player) {
        return player.containerMenu instanceof GoldenJukeboxMenu menu && menu.getBlockEntity().isMounted()
                && menu.stillValid(player) ? menu.getBlockEntity() : null;
    }

    // ── server → client ハンドラ (client でのみ invoke される。dedicated server では never-load) ──

    public static void handleVanillaPlay(PlayVanillaDiscPayload payload) {
        ClientPlaybackHandler.vanillaPlay(payload);
    }

    public static void handlePlay(PlayDiscPayload payload) {
        ClientPlaybackHandler.play(payload);
    }

    /** client 受信: 再生停止 (client 専用経路)。 */
    public static void handleStop(StopDiscPayload payload) {
        ClientPlaybackHandler.stop(payload);
    }

    /**
     * client 受信: ブームボックスの再生 / keep-alive。
     *
     * @param payload 再生要求
     */
    public static void handleBoomboxPlay(BoomboxPlayPayload payload) {
        ClientPlaybackHandler.boomboxPlay(payload);
    }

    /**
     * client 受信: ブームボックスの停止。
     *
     * @param payload 停止要求
     */
    /** 機体の所持・設置状態とmenuの世代が一致した要求だけを適用する。 */
    public static void handleControlBoombox(ControlBoomboxPayload payload, ServerPlayer player) {
        if (!(player.containerMenu instanceof BoomboxMenu menu) || menu.containerId != payload.containerId()) {
            BoomboxTrace.control("reject-menu", payload, -1L);
            return;
        }
        if (!menu.stillValid(player) || menu.currentStack().isEmpty()) {
            BoomboxTrace.control("reject-source", payload, -1L);
            return;
        }
        final long currentGeneration = BoomboxPlayback.stateOf(menu.currentStack()).cursor().generation();
        if (currentGeneration != payload.generation()) {
            BoomboxTrace.control("reject-generation", payload, currentGeneration);
            sendBoomboxState(player, menu);
            return;
        }
        BoomboxTrace.control("control-received", payload, currentGeneration);
        final int action = payload.action();
        final long value = payload.value();
        if (action < ControlBoomboxPayload.PLAY || action > ControlBoomboxPayload.VOLUME
                || (action == ControlBoomboxPayload.SEEK && value < 0)
                || ((action == ControlBoomboxPayload.REPEAT || action == ControlBoomboxPayload.SHUFFLE)
                    && value != 0 && value != 1)
                || (action == ControlBoomboxPayload.VOLUME && (value < 0 || value > 100))) return;
        final long generation = payload.generation();
        if (menu.source().isPlaced()) {
            final ServerLevel level = (ServerLevel) player.level();
            final BlockPos pos = menu.source().pos();
            if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof BoomboxBlockEntity be)
                    || be.getStored() != menu.currentStack()) return;
            switch (action) {
                case ControlBoomboxPayload.PLAY -> BoomboxPlayback.startPlaced(level, pos, be);
                case ControlBoomboxPayload.PAUSE -> BoomboxPlayback.pausePlaced(level, pos, be, generation);
                case ControlBoomboxPayload.PREVIOUS -> BoomboxPlayback.previousPlaced(level, pos, be, generation);
                case ControlBoomboxPayload.NEXT -> BoomboxPlayback.nextPlaced(level, pos, be, generation);
                case ControlBoomboxPayload.SEEK -> BoomboxPlayback.seekPlaced(level, pos, be, value, generation);
                case ControlBoomboxPayload.REPEAT -> BoomboxPlayback.setRepeatPlaced(level, pos, be, value == 1, generation);
                case ControlBoomboxPayload.SHUFFLE -> BoomboxPlayback.setShufflePlaced(level, pos, be, value == 1, generation);
                case ControlBoomboxPayload.VOLUME -> BoomboxPlayback.setVolumePlaced(level, pos, be, (int) value, generation);
                default -> { return; }
            }
        } else {
            final var stack = menu.currentStack();
            switch (action) {
                case ControlBoomboxPayload.PLAY -> BoomboxPlayback.startCarried(player, stack);
                case ControlBoomboxPayload.PAUSE -> BoomboxPlayback.pauseCarried(player, stack, generation);
                case ControlBoomboxPayload.PREVIOUS -> BoomboxPlayback.previousCarried(player, stack, generation);
                case ControlBoomboxPayload.NEXT -> BoomboxPlayback.nextCarried(player, stack, generation);
                case ControlBoomboxPayload.SEEK -> BoomboxPlayback.seekCarried(player, stack, value, generation);
                case ControlBoomboxPayload.REPEAT -> BoomboxPlayback.setRepeatCarried(player, stack, value == 1, generation);
                case ControlBoomboxPayload.SHUFFLE -> BoomboxPlayback.setShuffleCarried(player, stack, value == 1, generation);
                case ControlBoomboxPayload.VOLUME -> BoomboxPlayback.setVolumeCarried(player, stack, (int) value, generation);
                default -> { return; }
            }
        }
        menu.broadcastChanges();
        sendBoomboxState(player, menu);
    }

    public static void sendBoomboxState(ServerPlayer player, BoomboxMenu menu) {
        if (player.containerMenu == menu && menu.stillValid(player)) {
            Services.NETWORK.sendToPlayer(player,
                    new BoomboxStatePayload(menu.containerId, BoomboxPlayback.stateOf(menu.currentStack())));
        }
    }

    public static void handleGoldenCartPlay(GoldenCartPlayPayload payload) {
        ClientPlaybackHandler.goldenCartPlay(payload);
    }

    public static void handleGoldenCartStop(GoldenCartStopPayload payload) {
        ClientPlaybackHandler.goldenCartStop(payload);
    }

    public static void handleGoldenCartSync(GoldenCartSyncPayload payload) {
        ClientPlaybackHandler.goldenCartSync(payload);
    }

    public static void handleSpeakerSet(SpeakerSetPayload payload) {
        ClientPlaybackHandler.speakerSet(payload);
    }

    public static void handleBoomboxState(BoomboxStatePayload payload) {
        ClientPlaybackHandler.boomboxState(payload);
    }

    public static void handleBoomboxStop(BoomboxStopPayload payload) {
        ClientPlaybackHandler.boomboxStop(payload);
    }

    /**
     * client 受信: play 段ハンドシェイク (B5/C12)。サーバーの wire 版を受け取り、
     * 自分の版と比較して食い違っていたら警告 + client 機能の無効化。
     * Fabric の 4 ノードだけの経路 (NeoForge は registrar 交渉 / Forge 1.20.1 は
     * {@code acceptMissingOr} で login 段に版ゲートがあるため、ここへ来る前に弾かれる)。
     */
    public static void handleClientVersion(VersionPayload payload) {
        ClientPlaybackHandler.handleServerVersion(payload.protocolVersion());
    }
}

