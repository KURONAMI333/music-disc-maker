package com.kuronami.musicdiscmaker.mixin;

//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sound Physics Remastered にだけ、MDM のカスタム盤を Records 以外として見せる。
 *
 * <p>SPR は {@code update_moving_sounds=false}（既定）のとき Records を環境評価から外す。
 * ここで差し替えるのは SPR が内部に持つ「最後に再生したカテゴリ」だけで、OpenAL の
 * カテゴリは Records のまま。したがって Records の音量スライダーや他 MOD の判定は変えず、
 * SPR の反響・遮蔽だけを MDM の音に通す。SPR が無い環境では {@link Pseudo} により適用されない。</p>
 */
@Pseudo
@Mixin(targets = "com.sonicether.soundphysics.SoundPhysics")
public abstract class SoundPhysicsCategoryMixin {
    @Shadow private static SoundSource lastSoundCategory;

    //? if >=1.21.11 {
    @Shadow private static Identifier lastSound;

    @Inject(method = "setLastSoundCategoryAndName", at = @At("HEAD"), cancellable = true, require = 0)
    private static void mdm$useMusicCategoryForCustomDiscs(SoundSource source, Identifier location,
            CallbackInfo ci) {
        if (source == SoundSource.RECORDS && location != null
                && "music_disc_maker".equals(location.getNamespace())) {
            lastSoundCategory = SoundSource.MUSIC;
            lastSound = location;
            ci.cancel();
        }
    }
    //?} else {
/*    @Shadow private static ResourceLocation lastSound;

    @Inject(method = "setLastSoundCategoryAndName", at = @At("HEAD"), cancellable = true, require = 0)
    private static void mdm$useMusicCategoryForCustomDiscs(SoundSource source, ResourceLocation location,
            CallbackInfo ci) {
        if (source == SoundSource.RECORDS && location != null
                && "music_disc_maker".equals(location.getNamespace())) {
            lastSoundCategory = SoundSource.MUSIC;
            lastSound = location;
            ci.cancel();
        }
    }
*///?}
}
