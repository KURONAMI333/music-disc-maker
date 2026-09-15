package com.kuronami.musicdiscmaker.client.render;

import java.util.Optional;

import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.item.SpeakerItem;
import com.kuronami.musicdiscmaker.speaker.SpeakerLink;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
//? if >=1.21.11 {
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
//?} else {
/*import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.RenderType;
*///?}

/** Linked Golden source shown by a solid, raised twelve-edge selection frame. */
public final class SpeakerLinkOutlineRenderer {
    private static final int COLOR = 0xFFB8FF2E;

    private SpeakerLinkOutlineRenderer() {}

    //? if >=1.21.11 {
    /** submit-node経路。debugQuadsの通常depthを使うので遮蔽物越しには描かれない。 */
    public static void submitModern(PoseStack poseStack, SubmitNodeCollector collector, Vec3 camera) {
        target().ifPresent(pos -> withTargetPose(poseStack, camera, pos, () ->
                collector.submitCustomGeometry(poseStack, RenderTypes.debugQuads(), (pose, buffer) -> emitBands(pose, buffer, pos))));
    }
    //? if <26.1 {
    /*public static void renderIntermediate(PoseStack poseStack, VertexConsumer quads, Vec3 camera) {
        target().ifPresent(pos -> withTargetPose(poseStack, camera, pos, () -> emitBands(poseStack.last(), quads, pos)));
    }

    // NeoForge 1.21.11 stage event has no context-owned consumer.
    public static void renderIntermediateWithSharedBuffer(PoseStack poseStack, Vec3 camera) {
        target().ifPresent(pos -> {
            final var buffers = Minecraft.getInstance().renderBuffers().bufferSource();
            final VertexConsumer quads = buffers.getBuffer(RenderTypes.debugQuads());
            withTargetPose(poseStack, camera, pos, () -> emitBands(poseStack.last(), quads, pos));
            buffers.endBatch(RenderTypes.debugQuads());
        });
    }
    *///?}
    //?} else {
    /*public static void renderLegacy(PoseStack poseStack) {
        target().ifPresent(pos -> {
            final Minecraft minecraft = Minecraft.getInstance();
            final Vec3 camera = minecraft.gameRenderer.getMainCamera().getPosition();
            final VertexConsumer quads = minecraft.renderBuffers().bufferSource().getBuffer(RenderType.debugQuads());
            withTargetPose(poseStack, camera, pos, () -> emitBands(poseStack.last(), quads, pos));
            minecraft.renderBuffers().bufferSource().endBatch(RenderType.debugQuads());
        });
    }
    *///?}

    private static void withTargetPose(PoseStack poseStack, Vec3 camera, BlockPos pos, Runnable draw) {
        poseStack.pushPose();
        poseStack.translate(pos.getX() - camera.x(), pos.getY() - camera.y(), pos.getZ() - camera.z());
        draw.run();
        poseStack.popPose();
    }

    private static void emitBands(PoseStack.Pose pose, VertexConsumer buffer, BlockPos pos) {
        final Level level = Minecraft.getInstance().level;
        if (level == null) return;
        for (var rail : SpeakerLinkFrame.rails(
                adjoining(level, pos, Direction.WEST), adjoining(level, pos, Direction.EAST),
                adjoining(level, pos, Direction.DOWN), adjoining(level, pos, Direction.UP),
                adjoining(level, pos, Direction.NORTH), adjoining(level, pos, Direction.SOUTH))) {
            box(pose, buffer, rail.x0(), rail.y0(), rail.z0(), rail.x1(), rail.y1(), rail.z1());
        }
    }

    private static boolean adjoining(Level level, BlockPos source, Direction direction) {
        final BlockPos neighbor = source.relative(direction);
        if (!level.isLoaded(neighbor)) return false;
        final var shape = level.getBlockState(neighbor).getShape(level, neighbor);
        if (shape.isEmpty()) return false;
        // Only shapes reaching the shared plane can bury a rail. A bottom slab below
        // the source, for example, leaves a gap and needs no inset.
        return switch (direction) {
            case DOWN -> shape.max(Direction.Axis.Y) >= 1 - SpeakerLinkFrame.GAP;
            case UP -> shape.min(Direction.Axis.Y) <= SpeakerLinkFrame.GAP;
            case WEST -> shape.max(Direction.Axis.X) >= 1 - SpeakerLinkFrame.GAP;
            case EAST -> shape.min(Direction.Axis.X) <= SpeakerLinkFrame.GAP;
            case NORTH -> shape.max(Direction.Axis.Z) >= 1 - SpeakerLinkFrame.GAP;
            case SOUTH -> shape.min(Direction.Axis.Z) <= SpeakerLinkFrame.GAP;
        };
    }

    private static void box(PoseStack.Pose pose, VertexConsumer buffer,
            float x0, float y0, float z0, float x1, float y1, float z1) {
        // The debug render type is unlit; explicit face shading makes the square section readable.
        quad(pose, buffer, 0.58F, x0,y0,z0, x1,y0,z0, x1,y0,z1, x0,y0,z1);
        quad(pose, buffer, 1.00F, x0,y1,z1, x1,y1,z1, x1,y1,z0, x0,y1,z0);
        quad(pose, buffer, 0.84F, x1,y0,z0, x0,y0,z0, x0,y1,z0, x1,y1,z0);
        quad(pose, buffer, 0.84F, x0,y0,z1, x1,y0,z1, x1,y1,z1, x0,y1,z1);
        quad(pose, buffer, 0.70F, x0,y0,z0, x0,y0,z1, x0,y1,z1, x0,y1,z0);
        quad(pose, buffer, 0.70F, x1,y0,z1, x1,y0,z0, x1,y1,z0, x1,y1,z1);
    }

    private static void quad(PoseStack.Pose pose, VertexConsumer buffer, float shade,
            float x0, float y0, float z0, float x1, float y1, float z1,
            float x2, float y2, float z2, float x3, float y3, float z3) {
        final int color = 0xFF000000
                | Math.round(((COLOR >>> 16) & 255) * shade) << 16
                | Math.round(((COLOR >>> 8) & 255) * shade) << 8
                | Math.round((COLOR & 255) * shade);
        vertex(pose, buffer, x0, y0, z0, color);
        vertex(pose, buffer, x1, y1, z1, color);
        vertex(pose, buffer, x2, y2, z2, color);
        vertex(pose, buffer, x3, y3, z3, color);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, float x, float y, float z, int color) {
        //? if >=1.21 {
        buffer.addVertex(pose, x, y, z).setColor(color);
        //?} else {
        /*buffer.vertex(pose.pose(), x, y, z).color(
                ((color >>> 16) & 255) / 255.0F, ((color >>> 8) & 255) / 255.0F,
                (color & 255) / 255.0F, 1.0F).endVertex();
        *///?}
    }

    private static Optional<BlockPos> target() {
        final Minecraft minecraft = Minecraft.getInstance();
        final Player player = minecraft.player;
        final Level level = minecraft.level;
        if (player == null || level == null) return Optional.empty();
        final Optional<SpeakerLink> link = linkOf(player.getMainHandItem()).or(() -> linkOf(player.getOffhandItem()));
        if (link.isEmpty() || !sameDimension(link.get(), level)) return Optional.empty();
        final BlockPos pos = BlockPos.of(link.get().packedPos());
        // 未ロードchunkへ getBlockEntity で触れない。
        return level.isLoaded(pos) && level.getBlockEntity(pos) instanceof GoldenJukeboxBlockEntity ? Optional.of(pos) : Optional.empty();
    }

    private static Optional<SpeakerLink> linkOf(ItemStack stack) {
        return stack.getItem() instanceof SpeakerItem ? SpeakerItem.linkOf(stack) : Optional.empty();
    }

    private static boolean sameDimension(SpeakerLink link, Level level) {
        //? if >=1.21.2 {
        return link.dimensionId().equals(level.dimension().identifier().toString());
        //?} else {
        /*return link.dimensionId().equals(level.dimension().location().toString());
        *///?}
    }
}
