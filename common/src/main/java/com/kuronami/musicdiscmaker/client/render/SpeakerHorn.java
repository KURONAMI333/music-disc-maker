package com.kuronami.musicdiscmaker.client.render;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.SpeakerBlock;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.properties.AttachFace;
//? if >=1.21.2 {
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
*///?}

/** Draws the horn elements while leaving the mounting bracket in the static block model. */
final class SpeakerHorn {

    /** Matches the shifted static support models for the two diagonal turns. */
    private static final float DIAGONAL_SUPPORT_SHIFT = 3.75F / 16.0F;

    //? if >=1.21.2 {
    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "textures/block/speaker_brass.png");
    //?} elif >=1.21 {
    /*private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "textures/block/speaker_brass.png");
    *///?} else {
    /*private static final ResourceLocation TEXTURE =
            new ResourceLocation(MusicDiscMaker.MODID, "textures/block/speaker_brass.png");
    *///?}

    private SpeakerHorn() {
    }

    static RenderType renderType() {
        //? if >=1.21.2 {
        return RenderTypes.entitySolid(TEXTURE);
        //?} else {
        /*return RenderType.entitySolid(TEXTURE);
        *///?}
    }

    static SpeakerHornGeometry.Element[] elements(AttachFace face) {
        return face == AttachFace.WALL ? SpeakerHornGeometry.WALL : SpeakerHornGeometry.FLOOR;
    }

    /** Applies the old blockstate transform and turns the horn around its support bearing. */
    static void poseBlock(PoseStack poseStack, AttachFace face, Direction facing, SpeakerBlock.HornTurn turn) {
        float turnYaw = switch (turn) {
            case LEFT -> 45.0F;
            case RIGHT -> -45.0F;
            default -> 0.0F;
        };
        final float floorYaw = switch (facing) {
            case EAST -> 90.0F;
            case SOUTH -> 180.0F;
            case WEST -> 270.0F;
            default -> 0.0F;
        };
        final float modelYaw = face == AttachFace.CEILING ? (floorYaw + 180.0F) % 360.0F : floorYaw;
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(-modelYaw));
        if (face == AttachFace.CEILING) {
            poseStack.mulPose(Axis.XP.rotationDegrees(-180.0F));
            turnYaw = -turnYaw;
        }
        poseStack.translate(-0.5F, -0.5F, -0.5F);
        // Move the complete horn before turning around its bearing.  At 45 degrees the
        // original model crosses a block side by 3.531 units; this gives it a quarter-unit
        // margin and stays aligned with the matching shifted support model.
        if (turnYaw > 0.0F) {
            poseStack.translate(DIAGONAL_SUPPORT_SHIFT, 0.0F, 0.0F);
        } else if (turnYaw < 0.0F) {
            poseStack.translate(-DIAGONAL_SUPPORT_SHIFT, 0.0F, 0.0F);
        }
        final float pivotX = face == AttachFace.WALL
                ? SpeakerHornGeometry.WALL_PIVOT_X : SpeakerHornGeometry.FLOOR_PIVOT_X;
        final float pivotZ = face == AttachFace.WALL
                ? SpeakerHornGeometry.WALL_PIVOT_Z : SpeakerHornGeometry.FLOOR_PIVOT_Z;
        poseStack.translate(pivotX / 16.0F, 0.0F, pivotZ / 16.0F);
        poseStack.mulPose(Axis.YP.rotationDegrees(turnYaw));
        poseStack.translate(-pivotX / 16.0F, 0.0F, -pivotZ / 16.0F);
    }

    static void poseElement(PoseStack poseStack, SpeakerHornGeometry.Element element) {
        final SpeakerHornGeometry.Rotation rotation = element.rotation();
        if (rotation == null) {
            return;
        }
        poseStack.translate(rotation.originX() / 16.0F, rotation.originY() / 16.0F,
                rotation.originZ() / 16.0F);
        switch (rotation.axis()) {
            case 'x' -> poseStack.mulPose(Axis.XP.rotationDegrees(rotation.angle()));
            case 'y' -> poseStack.mulPose(Axis.YP.rotationDegrees(rotation.angle()));
            case 'z' -> poseStack.mulPose(Axis.ZP.rotationDegrees(rotation.angle()));
            default -> throw new IllegalArgumentException("Unknown speaker model rotation axis: " + rotation.axis());
        }
        poseStack.translate(-rotation.originX() / 16.0F, -rotation.originY() / 16.0F,
                -rotation.originZ() / 16.0F);
    }

    static void emitElement(SpeakerHornGeometry.Element element, PoseStack.Pose pose, VertexConsumer buffer,
            int light, int overlay) {
        for (SpeakerHornGeometry.Face face : element.faces()) {
            final float[] corners = corners(element, face.direction());
            final Direction direction = face.direction();
            vertex(pose, buffer, corners[0], corners[1], corners[2], face.u0(), face.v1(), light, overlay,
                    direction);
            vertex(pose, buffer, corners[3], corners[4], corners[5], face.u1(), face.v1(), light, overlay,
                    direction);
            vertex(pose, buffer, corners[6], corners[7], corners[8], face.u1(), face.v0(), light, overlay,
                    direction);
            vertex(pose, buffer, corners[9], corners[10], corners[11], face.u0(), face.v0(), light, overlay,
                    direction);
        }
    }

    private static float[] corners(SpeakerHornGeometry.Element e, Direction direction) {
        return switch (direction) {
            case DOWN -> new float[] {e.x0(), e.y0(), e.z0(), e.x1(), e.y0(), e.z0(),
                    e.x1(), e.y0(), e.z1(), e.x0(), e.y0(), e.z1()};
            case UP -> new float[] {e.x0(), e.y1(), e.z1(), e.x1(), e.y1(), e.z1(),
                    e.x1(), e.y1(), e.z0(), e.x0(), e.y1(), e.z0()};
            case NORTH -> new float[] {e.x1(), e.y0(), e.z0(), e.x0(), e.y0(), e.z0(),
                    e.x0(), e.y1(), e.z0(), e.x1(), e.y1(), e.z0()};
            case SOUTH -> new float[] {e.x0(), e.y0(), e.z1(), e.x1(), e.y0(), e.z1(),
                    e.x1(), e.y1(), e.z1(), e.x0(), e.y1(), e.z1()};
            case WEST -> new float[] {e.x0(), e.y0(), e.z0(), e.x0(), e.y0(), e.z1(),
                    e.x0(), e.y1(), e.z1(), e.x0(), e.y1(), e.z0()};
            case EAST -> new float[] {e.x1(), e.y0(), e.z1(), e.x1(), e.y0(), e.z0(),
                    e.x1(), e.y1(), e.z0(), e.x1(), e.y1(), e.z1()};
        };
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, float x, float y, float z,
            float u, float v, int light, int overlay, Direction normal) {
        //? if >=1.21 {
        buffer.addVertex(pose, x / 16.0F, y / 16.0F, z / 16.0F)
                .setColor(0xFFFFFFFF)
                .setUv(u / 16.0F, v / 16.0F)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, normal.getStepX(), normal.getStepY(), normal.getStepZ());
        //?} else {
        /*buffer.vertex(pose.pose(), x / 16.0F, y / 16.0F, z / 16.0F)
                .color(255, 255, 255, 255)
                .uv(u / 16.0F, v / 16.0F)
                .overlayCoords(overlay)
                .uv2(light)
                .normal(pose.normal(), normal.getStepX(), normal.getStepY(), normal.getStepZ())
                .endVertex();
        *///?}
    }
}
