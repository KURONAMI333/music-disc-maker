package com.kuronami.musicdiscmaker.mixin;

//? if >=26.1 && <26.2 {
/*import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

import com.kuronami.musicdiscmaker.compat.amendments.AmendmentsDiscState;

import net.minecraft.world.item.ItemStack;

/^*
 * (非公式) Amendments 26.1.2 の {@code JukeboxRenderState} に、MDM の単曲を描くための持ち物を足す
 * ({@link AmendmentsDiscState})。使うのは {@code AmendmentsJukeboxSubmitMixin}。
 *
 * <p>安全性: {@code @Pseudo} なので Amendments 非導入環境では適用されない。足すのは自前のフィールドだけで、
 * Amendments 側には触れない。
 ^/
@Pseudo
@Mixin(targets = "net.mehvahdjukaar.amendments.client.renderers.JukeboxRenderState", remap = false)
public abstract class AmendmentsJukeboxRenderStateMixin implements AmendmentsDiscState {

    @Unique
    private ItemStack musicdiscmaker$disc = ItemStack.EMPTY;
    @Unique
    private float musicdiscmaker$angle;
    @Unique
    private int musicdiscmaker$light;

    @Override
    public void musicdiscmaker$setDisc(ItemStack disc, float angle, int light) {
        this.musicdiscmaker$disc = disc;
        this.musicdiscmaker$angle = angle;
        this.musicdiscmaker$light = light;
    }

    @Override
    public ItemStack musicdiscmaker$disc() {
        return this.musicdiscmaker$disc == null ? ItemStack.EMPTY : this.musicdiscmaker$disc;
    }

    @Override
    public float musicdiscmaker$angle() {
        return this.musicdiscmaker$angle;
    }

    @Override
    public int musicdiscmaker$light() {
        return this.musicdiscmaker$light;
    }
}
*///?}
