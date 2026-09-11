package com.kuronami.musicdiscmaker.mixin;
import java.util.Map;
import com.kuronami.musicdiscmaker.client.audio.VanillaJukeboxAudioAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
//? if >=1.21.2 {
import net.minecraft.client.renderer.LevelEventHandler;
@Mixin(LevelEventHandler.class)
//?} else {
/*import net.minecraft.client.renderer.LevelRenderer;
@Mixin(LevelRenderer.class)
*///?}
public abstract class MixinVanillaJukeboxAudio implements VanillaJukeboxAudioAccess {
    //? if >=1.21 {
    @Shadow @Final private Map<BlockPos, SoundInstance> playingJukeboxSongs;
    //?} else {
    /*@Shadow @Final private Map<BlockPos, SoundInstance> playingRecords;
    *///?}
    @Override
    public void mdm$stopVanillaRecord(BlockPos pos) {
        //? if >=1.21 {
        final SoundInstance previous = playingJukeboxSongs.remove(pos);
        //?} else {
        /*final SoundInstance previous = playingRecords.remove(pos);
        *///?}
        if (previous != null) Minecraft.getInstance().getSoundManager().stop(previous);
    }
}
