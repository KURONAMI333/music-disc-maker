package com.kuronami.musicdiscmaker.mixin;

import com.kuronami.musicdiscmaker.client.audio.BoomboxClientPlayback;
import com.kuronami.musicdiscmaker.event.BoomboxCarry;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.entity.state.PlayerRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Boombox の停止時の下げ持ちと、再生時の肩担ぎを手の実位置へ揃える。 */
@Mixin(HumanoidModel.class)
abstract class BoomboxShoulderPoseMixin {

    private static final float SHOULDER_X_ROT = -1.5707964F;
    private static final float SHOULDER_Y_ROT = 0.0F;
    private static final float SHOULDER_Z_ROT = 0.0F;

    @Shadow public ModelPart rightArm;
    @Shadow public ModelPart leftArm;

    @Inject(
            method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/HumanoidRenderState;)V",
            at = @At("TAIL"))
    private void musicDiscMaker$poseBoomboxShoulder(HumanoidRenderState state, CallbackInfo ci) {
        if (!(state instanceof PlayerRenderState playerState)) return;
        final ClientLevel level = Minecraft.getInstance().level;
        if (level == null || !(level.getEntity(playerState.id) instanceof AbstractClientPlayer player)) return;

        // 1.21.4 の render state は手の模型だけを保持し、生の ItemStack は保持しない。
        final ItemStack right = player.getItemHeldByArm(HumanoidArm.RIGHT);
        final ItemStack left = player.getItemHeldByArm(HumanoidArm.LEFT);
        poseRightArm(BoomboxCarry.isBoombox(right), BoomboxClientPlayback.isPlaying(right));
        poseLeftArm(BoomboxCarry.isBoombox(left), BoomboxClientPlayback.isPlaying(left));
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
