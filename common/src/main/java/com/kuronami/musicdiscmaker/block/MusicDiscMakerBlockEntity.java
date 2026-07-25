package com.kuronami.musicdiscmaker.block;

import org.jetbrains.annotations.NotNull;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.SilentSongs;
import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.register.ModBlockEntities;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.EitherHolder;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Music Disc Maker ブロックの BlockEntity。
 * 入力(空ディスク)/出力(custom disc) スロットと、解決済みの曲メタ情報を持つ。
 * URL 解決は server 側で行われ、結果がここに保存される (server authoritative)。
 *
 * <p>vanilla {@link Container} を実装。NeoForge は {@code Capabilities.Item.BLOCK} に
 * {@code VanillaContainerWrapper} で橋渡しして hopper 連携を提供し、ブロック破壊時の中身ドロップは
 * {@link BlockEntity#preRemoveSideEffects} のデフォルト (Container を自動ドロップ) に任せる。
 */
public class MusicDiscMakerBlockEntity extends BlockEntity implements Container {

    public static final int SLOT_INPUT = 0;
    public static final int SLOT_OUTPUT = 1;
    private static final int SIZE = 2;

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
        this.resolveFailed = false; // URL を編集したら過去のエラー表示を消す
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
            this.resolveFailed = false; // 新たな解決を開始したら過去のエラー表示を消す
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

    /**
     * 解決済みトラックから custom music disc を生成して出力スロットに置く。
     *
     * @return 成功したら true
     */
    public boolean createDisc() {
        if (!hasResolvedTrack()) {
            return false;
        }
        final ItemStack input = items.get(SLOT_INPUT);
        if (input.isEmpty() || !input.is(ModItems.BLANK_DISC.get())) {
            return false;
        }
        if (!items.get(SLOT_OUTPUT).isEmpty()) {
            return false;
        }

        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(), resolvedTrack);
        // バニラの「再生中」状態に乗せる: 曲長に合う無音 jukebox_song を JUKEBOX_PLAYABLE で参照
        // (Amendments の回転・コンパレータ・ホッパー等が機能する。音声は LavaPlayer)。
        // JukeboxPlayable の ctor は EitherHolder 1 引数 (showInTooltip は無い)。
        if (this.level != null) {
            this.level.registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG)
                    .get(SilentSongs.pick(resolvedTrack.durationMs(), resolvedTrack.radio()))
                    .ifPresent(holder -> disc.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(new EitherHolder<>(holder))));
        }

        // 出力を先に埋める: 直後の onContentsChanged→process は「出力が空でない」で早期 return し、
        // input 消費の onContentsChanged が再入して余計な再解決/二重生成を起こすのを防ぐ。
        setItem(SLOT_OUTPUT, disc);
        removeItem(SLOT_INPUT, 1);
        return true;
    }

    public void sync() {
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    /** スロット変更時: 同期 + (server 側で条件が揃えば) 自動生成。出力を先に埋める順序で再入を抑制。 */
    private void onContentsChanged() {
        sync();
        DiscFabrication.process(this);
    }

    // ── persistence (ValueInput/ValueOutput) ──

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items.clear();
        ContainerHelper.loadAllItems(input, items);
        currentUrl = input.getStringOr("url", "");
        resolvedForUrl = input.getStringOr("resolvedForUrl", "");
        this.resolvedTrack = input.read("track", CustomTrackData.CODEC).orElse(CustomTrackData.EMPTY);
        this.resolving = input.getBooleanOr("resolving", false); // 同期タグから (disk には無いので false)
        this.resolveFailed = input.getBooleanOr("resolveFailed", false); // 同期タグから
        this.failureReason = FailureReason.fromName(input.getStringOr("failureReason", "")); // 同期タグから
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
        output.putString("url", currentUrl);
        output.putString("resolvedForUrl", resolvedForUrl);
        if (hasResolvedTrack()) {
            output.store("track", CustomTrackData.CODEC, resolvedTrack);
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        // saveCustomOnly が saveAdditional 経由で CompoundTag を組む。sync 専用フラグ (永続化しない) を上乗せ。
        final CompoundTag tag = saveCustomOnly(registries);
        tag.putBoolean("resolving", resolving); // 解決中表示用
        tag.putBoolean("resolveFailed", resolveFailed); // エラー表示用
        tag.putString("failureReason", failureReason.name()); // 理由別メッセージ用
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ── Container 実装 (hopper 連携の土台。NeoForge は VanillaContainerWrapper で wrap) ──

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
        // hopper/プレイヤーの挿入規則: 入力スロットに空ディスクのみ。出力スロットは挿入不可。
        return slot == SLOT_INPUT && stack.is(ModItems.BLANK_DISC.get());
    }

    @Override
    public void clearContent() {
        items.clear();
    }
}
