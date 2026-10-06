package com.kuronami.musicdiscmaker.mixin;

import java.util.UUID;

import com.kuronami.musicdiscmaker.compat.sophisticatedcore.LegacySophisticatedCoreCompat;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 旧 SC の完全 descriptor のみ。新 SC の同名 lambda は引数が異なる。 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.upgrades.jukebox.JukeboxUpgradeWrapper", remap = false)
public abstract class LegacySophisticatedJukeboxMixin {

    @Inject(
            method = "lambda$playDisc$0(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/Level;Ljava/util/UUID;)V",
            at = @At("TAIL"), require = 0, remap = false)
    private void mdm$sendLegacyTrack(ServerLevel serverLevel, Level playingLevel, UUID storageUuid, CallbackInfo ci) {
        LegacySophisticatedCoreCompat.afterPlay(this, serverLevel, playingLevel, storageUuid);
    }
}
