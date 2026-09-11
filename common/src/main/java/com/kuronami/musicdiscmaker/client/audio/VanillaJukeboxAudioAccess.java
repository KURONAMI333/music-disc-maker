package com.kuronami.musicdiscmaker.client.audio;
import net.minecraft.core.BlockPos;
/** Stops only the vanilla voice at one block, preserving unrelated records and game events. */
public interface VanillaJukeboxAudioAccess {
    void mdm$stopVanillaRecord(BlockPos pos);
}
