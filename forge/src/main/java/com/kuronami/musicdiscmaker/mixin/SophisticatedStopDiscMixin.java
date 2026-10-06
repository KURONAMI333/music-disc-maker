package com.kuronami.musicdiscmaker.mixin;

import com.kuronami.musicdiscmaker.compat.sophisticatedcore.SophisticatedCoreCompatClient;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** SC が登録済み音声を止める前に、未登録の非同期ロードも取り消す。新旧 API 共通。 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.upgrades.jukebox.StopDiscPlaybackMessage", remap = false)
public abstract class SophisticatedStopDiscMixin {

    @Inject(method = "handleMessage", at = @At("HEAD"), require = 0, remap = false)
    private static void mdm$cancelPending(@Coerce Object packet, CallbackInfo ci) {
        SophisticatedCoreCompatClient.invalidateFromPacket(packet);
    }
}
