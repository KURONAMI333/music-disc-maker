package com.kuronami.musicdiscmaker.menu;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.component.GoldenCartState;
import com.kuronami.musicdiscmaker.component.PlaybackCursor;
import com.kuronami.musicdiscmaker.entity.GoldenJukeboxMinecartEntity;
import com.kuronami.musicdiscmaker.register.ModMenus;

//? if >=1.21 {
import net.minecraft.network.RegistryFriendlyByteBuf;
//?} else {
/*import net.minecraft.network.FriendlyByteBuf;
*///?}
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 金ジューク付きトロッコの menu。媒体スロット 1 枠 + プレイヤーインベントリ。
 * 対象は座標ではなく {@code entityId} で client ctor へ運ぶ (移動 entity は BlockPos で指せない)。
 *
 * <p>再生状態は {@link GoldenCartStatePayload} の server snapshot を正本とする
 * ({@link #applyPlaybackState})。スロット座標は {@link GoldenJukeboxMenu} と同じ
 * (8,18 の媒体枠 + imageHeight=242 の標準式)。
 */
public class GoldenJukeboxCartMenu extends AbstractContainerMenu {

    private final GoldenJukeboxMinecartEntity cart;
    private final int cartEntityId;

    /**
     * client 側が最後に受け取った server snapshot。未受信では
     * {@code null} のまま (画面は「未同期」として非 active にする)。
     */
    @Nullable
    private GoldenCartState playbackState;
    /** server 側の最終送信値 (broadcastChanges の差分抑止)。elapsed は別管理。 */
    @Nullable
    private GoldenCartState lastSentState;
    private long lastStateSentMillis;

    // client 側コンストラクタ (extended MenuType 経由で entityId を受け取る)。
    // NeoForge/Forge は buf をそのまま、Fabric は codec が decode した int を渡す。
    public GoldenJukeboxCartMenu(int containerId, Inventory playerInventory, int entityId) {
        this(containerId, playerInventory, resolve(playerInventory, entityId), entityId);
    }

    //? if >=1.21 {
    public GoldenJukeboxCartMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buf) {
    //?} else {
    /*public GoldenJukeboxCartMenu(int containerId, Inventory playerInventory, FriendlyByteBuf buf) {
    *///?}
        this(containerId, playerInventory, buf.readVarInt());
    }

    // server 側コンストラクタ
    public GoldenJukeboxCartMenu(int containerId, Inventory playerInventory, GoldenJukeboxMinecartEntity cart) {
        this(containerId, playerInventory, cart, cart == null ? -1 : cart.getId());
    }

    private GoldenJukeboxCartMenu(int containerId, Inventory playerInventory,
            @Nullable GoldenJukeboxMinecartEntity cart, int entityId) {
        super(ModMenus.GOLDEN_JUKEBOX_CART.get(), containerId);
        this.cart = cart;
        this.cartEntityId = entityId;

        // 媒体スロット (再生可能ディスクのみ) — GoldenJukeboxMenu と同じ座標
        addSlot(new DiscSlot(cart, GoldenJukeboxMinecartEntity.SLOT_MEDIA, 8, 18));

        // プレイヤーインベントリ 3 行 + ホットバー (GoldenJukeboxMenu の標準式と同じ)
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, 160 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, 8 + col * 18, 218));
        }
    }

    @Nullable
    private static GoldenJukeboxMinecartEntity resolve(Inventory playerInventory, int entityId) {
        final var entity = playerInventory.player.level().getEntity(entityId);
        // 追跡範囲から外れた瞬間の open は失敗させない — menu は立てるが stillValid が false なので
        // 直ちに閉じられる (開いた瞬間に entity が unload されても client が死なない形にする)。
        return entity instanceof GoldenJukeboxMinecartEntity cart ? cart : null;
    }

    /**
     * 対象の cart。client で entity が見つからない / server で除去済みなら {@code null}。
     * (client ctor は entityId 解決に失敗しても menu 自体は組み立てる)
     */
    @Nullable
    public GoldenJukeboxMinecartEntity getEntity() {
        return cart;
    }

    /** この menu が対象とする cart の entity id (client での payload 照合に使う)。 */
    public int cartEntityId() {
        return cartEntityId;
    }

    /** server snapshot の反映 (client)。menu が対象 cart と一致する時だけ呼ばれる。 */
    public void applyPlaybackState(GoldenCartState state) {
        this.playbackState = state;
    }

    // ---- client 表示用の媒体解決 (BoomboxMenu と同じ純 resolver を使う) ----

    private ItemStack resolvedMedium = ItemStack.EMPTY;
    @Nullable private com.kuronami.musicdiscmaker.component.MediaSequenceResolver.ResolvedSequence resolvedSequence;

    /** 媒体スロットの実 stack。cart が未解決な client では EMPTY。 */
    public @NotNull ItemStack medium() {
        if (cart == null) return ItemStack.EMPTY;
        return slots.get(0).hasItem() ? slots.get(0).getItem() : ItemStack.EMPTY;
    }

    private @Nullable com.kuronami.musicdiscmaker.component.MediaSequenceResolver.ResolvedSequence resolveMedium() {
        final ItemStack medium = medium();
        if (!ItemStack.matches(resolvedMedium, medium)) {
            resolvedMedium = medium.copy();
            resolvedSequence = com.kuronami.musicdiscmaker.component.MediaSequenceResolver.resolve(medium);
        }
        return resolvedSequence;
    }

    /** 現在カーソル位置の曲 (位置未確定なら先頭)。client 表示と sendControl の両方で使う。 */
    public @Nullable com.kuronami.musicdiscmaker.component.CustomTrackData currentTrack() {
        final var resolved = resolveMedium();
        if (resolved == null) return null;
        final com.kuronami.musicdiscmaker.component.PlaybackCursor cursor =
                playbackState == null ? null : playbackState.cursor();
        return (cursor != null && cursor.hasPosition()
                ? resolved.at(new com.kuronami.musicdiscmaker.component.MediaSequence.Position(
                        cursor.discIndex(), cursor.trackIndex()))
                : resolved.first())
                .map(com.kuronami.musicdiscmaker.component.MediaSequenceResolver.ResolvedTrack::track)
                .orElse(null);
    }

    public int navigableTrackCount() {
        final var resolved = resolveMedium();
        return resolved == null ? 0 : resolved.tracks().size();
    }

    public boolean hasPlaybackState() {
        // server 側 (menu を持つ側) は実体から直接読めるので常に同期済み扱いにしない。
        return playbackState != null;
    }

    @Nullable
    public GoldenCartState playbackState() {
        return playbackState;
    }

    @Override
    public boolean stillValid(Player player) {
        // バニラ container cart と同じ 8 ブロック (64²)。
        return cart != null && cart.isAlive() && player.distanceToSqr(cart) <= 64.0;
    }

    /**
     * server 側の定期同期。状態が変わった時、および再生中は 1 秒ごとに
     * {@link GoldenCartStatePayload} を開いている client へ届ける
     * (BoomboxMenu#broadcastChanges と同じ抑止規則 — 経過時間だけの変化では撃たない)。
     */
    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (cart == null || !(cart.level() instanceof net.minecraft.server.level.ServerLevel)
                || cart.isRemoved()) {
            return;
        }
        final GoldenCartState state =
                com.kuronami.musicdiscmaker.event.GoldenCartPlayback.stateOf(cart);
        final long now = System.currentTimeMillis();
        if (lastSentState == null || stateChanged(lastSentState, state)
                || (state.cursor().state() == PlaybackCursor.State.PLAYING
                        && now - lastStateSentMillis >= 1_000L)) {
            for (final var player : cart.level().players()) {
                if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                        && serverPlayer.containerMenu == this) {
                    com.kuronami.musicdiscmaker.platform.Services.NETWORK.sendToPlayer(serverPlayer,
                            new com.kuronami.musicdiscmaker.network.GoldenCartStatePayload(
                                    containerId, cartEntityId, state));
                }
            }
            lastSentState = state;
            lastStateSentMillis = now;
        }
    }

    /** elapsedMs 以外の差分だけを見る (再生中の毎 tick 送信を防ぐ)。 */
    private static boolean stateChanged(GoldenCartState a, GoldenCartState b) {
        return !a.cursor().equals(b.cursor()) || a.repeat() != b.repeat()
                || a.shuffle() != b.shuffle() || a.volumePercent() != b.volumePercent()
                || a.rangeBlocks() != b.rangeBlocks() || a.directional() != b.directional();
    }

    @Override
    public @NotNull ItemStack quickMoveStack(Player player, int index) {
        // entityId が解決できなかった client menu の仮 slot は表示同期専用。player inventoryへの
        // 移動も含め、invalid menuからは一切 item を動かさない。
        if (cart == null || index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        final Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        final ItemStack stack = slot.getItem();
        final ItemStack original = stack.copy();

        final int cartSlots = 1;
        final int playerStart = cartSlots;
        final int playerEnd = slots.size();

        if (index < cartSlots) {
            // 媒体スロット → プレイヤー
            if (!moveItemStackTo(stack, playerStart, playerEnd, true)) {
                return ItemStack.EMPTY;
            }
            slot.onQuickCraft(stack, original);
        } else {
            // プレイヤー → 媒体スロット (再生可能媒体のみ)
            if (!moveItemStackTo(stack, GoldenJukeboxMinecartEntity.SLOT_MEDIA,
                    GoldenJukeboxMinecartEntity.SLOT_MEDIA + 1, false)) {
                return ItemStack.EMPTY;
            }
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == original.getCount()) {
            return ItemStack.EMPTY;
        }
        slot.onTake(player, stack);
        return original;
    }

    /** 媒体スロット: entity のスロット規則 (再生可能媒体 1 本) に委譲する。 */
    private static class DiscSlot extends Slot {
        private final GoldenJukeboxMinecartEntity cart;
        private final int cartSlot;

        DiscSlot(@Nullable GoldenJukeboxMinecartEntity cart, int slot, int x, int y) {
            // Slot#getItem/setChangedは必ずContainerへ委譲する。untracked entityでもmenuのslot syncは
            // 走るため、null cartの時だけ bounded placeholderを使う。cart参照自体はnullのまま保持する。
            super(cart == null ? new SimpleContainer(1) : cart, slot, x, y);
            this.cart = cart;
            this.cartSlot = slot;
        }

        @Override
        public boolean mayPlace(@NotNull ItemStack stack) {
            return cart != null && cart.canPlaceItem(cartSlot, stack);
        }

        @Override
        public boolean mayPickup(Player player) {
            return cart != null;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }
    }
}
