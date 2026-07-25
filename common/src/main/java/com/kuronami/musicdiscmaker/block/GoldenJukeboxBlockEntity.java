package com.kuronami.musicdiscmaker.block;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.event.SpeakerNetwork;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.SpeakerSetPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.register.ModBlockEntities;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.JukeboxSongPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 強化版ジュークボックスの BlockEntity。
 *
 * <p>ディスク 1 枚を持ち、再生設定 (可聴範囲・音量・リピート・再生/停止) を NBT に永続化する。
 * vanilla {@link JukeboxSongPlayer} を埋め込み、vanilla ディスクの音・ノートパーティクル・
 * コンパレータ出力・gameEvent・曲終了検知を得る。custom disc は既存 maker と同じく silent song
 * (JUKEBOX_PLAYABLE) 経由で vanilla 再生状態に乗り、実音声は LavaPlayer が per-block の
 * 可聴範囲・音量つきでストリームする ({@link PlayDiscPayload})。
 *
 * <p>再生の権威はこの BE (server)。ActiveDiscRegistry は使わず、late-join には
 * {@link #resendTo(ServerPlayer)} で現在位置を送る。
 */
public class GoldenJukeboxBlockEntity extends BlockEntity implements Container {

    public static final int SLOT_DISC = 0;
    private static final int SIZE = 1;

    public static final int RANGE_MIN = 16;
    public static final int RANGE_MAX = 256;
    public static final int RANGE_DEFAULT = 64;
    public static final int VOLUME_MIN = 0;
    public static final int VOLUME_MAX = 200;
    public static final int VOLUME_DEFAULT = 100;

    private final NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);

    // ── 永続設定 ──
    private int rangeBlocks = RANGE_DEFAULT;
    private int volumePercent = VOLUME_DEFAULT;
    private boolean repeat = false;
    private boolean paused = false;
    /**
     * 音の指向性。true = 従来どおりの positional audio (左右定位・距離減衰つき)。
     * false = 可聴範囲の中にいる限り位置に関係なくフラットに聴こえる (BGM モード)。範囲外では
     * 黙る (範囲ゲートは維持する)。client へは {@link SpeakerSetPayload} が運ぶ (= 聴取モデル)。
     */
    private boolean directional;
    /** NBT に {@code directional} が無い (= 新規設置 / 旧セーブ) ときの初期値。ブロックごとに違う。 */
    private final boolean defaultDirectional;
    /** pause 時に保存した再生位置 (ms)。resume で offset seek に使う。 */
    private long pausedOffsetMs = 0L;
    /**
     * 再生開始時の {@link net.minecraft.world.level.Level#getGameTime()}。-1 = 停止/一時停止中。
     * client の進捗バー計算用に同期する (wall-clock はマルチプレイで機体差があるため gameTime を使う)。
     */
    private long playbackStartGameTime = -1L;

    // ── server 揮発 ──
    private final JukeboxSongPlayer songPlayer = new JukeboxSongPlayer(this::onSongChanged, this.getBlockPos());
    /** 現在の再生開始 wall-clock (ms)。0 = 未再生。live sync / repeat / late-join の offset 計算に使う。 */
    private long startMillis = 0L;
    /** 初回 server tick で chunk load 後の再生復帰を 1 度だけ行うためのフラグ。 */
    private boolean initialized = false;

    public GoldenJukeboxBlockEntity(BlockPos pos, BlockState state) {
        this(ModBlockEntities.GOLDEN_JUKEBOX.get(), pos, state, true);
    }

    /**
     * 派生ブロック (ブームボックス) 用。BE 型と指向性の既定値だけを差し替えて再生機構をそのまま使う。
     */
    protected GoldenJukeboxBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state,
            boolean defaultDirectional) {
        super(type, pos, state);
        this.defaultDirectional = defaultDirectional;
        this.directional = defaultDirectional;
    }

    // ── GUI / block 用アクセサ ──

    public int getRangeBlocks() {
        return rangeBlocks;
    }

    public int getVolumePercent() {
        return volumePercent;
    }

    public boolean isRepeat() {
        return repeat;
    }

    public boolean isPaused() {
        return paused;
    }

    /** true = positional audio (既定)。false = 範囲内フラット聴取 (BGM モード)。 */
    public boolean isDirectional() {
        return directional;
    }

    /**
     * 現在の再生経過 (ms)。progress バー表示用。一時停止中は保存 offset、停止中は 0。
     * client からも呼べる (playbackStartGameTime を同期しているため)。
     */
    public long currentElapsedMs() {
        if (paused) {
            return pausedOffsetMs;
        }
        if (playbackStartGameTime < 0L || level == null) {
            return 0L;
        }
        long elapsed = Math.max(0L, (level.getGameTime() - playbackStartGameTime) * 50L);
        // 有限尺は総尺でクランプ (非リピートの自然終了後に経過が尺を超えて増え続けるのを防ぐ)。
        // リピート時はループごとに anchor がリセットされるため境界クランプは無害。
        final long dur = trackDurationMs();
        if (dur > 0L) {
            elapsed = Math.min(elapsed, dur);
        }
        return elapsed;
    }

    /** 現在のディスクの総尺 (ms)。custom disc のみ。0 = 不明/ラジオ/vanilla disc。 */
    public long trackDurationMs() {
        final CustomTrackData t = currentTrack();
        return t != null ? t.durationMs() : 0L;
    }

    /** progress バーで頭出し操作を許可できるか (有限尺の custom disc のみ)。 */
    public boolean isSeekable() {
        return hasDisc() && !isLiveStream() && trackDurationMs() > 0L;
    }

    public boolean hasDisc() {
        return !items.get(SLOT_DISC).isEmpty();
    }

    public ItemStack getDisc() {
        return items.get(SLOT_DISC);
    }

    /** 現在のディスクが custom disc なら track を、そうでなければ null。 */
    public CustomTrackData currentTrack() {
        final ItemStack disc = items.get(SLOT_DISC);
        if (disc.is(ModItems.CUSTOM_MUSIC_DISC.get()) && disc.has(ModDataComponents.CUSTOM_TRACK.get())) {
            final CustomTrackData t = disc.get(ModDataComponents.CUSTOM_TRACK.get());
            return t != null && !t.isEmpty() ? t : null;
        }
        return null;
    }

    /**
     * vanilla / 他 MOD のディスクの {@link JukeboxSong#description()}（例: "C418 - cat"）。
     * custom disc・description を持たないディスク・{@code level==null} では {@code null}。
     * {@code jukebox_song} は同期される動的レジストリなので client 側 BE からも解決できる。
     */
    @Nullable
    public Component discSongDescription() {
        return songFor(getDisc()).map(Holder::value).map(JukeboxSong::description).orElse(null);
    }

    /** 無限長ストリーム (ライブ/ラジオ) の custom disc か。リピートを無効化する判定に使う。 */
    public boolean isLiveStream() {
        final CustomTrackData t = currentTrack();
        return t != null && (t.radio() || t.durationMs() <= 0L);
    }

    /** コンパレータ出力 (vanilla 同等: song の comparatorOutput)。 */
    public int getComparatorOutput() {
        if (level == null) {
            return 0;
        }
        return JukeboxSong.fromStack(level.registryAccess(), items.get(SLOT_DISC))
                .map(Holder::value).map(JukeboxSong::comparatorOutput).orElse(0);
    }

    /** vanilla 再生状態 (redstone 信号源 = 再生中 15 の判定に使う)。 */
    public boolean isVanillaPlaying() {
        return songPlayer.isPlaying();
    }

    private boolean isServer() {
        return level != null && !level.isClientSide;
    }

    private Optional<Holder<JukeboxSong>> songFor(ItemStack disc) {
        return level == null ? Optional.empty() : JukeboxSong.fromStack(level.registryAccess(), disc);
    }

    // ── 設定変更 (GUI から C2S 経由) ──

    public void setRangeBlocks(int value) {
        this.rangeBlocks = Mth.clamp(value, RANGE_MIN, RANGE_MAX);
        // 可聴範囲は client 側 BE から tick で live 再読され、変化時にチャンネルの減衰半径 (OpenAL の
        // max distance) をライブ更新する (音量と同じ即反映)。再ストリーム不要 = sync のみ。
        sync();
    }

    public void setVolumePercent(int value) {
        this.volumePercent = Mth.clamp(value, VOLUME_MIN, VOLUME_MAX);
        // 音量は client 側 BE から tick で live 再読されるので再ブロードキャスト不要。
        sync();
    }

    public void setRepeat(boolean value) {
        this.repeat = value;
        sync();
    }

    /**
     * 指向性の切り替え。client は再生を止めずにその場でモードを変える (位置の書き換えだけで済み、
     * {@code relative} を触らないので再ストリーム不要)。
     *
     * <p>{@code sync()} だけでは足りない: スピーカー圏にいる listener は音源の chunk を持たないので
     * client 側 BE を読めない。{@link SpeakerSetPayload} (= 聴取モデルの配送) に載せて、音源チャンク
     * ∪ 全スピーカーチャンクへ届ける。
     */
    public void setDirectional(boolean value) {
        if (this.directional == value) {
            return;
        }
        this.directional = value;
        sync();
        broadcastSpeakerSet();
    }

    /** 再生/停止トグル。停止で位置保存、再開で位置から再生。 */
    public void setPaused(boolean value) {
        if (!isServer() || this.paused == value) {
            return;
        }
        this.paused = value;
        if (value) {
            final long elapsed = startMillis > 0L ? System.currentTimeMillis() - startMillis : 0L;
            final CustomTrackData track = currentTrack();
            final long dur = track != null ? track.durationMs() : 0L;
            this.pausedOffsetMs = dur > 0L ? Math.min(elapsed, dur) : elapsed;
            songPlayer.stop(level, getBlockState());
            startMillis = 0L;
            playbackStartGameTime = -1L;
            broadcast(new StopDiscPayload(getBlockPos()));
        } else if (hasDisc()) {
            startPlayback(pausedOffsetMs);
            pausedOffsetMs = 0L;
        }
        sync();
    }

    /**
     * GUI シークバーからの頭出し。有限尺 (非ラジオ) の custom disc の時だけ効く。
     * 再生中は即座にその位置へ、一時停止中は resume 位置だけを更新する。
     */
    public void seekTo(long offsetMs) {
        if (!isServer() || !hasDisc() || isLiveStream()) {
            return;
        }
        final CustomTrackData track = currentTrack();
        if (track == null || track.durationMs() <= 0L) {
            return;
        }
        final long clamped = Mth.clamp(offsetMs, 0L, track.durationMs());
        if (paused) {
            pausedOffsetMs = clamped;
            sync();
        } else {
            startPlayback(clamped);
            sync();
        }
    }

    // ── 再生制御 ──

    /**
     * chunk ロード / contraption 解体・Sable 飛空艇解体で BE が復元された後の再生復帰。transient な
     * {@code startMillis} は 0 に戻るが、永続化された {@code playbackStartGameTime} から現在位置を復元して
     * 途中から再生する (頭出しにしない)。
     *
     * <p>この復元が非対称バグの核。捕獲式 (Create) の組立は MovementBehaviour が同じ
     * {@code playbackStartGameTime} 基準で offset を算出して追従するため位置が継続していたが、解体で
     * 復元された BE はここが {@code startPlayback(0L)} だったため頭出しになっていた。共通の serverTick に
     * あるので Create 捕獲式・Sable 変換式・VS2 (mod-048)・通常 chunk 再ロードの全経路を一括で直す。
     */
    private void resumePlaybackAfterLoad() {
        // 保存された再生起点なし (初回配置 or 停止済みで復元) → 頭から。
        if (playbackStartGameTime < 0L) {
            startPlayback(0L);
            return;
        }
        // ライブ / ラジオ (無限長) は位置の概念が無い → ライブ先頭へ再接続する (offset 無意味)。
        if (isLiveStream()) {
            startPlayback(0L);
            return;
        }
        final long rawElapsed = Math.max(0L, (level.getGameTime() - playbackStartGameTime) * 50L);
        final long dur = trackDurationMs();
        if (dur > 0L && rawElapsed >= dur) {
            if (repeat) {
                startPlayback(rawElapsed % dur); // リピートはループ内の現在位置へ。
            } else {
                // 非リピートで復元前に自然終了済み → replay しない (頭出し再生を防ぐ)。
                stopPlayback();
            }
            return;
        }
        MusicDiscMaker.LOGGER.debug("金ジューク再生を復元: resume={}ms (playbackStartGameTime={} gameTime={})",
                rawElapsed, playbackStartGameTime, level.getGameTime());
        startPlayback(rawElapsed);
    }

    private void startPlayback(long offsetMs) {
        if (!isServer() || !hasDisc()) {
            return;
        }
        final ItemStack disc = items.get(SLOT_DISC);
        // vanilla 再生状態 (particle / comparator / 曲終了) + vanilla disc の実音。
        songFor(disc).ifPresent(song -> songPlayer.play(level, song));
        startMillis = System.currentTimeMillis() - offsetMs;
        playbackStartGameTime = level.getGameTime() - offsetMs / 50L;
        // custom disc は LavaPlayer ストリームを per-block 設定つきで broadcast。
        final CustomTrackData track = currentTrack();
        if (track != null) {
            broadcast(new PlayDiscPayload(getBlockPos(), track, offsetMs, rangeBlocks, volumePercent));
            // 再生 packet の直後に必ず集合を送る。停止 packet で client が集合を忘れるので、
            // 再生開始のたびに張り直すことで packet 落ち・順序に依存しない状態にする。
            broadcastSpeakerSet();
        }
        // playbackStartGameTime を client へ反映する (progress バーの起点)。
        sync();
    }

    private void stopPlayback() {
        if (!isServer()) {
            return;
        }
        songPlayer.stop(level, getBlockState());
        startMillis = 0L;
        playbackStartGameTime = -1L;
        broadcast(new StopDiscPayload(getBlockPos()));
    }

    /** ディスクスロットが変わった時 (挿入/取り出し・ホッパー・コマンド・GUI)。再生を起動/停止する。 */
    private void onDiscChanged() {
        final boolean hasDisc = hasDisc();
        updateHasRecordState(hasDisc);
        if (isServer()) {
            pausedOffsetMs = 0L;
            if (!hasDisc) {
                // ディスク取り出しで停止状態を解除する (次に入れたディスクは自動再生される)。
                paused = false;
            }
        }
        setChanged();
        sync();
        if (!isServer()) {
            return;
        }
        if (hasDisc && !paused) {
            startPlayback(0L);
        } else {
            stopPlayback();
        }
    }

    private void updateHasRecordState(boolean hasRecord) {
        if (level != null && level.getBlockState(getBlockPos()) == getBlockState()
                && getBlockState().hasProperty(GoldenJukeboxBlock.HAS_RECORD)
                && getBlockState().getValue(GoldenJukeboxBlock.HAS_RECORD) != hasRecord) {
            level.setBlock(getBlockPos(), getBlockState().setValue(GoldenJukeboxBlock.HAS_RECORD, hasRecord), 2);
        }
    }

    /** ブロック破壊時 (setItem を通らない) に鳴りっぱなしを防ぐ。 */
    public void onBlockRemoved() {
        stopPlayback();
    }

    /**
     * 現在の再生位置を載せた {@link PlayDiscPayload}。停止中・一時停止中・repeat off の自然終了済みは
     * {@code null}。late-join 再送と、後から生えたスピーカーへの再送で共有する。
     */
    @Nullable
    private PlayDiscPayload currentPlayPayload() {
        if (!isServer() || paused) {
            return null;
        }
        final CustomTrackData track = currentTrack();
        if (track == null || startMillis == 0L) {
            return null;
        }
        final long elapsed = Math.max(0L, System.currentTimeMillis() - startMillis);
        final long dur = track.durationMs();
        if (dur > 0L && elapsed >= dur && !repeat) {
            return null; // repeat off の自然終了済み
        }
        final long offset = (dur > 0L && repeat) ? elapsed % dur : elapsed;
        return new PlayDiscPayload(getBlockPos(), track, offset, rangeBlocks, volumePercent);
    }

    /** late-join した player へ、再生中なら現在位置で PlayDiscPayload を再送する。 */
    public void resendTo(ServerPlayer player) {
        final PlayDiscPayload payload = currentPlayPayload();
        if (payload == null) {
            return;
        }
        Services.NETWORK.sendToPlayer(player, payload);
        sendSpeakerSetTo(player);
    }

    /**
     * 後からぶら下がったスピーカーの chunk にいる player へ、現在の再生を届ける。
     *
     * <p>スピーカーを新設した player はその chunk を既に追跡済みなので chunk-watch 起点の late-join では
     * 救えず、かつ {@code SpeakerSetPayload} は集合を運ぶだけで再生を始めない。これが無いと
     * 「鳴っている金ジュークから離れた場所にスピーカーを置く」＝本機能の主フローで、次の
     * {@code startPlayback}（シーク・リピート折返し・ディスク差し替え）まで無音になる。
     */
    public void resendPlaybackToChunk(ChunkPos chunk) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        final PlayDiscPayload payload = currentPlayPayload();
        if (payload != null) {
            // 既に鳴っている client には同一 URL の再送になるが、client 側 dedup が握りつぶす。
            Services.NETWORK.sendToPlayersTrackingChunk(serverLevel, chunk, payload);
        }
    }

    // ── tick (server) ──

    public static void serverTick(net.minecraft.world.level.Level level, BlockPos pos, BlockState state,
            GoldenJukeboxBlockEntity be) {
        be.tickServer();
    }

    private void tickServer() {
        if (!isServer()) {
            return;
        }
        // chunk load / contraption 解体で BE が復元された後の 1 度きり: disc があり非 pause なら
        // 保存済み再生起点から現在位置を復元して再生を復帰させる (頭出しにしない)。
        if (!initialized) {
            initialized = true;
            if (startMillis == 0L && hasDisc() && !paused) {
                resumePlaybackAfterLoad();
            }
        }
        // vanilla songPlayer: particle / gameEvent / 曲終了で停止。
        songPlayer.tick(level, getBlockState());
        // 案B (ラジオ): 無限長ストリームは silent song の最大尺 (2h) で vanilla 再生状態が切れるが、
        // client 側の音声は独立に流れ続ける。再生状態 (コンパレータ/particle) だけを再起動して
        // 無限に維持する。client への再 broadcast はしないので音声は途切れない。
        if (startMillis > 0L && !paused && !songPlayer.isPlaying()) {
            final CustomTrackData track = currentTrack();
            if (track != null && track.radio()) {
                songFor(items.get(SLOT_DISC)).ifPresent(song -> songPlayer.play(level, song));
            }
        }
        // repeat: 有限曲を曲尺でループ。
        if (repeat && !paused && startMillis > 0L) {
            final CustomTrackData track = currentTrack();
            if (track != null && track.durationMs() > 0L
                    && System.currentTimeMillis() - startMillis >= track.durationMs()) {
                startPlayback(0L);
            }
        }
    }

    private void onSongChanged() {
        if (level != null) {
            level.updateNeighborsAt(getBlockPos(), getBlockState().getBlock());
        }
        setChanged();
    }

    /**
     * 再生制御 packet の配送。宛先は「音源チャンク ∪ ぶら下がる全スピーカーのチャンク」。
     *
     * <p>{@code sendToPlayersTrackingChunk} は音源チャンクを追跡中の player にしか届かないため、
     * 200 ブロック離れたスピーカーの傍にいる player には初回 packet が構造的に届かない。スピーカー側の
     * 逆引き ({@link SpeakerNetwork}) を使ってチャンクを足すことで解消する。同じ player に複数チャンク
     * から届いても client 側 dedup が吸収する。
     */
    private void broadcast(CustomPacketPayload payload) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        final Set<ChunkPos> targets = new LinkedHashSet<>();
        targets.add(new ChunkPos(getBlockPos()));
        for (final BlockPos speaker : SpeakerNetwork.speakersOf(serverLevel, getBlockPos())) {
            targets.add(new ChunkPos(speaker));
        }
        for (final ChunkPos chunk : targets) {
            Services.NETWORK.sendToPlayersTrackingChunk(serverLevel, chunk, payload);
        }
    }

    /**
     * 現在の有効スピーカー集合を配送する。{@link PlayDiscPayload} には相乗りさせない
     * (client の再生 dedup が同一 URL の再送を握りつぶすため、集合の変化が伝わらない)。
     */
    public void broadcastSpeakerSet() {
        if (level instanceof ServerLevel serverLevel) {
            broadcast(new SpeakerSetPayload(getBlockPos(), directional,
                    SpeakerNetwork.activeEntries(serverLevel, getBlockPos())));
        }
    }

    /** late-join した player へ現在の有効スピーカー集合を送る。 */
    public void sendSpeakerSetTo(ServerPlayer player) {
        if (level instanceof ServerLevel serverLevel) {
            Services.NETWORK.sendToPlayer(player, new SpeakerSetPayload(getBlockPos(), directional,
                    SpeakerNetwork.activeEntries(serverLevel, getBlockPos())));
        }
    }

    public void sync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    // ── persistence ──

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items.clear();
        ContainerHelper.loadAllItems(tag.getCompound("inventory"), items, registries);
        this.rangeBlocks = tag.contains("range") ? Mth.clamp(tag.getInt("range"), RANGE_MIN, RANGE_MAX) : RANGE_DEFAULT;
        this.volumePercent = tag.contains("volume") ? Mth.clamp(tag.getInt("volume"), VOLUME_MIN, VOLUME_MAX) : VOLUME_DEFAULT;
        this.repeat = tag.getBoolean("repeat");
        this.paused = tag.getBoolean("paused");
        this.directional = tag.contains("directional") ? tag.getBoolean("directional") : defaultDirectional;
        this.pausedOffsetMs = tag.getLong("pausedOffset");
        this.playbackStartGameTime = tag.contains("playbackStartGameTime")
                ? tag.getLong("playbackStartGameTime") : -1L;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        final CompoundTag invTag = new CompoundTag();
        ContainerHelper.saveAllItems(invTag, items, registries);
        tag.put("inventory", invTag);
        tag.putInt("range", rangeBlocks);
        tag.putInt("volume", volumePercent);
        tag.putBoolean("repeat", repeat);
        tag.putBoolean("paused", paused);
        tag.putBoolean("directional", directional);
        tag.putLong("pausedOffset", pausedOffsetMs);
        tag.putLong("playbackStartGameTime", playbackStartGameTime);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        final CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ── Container (1 スロット・hopper / comparator の土台) ──

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    public boolean isEmpty() {
        return items.get(SLOT_DISC).isEmpty();
    }

    @Override
    public @NotNull ItemStack getItem(int slot) {
        return items.get(slot);
    }

    @Override
    public @NotNull ItemStack removeItem(int slot, int amount) {
        final ItemStack removed = ContainerHelper.removeItem(items, slot, amount);
        if (!removed.isEmpty()) {
            onDiscChanged();
        }
        return removed;
    }

    @Override
    public @NotNull ItemStack removeItemNoUpdate(int slot) {
        final ItemStack removed = ContainerHelper.takeItem(items, slot);
        if (!removed.isEmpty()) {
            onDiscChanged();
        }
        return removed;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        if (!stack.isEmpty() && stack.getCount() > getMaxStackSize()) {
            stack.setCount(getMaxStackSize());
        }
        onDiscChanged();
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        // 再生可能ディスク (vanilla + custom、いずれも JUKEBOX_PLAYABLE を持つ) のみ、空きスロットへ。
        return slot == SLOT_DISC && stack.has(DataComponents.JUKEBOX_PLAYABLE) && items.get(SLOT_DISC).isEmpty();
    }

    @Override
    public void clearContent() {
        items.clear();
    }
}
