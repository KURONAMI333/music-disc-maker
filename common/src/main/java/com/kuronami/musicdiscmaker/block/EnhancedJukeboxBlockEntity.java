package com.kuronami.musicdiscmaker.block;

import org.jetbrains.annotations.NotNull;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.register.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
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

/**
 * 強化版ジュークボックス (Gilded Jukebox) の BlockEntity。
 *
 * <p>ディスク 1 枚を持ち、再生設定 (可聴範囲・音量・リピート・再生/停止) を NBT に永続化する。
 * 1.20.1 には {@code JukeboxSongPlayer} が無いため、vanilla の「再生中」状態は自前で表現する:
 * custom disc は {@link PlayDiscPayload} を per-block 設定つきで broadcast して各 client が LavaPlayer
 * でストリームし、vanilla レコードは {@code levelEvent(1010/1011)} で鳴らす。再生の権威はこの BE
 * (server)。redstone/comparator は {@link RecordItem#getAnalogOutput()} と再生状態から出す。
 *
 * <p>late-join は {@link #resendTo(ServerPlayer)} を既存の chunk-watch 経路から呼んで現在位置を送る。
 */
public class EnhancedJukeboxBlockEntity extends BlockEntity implements Container {

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

    // ── server 揮発 ──
    /** 現在の再生開始 wall-clock (ms)。0 = 未再生。live sync / repeat / late-join の offset 計算に使う。 */
    private long startMillis = 0L;
    /** 初回 server tick で chunk load 後の再生復帰を 1 度だけ行うためのフラグ。 */
    private boolean initialized = false;

    public EnhancedJukeboxBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ENHANCED_JUKEBOX.get(), pos, state);
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
        this.rangeBlocks = Mth.clamp(value, RANGE_MIN, RANGE_MAX);
        // 再生中の変更は次回再生時に反映 (実効 range は client で PlayDiscPayload から適用済み)。
        sync();
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

    // ── 再生制御 ──

    private void startPlayback(long offsetMs) {
        if (!isServer() || !hasDisc()) {
            return;
        }
        final ItemStack disc = items.get(SLOT_DISC);
        startMillis = System.currentTimeMillis() - offsetMs;
        final CustomTrackData track = currentTrack();
        if (track != null) {
            // custom disc は LavaPlayer ストリームを per-block 設定つきで broadcast。
            broadcast(new PlayDiscPayload(getBlockPos(), track, offsetMs, rangeBlocks, volumePercent));
        } else {
            // vanilla レコードは vanilla の levelEvent でその場再生 (particle / now-playing 込み)。
            level.levelEvent(EVENT_PLAY_RECORD, getBlockPos(), Item.getId(disc.getItem()));
        }
    }

    private void stopPlayback() {
        if (!isServer()) {
            return;
        }
        startMillis = 0L;
        // custom (LavaPlayer) / vanilla (levelEvent) の両経路を冪等に止める。
        level.levelEvent(EVENT_STOP_RECORD, getBlockPos(), 0);
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
        if (level != null) {
            level.updateNeighborsAt(getBlockPos(), getBlockState().getBlock());
        }
    }

    private void updateHasRecordState(boolean hasRecord) {
        if (level != null && level.getBlockState(getBlockPos()) == getBlockState()
                && getBlockState().hasProperty(EnhancedJukeboxBlock.HAS_RECORD)
                && getBlockState().getValue(EnhancedJukeboxBlock.HAS_RECORD) != hasRecord) {
            level.setBlock(getBlockPos(), getBlockState().setValue(EnhancedJukeboxBlock.HAS_RECORD, hasRecord), 2);
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

    public static void serverTick(Level level, BlockPos pos, BlockState state, EnhancedJukeboxBlockEntity be) {
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
