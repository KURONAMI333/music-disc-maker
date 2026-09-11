package com.kuronami.musicdiscmaker.mixin;
import com.kuronami.musicdiscmaker.client.audio.ClientLevelAudioBridge;
import com.kuronami.musicdiscmaker.client.audio.VanillaJukeboxAudioAccess;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Minecraft;
import com.kuronami.musicdiscmaker.component.PlaybackClockAccess;
import com.kuronami.musicdiscmaker.component.PauseAwarePlaybackClock;
import org.spongepowered.asm.mixin.Mixin;
//? if >=1.21.2 {
import net.minecraft.client.renderer.LevelEventHandler;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
//?}
@Mixin(ClientLevel.class)
public abstract class MixinClientLevelAudioBridge implements ClientLevelAudioBridge, PlaybackClockAccess {
    @Override
    public long mdm$playbackTimeMs() {
        final var server = Minecraft.getInstance().getSingleplayerServer();
        return server instanceof PlaybackClockAccess clock
                ? clock.mdm$playbackTimeMs() : PauseAwarePlaybackClock.realTimeMs();
    }

    //? if >=1.21.2 {
    @Shadow @Final private LevelEventHandler levelEventHandler;
    //?}
    @Override
    public VanillaJukeboxAudioAccess mdm$jukeboxAudio() {
        //? if >=1.21.2 {
        return (VanillaJukeboxAudioAccess) levelEventHandler;
        //?} else {
        /*return null;
        *///?}
    }
}
