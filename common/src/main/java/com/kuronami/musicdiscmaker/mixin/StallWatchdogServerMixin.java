package com.kuronami.musicdiscmaker.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.kuronami.musicdiscmaker.diag.StallWatchdog;

import net.minecraft.server.MinecraftServer;

/**
 * <b>一時的な計測器（原因を特定したら {@link StallWatchdog} ごと外す）。</b>
 *
 * <p>server スレッドの心拍。{@code runServer} のループは 1 周ごとに
 * {@code tickServer} → {@code waitUntilNextTick} を呼ぶので、待ち側の HEAD に置けば 1 周 1 拍になる。
 *
 * <p>{@code tickServer} でなく {@code waitUntilNextTick} なのは、{@code IntegratedServer#tickServer} が
 * ポーズ中に {@code super.tickServer} を呼ばないため。{@code tickServer} 側に置くと、シングルプレイで
 * ポーズ画面を開くたびに偽の停止が出る。{@code waitUntilNextTick} はポーズ中も毎周呼ばれる。
 */
@Mixin(MinecraftServer.class)
public class StallWatchdogServerMixin {

    @Inject(method = "waitUntilNextTick()V", at = @At("HEAD"))
    private void musicdiscmaker$stallWatchdogBeat(CallbackInfo ci) {
        StallWatchdog.beatServer();
    }
}
