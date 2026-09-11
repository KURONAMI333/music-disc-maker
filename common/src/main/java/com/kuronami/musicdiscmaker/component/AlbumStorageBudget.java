package com.kuronami.musicdiscmaker.component;

import java.util.List;

import io.netty.buffer.Unpooled;
//? if >=1.21 {
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
//?} else {
/*import net.minecraft.network.FriendlyByteBuf;
*///?}
import net.minecraft.world.item.ItemStack;

/** Albumへ新たに収納する値の、実同期ItemStackに対する製品上の予算。 */
public final class AlbumStorageBudget {

    /**
     * 挿入を受け入れる ItemStack 同期値の上限。Album component 内部とForge NBT decoderの2 MiBより
     * 十分小さくし、container packetの外側と将来追加されるcomponentのための余白を残す。
     */
    public static final int INSERT_WIRE_BYTE_BUDGET = 1024 * 1024;

    /** 初回menu同期は既存データを開く経路なので、2 MiB packet枠のうち512 KiBをheader等に残す。 */
    public static final int INITIAL_MENU_WIRE_BYTE_BUDGET = 1536 * 1024;

    private AlbumStorageBudget() {
    }

    //? if >=1.21 {
    /** 実際の DataComponent 同期 codec で、AlbumまたはBoombox ItemStack全体を事前検証する。 */
    public static boolean fits(ItemStack stack, RegistryAccess registryAccess) {
        final RegistryFriendlyByteBuf wire = new RegistryFriendlyByteBuf(
                Unpooled.buffer(256, INSERT_WIRE_BYTE_BUDGET), registryAccess);
        try {
            ItemStack.OPTIONAL_STREAM_CODEC.encode(wire, stack);
            final ItemStack restored = ItemStack.OPTIONAL_STREAM_CODEC.decode(wire);
            return wire.readableBytes() == 0 && ItemStack.matches(stack, restored);
        } catch (RuntimeException rejected) {
            return false;
        } finally {
            wire.release();
        }
    }

    /**
     * Album menu が同期する各slotとcursorの ItemStack codec 合計を検証する。
     * packet headerはここへ含めず、合計を1 MiBに留めてその分の余白を確保する。
     */
    public static boolean fitsMenuSync(List<ItemStack> slots, ItemStack carried, RegistryAccess registryAccess) {
        return fitsMenuSync(slots, carried, registryAccess, INSERT_WIRE_BYTE_BUDGET);
    }

    /** 初回openで送る全slotとcursorを、packet外側の余白を残して往復検証する。 */
    public static boolean fitsInitialMenuSync(List<ItemStack> slots, ItemStack carried, RegistryAccess registryAccess) {
        return fitsMenuSync(slots, carried, registryAccess, INITIAL_MENU_WIRE_BYTE_BUDGET);
    }

    private static boolean fitsMenuSync(List<ItemStack> slots, ItemStack carried, RegistryAccess registryAccess,
            int byteBudget) {
        final RegistryFriendlyByteBuf wire = new RegistryFriendlyByteBuf(
                Unpooled.buffer(256, byteBudget), registryAccess);
        try {
            for (final ItemStack stack : slots) ItemStack.OPTIONAL_STREAM_CODEC.encode(wire, stack);
            ItemStack.OPTIONAL_STREAM_CODEC.encode(wire, carried);
            for (final ItemStack stack : slots) {
                if (!ItemStack.matches(stack, ItemStack.OPTIONAL_STREAM_CODEC.decode(wire))) return false;
            }
            return ItemStack.matches(carried, ItemStack.OPTIONAL_STREAM_CODEC.decode(wire))
                    && wire.readableBytes() == 0;
        } catch (RuntimeException rejected) {
            return false;
        } finally {
            wire.release();
        }
    }
    //?} else {
    /*/^** Forge 1.20.1 の実際の NBT ItemStack 同期 codec で事前検証する。 ^/
    public static boolean fits(ItemStack stack) {
        final FriendlyByteBuf wire = new FriendlyByteBuf(Unpooled.buffer(256, INSERT_WIRE_BYTE_BUDGET));
        try {
            wire.writeItem(stack);
            return ItemStack.matches(stack, wire.readItem()) && wire.readableBytes() == 0;
        } catch (RuntimeException rejected) {
            return false;
        } finally {
            wire.release();
        }
    }

    /^** Forge 1.20.1 のmenu同期を構成する全slotとcursorを、実Nbt codecで往復して検証する。 ^/
    public static boolean fitsMenuSync(List<ItemStack> slots, ItemStack carried) {
        return fitsMenuSync(slots, carried, INSERT_WIRE_BYTE_BUDGET);
    }

    /^** Forge 1.20.1の初回menu同期を、2 MiB packet枠の余白を残して往復検証する。 ^/
    public static boolean fitsInitialMenuSync(List<ItemStack> slots, ItemStack carried) {
        return fitsMenuSync(slots, carried, INITIAL_MENU_WIRE_BYTE_BUDGET);
    }

    private static boolean fitsMenuSync(List<ItemStack> slots, ItemStack carried, int byteBudget) {
        final FriendlyByteBuf wire = new FriendlyByteBuf(Unpooled.buffer(256, byteBudget));
        try {
            for (final ItemStack stack : slots) wire.writeItem(stack);
            wire.writeItem(carried);
            for (int i = 0; i <= slots.size(); i++) wire.readItem();
            return wire.readableBytes() == 0;
        } catch (RuntimeException rejected) {
            return false;
        } finally {
            wire.release();
        }
    }
    *///?}
}
