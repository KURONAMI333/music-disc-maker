package com.kuronami.musicdiscmaker.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.kuronami.musicdiscmaker.event.JukeboxHandler;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;

/**
 * jukebox の中身変化を1点で捕捉し、custom disc のストリーミング再生を起動/停止する。
 *
 * <p>{@code setTheItem} は ContainerSingleItem の setItem (右クリック挿入)・ホッパー・コマンドの
 * すべてが通る一本道 (26.1.2 vanilla jar で確認済み)。ワールド読込は item 直代入で setTheItem を
 * 通らないため、読込での誤発火は起きない。
 */
@Mixin(JukeboxBlockEntity.class)
public abstract class JukeboxBlockEntityMixin {

    @Inject(method = "setTheItem", at = @At("TAIL"))
    private void musicdiscmaker$onSetItem(ItemStack item, CallbackInfo ci) {
        final BlockEntity self = (BlockEntity) (Object) this;
        final Level level = self.getLevel();
        if (level != null) {
            JukeboxHandler.onContentChanged(level, self.getBlockPos(), item);
        }
    }
}
