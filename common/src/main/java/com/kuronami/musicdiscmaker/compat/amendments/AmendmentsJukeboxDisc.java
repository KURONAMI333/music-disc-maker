package com.kuronami.musicdiscmaker.compat.amendments;

//? if <1.21.2 {
/*import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.client.DiscDyeTint;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.Material;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;

/^*
 * 1.20.1 / 1.21.1 (render 1 本の BER) での Amendments 互換の盤の描き方。何をするかは {@link AmendmentsCompat}。
 *
 * <p>座標系は Amendments の {@code JukeboxTileRenderer} と同じ: 盤の中心が原点、X 軸 90° 回転後で、
 * 盤は x/y が -0.5..0.5、表は -z (= ワールドの上) を向く。
 ^/
public final class AmendmentsJukeboxDisc {

    private static final Material[] TOP_LAYER_MATERIALS = new Material[AmendmentsCompat.TOP_LAYERS.length];

    static {
        for (int i = 0; i < TOP_LAYER_MATERIALS.length; i++) {
            TOP_LAYER_MATERIALS[i] = new Material(TextureAtlas.LOCATION_BLOCKS,
                    texture(AmendmentsCompat.topLayerTexture(i)));
        }
    }

    private static boolean reflected;
    @Nullable
    private static MethodHandle recordMaterial;

    private AmendmentsJukeboxDisc() {
    }

    /^*
     * Amendments のバニラのジュークボックスの描画から呼ぶ (loader ごとの {@code AmendmentsJukeboxRendererMixin})。
     * pose は Amendments が盤の位置・回転・X 90° を積み終えたもの。MDM の単曲なら描いて true
     * (呼び出し側は Amendments の 1 枚絵を飛ばす)。それ以外は何もせず false。
     ^/
    public static boolean drawOnVanillaJukebox(JukeboxBlockEntity jukebox, PoseStack pose, MultiBufferSource buffers) {
        final ItemStack disc = jukeboxDisc(jukebox);
        if (!AmendmentsCompat.isCustomDisc(disc) || jukebox.getLevel() == null) {
            return false;
        }
        // Amendments と同じく、盤の明るさは 1 つ上のブロックから取る。
        final int light = LevelRenderer.getLightColor(jukebox.getLevel(), jukebox.getBlockPos().above());
        drawCustomDisc(disc, pose, buffers, light);
        return true;
    }

    /^*
     * 盤を {@code y} の高さに描く。pose はブロックの 0..1 空間 (BER / トロッコの中身と同じ) で受け取る。
     ^/
    public static void drawOnTop(ItemStack disc, AmendmentsCompat.Spin spin, boolean playing, Level level,
            float partialTick, float y, PoseStack pose, MultiBufferSource buffers, int light) {
        final float angle = spin.angle(level, playing, partialTick);
        pose.pushPose();
        pose.translate(0.5f, y, 0.5f);
        if (AmendmentsCompat.spinEnabled()) {
            pose.mulPose(Axis.YP.rotationDegrees(angle));
        }
        pose.mulPose(Axis.XP.rotationDegrees(90f));
        drawDisc(disc, pose, buffers, light, level);
        pose.popPose();
    }

    /^*
     * Amendments の盤の座標系で 1 枚描く。MDM の単曲は Amendments の型に合わせたマスクを tint 色で、
     * それ以外は Amendments が作った盤の絵 (バニラのジュークボックスと同じ見た目)。
     * 絵が引けなければアイテム模型。
     ^/
    public static void drawDisc(ItemStack disc, PoseStack pose, MultiBufferSource buffers, int light,
            @Nullable Level level) {
        if (AmendmentsCompat.isCustomDisc(disc)) {
            drawCustomDisc(disc, pose, buffers, light);
            return;
        }
        final Material material = recordMaterial(disc.getItem());
        if (material != null) {
            drawQuad(material.buffer(buffers, RenderType::entityCutout), pose, light, 0xFFFFFF);
            return;
        }
        drawItemModel(disc, pose, buffers, light, level);
    }

    /^*
     * MDM の単曲を Amendments の盤と同じ形で描く。マスクは互いに重ならないので同じ面に並べてよい。
     * 色はアイテムの tint と同じ ({@link DiscDyeTint#color})。染色・曲ごとの自動色がそのまま乗る。
     ^/
    public static void drawCustomDisc(ItemStack disc, PoseStack pose, MultiBufferSource buffers, int light) {
        for (int i = 0; i < TOP_LAYER_MATERIALS.length; i++) {
            final int color = DiscDyeTint.color(disc, AmendmentsCompat.TOP_LAYERS[i]);
            drawQuad(TOP_LAYER_MATERIALS[i].buffer(buffers, RenderType::entityCutout), pose, light, color);
        }
    }

    /^*
     * Amendments の絵が引けない盤の代わり。アイテム模型を平たく描く。模型の表 (south, +z) を -z へ
     * 向けるため Y で 180° 回し、厚み (1px) は Amendments の 1 枚絵に合わせて潰す。
     ^/
    private static void drawItemModel(ItemStack disc, PoseStack pose, MultiBufferSource buffers, int light,
            @Nullable Level level) {
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(180f));
        pose.scale(1f, 1f, 1f / 16f);
        Minecraft.getInstance().getItemRenderer().renderStatic(disc, ItemDisplayContext.NONE, light,
                OverlayTexture.NO_OVERLAY, pose, buffers, level, 0);
        pose.popPose();
    }

    /^* Moonlight の {@code VertexUtil.addQuad} と同じ並び・UV・法線の 1 枚 (表は -z)。 ^/
    private static void drawQuad(VertexConsumer builder, PoseStack pose, int light, int rgb) {
        final PoseStack.Pose last = pose.last();
        vertex(builder, last, -0.5f, 0.5f, 1f, 0f, light, rgb);
        vertex(builder, last, 0.5f, 0.5f, 0f, 0f, light, rgb);
        vertex(builder, last, 0.5f, -0.5f, 0f, 1f, light, rgb);
        vertex(builder, last, -0.5f, -0.5f, 1f, 1f, light, rgb);
    }

*///?}
//? if >=1.21 && <1.21.2 {
/*    private static void vertex(VertexConsumer builder, PoseStack.Pose pose, float x, float y, float u, float v,
            int light, int rgb) {
        builder.addVertex(pose, x, y, 0f)
                .setColor(0xFF000000 | rgb)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, 0f, 0f, -1f);
    }

    private static ResourceLocation texture(String path) {
        return ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, path);
    }

    private static ItemStack jukeboxDisc(JukeboxBlockEntity jukebox) {
        return jukebox.getTheItem();
    }

*///?} elif <1.21 {
/*    private static void vertex(VertexConsumer builder, PoseStack.Pose pose, float x, float y, float u, float v,
            int light, int rgb) {
        builder.vertex(pose.pose(), x, y, 0f)
                .color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 0xFF)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(pose.normal(), 0f, 0f, -1f)
                .endVertex();
    }

    private static ResourceLocation texture(String path) {
        return new ResourceLocation(MusicDiscMaker.MODID, path);
    }

    private static ItemStack jukeboxDisc(JukeboxBlockEntity jukebox) {
        return jukebox.getFirstItem();
    }

*///?}
//? if <1.21.2 {
/*    /^* Amendments が盤ごとに作った絵 ({@code AmendmentsClient#getRecordMaterial})。引けなければ null。 ^/
    @Nullable
    private static Material recordMaterial(Item item) {
        if (!reflected) {
            reflected = true;
            try {
                final Class<?> client = Class.forName("net.mehvahdjukaar.amendments.AmendmentsClient");
                recordMaterial = MethodHandles.publicLookup().findStatic(client, "getRecordMaterial",
                        MethodType.methodType(Material.class, Item.class));
            } catch (Throwable t) {
                MusicDiscMaker.LOGGER.debug("Amendments record textures unavailable; using item models", t);
            }
        }
        final MethodHandle handle = recordMaterial;
        if (handle == null) {
            return null;
        }
        try {
            return (Material) handle.invoke(item);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
*///?}
