package com.kuronami.musicdiscmaker.block;

//? if >=1.21 && <1.21.2 {
/*import com.kuronami.musicdiscmaker.MusicDiscMaker;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
*///?}

/**
 * 1.21.1 系向けのスロット単位耐性付きコンテナ保存。
 *
 * <p>vanilla の {@code ContainerHelper.saveAllItems} は 1 枚でも component が
 * {@code RegistryFixedCodec} 系の unbound holder（例: 細工/他 MOD 由来の Direct holder を持つ
 * {@code jukebox_playable}）で失敗すると例外で全体を中断し、呼び出し元の BlockEntity の
 * 保存自体が丸ごと失われる。こちらは失敗したスロットだけを落とし、残りを必ず書き出す。
 */
public final class SafeItemSaver {

    private SafeItemSaver() {
    }

    //? if >=1.21 && <1.21.2 {
    /*public static void saveAllItems(CompoundTag tag, NonNullList<ItemStack> items,
            HolderLookup.Provider registries) {
        final ListTag list = new ListTag();
        for (int i = 0; i < items.size(); i++) {
            final ItemStack stack = items.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            try {
                final CompoundTag slotTag = new CompoundTag();
                slotTag.putByte("Slot", (byte) i);
                list.add(stack.save(registries, slotTag));
            } catch (RuntimeException saveFailure) {
                MusicDiscMaker.LOGGER.warn("block entity slot {} holds an unserializable item {}; "
                        + "dropping only that slot from saved state", i, stack.getItem(), saveFailure);
            }
        }
        tag.put("Items", list);
    }
    *///?}
}
