package com.kuronami.musicdiscmaker.mixin;

import com.kuronami.musicdiscmaker.client.audio.ClientSoundReloadRecovery;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** resource 再読み込みで vanilla が OpenAL channel を全破棄・再作成した後に MDM voice を再開する。 */
@Mixin(SoundEngine.class)
public abstract class MixinSoundEngineReload {
    @Inject(method = "reload", at = @At("RETURN"))
    private void mdm$recoverAfterReload(CallbackInfo ci) {
        ClientSoundReloadRecovery.afterSoundEngineReload();
    }
}
