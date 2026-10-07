package com.kuronami.musicdiscmaker.mixin;

//? if >=1.21.2 {
//?} else {
/*import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.compat.railways.ExternalCartPlayback;
import com.kuronami.musicdiscmaker.compat.railways.JukeboxCartAccess;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.AbstractMinecart;

/^*
 * 他 MOD のジュークボックス付きトロッコ ({@link JukeboxCartAccess} を持つもの) に入った MDM の盤を鳴らす。
 *
 * <p>Steam 'n' Rails の {@code MinecartJukebox} は {@code tick} / 保存 / 読込の最初で親を呼び、間の
 * {@code MinecartBlock} はどれも上書きしないので、ここ (バニラの {@link AbstractMinecart}) を必ず通る。
 * 他のトロッコでは {@code instanceof} で素通りする。
 ^/
@Mixin(AbstractMinecart.class)
public abstract class AbstractMinecartJukeboxCartMixin {

    @Unique
    private static final String MUSICDISCMAKER$KEY = "MusicDiscMakerCartTrack";

    @Unique
    private static final String MUSICDISCMAKER$OFFSET = "MusicDiscMakerCartOffsetMs";

    @Inject(method = "tick", at = @At("HEAD"))
    private void musicdiscmaker$tickJukeboxCart(CallbackInfo ci) {
        if (!(this instanceof JukeboxCartAccess cart)) {
            return;
        }
        final Entity self = (Entity) (Object) this;
        if (!(self.level() instanceof ServerLevel level)) {
            return;
        }
        try {
            ExternalCartPlayback.tick(self, level, cart.musicdiscmaker$cartDisc(), cart.musicdiscmaker$cartState());
        } catch (final RuntimeException e) {
            // fail-soft: 相手のトロッコの内部が変わっても tick を落とさない (無音に退化)。
            MusicDiscMaker.LOGGER.debug("Jukebox cart streaming hook skipped", e);
        }
    }

    @Inject(method = "addAdditionalSaveData", at = @At("RETURN"))
    private void musicdiscmaker$saveJukeboxCart(CompoundTag tag, CallbackInfo ci) {
        if (this instanceof JukeboxCartAccess cart) {
            final ExternalCartPlayback.State state = cart.musicdiscmaker$cartState();
            final String key = state.savedKey();
            if (key != null) {
                tag.putString(MUSICDISCMAKER$KEY, key);
                tag.putLong(MUSICDISCMAKER$OFFSET, state.savedOffsetMs((Entity) (Object) this));
            }
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("RETURN"))
    private void musicdiscmaker$loadJukeboxCart(CompoundTag tag, CallbackInfo ci) {
        if (this instanceof JukeboxCartAccess cart && tag.contains(MUSICDISCMAKER$KEY)) {
            cart.musicdiscmaker$cartState().restore(tag.getString(MUSICDISCMAKER$KEY),
                    tag.getLong(MUSICDISCMAKER$OFFSET));
        }
    }
}

*///?}
