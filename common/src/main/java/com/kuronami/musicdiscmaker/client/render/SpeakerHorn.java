package com.kuronami.musicdiscmaker.client.render;

import com.kuronami.musicdiscmaker.block.SpeakerBlock;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.AttachFace;

/** Applies the block orientation and fixed ±45-degree turn to a resource-pack horn model. */
final class SpeakerHorn {

    private SpeakerHorn() {
    }

    static DevicePartModels.Part model(AttachFace face) {
        return face == AttachFace.WALL
                ? DevicePartModels.Part.SPEAKER_HORN_WALL
                : DevicePartModels.Part.SPEAKER_HORN_FLOOR;
    }

    /**
     * Applies the same transform as the old Java horn geometry. The model file supplies only the
     * horn shape; gameplay facing and left/right semantics remain owned by the block state.
     */
    static void poseBlock(PoseStack poseStack, AttachFace face, Direction facing, SpeakerBlock.HornTurn turn) {
        float turnYaw = switch (turn) {
            case LEFT -> 45.0F;
            case RIGHT -> -45.0F;
            case CENTER -> 0.0F;
        };
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - facing.toYRot()));
        if (face == AttachFace.CEILING) {
            poseStack.mulPose(Axis.ZP.rotationDegrees(180.0F));
            turnYaw = -turnYaw;
        }
        poseStack.translate(-0.5F, -0.5F, -0.5F);

        final DeviceModelPoses.Pose pose = DeviceModelPoses.current();
        final float shift = pose.speakerDiagonalShift() / 16.0F;
        if (turnYaw > 0.0F) {
            poseStack.translate(shift, 0.0F, 0.0F);
        } else if (turnYaw < 0.0F) {
            poseStack.translate(-shift, 0.0F, 0.0F);
        }
        final float pivotX = face == AttachFace.WALL
                ? pose.speakerWallPivotX() : pose.speakerFloorPivotX();
        final float pivotZ = face == AttachFace.WALL
                ? pose.speakerWallPivotZ() : pose.speakerFloorPivotZ();
        poseStack.translate(pivotX / 16.0F, 0.0F, pivotZ / 16.0F);
        poseStack.mulPose(Axis.YP.rotationDegrees(turnYaw));
        poseStack.translate(-pivotX / 16.0F, 0.0F, -pivotZ / 16.0F);
    }
}
