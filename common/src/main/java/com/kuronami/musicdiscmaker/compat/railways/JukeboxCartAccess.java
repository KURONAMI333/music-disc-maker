package com.kuronami.musicdiscmaker.compat.railways;

import net.minecraft.world.item.ItemStack;

/**
 * Create: Steam 'n' Rails のジュークボックス付きトロッコ ({@code MinecartJukebox}) へ mixin で生やす口。
 * Steam 'n' Rails の型を MDM から参照しないために、盤と MDM 側の再生状態だけをこの形で読む。
 */
public interface JukeboxCartAccess {

    /** トロッコに入っている盤 (無ければ空)。 */
    ItemStack musicdiscmaker$cartDisc();

    /** このトロッコの MDM 再生状態。entity と同じ寿命で、読み込み直しでは保存値から戻る。 */
    ExternalCartPlayback.State musicdiscmaker$cartState();
}
