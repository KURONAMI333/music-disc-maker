package com.kuronami.musicdiscmaker.compat.amendments;

import net.minecraft.world.item.ItemStack;

/**
 * 26.1.2 の Amendments {@code JukeboxRenderState} に足す、MDM の単曲を描くための持ち物
 * ({@code AmendmentsJukeboxRenderStateMixin})。抽出の段で詰め、submit の段で読む。
 * MDM の単曲でなければ盤は空のまま (Amendments がそのまま描く)。
 */
public interface AmendmentsDiscState {

    void musicdiscmaker$setDisc(ItemStack disc, float angle, int light);

    ItemStack musicdiscmaker$disc();

    float musicdiscmaker$angle();

    int musicdiscmaker$light();
}
