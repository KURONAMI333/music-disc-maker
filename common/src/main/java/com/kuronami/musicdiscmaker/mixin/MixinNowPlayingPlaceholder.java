package com.kuronami.musicdiscmaker.mixin;

import com.kuronami.musicdiscmaker.client.NowPlaying;

import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//? if >=26.2 {
import net.minecraft.client.gui.Hud;
@Mixin(Hud.class)
//?} else {
/*import net.minecraft.client.gui.Gui;
@Mixin(Gui.class)
*///?}
/**
 * バニラのジュークボックスや他 MOD が MDM の盤の仮の名前 (「Custom Music Disc」) を Now Playing に
 * 出すのを止める。本当の曲名は MDM が設定に従って出す ({@link NowPlaying})。
 */
public abstract class MixinNowPlayingPlaceholder {

    @Inject(method = "setNowPlaying", at = @At("HEAD"), cancellable = true)
    private void mdm$hidePlaceholder(Component text, CallbackInfo ci) {
        if (NowPlaying.isPlaceholder(text)) {
            ci.cancel();
        }
    }
}
