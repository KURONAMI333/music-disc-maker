package com.kuronami.musicdiscmaker.mixin;

import java.util.function.BooleanSupplier;
import com.kuronami.musicdiscmaker.component.PauseAwarePlaybackClock;
import com.kuronami.musicdiscmaker.component.PlaybackClockAccess;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IntegratedServer.class)
public abstract class IntegratedServerPlaybackClockMixin implements PlaybackClockAccess {
    @Shadow private boolean paused;
    @Unique private final PauseAwarePlaybackClock mdm$playbackClock = new PauseAwarePlaybackClock();

    @Inject(method = "tickServer", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/server/IntegratedServer;paused:Z",
            opcode = 181, shift = At.Shift.AFTER))
    private void mdm$updatePlaybackPause(BooleanSupplier haveTime, CallbackInfo ci) {
        mdm$playbackClock.setPaused(paused);
    }

    @Override
    public long mdm$playbackTimeMs() {
        return mdm$playbackClock.timeMs();
    }
}
