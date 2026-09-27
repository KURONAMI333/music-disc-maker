package com.kuronami.musicdiscmaker.compat.curios;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.event.BoomboxCarry;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Curios の型を common へ漏らさず、機能スロットに装備されたブームボックスを読む口。
 *
 * <p>実装は各 loader が Curios の導入を確認してから {@link #install(Provider)} する。未導入時は
 * 空リストを返すため、このクラスを読むだけでは Curios の class loader に触れない。
 * cosmetic stack は loader adapter が列挙せず、ここでも専用 {@value #BOOMBOX_SLOT} slot だけを受ける。
 */
public final class CuriosEquipmentAccess {
    public static final String BOOMBOX_SLOT = "boombox";
    public static final int BOOMBOX_INDEX = 0;

    private static final Provider NONE = player -> List.of();
    private static volatile Provider provider = NONE;

    private CuriosEquipmentAccess() {
    }

    /** Curios の機能スロットを指す。stack は handler が持つ実物の参照で、copy してはいけない。 */
    public record Entry(String slotId, int index, ItemStack stack) {
    }

    @FunctionalInterface
    public interface Provider {
        List<Entry> functional(Player player);
    }

    /** Curios を実際に読み込める loader adapter だけが呼ぶ。 */
    public static void install(Provider implementation) {
        provider = implementation == null ? NONE : implementation;
    }

    /** 専用 boombox slot の機能側だけを列挙する。 */
    public static List<Entry> equipped(Player player) {
        return provider.functional(player).stream()
                .filter(CuriosEquipmentAccess::isBoomboxSlot)
                .filter(entry -> BoomboxCarry.isBoombox(entry.stack()))
                .toList();
    }

    /** wire から来た slot/index を現在の handler に引き直す。 */
    @Nullable
    public static Entry resolve(Player player, String slotId, int index) {
        if (!BOOMBOX_SLOT.equals(slotId) || index != BOOMBOX_INDEX) return null;
        for (final Entry entry : equipped(player)) {
            if (entry.index() == index && entry.slotId().equals(slotId)) return entry;
        }
        return null;
    }

    @Nullable
    public static Entry first(Player player) {
        final List<Entry> entries = equipped(player);
        return entries.isEmpty() ? null : entries.get(0);
    }

    private static boolean isBoomboxSlot(Entry entry) {
        return entry != null && BOOMBOX_SLOT.equals(entry.slotId())
                && entry.index() == BOOMBOX_INDEX && entry.stack() != null;
    }

    static Provider providerForTests() {
        return provider;
    }
}
