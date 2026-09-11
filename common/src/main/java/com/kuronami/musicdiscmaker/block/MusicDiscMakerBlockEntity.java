package com.kuronami.musicdiscmaker.block;

import org.jetbrains.annotations.NotNull;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
//? if >=1.21 {
import com.kuronami.musicdiscmaker.component.SilentSongs;
//?} else {
/*import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
*///?}
import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.register.ModBlockEntities;
//? if >=1.21 {
import com.kuronami.musicdiscmaker.register.ModDataComponents;
//?} else {
//?}
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
//? if >=1.21.2 {
import net.minecraft.core.HolderLookup;
//?} elif >=1.21 {
/*import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
*///?} else {
//?}
import net.minecraft.core.NonNullList;
//? if >=1.21.2 {
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
//?} else {
//?}
import net.minecraft.nbt.CompoundTag;
//? if >=1.21 {
//?} else {
/*import net.minecraft.nbt.NbtOps;
*///?}
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
//? if >=1.21.2 {
//?} elif >=1.21 {
/*import net.minecraft.world.item.EitherHolder;
*///?} else {
//?}
import net.minecraft.world.item.ItemStack;
//? if >=26.1 {
import net.minecraft.world.item.JukeboxPlayable;
//?} elif >=1.21.2 {
/*import net.minecraft.world.item.EitherHolder;
import net.minecraft.world.item.JukeboxPlayable;
*///?} elif >=1.21 {
/*import net.minecraft.world.item.JukeboxPlayable;
*///?} else {
//?}
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
//? if >=1.21.2 {
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
//?} else {
//?}

/**
 * Music Disc Maker ブロックの BlockEntity。
 * 入力(空ディスク)/出力(custom disc) スロットと、解決済みの曲メタ情報を持つ。
 * URL 解決は server 側で行われ、結果がここに保存される (server authoritative)。
 *
 * <p>vanilla {@link WorldlyContainer} を全帯で実装する (loader 非依存)。面規則は
 * 「投入は入力スロット、取り出しは出力スロットだけ」。旧補助枠の保存材料は
 * GUIを開く際に返却する内部領域へ残し、新規投入や自動搬出には使わない。
 *
 * <p>バニラと Fabric の hopper はこの規則を直接読む。NeoForge は capability 経由で読むので、
 * {@code MusicDiscMakerNeoForge#registerCapabilities} が <b>side を渡す</b> wrapper を登録する
 * (side を捨てる wrapper だとスロット別の制限が効かない)。
 *
 * <p>ブロック破壊時の中身ドロップは 1.21 以降 {@code preRemoveSideEffects} のデフォルト
 * (Container を自動ドロップ) に任せる。
 */
public class MusicDiscMakerBlockEntity extends BlockEntity implements WorldlyContainer {

    public static final int SLOT_INPUT = 0;
    public static final int SLOT_OUTPUT = 1;
    /** 旧保存材料の回収専用領域。新規投入は不可。 */
    private static final int LEGACY_UPGRADE = 2;
    private static final int SIZE = 3;

    /** 面へ公開するスロット。面ごとの可否は下の 2 つの ThroughFace が決める。 */
    private static final int[] SLOTS = {SLOT_INPUT, SLOT_OUTPUT};

    private final NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);

    private String currentUrl = "";
    private CustomTrackData resolvedTrack = CustomTrackData.EMPTY;
    /** {@link #resolvedTrack} を生成した元 URL。同一 URL の複数ディスクを完全一致させるためのキャッシュ鍵。 */
    private String resolvedForUrl = "";
    private boolean resolving = false;
    /** 直近の解決が失敗したか (GUI のエラー表示用)。同期のみ・永続化しない。 */
    private boolean resolveFailed = false;
    /** 直近の失敗理由 (GUI の理由別メッセージ用)。{@link #resolveFailed} が true の時だけ意味を持つ。 */
    private FailureReason failureReason = FailureReason.UNKNOWN;

    public MusicDiscMakerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MUSIC_DISC_MAKER.get(), pos, state);
    }

    public String getCurrentUrl() {
        return currentUrl;
    }

    /** client から URL がコミットされたとき (server)。条件が揃えば自動生成を試みる。 */
    public void setCurrentUrl(String url) {
        final String u = url == null ? "" : url;
        // URL が解決済みキャッシュと異なるなら、キャッシュを無効化して再解決させる
        if (!u.equals(this.resolvedForUrl)) {
            this.resolvedTrack = CustomTrackData.EMPTY;
            this.resolvedForUrl = "";
        }
        this.currentUrl = u;
        //? if >=1.21.2 {
        this.resolveFailed = false; // URL を編集したら過去のエラー表示を消す
        //?} else {
        /*this.resolveFailed = false;
        *///?}
        sync();
        DiscFabrication.process(this);
    }

    public String getResolvedForUrl() {
        return resolvedForUrl;
    }

    public boolean isResolving() {
        return resolving;
    }

    public void setResolving(boolean resolving) {
        this.resolving = resolving;
        if (resolving) {
            //? if >=1.21.2 {
            this.resolveFailed = false; // 新たな解決を開始したら過去のエラー表示を消す
            //?} else {
            /*this.resolveFailed = false;
            *///?}
        }
        sync();
    }

    public boolean isResolveFailed() {
        return resolveFailed;
    }

    public FailureReason getFailureReason() {
        return failureReason;
    }

    /** 解決失敗を理由つきで記録する (GUI が理由別メッセージを出す)。 */
    public void setResolveFailed(FailureReason reason) {
        this.resolveFailed = true;
        this.failureReason = reason == null ? FailureReason.UNKNOWN : reason;
        sync();
    }

    public CustomTrackData getResolvedTrack() {
        return resolvedTrack;
    }

    public boolean hasResolvedTrack() {
        return resolvedTrack != null && !resolvedTrack.isEmpty();
    }

    /** server 側で URL 解決が成功したら呼ぶ。{@code forUrl} は解決元 URL (キャッシュ鍵)。 */
    public void setResolvedTrack(CustomTrackData track, String forUrl) {
        this.resolvedTrack = track == null ? CustomTrackData.EMPTY : track;
        this.resolvedForUrl = forUrl == null ? "" : forUrl;
        sync();
    }

    /** 製作機へ投入できる未記録ディスクか。 */
    public static boolean isBlankDisc(ItemStack stack) {
        return stack.is(ModItems.BLANK_DISC.get());
    }

    /** 旧補助枠の材料を、画面を開いたプレイヤーへ一度だけ返す。 */
    public void returnLegacyMaterials(Player player) {
        //? if >=1.21.2 {
        if (level == null || level.isClientSide()) {
        //?} else {
        /*if (level == null || level.isClientSide) {
        *///?}
            return;
        }
        final ItemStack legacy = items.get(LEGACY_UPGRADE);
        if (legacy.isEmpty()) return;
        items.set(LEGACY_UPGRADE, ItemStack.EMPTY);
        final var inventory = player.getInventory();
        // Inventory.add はクリエイティブの満杯時に余りを消すため、収納可能な分だけ明示的に返す。
        for (int pass = 0; pass < 2 && !legacy.isEmpty(); pass++) {
            for (int slot = 0; slot < 36 && !legacy.isEmpty(); slot++) {
                final ItemStack stored = inventory.getItem(slot);
                if (stored.isEmpty()) {
                    if (pass == 0) continue;
                    final int moved = Math.min(legacy.getCount(),
                            Math.min(legacy.getMaxStackSize(), inventory.getMaxStackSize()));
                    inventory.setItem(slot, legacy.copyWithCount(moved));
                    legacy.shrink(moved);
                //? if >=1.21 {
                } else if (pass == 0 && ItemStack.isSameItemSameComponents(stored, legacy)) {
                //?} else {
                /*} else if (pass == 0 && ItemStack.isSameItemSameTags(stored, legacy)) {
                *///?}
                    final int moved = Math.min(legacy.getCount(),
                            Math.max(0, Math.min(stored.getMaxStackSize(), inventory.getMaxStackSize()) - stored.getCount()));
                    stored.grow(moved);
                    legacy.shrink(moved);
                }
            }
        }
        inventory.setChanged();
        if (!legacy.isEmpty() && player.drop(legacy.copy(), false) == null) {
            // 他MODがdropを拒否した場合は、次の回収まで元の保存領域へ戻す。
            items.set(LEGACY_UPGRADE, legacy);
        }
        sync();
    }

    public void clearResolvedTrack() {
        this.resolvedTrack = CustomTrackData.EMPTY;
        this.resolvedForUrl = "";
        sync();
    }

    /** ディスクを1枚生成した後の後始末: URL・キャッシュ・状態を消してニュートラルに戻す (1 URL = 1 枚)。 */
    public void resetToNeutral() {
        this.currentUrl = "";
        this.resolvedTrack = CustomTrackData.EMPTY;
        this.resolvedForUrl = "";
        this.resolveFailed = false;
        sync();
    }

    /** 盤に貼る無音 jukebox_song の尺 (ms)。 */
    private long silentSongMillis() {
        return resolvedTrack.durationMs();
    }

    /** 解決済みの曲を書き込み、出力確保後に未記録ディスクを1枚だけ消費する。 */
    public boolean createDisc() {
        if (!hasResolvedTrack()) {
            return false;
        }
        final ItemStack input = items.get(SLOT_INPUT);
        if (input.isEmpty() || !isBlankDisc(input)) {
            return false;
        }
        if (!items.get(SLOT_OUTPUT).isEmpty()) {
            return false;
        }

        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        //? if >=26.1 {
        disc.set(ModDataComponents.CUSTOM_TRACK.get(), resolvedTrack);
        // バニラの「再生中」状態に乗せる: 曲長に合う無音 jukebox_song を JUKEBOX_PLAYABLE で参照
        // (Amendments の回転・コンパレータ・ホッパー等が機能する。音声は LavaPlayer)。
        // 26.1.2 の JukeboxPlayable は Holder<JukeboxSong> 単独 (1.21.1 の EitherHolder+boolean とは別形)。
        if (this.level != null) {
            this.level.registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG)
                    .get(SilentSongs.pick(silentSongMillis(), resolvedTrack.radio()))
                    .ifPresent(holder -> disc.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(holder)));
        }
        //?} elif >=1.21.2 {
        /*disc.set(ModDataComponents.CUSTOM_TRACK.get(), resolvedTrack);
        // バニラの「再生中」状態に乗せる: 曲長に合う無音 jukebox_song を JUKEBOX_PLAYABLE で参照
        // (Amendments の回転・コンパレータ・ホッパー等が機能する。音声は LavaPlayer)。
        // JukeboxPlayable の ctor は EitherHolder 1 引数 (showInTooltip は無い)。
        if (this.level != null) {
            this.level.registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG)
                    .get(SilentSongs.pick(silentSongMillis(), resolvedTrack.radio()))
                    .ifPresent(holder -> disc.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(new EitherHolder<>(holder))));
        }
        *///?} elif >=1.21 {
        /*disc.set(ModDataComponents.CUSTOM_TRACK.get(), resolvedTrack);
        // バニラの「再生中」状態に乗せる: 曲長に合う無音 jukebox_song を JUKEBOX_PLAYABLE で参照。
        // これでバニラの挿入/ホッパー/コンパレータ/Amendments の回転等が機能する (音声は LavaPlayer)。
        // show_in_tooltip=false: song の description はツールチップに出さない (曲名は CUSTOM_TRACK 側で表示)。
        disc.set(DataComponents.JUKEBOX_PLAYABLE,
                new JukeboxPlayable(new EitherHolder<>(
                        SilentSongs.pick(silentSongMillis(), resolvedTrack.radio())), false));
        *///?} else {
        /*CustomMusicDiscItem.setTrack(disc, resolvedTrack);
        *///?}

        // 出力を先に埋める: 直後の onContentsChanged→process は「出力が空でない」で早期 return し、
        // input 消費の onContentsChanged が再入して余計な再解決/二重生成を起こすのを防ぐ。
        setItem(SLOT_OUTPUT, disc);
        removeItem(SLOT_INPUT, 1);
        return true;
    }

    public void sync() {
        setChanged();
        //? if >=1.21.2 {
        if (level != null && !level.isClientSide()) {
        //?} else {
        /*if (level != null && !level.isClientSide) {
        *///?}
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    /** スロット変更時: 同期 + (server 側で条件が揃えば) 自動生成。出力を先に埋める順序で再入を抑制。 */
    private void onContentsChanged() {
        //? if >=1.21.2 {
        if (resolveFailed && items.get(SLOT_INPUT).isEmpty()) {
            // 失敗の原因だった入力の空ディスクが取り出された = そのペンディングは無かったことになる。
            // 消さずに置くと、次に来た人が何もしていないのに古い失敗表示だけが残って紛らわしい。
            resolveFailed = false;
        }
        //?} elif >=1.21 {
        /*if (resolveFailed && items.get(SLOT_INPUT).isEmpty()) {
            // 失敗の原因だった入力の空ディスクが取り出された = そのペンディングは無かったことになる。
            // 消さずに置くと、次に来た人が何もしていないのに古い失敗表示だけが残って紛らわしい。
            resolveFailed = false;
        }
        *///?} else {
        //?}
        sync();
        DiscFabrication.process(this);
    }

    // ── persistence (26.1.2: ValueInput/ValueOutput) ──

    @Override
    //? if >=1.21.2 {
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
    //?} elif >=1.21 {
    /*protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
    *///?} else {
    /*public void load(CompoundTag tag) {
        super.load(tag);
    *///?}
        items.clear();
        //? if >=1.21.2 {
        ContainerHelper.loadAllItems(input, items);
        currentUrl = input.getStringOr("url", "");
        resolvedForUrl = input.getStringOr("resolvedForUrl", "");
        this.resolvedTrack = input.read("track", CustomTrackData.CODEC).orElse(CustomTrackData.EMPTY);
        this.resolving = input.getBooleanOr("resolving", false); // 同期タグから (disk には無いので false)
        this.resolveFailed = input.getBooleanOr("resolveFailed", false); // 同期タグから
        this.failureReason = FailureReason.fromName(input.getStringOr("failureReason", "")); // 同期タグから
        //?} elif >=1.21 {
        /*// ItemStackHandler 旧形式 ({Size, Items:[...]}) も Items リストとして読める (Size は無視)。
        ContainerHelper.loadAllItems(tag.getCompound("inventory"), items, registries);
        currentUrl = tag.getString("url");
        resolvedForUrl = tag.getString("resolvedForUrl");
        if (tag.contains("track")) {
            CustomTrackData.CODEC
                    .parse(net.minecraft.nbt.NbtOps.INSTANCE, tag.get("track"))
                    .result()
                    .ifPresent(t -> this.resolvedTrack = t);
        } else {
            this.resolvedTrack = CustomTrackData.EMPTY;
        }
        this.resolving = tag.getBoolean("resolving");
        this.resolveFailed = tag.getBoolean("resolveFailed");
        this.failureReason = FailureReason.fromName(tag.getString("failureReason"));
        *///?} else {
        /*// ItemStackHandler 旧形式 ({Size, Items:[...]}) も Items リストとして読める (Size は無視)。
        ContainerHelper.loadAllItems(tag.getCompound("inventory"), items);
        currentUrl = tag.getString("url");
        resolvedForUrl = tag.getString("resolvedForUrl");
        if (tag.contains("track")) {
            CustomTrackData.CODEC
                    .parse(NbtOps.INSTANCE, tag.get("track"))
                    .result()
                    .ifPresent(t -> this.resolvedTrack = t);
        } else {
            this.resolvedTrack = CustomTrackData.EMPTY;
        }
        this.resolving = tag.getBoolean("resolving");
        this.resolveFailed = tag.getBoolean("resolveFailed");
        this.failureReason = FailureReason.fromName(tag.getString("failureReason"));
        *///?}
        // Discard the unpublished multi-track cache so it cannot bypass single-track resolution.
        //? if >=1.21.2 {
        final boolean legacyMultiTrackCache = input.read("tracks", com.mojang.serialization.Codec.PASSTHROUGH).isPresent();
        //?} else {
        /*final boolean legacyMultiTrackCache = tag.contains("tracks");
        *///?}
        if (legacyMultiTrackCache) {
            this.resolvedTrack = CustomTrackData.EMPTY;
            this.resolvedForUrl = "";
            this.resolving = false;
        }
    }

    @Override
    //? if >=1.21.2 {
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
        output.putString("url", currentUrl);
        output.putString("resolvedForUrl", resolvedForUrl);
    //?} elif >=1.21 {
    /*protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        final CompoundTag invTag = new CompoundTag();
        ContainerHelper.saveAllItems(invTag, items, registries);
        tag.put("inventory", invTag);
        tag.putString("url", currentUrl);
        tag.putString("resolvedForUrl", resolvedForUrl);
        // resolveFailed/failureReason: GUI を閉じて開き直しても失敗表示が残るように永続化する
        // (resolving は意図的に含めない。server 再起動でスレッドプールごと消える一時状態なので、
        // 永続化すると「取得中…」のまま二度と終わらない spinner が固定化してしまう)。
        tag.putBoolean("resolveFailed", resolveFailed);
        tag.putString("failureReason", failureReason.name());
    *///?} else {
    /*protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        final CompoundTag invTag = new CompoundTag();
        ContainerHelper.saveAllItems(invTag, items);
        tag.put("inventory", invTag);
        tag.putString("url", currentUrl);
        tag.putString("resolvedForUrl", resolvedForUrl);
    *///?}
        // resolveFailed/failureReason: GUI を閉じて開き直しても失敗表示が残るように永続化する
        // (resolving は意図的に含めない。server 再起動でスレッドプールごと消える一時状態なので、
        // 永続化すると「取得中…」のまま二度と終わらない spinner が固定化してしまう)。
        //? if >=1.21.2 {
        output.putBoolean("resolveFailed", resolveFailed);
        output.putString("failureReason", failureReason.name());
        //?}
        if (hasResolvedTrack()) {
            //? if >=1.21.2 {
            output.store("track", CustomTrackData.CODEC, resolvedTrack);
            //?} elif >=1.21 {
            /*CustomTrackData.CODEC
                    .encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, resolvedTrack)
                    .result()
                    .ifPresent(encoded -> tag.put("track", encoded));
            *///?} else {
            /*CustomTrackData.CODEC
                    .encodeStart(NbtOps.INSTANCE, resolvedTrack)
                    .result()
                    .ifPresent(encoded -> tag.put("track", encoded));
            *///?}
        }
    }

    @Override
    //? if >=1.21.2 {
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        // saveCustomOnly が saveAdditional 経由で CompoundTag を組む。sync 専用フラグ (永続化しない) を上乗せ。
        final CompoundTag tag = saveCustomOnly(registries);
        tag.putBoolean("resolving", resolving); // 解決中表示用
        tag.putBoolean("resolveFailed", resolveFailed); // エラー表示用
        tag.putString("failureReason", failureReason.name()); // 理由別メッセージ用
    //?} elif >=1.21 {
    /*public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        final CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries); // resolveFailed/failureReason はここに含まれる (永続化と共用)
        // resolving は非永続 (server 再起動でスレッドプールごと消える一時状態) なので同期専用にここで足す。
        tag.putBoolean("resolving", resolving);
    *///?} else {
    /*public CompoundTag getUpdateTag() {
        final CompoundTag tag = new CompoundTag();
        saveAdditional(tag);
        tag.putBoolean("resolving", resolving);
        tag.putBoolean("resolveFailed", resolveFailed);
        tag.putString("failureReason", failureReason.name());
    *///?}
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ── WorldlyContainer 実装 (hopper 連携の土台。NeoForge は side 付き wrapper で橋渡し) ──

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    public boolean isEmpty() {
        for (final ItemStack stack : items) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public @NotNull ItemStack getItem(int slot) {
        return items.get(slot);
    }

    @Override
    public @NotNull ItemStack removeItem(int slot, int amount) {
        final ItemStack removed = ContainerHelper.removeItem(items, slot, amount);
        if (!removed.isEmpty()) {
            onContentsChanged();
        }
        return removed;
    }

    @Override
    public @NotNull ItemStack removeItemNoUpdate(int slot) {
        final ItemStack removed = ContainerHelper.takeItem(items, slot);
        if (!removed.isEmpty()) {
            onContentsChanged();
        }
        return removed;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        if (!stack.isEmpty() && stack.getCount() > getMaxStackSize()) {
            stack.setCount(getMaxStackSize());
        }
        onContentsChanged();
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return slot == SLOT_INPUT && isBlankDisc(stack);
    }

    @Override
    public void clearContent() {
        items.clear();
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return SLOTS;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction direction) {
        return canPlaceItem(slot, stack);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
        // 自動回収は完成品のみ。未記録の入力ディスクは機械に残す。
        return slot == SLOT_OUTPUT;
    }
}

