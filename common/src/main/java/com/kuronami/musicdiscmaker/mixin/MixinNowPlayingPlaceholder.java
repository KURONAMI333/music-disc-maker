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
 * Now Playing の表示を止める。設定が無効 (既定) ならバニラや他 MOD の分も含めて全部、
 * 有効なら MDM の盤の仮の名前 (「Custom Music Disc」) だけ ({@link NowPlaying#suppresses})。
 */
public abstract class MixinNowPlayingPlaceholder {

    @Inject(method = "setNowPlaying", at = @At("HEAD"), cancellable = true)
    private void mdm$hidePlaceholder(Component text, CallbackInfo ci) {
        if (NowPlaying.suppresses(text)) {
            ci.cancel();
        }
    }
}
