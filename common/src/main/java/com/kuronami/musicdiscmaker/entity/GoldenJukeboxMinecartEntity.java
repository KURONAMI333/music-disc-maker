package com.kuronami.musicdiscmaker.entity;

import com.kuronami.musicdiscmaker.audio.local.LocalAudioServer;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxMount;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.VanillaTrackData;
import com.kuronami.musicdiscmaker.menu.GoldenJukeboxMenu;
import com.kuronami.musicdiscmaker.network.GoldenCartPlayPayload;
import com.kuronami.musicdiscmaker.network.GoldenCartStopPayload;
import com.kuronami.musicdiscmaker.network.GoldenCartSyncPayload;
import com.kuronami.musicdiscmaker.network.PlaybackSourceStamp;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModEntities;
import com.kuronami.musicdiscmaker.register.ModItems;
import org.jetbrains.annotations.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
//? if >=1.21.11 {
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
//?} else {
/*import net.minecraft.world.entity.vehicle.AbstractMinecart;
*///?}
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
//? if >=26.1 {
import net.minecraft.world.phys.Vec3;
//?}

/**
 * 金のジュークボックス付きトロッコ。金のジュークボックスが動くだけのもの。
 *
 * <p>中に {@link GoldenJukeboxBlockEntity} を 1 つ載せ、盤の出し入れ・GUI・曲の選び方・送り方・
 * 止め方・保存はすべてそれに任せる (設置した金のジュークボックスと同じ処理・同じ画面)。
 * トロッコが受け持つのは {@link GoldenJukeboxMount} の部分だけで、音をトロッコに付いて鳴らすこと
 * ({@link GoldenCartPlayPayload} を 1 秒ごとに送り直す) と、開いている画面へ状態を届けることだけ。
 *
 * <p>壊すとバニラのチェスト付きトロッコと同じく、トロッコのアイテムと中の盤を落とす。
 */
public class GoldenJukeboxMinecartEntity extends GoldenJukeboxCartBase implements WorldlyContainer,
        GoldenJukeboxMount {

    /** 再生中にトロッコへ付いた音を送り直す間隔 (tick)。範囲へ入り直した player にもこれで届く。 */
    public static final int KEEPALIVE_INTERVAL_TICKS = 20;
    /** 載せた金のジュークボックスの保存先。 */
    private static final String KEY_JUKEBOX = "GoldenJukebox";

    @Nullable
    private GoldenJukeboxBlockEntity jukebox;

    public GoldenJukeboxMinecartEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.mountJukebox(level);
    }

    public GoldenJukeboxMinecartEntity(Level level, double x, double y, double z) {
        super(ModEntities.GOLDEN_JUKEBOX_MINECART.get(), x, y, z, level);
        this.mountJukebox(level);
    }

    private void mountJukebox(Level level) {
        this.jukebox = new GoldenJukeboxBlockEntity(BlockPos.ZERO,
                ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState(), this);
        this.jukebox.setLevel(level);
    }

    /** 載せている金のジュークボックス。 */
    public GoldenJukeboxBlockEntity jukebox() {
        return this.jukebox;
    }

    // ── 表示 ──

    @Override
    public BlockState getDefaultDisplayBlockState() {
        return ModBlocks.GOLDEN_JUKEBOX.get().defaultBlockState();
    }

    //? if <1.21.2 {
    /*@Override
    public AbstractMinecart.Type getMinecartType() {
        return AbstractMinecart.Type.CHEST;
    }
    *///?}

    // ── 盤のスロット (載せた金のジュークボックスのもの) ──

    @Override
    public int getContainerSize() {
        return 1;
    }

    @Override
    public NonNullList<ItemStack> getItemStacks() {
        // super の constructor から呼ばれる時はまだ載せていない。
        return this.jukebox == null ? NonNullList.withSize(1, ItemStack.EMPTY) : this.jukebox.mountedItems();
    }

    @Override
    public void clearItemStacks() {
        if (this.jukebox != null) this.jukebox.clearContent();
    }

    @Override
    public boolean isEmpty() {
        return this.jukebox == null || this.jukebox.isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        return this.jukebox == null ? ItemStack.EMPTY : this.jukebox.getItem(slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (this.jukebox != null) this.jukebox.setItem(slot, stack);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return this.jukebox == null ? ItemStack.EMPTY : this.jukebox.removeItem(slot, amount);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return this.jukebox == null ? ItemStack.EMPTY : this.jukebox.removeItemNoUpdate(slot);
    }

    @Override
    public void clearContent() {
        if (this.jukebox != null) this.jukebox.clearContent();
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return this.jukebox != null && this.jukebox.canPlaceItem(slot, stack);
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return this.jukebox.getSlotsForFace(side);
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) {
        return this.jukebox.canPlaceItemThroughFace(slot, stack, side);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return this.jukebox.canTakeItemThroughFace(slot, stack, side);
    }

    // ── 右クリック (設置した金のジュークボックスと同じ: 空なら盤を入れる、それ以外は GUI) ──

    //? if >=26.1 {
    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        return this.interactCommon(player, hand);
    }
    //?} else {
    /*@Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        return this.interactCommon(player, hand);
    }
    *///?}

    private InteractionResult interactCommon(Player player, InteractionHand hand) {
        final ItemStack held = player.getItemInHand(hand);
        if (this.jukebox.isEmpty() && !held.isEmpty()
                && this.jukebox.canPlaceItem(GoldenJukeboxBlockEntity.SLOT_DISC, held)) {
            if (!this.isClientSideLevel()) {
                final ItemStack insert = held.copy();
                insert.setCount(1);
                if (!player.getAbilities().instabuild) {
                    held.shrink(1);
                }
                this.jukebox.setItem(GoldenJukeboxBlockEntity.SLOT_DISC, insert);
            }
            return this.isClientSideLevel() ? InteractionResult.SUCCESS : InteractionResult.CONSUME;
        }
        if (this.isRemoved()) {
            return InteractionResult.PASS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            // 画面が最初の描画から今の状態を出せるよう、開く前に届ける。
            this.sendState(serverPlayer);
            Services.MENU.openGoldenJukeboxCartMenu(serverPlayer, this);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.SUCCESS;
    }

    private boolean isClientSideLevel() {
        //? if >=1.21.2 {
        return this.level().isClientSide();
        //?} else {
        /*return this.level().isClientSide;
        *///?}
    }

    @Nullable
    @Override
    public ItemStack getPickResult() {
        return new ItemStack(ModItems.GOLDEN_JUKEBOX_MINECART.get());
    }

    @Nullable
    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory playerInventory) {
        // client へ entity id を運ぶ extended menu を各 loader の helper から開く。
        return null;
    }

    // ── tick ──

    @Override
    public void tick() {
        super.tick();
        if (this.level() instanceof ServerLevel) {
            if (this.jukebox.getLevel() != this.level()) this.jukebox.setLevel(this.level());
            GoldenJukeboxBlockEntity.serverTick(this.level(), this.jukebox.getBlockPos(),
                    this.jukebox.getBlockState(), this.jukebox);
            if (this.tickCount % KEEPALIVE_INTERVAL_TICKS == 0) {
                this.sendKeepAlive();
            }
        }
    }

    @Override
    public void remove(Entity.RemovalReason reason) {
        if (this.level() instanceof ServerLevel && this.jukebox.peekSourceIdentity() != null) {
            // 破壊・回収・chunk の unload・次元移動のどれでも、この entity に付いた音を止める。
            // 再生状態は変えない (読み込み直した entity が続きから鳴らす)。
            this.broadcastStop(this.jukebox.playbackIdentity(), false);
        }
        super.remove(reason);
    }

    // ── GoldenJukeboxMount ──

    @Override
    public void play(@Nullable CustomTrackData track, @Nullable VanillaTrackData vanilla, long offsetMs,
            int rangeBlocks, int volumePercent, boolean directional, PlaybackSourceStamp identity) {
        if (this.level() instanceof ServerLevel level) {
            this.broadcastPlay(level, new GoldenCartPlayPayload(this.getId(),
                    track == null ? CustomTrackData.EMPTY : track, vanilla, offsetMs,
                    rangeBlocks, volumePercent, directional, identity));
        }
    }

    @Override
    public void stop(PlaybackSourceStamp identity, boolean naturalEnd) {
        this.broadcastStop(identity, naturalEnd);
    }

    @Override
    public void sync() {
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }
        for (final ServerPlayer player : level.players()) {
            if (player.containerMenu instanceof GoldenJukeboxMenu menu && menu.getBlockEntity() == this.jukebox) {
                this.sendState(player);
            }
        }
        // 音量・範囲・指向性の変更を、次の送り直しを待たずに鳴っている音へ届ける。
        this.sendKeepAlive();
    }

    @Override
    public void markDirty() {
        // entity は chunk の保存時に毎回書き出されるので、印を付ける先は無い。
    }

    @Override
    public boolean stillValid(Player player) {
        return !this.isRemoved() && player.distanceToSqr(this) <= 64.0;
    }

    // ── 配送 ──

    private void sendState(ServerPlayer player) {
        Services.NETWORK.sendToPlayer(player, new GoldenCartSyncPayload(this.getId(), this.jukebox.mountedStateTag()));
    }

    /** 鳴っている間、今の曲と位置をトロッコに付いた音として送り直す。 */
    private void sendKeepAlive() {
        if (!(this.level() instanceof ServerLevel level) || !this.jukebox.isSounding()) {
            return;
        }
        final CustomTrackData track = this.jukebox.currentTrack();
        final VanillaTrackData vanilla = track == null ? this.jukebox.currentVanillaTrack() : null;
        if (track == null && vanilla == null) {
            return;
        }
        this.broadcastPlay(level, new GoldenCartPlayPayload(this.getId(),
                track == null ? CustomTrackData.EMPTY : track, vanilla, this.jukebox.currentElapsedMs(),
                this.jukebox.getRangeBlocks(), this.jukebox.getVolumePercent(), this.jukebox.isDirectional(),
                this.jukebox.playbackIdentity()));
    }

    /** 可聴範囲の中の player へ。 */
    private void broadcastPlay(ServerLevel level, GoldenCartPlayPayload payload) {
        final double rangeSq = (double) payload.rangeBlocks() * payload.rangeBlocks();
        for (final ServerPlayer player : level.players()) {
            if (player.distanceToSqr(this) <= rangeSq) {
                LocalAudioServer.observe(player, payload);
                Services.NETWORK.sendToPlayer(player, payload);
            }
        }
    }

    /**
     * 止める知らせは同じ level の全 player へ。鳴らす知らせは距離で絞るが、受け取った後に範囲の外へ
     * 出た player や範囲を狭めて外れた player にも音を残さないため、こちらは絞らない。
     */
    private void broadcastStop(PlaybackSourceStamp identity, boolean naturalEnd) {
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }
        final GoldenCartStopPayload payload = new GoldenCartStopPayload(this.getId(), identity, naturalEnd);
        for (final ServerPlayer player : level.players()) {
            Services.NETWORK.sendToPlayer(player, payload);
        }
    }

    // ── 保存 ──

    //? if >=1.21.11 {
    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        output.store(KEY_JUKEBOX, CompoundTag.CODEC, this.jukebox.mountedStateTag());
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        input.read(KEY_JUKEBOX, CompoundTag.CODEC).ifPresent(this::loadJukebox);
    }
    //?} else {
    /*@Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.put(KEY_JUKEBOX, this.jukebox.mountedStateTag());
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains(KEY_JUKEBOX)) {
            this.loadJukebox(tag.getCompound(KEY_JUKEBOX));
        }
    }
    *///?}

    private void loadJukebox(CompoundTag tag) {
        if (this.jukebox.getLevel() != this.level()) this.jukebox.setLevel(this.level());
        this.jukebox.loadMountedState(tag);
    }

    /** client: server から届いた載せた金のジュークボックスの状態を、画面用に反映する。 */
    public void applySyncedState(CompoundTag tag) {
        this.loadJukebox(tag);
    }
}
