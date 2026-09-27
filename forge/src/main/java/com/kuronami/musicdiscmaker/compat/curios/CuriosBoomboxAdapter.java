package com.kuronami.musicdiscmaker.compat.curios;

import java.util.ArrayList;
import java.util.List;

import top.theillusivec4.curios.api.CuriosApi;

/**
 * Forge 1.20.1 Curios API ({@code LazyOptional}) を common の中立装備参照へ変換する薄い adapter。
 * この class は Curios 導入時だけロードされる (呼び出し側が {@code isModLoaded("curios")} で守る)。
 */
public final class CuriosBoomboxAdapter {
    private CuriosBoomboxAdapter() {
    }

    public static void install() {
        CuriosEquipmentAccess.install(player -> CuriosApi.getCuriosInventory(player)
                .resolve()
                .map(handler -> {
                    final List<CuriosEquipmentAccess.Entry> result = new ArrayList<>();
                    handler.getCurios().forEach((slotId, slots) -> {
                        if (!CuriosEquipmentAccess.BOOMBOX_SLOT.equals(slotId)) return;
                        // cosmetic handler は列挙しない。機能側の実 ItemStack 参照だけを返す。
                        final var functional = slots.getStacks();
                        for (int index = 0; index < functional.getSlots(); index++) {
                            result.add(new CuriosEquipmentAccess.Entry(
                                    slotId, index, functional.getStackInSlot(index)));
                        }
                    });
                    return List.copyOf(result);
                })
                .orElseGet(List::of));
    }
}
