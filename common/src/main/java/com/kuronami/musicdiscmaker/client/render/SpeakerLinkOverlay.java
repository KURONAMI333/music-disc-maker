package com.kuronami.musicdiscmaker.client.render;

import java.util.LinkedHashSet;
import java.util.Set;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import com.kuronami.musicdiscmaker.block.SpeakerBlockEntity;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * スピーカーのリンクを持っている間だけ、繋がっている先をブロックの輪郭で示す client 描画。
 *
 * <p>リンクは NBT と component の中にしか無く、置いた本人でも「どれがどこに繋がっているか」を
 * 見る手段が無かった。描くのは輪郭だけ — 光らせない・線を引かない・パーティクルも出さない。
 *
 * <p>出す条件は<b>スピーカーのブロックアイテムを手に持っている間</b>だけ。常時出すと、
 * 拠点にスピーカーを並べた時に輪郭だらけになる。
 */
public final class SpeakerLinkOverlay {

    /** 音源 (強化版ジュークボックス) の輪郭。Golden Jukebox のアクセント (206,168,68)。 */
    private static final float SOURCE_R = 206 / 255.0F;
    private static final float SOURCE_G = 168 / 255.0F;
    private static final float SOURCE_B = 68 / 255.0F;
    /** 狙っているスピーカー側の輪郭。音源と役割が違うので彩度を落とす。 */
    private static final float SPEAKER_R = 0.72F;
    private static final float SPEAKER_G = 0.74F;
    private static final float SPEAKER_B = 0.78F;
    private static final float ALPHA = 0.65F;
    /** 面と z-fight しないよう、ブロックの外へ少しだけ膨らませる。 */
    private static final double INFLATE = 0.002;

    private SpeakerLinkOverlay() {
    }

    /**
     * 輪郭を描く。呼び出し側 (各ローダーの level render フック) は camera 相対の PoseStack と
     * 行 buffer を渡すこと。何も描かない場合は PoseStack を触らない。
     */
    public static void render(PoseStack poseStack, MultiBufferSource buffers, Vec3 camera) {
        final Minecraft mc = Minecraft.getInstance();
        final Level level = mc.level;
        if (level == null || mc.player == null || !holdingSpeaker(mc)) {
            return;
        }

        // 手に持っているスピーカーが覚えている音源。
        final Set<BlockPos> sources = new LinkedHashSet<>();
        final Set<BlockPos> speakers = new LinkedHashSet<>();
        for (final InteractionHand hand : InteractionHand.values()) {
            final GlobalPos link = linkOf(mc.player.getItemInHand(hand));
            if (link != null && link.dimension().equals(level.dimension())) {
                sources.add(link.pos());
            }
        }

        // 狙っている先が設置済みのスピーカーなら、そのスピーカーとリンク相手も出す
        // (「これはどこに繋がっている？」を持ち替えずに確かめられる)。
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                && level.getBlockEntity(hit.getBlockPos()) instanceof SpeakerBlockEntity speaker) {
            final BlockPos source = speaker.getSourcePos();
            if (source != null) {
                speakers.add(hit.getBlockPos());
                sources.add(source);
            }
        }

        if (sources.isEmpty() && speakers.isEmpty()) {
            return;
        }

        final VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        poseStack.pushPose();
        poseStack.translate(-camera.x, -camera.y, -camera.z);
        for (final BlockPos pos : speakers) {
            outline(poseStack, lines, pos, SPEAKER_R, SPEAKER_G, SPEAKER_B);
        }
        for (final BlockPos pos : sources) {
            outline(poseStack, lines, pos, SOURCE_R, SOURCE_G, SOURCE_B);
        }
        poseStack.popPose();
    }

    private static void outline(PoseStack poseStack, VertexConsumer lines, BlockPos pos,
            float red, float green, float blue) {
        LevelRenderer.renderLineBox(poseStack, lines, new AABB(pos).inflate(INFLATE),
                red, green, blue, ALPHA);
    }

    private static boolean holdingSpeaker(Minecraft mc) {
        for (final InteractionHand hand : InteractionHand.values()) {
            if (mc.player.getItemInHand(hand).is(ModItems.SPEAKER.get())) {
                return true;
            }
        }
        return false;
    }

    private static GlobalPos linkOf(ItemStack stack) {
        return stack.is(ModItems.SPEAKER.get())
                ? stack.get(ModDataComponents.SPEAKER_SOURCE.get())
                : null;
    }
}
