package com.kuronami.musicdiscmaker.mixin;

import com.kuronami.musicdiscmaker.client.audio.BoomboxClientPlayback;
import com.kuronami.musicdiscmaker.event.BoomboxCarry;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Boombox の停止時の下げ持ちと、再生時の肩担ぎを手の実位置へ揃える。
 *
 * <p>停止時は vanilla ITEM pose の前曲げを戻し、取っ手を握ったまま下げる。再生時は
 * {@code setupAnim} の最後で肩高へ上げ、item display が本体底面を水平な腕の上面へ揃える。
 */
@Mixin(HumanoidModel.class)
abstract class BoomboxShoulderPoseMixin {

    private static final float SHOULDER_X_ROT = -1.5707964F;
    private static final float SHOULDER_Y_ROT = 0.0F;
    private static final float SHOULDER_Z_ROT = 0.0F;

    @Shadow public ModelPart rightArm;
    @Shadow public ModelPart leftArm;

    @Inject(
            method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V",
            at = @At("TAIL"))
    private void musicDiscMaker$poseBoomboxShoulder(LivingEntity entity, float limbSwing,
            float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch,
            CallbackInfo ci) {
        if (!(entity instanceof AbstractClientPlayer player)) {
            return;
        }
        final boolean mainHandBoombox = BoomboxCarry.isBoombox(player.getMainHandItem());
        final boolean offHandBoombox = BoomboxCarry.isBoombox(player.getOffhandItem());
        final boolean mainHandPlaying = BoomboxClientPlayback.isPlaying(player.getMainHandItem());
        final boolean offHandPlaying = BoomboxClientPlayback.isPlaying(player.getOffhandItem());
        if (player.getMainArm() == HumanoidArm.RIGHT) {
            poseRightArm(mainHandBoombox, mainHandPlaying);
            poseLeftArm(offHandBoombox, offHandPlaying);
        } else {
            poseLeftArm(mainHandBoombox, mainHandPlaying);
            poseRightArm(offHandBoombox, offHandPlaying);
        }
    }

    private void poseRightArm(boolean boombox, boolean playing) {
        if (!boombox) return;
        if (playing) {
            poseRightShoulder();
        } else {
            rightArm.xRot = 0.0F;
            rightArm.yRot = 0.0F;
            rightArm.zRot = 0.0F;
        }
    }

    private void poseLeftArm(boolean boombox, boolean playing) {
        if (!boombox) return;
        if (playing) {
            poseLeftShoulder();
        } else {
            leftArm.xRot = 0.0F;
            leftArm.yRot = 0.0F;
            leftArm.zRot = 0.0F;
        }
    }

    private void poseRightShoulder() {
        rightArm.xRot = SHOULDER_X_ROT;
        rightArm.yRot = SHOULDER_Y_ROT;
        rightArm.zRot = -SHOULDER_Z_ROT;
    }

    private void poseLeftShoulder() {
        leftArm.xRot = SHOULDER_X_ROT;
        leftArm.yRot = -SHOULDER_Y_ROT;
        leftArm.zRot = SHOULDER_Z_ROT;
    }
}
