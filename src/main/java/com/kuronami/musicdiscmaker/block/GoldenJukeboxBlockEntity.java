package com.kuronami.musicdiscmaker.block;

import java.util.Optional;

import org.jetbrains.annotations.NotNull;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.register.ModBlockEntities;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.network.PacketDistributor;

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
    /** pause 時に保存した再生位置 (ms)。resume で offset seek に使う。 */
    private long pausedOffsetMs = 0L;

    // ── server 揮発 ──
    private final JukeboxSongPlayer songPlayer = new JukeboxSongPlayer(this::onSongChanged, this.getBlockPos());
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

    /** 無限長ストリーム (ライブ/ラジオ) の custom disc か。リピートを無効化する判定に使う。 */
    public boolean isLiveStream() {
        final CustomTrackData t = currentTrack();
        return t != null && (t.radio() || t.durationMs() <= 0L);
    }

    /** コンパレータ出力 (vanilla 同等: song の comparatorOutput)。 */
    public int getComparatorOutput() {
        return JukeboxSong.fromStack(items.get(SLOT_DISC))
                .map(Holder::value).map(JukeboxSong::comparatorOutput).orElse(0);
    }

    /** vanilla 再生状態 (redstone 信号源 = 再生中 15 の判定に使う)。 */
    public boolean isVanillaPlaying() {
        return songPlayer.isPlaying();
    }

    private boolean isServer() {
        return level != null && !level.isClientSide();
    }

    private Optional<Holder<JukeboxSong>> songFor(ItemStack disc) {
        return JukeboxSong.fromStack(disc);
    }

    // ── 設定変更 (GUI から C2S 経由) ──

    public void setRangeBlocks(int value) {
        this.rangeBlocks = Mth.clamp(value, RANGE_MIN, RANGE_MAX);
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
            broadcast(new StopDiscPayload(getBlockPos()));
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
        // vanilla 再生状態 (particle / comparator / 曲終了) + vanilla disc の実音。
        songFor(disc).ifPresent(song -> songPlayer.play(level, song));
        startMillis = System.currentTimeMillis() - offsetMs;
        // custom disc は LavaPlayer ストリームを per-block 設定つきで broadcast。
        final CustomTrackData track = currentTrack();
        if (track != null) {
            broadcast(new PlayDiscPayload(getBlockPos(), track, offsetMs, rangeBlocks, volumePercent));
        }
    }

    private void stopPlayback() {
        if (!isServer()) {
            return;
        }
        songPlayer.stop(level, getBlockState());
        startMillis = 0L;
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

    /** late-join した player へ、再生中なら現在位置で PlayDiscPayload を再送する。 */
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
        PacketDistributor.sendToPlayer(player,
                new PlayDiscPayload(getBlockPos(), track, offset, rangeBlocks, volumePercent));
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
        // chunk load 後の 1 度きり: disc があり非 pause なら再生を復帰させる。
        if (!initialized) {
            initialized = true;
            if (startMillis == 0L && hasDisc() && !paused) {
                startPlayback(0L);
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

    private void broadcast(CustomPacketPayload payload) {
        if (level instanceof ServerLevel serverLevel) {
            PacketDistributor.sendToPlayersTrackingChunk(serverLevel, ChunkPos.containing(getBlockPos()), payload);
        }
    }

    public void sync() {
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    // ── persistence (26.1.2: ValueInput/ValueOutput) ──

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items.clear();
        ContainerHelper.loadAllItems(input, items);
        this.rangeBlocks = Mth.clamp(input.getIntOr("range", RANGE_DEFAULT), RANGE_MIN, RANGE_MAX);
        this.volumePercent = Mth.clamp(input.getIntOr("volume", VOLUME_DEFAULT), VOLUME_MIN, VOLUME_MAX);
        this.repeat = input.getBooleanOr("repeat", false);
        this.paused = input.getBooleanOr("paused", false);
        this.pausedOffsetMs = input.getLongOr("pausedOffset", 0L);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
        output.putInt("range", rangeBlocks);
        output.putInt("volume", volumePercent);
        output.putBoolean("repeat", repeat);
        output.putBoolean("paused", paused);
        output.putLong("pausedOffset", pausedOffsetMs);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
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
