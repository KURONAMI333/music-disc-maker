package com.kuronami.musicdiscmaker.client.render;

import com.kuronami.musicdiscmaker.block.BoomboxBlock;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.world.level.block.state.BlockState;

/**
 * Applies the placed boombox orientation and handle animation to the separately baked handle model.
 * The handle shape lives at {@code models/block/boombox_handle.json} so a resource pack may replace
 * or remove it without changing animation timing or playback state.
 */
public final class BoomboxHandle {

    private BoomboxHandle() {
    }

    /** blockstate {@code y} rotation in degrees, matching {@code blockstates/boombox.json}. */
    public static float yawOf(BlockState state) {
        if (!state.hasProperty(BoomboxBlock.FACING)) {
            return 0.0F;
        }
        return switch (state.getValue(BoomboxBlock.FACING)) {
            case EAST -> 90.0F;
            case SOUTH -> 180.0F;
            case WEST -> 270.0F;
            default -> 0.0F;
        };
    }

    /** Applies 0 = upright through 1 = fully lowered around the configured model-unit pivot. */
    public static void pose(PoseStack poseStack, float yaw, float fall) {
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(-yaw));
        poseStack.translate(-0.5F, -0.5F, -0.5F);
        if (fall > 0.0F) {
            final DeviceModelPoses.Pose pose = DeviceModelPoses.current();
            poseStack.translate(pose.boomboxPivotX() / 16.0F,
                    pose.boomboxPivotY() / 16.0F, pose.boomboxPivotZ() / 16.0F);
            poseStack.mulPose(Axis.XP.rotationDegrees(pose.boomboxDownAngle() * fall));
            poseStack.translate(-pose.boomboxPivotX() / 16.0F,
                    -pose.boomboxPivotY() / 16.0F, -pose.boomboxPivotZ() / 16.0F);
        }
    }
}
