package com.kuronami.musicdiscmaker.block;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.register.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.RecordItem;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;

/**
 * 強化版ジュークボックス (Golden Jukebox) の BlockEntity。
 *
 * <p>ディスク 1 枚を持ち、再生設定 (可聴範囲・音量・リピート・再生/停止) を NBT に永続化する。
 * 1.20.1 には {@code JukeboxSongPlayer} が無いため、vanilla の「再生中」状態は自前で表現する:
 * custom disc は {@link PlayDiscPayload} を per-block 設定つきで broadcast して各 client が LavaPlayer
 * でストリームし、vanilla レコードは {@code levelEvent(1010/1011)} で鳴らす。再生の権威はこの BE
 * (server)。redstone/comparator は {@link RecordItem#getAnalogOutput()} と再生状態から出す。
 *
 * <p>late-join は {@link #resendTo(ServerPlayer)} を既存の chunk-watch 経路から呼んで現在位置を送る。
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

    /** vanilla の record 再生開始 levelEvent (client でレコード音を鳴らす)。 */
    private static final int EVENT_PLAY_RECORD = 1010;
    /** vanilla の record 停止 levelEvent。 */
    private static final int EVENT_STOP_RECORD = 1011;

    private final NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);

    // ── 永続設定 ──
    private int rangeBlocks = RANGE_DEFAULT;
    private int volumePercent = VOLUME_DEFAULT;
    private boolean repeat = false;
    private boolean paused = false;
    /** pause 時に保存した再生位置 (ms)。resume で offset seek に使う。 */
    private long pausedOffsetMs = 0L;
    /**
     * 再生開始時の {@link Level#getGameTime()}。-1 = 停止/一時停止中。
     * client の進捗バー計算用に同期する (wall-clock はマルチプレイで機体差があるため gameTime を使う)。
     */
    private long playbackStartGameTime = -1L;

    // ── server 揮発 ──
    /** 現在の再生開始 wall-clock (ms)。0 = 未再生。live sync / repeat / late-join の offset 計算に使う。 */
    private long startMillis = 0L;
    /** 初回 server tick で chunk load 後の再生復帰を 1 度だけ行うためのフラグ。 */
    private boolean initialized = false;

    public GoldenJukeboxBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.GOLDEN_JUKEBOX.get(), pos, state);
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
        if (disc.getItem() instanceof CustomMusicDiscItem) {
            final CustomTrackData t = CustomMusicDiscItem.getTrack(disc);
            return !t.isEmpty() ? t : null;
        }
        return null;
    }

    /**
     * vanilla / 他 MOD のディスクの説明文 (例: "C418 - cat")。1.20.1 には {@code JukeboxSong} が無いため、
     * {@link RecordItem#getDisplayName()}（レコードの ".desc" 訳。バニラの "C418 - cat" 相当）を返す。
     * custom disc・RecordItem 以外では {@code null}。挿入可能ディスクは canPlaceItem で RecordItem に
     * 限定されているので、これで全挿入ディスクを網羅する。
     */
    @Nullable
    public Component discSongDescription() {
        final ItemStack disc = getDisc();
        return disc.getItem() instanceof RecordItem record ? record.getDisplayName() : null;
    }

    /** 無限長ストリーム (ライブ/ラジオ) の custom disc か。リピートを無効化する判定に使う。 */
    public boolean isLiveStream() {
        final CustomTrackData t = currentTrack();
        return t != null && (t.radio() || t.durationMs() <= 0L);
    }

    /** コンパレータ出力 (vanilla 同等: RecordItem の analogOutput)。 */
    public int getComparatorOutput() {
        final ItemStack disc = items.get(SLOT_DISC);
        return disc.getItem() instanceof RecordItem record ? record.getAnalogOutput() : 0;
    }

    /** 再生中か (redstone 信号源 = 再生中 15 の判定に使う)。 */
    public boolean isVanillaPlaying() {
        return startMillis != 0L && !paused;
    }

    private boolean isServer() {
        return level != null && !level.isClientSide;
    }

    // ── 設定変更 (GUI から C2S 経由) ──

    public void setRangeBlocks(int value) {
        final int clamped = Mth.clamp(value, RANGE_MIN, RANGE_MAX);
        final boolean changed = clamped != this.rangeBlocks;
        this.rangeBlocks = clamped;
        // 可聴範囲は client の SoundInstance 生成時に減衰半径として焼き込まれるため、再生中の変更は
        // 現在位置での再ストリーム (seek と同じ機構) でしか反映できない。確定時に一度だけ再ブロードキャストする。
        if (changed) {
            rebroadcastForRangeChange();
        }
        sync();
    }

    /**
     * 再生中の custom disc に可聴範囲の変更を即反映する。現在の再生位置で {@link PlayDiscPayload} を
     * 再送し、client 側で新しい範囲の SoundInstance を生成させる (seek と同じ経路)。
     * 停止/一時停止中・vanilla disc・自然終了済みでは何もしない。ラジオは瞬間的に再接続する。
     */
    private void rebroadcastForRangeChange() {
        if (!isServer() || paused || startMillis <= 0L) {
            return;
        }
        final CustomTrackData track = currentTrack();
        if (track == null) {
            return; // vanilla レコードは LavaPlayer ストリームを持たない
        }
        long offset = Math.max(0L, System.currentTimeMillis() - startMillis);
        final long dur = track.durationMs();
        if (dur > 0L) {
            if (offset >= dur && !repeat) {
                return; // 非リピートで自然終了済み
            }
            offset = repeat ? offset % dur : Math.min(offset, dur);
        }
        startPlayback(offset);
    }

    public void setVolumePercent(int value) {
        this.volumePercent = Mth.clamp(value, VOLUME_MIN, VOLUME_MAX);
        sync();
    }

    public void setRepeat(boolean value) {
        this.repeat = value;
        sync();
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
            stopPlayback();
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
        // 1.20.1 の Mth.clamp に long オーバーロードが無いため Math で clamp する。
        final long clamped = Math.max(0L, Math.min(offsetMs, track.durationMs()));
        if (paused) {
            pausedOffsetMs = clamped;
            sync();
        } else {
            startPlayback(clamped);
            sync();
        }
    }

    // ── 再生制御 ──

    private void startPlayback(long offsetMs) {
        if (!isServer() || !hasDisc()) {
            return;
        }
        final ItemStack disc = items.get(SLOT_DISC);
        startMillis = System.currentTimeMillis() - offsetMs;
        playbackStartGameTime = level.getGameTime() - offsetMs / 50L;
        final CustomTrackData track = currentTrack();
        if (track != null) {
            // custom disc は LavaPlayer ストリームを per-block 設定つきで broadcast。
            broadcast(new PlayDiscPayload(getBlockPos(), track, offsetMs, rangeBlocks, volumePercent));
            // 再生開始を sculk 等へ即通知する (以後 tick で周期的に再発火)。
            level.gameEvent(GameEvent.JUKEBOX_PLAY, getBlockPos(), GameEvent.Context.of(getBlockState()));
        } else {
            // vanilla レコードは vanilla の levelEvent でその場再生 (particle / now-playing 込み)。
            level.levelEvent(EVENT_PLAY_RECORD, getBlockPos(), Item.getId(disc.getItem()));
        }
        // playbackStartGameTime を client へ反映する (progress バーの起点)。
        sync();
    }

    private void stopPlayback() {
        if (!isServer()) {
            return;
        }
        final boolean wasPlaying = startMillis != 0L;
        startMillis = 0L;
        playbackStartGameTime = -1L;
        // custom (LavaPlayer) / vanilla (levelEvent) の両経路を冪等に止める。
        level.levelEvent(EVENT_STOP_RECORD, getBlockPos(), 0);
        broadcast(new StopDiscPayload(getBlockPos()));
        // 再生していた時だけ JUKEBOX_STOP を出す (sculk 等の検知解除)。
        if (wasPlaying) {
            level.gameEvent(GameEvent.JUKEBOX_STOP_PLAY, getBlockPos(), GameEvent.Context.of(getBlockState()));
        }
        sync();
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
        if (level != null) {
            level.updateNeighborsAt(getBlockPos(), getBlockState().getBlock());
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

    /** late-join した player へ、再生中なら現在位置で PlayDiscPayload を再送する (custom disc のみ)。 */
    public void resendTo(ServerPlayer player) {
        if (!isServer() || paused) {
            return;
        }
        final CustomTrackData track = currentTrack();
        if (track == null || startMillis == 0L) {
            return;
        }
        final long elapsed = Math.max(0L, System.currentTimeMillis() - startMillis);
        final long dur = track.durationMs();
        if (dur > 0L && elapsed >= dur && !repeat) {
            return; // repeat off の自然終了済み
        }
        final long offset = (dur > 0L && repeat) ? elapsed % dur : elapsed;
        Services.NETWORK.sendToPlayer(player,
                new PlayDiscPayload(getBlockPos(), track, offset, rangeBlocks, volumePercent));
    }

    // ── tick (server) ──

    public static void serverTick(Level level, BlockPos pos, BlockState state, GoldenJukeboxBlockEntity be) {
        be.tickServer();
    }

    private void tickServer() {
        if (!isServer()) {
            return;
        }
        // chunk load 後の 1 度きり: disc があり非 pause なら再生を復帰させる。
        if (!initialized) {
            initialized = true;
            if (startMillis == 0L && hasDisc() && !paused) {
                startPlayback(0L);
            }
        }
        // repeat: 有限曲を曲尺でループ (無限長ストリーム=radio はループ対象外)。
        if (repeat && !paused && startMillis > 0L) {
            final CustomTrackData track = currentTrack();
            if (track != null && track.durationMs() > 0L
                    && System.currentTimeMillis() - startMillis >= track.durationMs()) {
                startPlayback(0L);
            }
        }
        // 非リピートの有限 custom disc が総尺に達したら再生状態を解除する (disc はスロットに残す)。
        // 1.20.1 は JukeboxSongPlayer が無く曲終了を自前で検知する必要がある。これが無いと
        // startMillis が落ちず isVanillaPlaying() が恒真になり、コンパレータ出力が 15 に張り付く。
        if (!repeat && !paused && startMillis > 0L) {
            final CustomTrackData track = currentTrack();
            if (track != null && track.durationMs() > 0L
                    && System.currentTimeMillis() - startMillis >= track.durationMs()) {
                stopPlayback();
            }
        }
        // 再生中 (custom disc) は音符パーティクルと JUKEBOX_PLAY gameEvent を周期的に出す。
        // vanilla jukebox は JukeboxSongPlayer がこれを担うが 1.20.1 の強化版には無いため代替する。
        // sculk センサー/アレイが再生を検知でき、ブロック上に音符が舞う (バニラ相当・20t 周期)。
        if (startMillis > 0L && !paused && currentTrack() != null
                && level instanceof ServerLevel serverLevel && level.getGameTime() % 20L == 0L) {
            level.gameEvent(GameEvent.JUKEBOX_PLAY, getBlockPos(), GameEvent.Context.of(getBlockState()));
            spawnNoteParticle(serverLevel);
        }
    }

    /** ブロック上に音符パーティクルを 1 つ出す (バニラ note block と同じ色エンコード)。 */
    private void spawnNoteParticle(ServerLevel serverLevel) {
        final BlockPos pos = getBlockPos();
        final double note = serverLevel.getRandom().nextInt(24) / 24.0;
        serverLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.NOTE,
                pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, 0, note, 0.0, 0.0, 1.0);
    }

    private void broadcast(com.kuronami.musicdiscmaker.network.ModPayload payload) {
        if (level instanceof ServerLevel serverLevel) {
            Services.NETWORK.sendToPlayersTrackingChunk(serverLevel, new ChunkPos(getBlockPos()), payload);
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
    public void load(CompoundTag tag) {
        super.load(tag);
        items.clear();
        ContainerHelper.loadAllItems(tag.getCompound("inventory"), items);
        this.rangeBlocks = tag.contains("range") ? Mth.clamp(tag.getInt("range"), RANGE_MIN, RANGE_MAX) : RANGE_DEFAULT;
        this.volumePercent = tag.contains("volume")
                ? Mth.clamp(tag.getInt("volume"), VOLUME_MIN, VOLUME_MAX) : VOLUME_DEFAULT;
        this.repeat = tag.getBoolean("repeat");
        this.paused = tag.getBoolean("paused");
        this.pausedOffsetMs = tag.getLong("pausedOffset");
        this.playbackStartGameTime = tag.contains("playbackStartGameTime")
                ? tag.getLong("playbackStartGameTime") : -1L;
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        final CompoundTag invTag = new CompoundTag();
        ContainerHelper.saveAllItems(invTag, items);
        tag.put("inventory", invTag);
        tag.putInt("range", rangeBlocks);
        tag.putInt("volume", volumePercent);
        tag.putBoolean("repeat", repeat);
        tag.putBoolean("paused", paused);
        tag.putLong("pausedOffset", pausedOffsetMs);
        tag.putLong("playbackStartGameTime", playbackStartGameTime);
    }

    @Override
    public CompoundTag getUpdateTag() {
        final CompoundTag tag = new CompoundTag();
        saveAdditional(tag);
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
        // 再生可能ディスク (vanilla + custom = RecordItem) のみ、空きスロットへ。
        return slot == SLOT_DISC && stack.getItem() instanceof RecordItem && items.get(SLOT_DISC).isEmpty();
    }

    @Override
    public void clearContent() {
        items.clear();
    }
}
