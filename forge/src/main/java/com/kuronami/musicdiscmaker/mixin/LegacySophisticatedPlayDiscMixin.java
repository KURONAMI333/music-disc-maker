package com.kuronami.musicdiscmaker.mixin;

import com.kuronami.musicdiscmaker.compat.sophisticatedcore.LegacySophisticatedCoreCompat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** @Shadow や SC 型を持たず、API 導入前の custom disc 固定音源だけを抑止する。 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.upgrades.jukebox.PlayDiscMessage", remap = false)
public abstract class LegacySophisticatedPlayDiscMixin {

    @Inject(method = "handleMessage", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void mdm$skipLegacySilentDisc(@Coerce Object packet, CallbackInfo ci) {
        if (LegacySophisticatedCoreCompat.suppressNativePlay(packet)) {
            ci.cancel();
        } else {
            // 通常盤への置換は、新旧SCとも未完了のMDMロードを取り消す。
            com.kuronami.musicdiscmaker.compat.sophisticatedcore.SophisticatedCoreCompatClient.invalidateFromPacket(packet);
        }
    }
}
