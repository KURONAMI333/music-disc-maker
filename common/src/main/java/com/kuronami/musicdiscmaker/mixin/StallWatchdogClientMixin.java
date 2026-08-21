package com.kuronami.musicdiscmaker.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.kuronami.musicdiscmaker.diag.StallWatchdog;

import net.minecraft.client.Minecraft;

/**
 * <b>一時的な計測器（原因を特定したら {@link StallWatchdog} ごと外す）。</b>
 *
 * <p>client のメインスレッド（Render thread）の心拍。{@code Minecraft#run()} のループは
 * 毎フレーム {@code runTick} を呼ぶので、ここが止まっている間はレンダリングもティックも止まっている。
 * ポーズ画面・ロード画面でも呼ばれるため、ティックイベントより取りこぼしが少ない。
 */
@Mixin(Minecraft.class)
public class StallWatchdogClientMixin {

    @Inject(method = "runTick(Z)V", at = @At("HEAD"))
    private void musicdiscmaker$stallWatchdogBeat(boolean renderLevel, CallbackInfo ci) {
        StallWatchdog.beatClient();
    }
}
