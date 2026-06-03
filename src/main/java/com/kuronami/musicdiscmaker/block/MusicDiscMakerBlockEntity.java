package com.kuronami.musicdiscmaker.block;

import org.jetbrains.annotations.NotNull;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.register.ModBlockEntities;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.ItemStackHandler;

/**
 * Music Disc Maker ブロックの BlockEntity。
 * 入力(空ディスク)/出力(custom disc) スロットと、解決済みの曲メタ情報を持つ。
 * URL 解決は server 側で行われ、結果がここに保存される (server authoritative)。
 */
public class MusicDiscMakerBlockEntity extends BlockEntity {

    public static final int SLOT_INPUT = 0;
    public static final int SLOT_OUTPUT = 1;

    private final ItemStackHandler inventory = new ItemStackHandler(2) {
        @Override
        protected void onContentsChanged(int slot) {
            MusicDiscMakerBlockEntity.this.sync();
            // 空ディスク投入で条件が揃えば自動生成 (server 側のみ DiscFabrication 内で判定)
            DiscFabrication.process(MusicDiscMakerBlockEntity.this);
        }

        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            return slot == SLOT_INPUT && stack.is(ModItems.BLANK_DISC.get());
        }
    };

    private String currentUrl = "";
    private CustomTrackData resolvedTrack = CustomTrackData.EMPTY;
    /** {@link #resolvedTrack} を生成した元 URL。同一 URL の複数ディスクを完全一致させるためのキャッシュ鍵。 */
    private String resolvedForUrl = "";
    private boolean resolving = false;
    /** 直近の解決が失敗したか (GUI のエラー表示用)。同期のみ・永続化しない。 */
    private boolean resolveFailed = false;

    public MusicDiscMakerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.MUSIC_DISC_MAKER.get(), pos, state);
    }

    public ItemStackHandler getInventory() {
        return inventory;
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

    public void setResolveFailed(boolean failed) {
        this.resolveFailed = failed;
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
        final ItemStack input = inventory.getStackInSlot(SLOT_INPUT);
        if (input.isEmpty() || !input.is(ModItems.BLANK_DISC.get())) {
            return false;
        }
        if (!inventory.getStackInSlot(SLOT_OUTPUT).isEmpty()) {
            return false;
        }

        final ItemStack disc = new ItemStack(ModItems.CUSTOM_MUSIC_DISC.get());
        disc.set(ModDataComponents.CUSTOM_TRACK.get(), resolvedTrack);

        // 出力を先に埋める: 直後の onContentsChanged→process は「出力が空でない」で早期 return し、
        // input 消費の onContentsChanged が再入して余計な再解決/二重生成を起こすのを防ぐ。
        inventory.setStackInSlot(SLOT_OUTPUT, disc);
        inventory.extractItem(SLOT_INPUT, 1, false);
        return true;
    }

    public void sync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        inventory.deserializeNBT(registries, tag.getCompound("inventory"));
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
        this.resolving = tag.getBoolean("resolving"); // 同期タグから (disk には無いので false)
        this.resolveFailed = tag.getBoolean("resolveFailed"); // 同期タグから
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("inventory", inventory.serializeNBT(registries));
        tag.putString("url", currentUrl);
        tag.putString("resolvedForUrl", resolvedForUrl);
        if (hasResolvedTrack()) {
            CustomTrackData.CODEC
                    .encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, resolvedTrack)
                    .result()
                    .ifPresent(encoded -> tag.put("track", encoded));
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        final CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        tag.putBoolean("resolving", resolving); // 解決中表示用 (同期のみ・永続化しない)
        tag.putBoolean("resolveFailed", resolveFailed); // エラー表示用 (同期のみ・永続化しない)
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
