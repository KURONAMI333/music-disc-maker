package com.kuronami.musicdiscmaker.mixin;

//? if >=1.21.2 {
//?} else {
/*import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import com.kuronami.musicdiscmaker.compat.railways.ExternalCartPlayback;
import com.kuronami.musicdiscmaker.compat.railways.JukeboxCartAccess;

import net.minecraft.world.item.ItemStack;

/^*
 * (非公式) Create: Steam 'n' Rails のジュークボックス付きトロッコに、盤の読み口と MDM の再生状態を持たせる。
 *
 * <p>処理そのものは {@link AbstractMinecartJukeboxCartMixin} が持つ。相手のトロッコで処理したい
 * {@code tick} / 保存 / 読込はバニラの上書きで、本番では loader ごとに名前が難読化される
 * (@Pseudo の対象は refmap で引けない)。そこで相手の型には mixin 名の付かない自前フィールド
 * {@code disc} の読み口だけを足し、フックはバニラの {@code AbstractMinecart} 側に置く。
 *
 * <p>安全性: {@code @Pseudo} で Steam 'n' Rails 非導入環境では適用されない。{@code disc} は
 * {@code MinecartJukebox} 直下のフィールド (1.20.1 Forge/Fabric 1.7.3・NeoForge 1.21.1 0.3.0-beta.2 で確認)。
 ^/
@Pseudo
@Mixin(targets = "com.railwayteam.railways.content.minecarts.MinecartJukebox", remap = false)
public abstract class RailwaysJukeboxCartMixin implements JukeboxCartAccess {

    @Shadow(remap = false)
    private ItemStack disc;

    @Unique
    private final ExternalCartPlayback.State musicdiscmaker$state = new ExternalCartPlayback.State();

    @Override
    public ItemStack musicdiscmaker$cartDisc() {
        return this.disc == null ? ItemStack.EMPTY : this.disc;
    }

    @Override
    public ExternalCartPlayback.State musicdiscmaker$cartState() {
        return this.musicdiscmaker$state;
    }
}

*///?}
