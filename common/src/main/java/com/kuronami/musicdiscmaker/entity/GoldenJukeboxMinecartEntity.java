package com.kuronami.musicdiscmaker.entity;

import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.component.GoldenDeck;
import com.kuronami.musicdiscmaker.component.MediaSequenceResolver;
import com.kuronami.musicdiscmaker.component.PlaybackCursor;
import com.kuronami.musicdiscmaker.event.GoldenCartPlayback;
import com.kuronami.musicdiscmaker.event.GoldenSourceRegistry;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModEntities;
import com.kuronami.musicdiscmaker.register.ModItems;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
//? if >=1.21.11 {
import com.mojang.serialization.Codec;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueOutput;
//?} else {
/*import net.minecraft.world.entity.vehicle.AbstractMinecart;
*///?}
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
//? if >=1.21 {
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
//?} else {
/*import net.minecraft.world.item.RecordItem;
*///?}
//? if >=1.21.11 {
import net.minecraft.world.level.gamerules.GameRules;
//?}
//? if <1.21.11 {
/*import net.minecraft.world.level.GameRules;
*///?}
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
//? if >=26.1 {
import net.minecraft.world.phys.Vec3;
//?}

/**
 * 金のジュークボックス付きトロッコ。バニラのチェスト付きトロッコと同じ手順でレールへ置き、
 * 破壊するとメディアと再生状態を埋め込んだ自身のアイテムへ戻る。
 *
 * <p>設計書 {@code MDM_310_GOLDEN_JUKEBOX_MINECART_DESIGN_RESULT_20260929.md} の Batch A+B:
 * 設置→保持→破壊→item化→再設置の縦経路と {@code source_id}/メディア/カーソルの保全に加え、
 * {@link GoldenCartPlayback} が menu 経由の再生操作・keep-alive・停止配送を担う。
 * redstone 挙動 (activator rail / comparator / detector) は未決定のため実装しない
 * (保存予約キーはデッキ側に残す)。speaker 連携も未実装。
 *
 * <h2>所有権の境界</h2>
 * <ul>
 *   <li>{@code source_id} は server の {@link ServerLevel} 上でのみ採番する
 *       ({@link GoldenJukeboxBlockEntity#sourceIdentity()} と同じ規則)。</li>
 *   <li>移動中の再生権威は {@link GoldenSourceRegistry#registerMoving} に載せ、
 *       除去時に必ず外す。</li>
 *   <li>破壊時はメディアを世界へ撒かず、drop item の entity data (NBT) へ同梱する。
 *       {@code remove(RemovalReason)} のコンテナ散乱経路へ渡る前にスロットを空にするため
 *       二重排出しない。</li>
 * </ul>
 */
public class GoldenJukeboxMinecartEntity extends GoldenJukeboxCartBase implements WorldlyContainer {

    /** 媒体スロット。固定ブロックの金ジュークと同じく 1 本だけを持つ。 */
    public static final int SLOT_MEDIA = 0;
    private static final int CONTAINER_SIZE = 1;

    private final NonNullList<ItemStack> mediaSlots = NonNullList.withSize(CONTAINER_SIZE, ItemStack.EMPTY);
    private final GoldenDeck deck = new GoldenDeck();
    // Slot/moveItemStackTo は格納済み stack を直接 split/shrink するため、実体参照では
    // 変更前を比較できない。最後に通知した媒体の copy を比較基準として保持する。
    private ItemStack observedMedia = ItemStack.EMPTY;

    public GoldenJukeboxMinecartEntity(EntityType<?> type, Level level) {
        super(type, level);
    }

    public GoldenJukeboxMinecartEntity(Level level, double x, double y, double z) {
        super(ModEntities.GOLDEN_JUKEBOX_MINECART.get(), x, y, z, level);
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

    // ── 媒体スロット ──

    @Override
    public int getContainerSize() {
        return CONTAINER_SIZE;
    }

    @Override
    public NonNullList<ItemStack> getItemStacks() {
        return this.mediaSlots;
    }

    @Override
    public void clearItemStacks() {
        this.mediaSlots.clear();
        this.setChanged();
    }

    @Override
    public void clearContent() {
        super.clearContent();
        this.setChanged();
    }

    public boolean hasMedia() {
        return !this.mediaSlots.get(SLOT_MEDIA).isEmpty();
    }

    public ItemStack getMedia() {
        return this.mediaSlots.get(SLOT_MEDIA);
    }

    /**
     * 金ジュークのスロット規則と同じ: 空スロットへ、再生できる媒体 1 本だけ。
     * (>=1.21 帯では固定ブロック側の {@code isPlayableInSlot} をそのまま使う。
     * 1.20.1 帯は同じ条件をここに並記する。BE には静的化された判定が無い。)
     */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        if (slot != SLOT_MEDIA || stack.isEmpty() || this.hasMedia()) {
            return false;
        }
        //? if >=1.21 {
        return GoldenJukeboxBlockEntity.isPlayableInSlot(stack);
        //?} else {
        /*return stack.getItem() instanceof RecordItem
                || (stack.is(ModItems.ALBUM.get()) && !MediaSequenceResolver.resolve(stack).tracks().isEmpty());
        *///?}
    }

    // ── hopper 経路 (GoldenJukeboxBlockEntity の WorldlyContainer 規則と同じ) ──

    private static final int[] MEDIA_SLOTS = {SLOT_MEDIA};

    @Override
    public int[] getSlotsForFace(Direction side) {
        return MEDIA_SLOTS;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) {
        return this.canPlaceItem(slot, stack) && side != Direction.DOWN;
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return slot == SLOT_MEDIA && side == Direction.DOWN
                && this.deck.isAutomationExtractionReady(this.hasMedia());
    }

    // ── 対話 (挿入 + menu。再生操作は menu から C2S へ) ──

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

    /**
     * 空スロットへ再生可能媒体を右手で入れると挿入 (server 側で {@code onMediaChanged} 経由の
     * 挿入即再生まで行く)。それ以外の右クリックは cart menu を開く。
     * 破壊済みの機体へは menu を開かない。
     */
    private InteractionResult interactCommon(Player player, InteractionHand hand) {
        final ItemStack held = player.getItemInHand(hand);
        if (!this.hasMedia() && !held.isEmpty() && this.canPlaceItem(SLOT_MEDIA, held)) {
            if (!this.isClientSideLevel()) {
                final ItemStack insert = held.copy();
                insert.setCount(1);
                // バニラのジュークボックスと同じく creative では手持ちを減らさない。
                if (!player.getAbilities().instabuild) {
                    held.shrink(1);
                }
                this.setItem(SLOT_MEDIA, insert);
            }
            return this.isClientSideLevel() ? InteractionResult.SUCCESS : InteractionResult.CONSUME;
        }
        if (this.isRemoved()) {
            return InteractionResult.PASS;
        }
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            Services.MENU.openGoldenJukeboxCartMenu(serverPlayer, this);
            if (serverPlayer.containerMenu
                    instanceof com.kuronami.musicdiscmaker.menu.GoldenJukeboxCartMenu menu) {
                com.kuronami.musicdiscmaker.network.ModNetwork.sendGoldenCartState(serverPlayer, menu);
            }
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

    // ── 破壊→item 化 ──

    //? if >=1.21.11 {
    @Override
    public void destroy(ServerLevel level, DamageSource source) {
        final ItemStack drop = this.createDropStack();
        this.clearItemStacks();
        this.kill(level);
        if (level.getGameRules().get(GameRules.ENTITY_DROPS)) {
            this.spawnAtLocation(level, drop);
        }
    }
    //?} elif >=1.21.2 {
    /*@Override
    public void destroy(ServerLevel level, DamageSource source) {
        final ItemStack drop = this.createDropStack();
        this.clearItemStacks();
        this.kill(level);
        if (level.getGameRules().getBoolean(GameRules.RULE_DOENTITYDROPS)) {
            this.spawnAtLocation(level, drop);
        }
    }
    *///?} else {
    /*@Override
    public void destroy(DamageSource source) {
        final ItemStack drop = this.createDropStack();
        this.clearItemStacks();
        this.kill();
        if (this.level().getGameRules().getBoolean(GameRules.RULE_DOENTITYDROPS)) {
            this.spawnAtLocation(drop);
        }
    }
    *///?}

    /**
     * 破壊時の drop item を組み立てる。メディア・デッキ状態を entity data として同梱する。
     * {@code Entity.remove} のコンテナ散乱より先に呼ぶこと (呼び出し側でスロットを空にする)。
     */
    private ItemStack createDropStack() {
        this.deck.freezeForCarry(this.currentOffsetMs());
        //? if >=1.21.11 {
        final TagValueOutput output =
                TagValueOutput.createWithContext(ProblemReporter.DISCARDING, this.registryAccess());
        this.addChestVehicleSaveData(output);
        writeDeckTo(this.deck, output);
        final CompoundTag entityTag = output.buildResult();
        //?} else {
        /*final CompoundTag entityTag = new CompoundTag();
        //? if >=1.21 {
        this.addChestVehicleSaveData(entityTag, this.registryAccess());
        //?} else {
        /^this.addChestVehicleSaveData(entityTag);
        ^///?}
        writeDeckTo(this.deck, entityTag);
        *///?}
        final ItemStack stack = new ItemStack(ModItems.GOLDEN_JUKEBOX_MINECART.get());
        //? if >=1.21 {
        if (this.hasCustomName()) {
            stack.set(DataComponents.CUSTOM_NAME, this.getCustomName());
        }
        //? if >=1.21.11 {
        stack.set(DataComponents.ENTITY_DATA,
                TypedEntityData.of(ModEntities.GOLDEN_JUKEBOX_MINECART.get(), entityTag));
        //?} else {
        /*// 1.21.1〜1.21.10 の ENTITY_DATA は id 付きの tag しか保存できない (CODEC_WITH_ID)。
        // 無いと item の保存で例外になり、地面・チェスト・player data の書込みごと失敗する。
        entityTag.putString("id", net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                .getKey(ModEntities.GOLDEN_JUKEBOX_MINECART.get()).toString());
        stack.set(DataComponents.ENTITY_DATA, CustomData.of(entityTag));
        *///?}
        //?} else {
        /*if (this.hasCustomName()) {
            stack.setHoverName(this.getCustomName());
        }
        stack.getOrCreateTag().put("EntityTag", entityTag);
        *///?}
        return stack;
    }

    /** 再生中の経過位置 (ms)。PLAYING 以外では pause 位置を返す。実装はドライバ側。 */
    private long currentOffsetMs() {
        return GoldenCartPlayback.currentOffsetMs(this);
    }

    @Nullable
    @Override
    public ItemStack getPickResult() {
        return new ItemStack(ModItems.GOLDEN_JUKEBOX_MINECART.get());
    }

    // ── 再生権威 (Batch B の再生送信が乗る土台) ──

    /**
     * 機体識別子。server 権威でのみ採番する ({@link GoldenJukeboxBlockEntity#sourceIdentity()} と同じ規則)。
     */
    public UUID sourceIdentity() {
        if (this.deck.peekSourceId() == null) {
            if (!(this.level() instanceof ServerLevel)) {
                throw new IllegalStateException("golden jukebox minecart identity requires a server level");
            }
            this.deck.ensureSourceId();
        }
        return this.deck.peekSourceId();
    }

    /** 採番済みなら識別子を返す。読み取り専用で、採番はしない。 */
    @Nullable
    public UUID peekSourceIdentity() {
        return this.deck.peekSourceId();
    }

    public GoldenDeck deck() {
        return this.deck;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level() instanceof ServerLevel serverLevel) {
            GoldenCartPlayback.tick(this, serverLevel);
        }
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        super.setItem(slot, stack);
        this.setChanged();
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        final ItemStack removed = super.removeItem(slot, amount);
        this.setChanged();
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        final ItemStack removed = super.removeItemNoUpdate(slot);
        this.setChanged();
        return removed;
    }

    /**
     * 媒体の実変更を一度だけ再生へ通知する。shift-click の in-place な空化も、
     * PICKUP の removeItem もこの比較へ通す。後続の Slot#setChanged は同じ内容なら無視する。
     * 読み込み中 (tickCount=0) は比較基準だけ更新し、復元する cursor を消さない。
     */
    @Override
    public void setChanged() {
        super.setChanged();
        if (this.mediaSlots == null) return; // super constructor 中の呼出しには媒体がまだ無い。
        final ItemStack media = this.getMedia();
        if (ItemStack.matches(this.observedMedia, media)) return;
        this.observedMedia = media.copy();
        if (this.tickCount > 0) {
            GoldenCartPlayback.onMediaChanged(this);
        }
    }

    @Override
    public void remove(Entity.RemovalReason reason) {
        if (this.level() instanceof ServerLevel serverLevel && this.deck.hasSourceId()) {
            // 破壊・pickup・chunk unload・次元移動のどれでも、鳴らしている経路を畳む。
            // client 側の lease は世代照合つきで畳まれ、keep-alive 途絶が拾い残しを覆う。
            GoldenCartPlayback.broadcastStop(this, serverLevel);
            GoldenSourceRegistry.unregisterMoving(serverLevel, this);
        }
        super.remove(reason);
    }

    // ── menu ──

    @Nullable
    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory playerInventory) {
        // vanilla の interactWithContainerVehicle 経路は使わず、extended menu
        // ({@link com.kuronami.musicdiscmaker.menu.GoldenJukeboxCartMenu}) を各 loader の
        // helper から開く。entityId を client へ運ぶ必要があるため、この経路は開かない。
        return null;
    }

    // ── 保存 ──

    //? if >=1.21.11 {
    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        writeDeckTo(this.deck, output);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        this.readDeckFrom(input);
    }

    private void readDeckFrom(ValueInput input) {
        final Map<String, Object> values = new HashMap<>();
        input.getString(GoldenDeck.KEY_SOURCE_ID).ifPresent(v -> values.put(GoldenDeck.KEY_SOURCE_ID, v));
        input.getInt(GoldenDeck.KEY_RANGE).ifPresent(v -> values.put(GoldenDeck.KEY_RANGE, v));
        input.getInt(GoldenDeck.KEY_VOLUME).ifPresent(v -> values.put(GoldenDeck.KEY_VOLUME, v));
        input.read(GoldenDeck.KEY_REPEAT, Codec.BOOL).ifPresent(v -> values.put(GoldenDeck.KEY_REPEAT, v));
        input.read(GoldenDeck.KEY_SHUFFLE, Codec.BOOL).ifPresent(v -> values.put(GoldenDeck.KEY_SHUFFLE, v));
        input.getLong(GoldenDeck.KEY_SHUFFLE_SEED).ifPresent(v -> values.put(GoldenDeck.KEY_SHUFFLE_SEED, v));
        input.getInt(GoldenDeck.KEY_SHUFFLE_ANCHOR_DISC)
                .ifPresent(v -> values.put(GoldenDeck.KEY_SHUFFLE_ANCHOR_DISC, v));
        input.getInt(GoldenDeck.KEY_SHUFFLE_ANCHOR_TRACK)
                .ifPresent(v -> values.put(GoldenDeck.KEY_SHUFFLE_ANCHOR_TRACK, v));
        input.read(GoldenDeck.KEY_DIRECTIONAL, Codec.BOOL)
                .ifPresent(v -> values.put(GoldenDeck.KEY_DIRECTIONAL, v));
        input.getString(GoldenDeck.KEY_PLAYBACK_STATE)
                .ifPresent(v -> values.put(GoldenDeck.KEY_PLAYBACK_STATE, v));
        input.getLong(GoldenDeck.KEY_PLAYBACK_GENERATION)
                .ifPresent(v -> values.put(GoldenDeck.KEY_PLAYBACK_GENERATION, v));
        input.read(GoldenDeck.KEY_PAUSED, Codec.BOOL).ifPresent(v -> values.put(GoldenDeck.KEY_PAUSED, v));
        input.read(GoldenDeck.KEY_REDSTONE_PAUSED, Codec.BOOL)
                .ifPresent(v -> values.put(GoldenDeck.KEY_REDSTONE_PAUSED, v));
        input.read(GoldenDeck.KEY_AUTOMATION_FINISHED, Codec.BOOL)
                .ifPresent(v -> values.put(GoldenDeck.KEY_AUTOMATION_FINISHED, v));
        input.getLong(GoldenDeck.KEY_PAUSED_OFFSET).ifPresent(v -> values.put(GoldenDeck.KEY_PAUSED_OFFSET, v));
        input.getLong(GoldenDeck.KEY_PLAYBACK_START_GAME_TIME)
                .ifPresent(v -> values.put(GoldenDeck.KEY_PLAYBACK_START_GAME_TIME, v));
        input.getInt(GoldenDeck.KEY_ALBUM_TRACK).ifPresent(v -> values.put(GoldenDeck.KEY_ALBUM_TRACK, v));
        input.getInt(GoldenDeck.KEY_PLAYLIST_TRACK).ifPresent(v -> values.put(GoldenDeck.KEY_PLAYLIST_TRACK, v));
        this.applyDeckValues(values);
    }

    private static void writeDeckTo(GoldenDeck deck, ValueOutput output) {
        deck.encode().forEach((key, value) -> {
            if (value instanceof String s) {
                output.putString(key, s);
            } else if (value instanceof Integer i) {
                output.putInt(key, i);
            } else if (value instanceof Long l) {
                output.putLong(key, l);
            } else if (value instanceof Boolean b) {
                output.putBoolean(key, b);
            }
        });
    }
    //?} else {
    /*@Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        writeDeckTo(this.deck, tag);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.readDeckFrom(tag);
    }

    private void readDeckFrom(CompoundTag tag) {
        final Map<String, Object> values = new HashMap<>();
        for (String key : GoldenDeck.KEYS) {
            if (!tag.contains(key)) {
                continue;
            }
            switch (key) {
                case GoldenDeck.KEY_SOURCE_ID, GoldenDeck.KEY_PLAYBACK_STATE -> values.put(key, tag.getString(key));
                case GoldenDeck.KEY_SHUFFLE_SEED, GoldenDeck.KEY_PLAYBACK_GENERATION,
                        GoldenDeck.KEY_PAUSED_OFFSET, GoldenDeck.KEY_PLAYBACK_START_GAME_TIME
                        -> values.put(key, tag.getLong(key));
                case GoldenDeck.KEY_REPEAT, GoldenDeck.KEY_SHUFFLE, GoldenDeck.KEY_DIRECTIONAL,
                        GoldenDeck.KEY_PAUSED, GoldenDeck.KEY_REDSTONE_PAUSED,
                        GoldenDeck.KEY_AUTOMATION_FINISHED -> values.put(key, tag.getBoolean(key));
                default -> values.put(key, tag.getInt(key));
            }
        }
        this.applyDeckValues(values);
    }

    private static void writeDeckTo(GoldenDeck deck, CompoundTag tag) {
        deck.encode().forEach((key, value) -> {
            if (value instanceof String s) {
                tag.putString(key, s);
            } else if (value instanceof Integer i) {
                tag.putInt(key, i);
            } else if (value instanceof Long l) {
                tag.putLong(key, l);
            } else if (value instanceof Boolean b) {
                tag.putBoolean(key, b);
            }
        });
    }
    *///?}

    /**
     * 復元値をデッキへ反映する。メディアの有無は super 側の chest vehicle 読み込みが
     * 済んだ後の実測を渡す (媒体の無い entity に再生状態を乗せない)。
     * {@code source_id} は保存値のある時だけ代入し、ここでは新規採番しない。
     */
    private void applyDeckValues(Map<String, Object> values) {
        final GoldenDeck restored = GoldenDeck.decode(values, this.hasMedia());
        this.deck.setCursor(restored.cursor());
        this.deck.setPausedOffsetMs(restored.pausedOffsetMs());
        this.deck.setPlaybackStartGameTime(restored.playbackStartGameTime());
        this.deck.setRangeBlocks(restored.rangeBlocks());
        this.deck.setVolumePercent(restored.volumePercent());
        this.deck.setRepeat(restored.isRepeat());
        this.deck.setShuffle(restored.isShuffle(), restored.shuffleSeed(),
                restored.shuffleAnchorDiscIndex(), restored.shuffleAnchorTrackIndex());
        this.deck.setDirectional(restored.isDirectional());
        this.deck.setRedstonePaused(restored.isRedstonePaused());
        this.deck.setAutomationFinished(restored.isAutomationFinished());
        if (restored.peekSourceId() != null) {
            this.deck.assignSourceId(restored.peekSourceId());
        }
        // vanilla の保存読込は媒体リストへ直接書く。復元済み媒体を変更前として記録し、
        // 最初の menu 通知で保存カーソルを再初期化しない。
        this.observedMedia = this.getMedia().copy();
    }
}
