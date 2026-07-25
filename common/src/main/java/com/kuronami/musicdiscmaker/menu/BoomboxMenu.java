package com.kuronami.musicdiscmaker.menu;

import java.util.UUID;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.event.BoomboxCarry;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModMenus;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * ブームボックス専用の小さい menu。ディスクスロット 1 つ + プレイヤーインベントリだけ。
 *
 * <h2>ブロックでなくアイテムに紐づく</h2>
 * 対象は BlockPos ではなく<b>アイテム個体の UUID</b>。GUI を開いている間にスタックがスロット間を
 * 動いても追随し、インベントリから出たら {@link #stillValid} が false になって閉じる。
 *
 * <p>ディスクスロットの実体は {@link DiscContainer} = 「そのスタックの {@code BOOMBOX_CONTENTS}
 * component を読み書きする 1 スロットの器」。器を別に持たないので、GUI で入れたディスクは即座に
 * アイテムの中身になり、閉じ忘れで消える経路が無い。
 *
 * <p>プレイヤーインベントリの行を出しているのは、出さないとディスクをスロットへ運ぶ手段が
 * 無くなるため (GUI を開くとインベントリ画面は閉じる)。「小さい」はジュークボックスの
 * transport 一式を持ち込まないという意味で、インベントリ行の省略ではない。
 */
public class BoomboxMenu extends AbstractContainerMenu {

    /** ディスクスロットの index。プレイヤーインベントリはこの後ろに並ぶ。 */
    private static final int DISC_SLOT = 0;
    private static final int PLAYER_SLOTS = 36;

    // レイアウト正本 = branding/gen_boombox_gui.py
    private static final int DISC_X = 8;
    private static final int DISC_Y = 18;
    private static final int INV_X = 8;
    private static final int INV_Y = 84;
    private static final int HOTBAR_Y = 142;

    private final Player player;
    private final UUID boomboxId;
    private final DiscContainer disc;

    public BoomboxMenu(int containerId, Inventory playerInventory, UUID boomboxId) {
        super(ModMenus.BOOMBOX.get(), containerId);
        this.player = playerInventory.player;
        this.boomboxId = boomboxId;
        this.disc = new DiscContainer();

        addSlot(new Slot(disc, 0, DISC_X, DISC_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                // custom disc も含めて「ジュークボックスで鳴らせるもの」だけ。ブームボックス自身は
                // この component を持たないので、自分の中に自分を入れる経路は塞がっている。
                return stack.has(DataComponents.JUKEBOX_PLAYABLE);
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });

        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(playerInventory, col + row * 9 + 9,
                        INV_X + col * 18, INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(playerInventory, col, INV_X + col * 18, HOTBAR_Y));
        }
    }

    public UUID getBoomboxId() {
        return boomboxId;
    }

    /** 開いているブームボックスの中身。見つからなければ既定値 (この tick で閉じる)。 */
    public BoomboxContents contents() {
        final ItemStack stack = boombox();
        return stack == null ? BoomboxContents.EMPTY
                : stack.getOrDefault(ModDataComponents.BOOMBOX_CONTENTS.get(), BoomboxContents.EMPTY);
    }

    @Nullable
    private ItemStack boombox() {
        return BoomboxCarry.find(player, boomboxId);
    }

    @Override
    public boolean stillValid(Player p) {
        // 落とした / チェストへ移した / 死んだ = 鳴らなくなる場所へ出たら GUI も閉じる。
        // 判定を BoomboxCarry に寄せてあるので、再生の継続条件と GUI の生存条件がずれない。
        return boombox() != null;
    }

    @Override
    public @NotNull ItemStack quickMoveStack(Player p, int index) {
        final Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        if (index == DISC_SLOT) {
            // ディスクスロットの中身は component の実体なので、その場で shrink させない
            // (component の値は不変として扱う)。写しを動かしてから残りを書き戻す。
            final ItemStack taken = slot.getItem().copy();
            final ItemStack original = taken.copy();
            if (!moveItemStackTo(taken, 1, 1 + PLAYER_SLOTS, true)) {
                return ItemStack.EMPTY;
            }
            disc.setItem(0, taken);
            return original;
        }
        final ItemStack inSlot = slot.getItem();
        final ItemStack original = inSlot.copy();
        if (!moveItemStackTo(inSlot, DISC_SLOT, DISC_SLOT + 1, false)) {
            return ItemStack.EMPTY;
        }
        if (inSlot.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }

    /**
     * ディスクスロットの器。状態を持たず、毎回 component を読み書きする。
     *
     * <p>{@link SimpleContainer} のように中身を抱えると、GUI を閉じた瞬間や menu の再構築で
     * 「器の中のディスク」と「component のディスク」が二重管理になる。読み書きを component 直付けに
     * すれば、その 2 つがずれる状態そのものが存在しない。
     */
    private final class DiscContainer implements Container {

        @Override
        public int getContainerSize() {
            return 1;
        }

        @Override
        public boolean isEmpty() {
            return !contents().hasDisc();
        }

        @Override
        public @NotNull ItemStack getItem(int slot) {
            return contents().disc();
        }

        @Override
        public @NotNull ItemStack removeItem(int slot, int count) {
            if (count <= 0) {
                return ItemStack.EMPTY;
            }
            return removeItemNoUpdate(slot);
        }

        @Override
        public @NotNull ItemStack removeItemNoUpdate(int slot) {
            final ItemStack current = contents().disc();
            if (current.isEmpty()) {
                return ItemStack.EMPTY;
            }
            write(ItemStack.EMPTY);
            return current.copy();
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            write(stack);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public void setChanged() {
            // component へ直接書いているので追加の永続化は不要。
        }

        @Override
        public boolean stillValid(Player p) {
            return BoomboxMenu.this.stillValid(p);
        }

        @Override
        public void clearContent() {
            write(ItemStack.EMPTY);
        }

        private void write(ItemStack value) {
            final ItemStack stack = boombox();
            if (stack == null) {
                return;
            }
            stack.set(ModDataComponents.BOOMBOX_CONTENTS.get(),
                    contents().withDisc(value.copy()));
        }
    }
}
