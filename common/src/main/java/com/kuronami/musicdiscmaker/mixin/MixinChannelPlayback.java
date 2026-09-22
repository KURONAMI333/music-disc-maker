package com.kuronami.musicdiscmaker.mixin;

import com.kuronami.musicdiscmaker.client.audio.LavaPlayerAudioStream;
import com.mojang.blaze3d.audio.Channel;
import net.minecraft.client.sounds.AudioStream;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** MDM の共有 PCM だけを実 OpenAL cursor に合わせる。他の音源や vanilla の queue 操作は変えない。 */
@Mixin(Channel.class)
public abstract class MixinChannelPlayback {
    @Shadow @Final private int source;
    @Shadow private AudioStream stream;

    @Inject(method = "attachBufferStream", at = @At("HEAD"))
    private void mdm$attach(AudioStream audio, CallbackInfo ci) {
        if (audio instanceof LavaPlayerAudioStream lava) lava.attachNativeChannel(source);
    }

    @Inject(method = "removeProcessedBuffers", at = @At("RETURN"))
    private void mdm$removed(CallbackInfoReturnable<Integer> cir) {
        if (stream instanceof LavaPlayerAudioStream lava) lava.nativeBuffersProcessed(cir.getReturnValue());
    }

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void mdm$alignBeforePlay(CallbackInfo ci) {
        if (stream instanceof LavaPlayerAudioStream lava && !lava.prepareNativePlay()) {
            lava.rejectNativePlay();
            ci.cancel();
        }
    }
}
