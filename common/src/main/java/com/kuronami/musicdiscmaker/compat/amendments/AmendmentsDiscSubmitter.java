package com.kuronami.musicdiscmaker.compat.amendments;

//? if >=26.1 && <26.2 {
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
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.sprite.SpriteId;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/^*
 * 26.1.2 (抽出 → submit の BER) での Amendments 互換の盤の描き方。何をするかは {@link AmendmentsCompat}。
 *
 * <p>Amendments 26.1.2 の {@code JukeboxTileRenderer#submit} と同じく、盤 1 枚を
 * {@code submitCustomGeometry} (blocks atlas の entityCutout) で出す。座標系も同じ: 盤の中心が原点、
 * X 軸 90° 回転後で、盤は x/y が -0.5..0.5、表は -z (= ワールドの上) を向く。
 ^/
public final class AmendmentsDiscSubmitter {

    private static final SpriteId[] TOP_LAYER_SPRITES = new SpriteId[AmendmentsCompat.TOP_LAYERS.length];

    static {
        for (int i = 0; i < TOP_LAYER_SPRITES.length; i++) {
            TOP_LAYER_SPRITES[i] = new SpriteId(TextureAtlas.LOCATION_BLOCKS,
                    Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, AmendmentsCompat.topLayerTexture(i)));
        }
    }

    private static boolean reflected;
    @Nullable
    private static MethodHandle recordSprite;

    private AmendmentsDiscSubmitter() {
    }

    /^*
     * 盤を {@code y} の高さに出す。pose はブロックの 0..1 空間 (BER / トロッコの中身と同じ) で受け取る。
     * {@code angle} は抽出の段で数えた角度 ({@link AmendmentsCompat.Spin})。
     ^/
    public static void submitOnTop(ItemStack disc, float angle, float y, PoseStack pose,
            SubmitNodeCollector collector, int light) {
        pose.pushPose();
        pose.translate(0.5f, y, 0.5f);
        if (AmendmentsCompat.spinEnabled()) {
            pose.mulPose(Axis.YP.rotationDegrees(angle));
        }
        pose.mulPose(Axis.XP.rotationDegrees(90f));
        submitDisc(disc, pose, collector, light);
        pose.popPose();
    }

    /^*
     * Amendments の盤の座標系で 1 枚出す。MDM の単曲は Amendments の型に合わせたマスクを tint 色で、
     * それ以外は Amendments が作った盤の絵。絵が引けない盤は出さない。
     ^/
    public static void submitDisc(ItemStack disc, PoseStack pose, SubmitNodeCollector collector, int light) {
        if (AmendmentsCompat.isCustomDisc(disc)) {
            submitCustomDisc(disc, pose, collector, light);
            return;
        }
        final SpriteId recordId = recordSprite(disc.getItem());
        if (recordId == null) {
            return;
        }
        final TextureAtlasSprite sprite = Minecraft.getInstance().getAtlasManager().get(recordId);
        collector.submitCustomGeometry(pose, recordId.renderType(RenderTypes::entityCutout),
                (last, buffer) -> quad(last, sprite.wrap(buffer), light, 0xFFFFFF));
    }

    /^*
     * MDM の単曲を Amendments の盤と同じ形で出す。マスクは互いに重ならないので同じ面に並べてよい。
     * 色はアイテムの tint と同じ ({@link DiscDyeTint#color})。色は抽出の時点の stack から引く。
     ^/
    public static void submitCustomDisc(ItemStack disc, PoseStack pose, SubmitNodeCollector collector, int light) {
        final TextureAtlasSprite[] sprites = new TextureAtlasSprite[TOP_LAYER_SPRITES.length];
        final int[] colors = new int[TOP_LAYER_SPRITES.length];
        for (int i = 0; i < sprites.length; i++) {
            sprites[i] = Minecraft.getInstance().getAtlasManager().get(TOP_LAYER_SPRITES[i]);
            colors[i] = DiscDyeTint.color(disc, AmendmentsCompat.TOP_LAYERS[i]);
        }
        collector.submitCustomGeometry(pose, RenderTypes.entityCutout(TextureAtlas.LOCATION_BLOCKS), (last, buffer) -> {
            for (int i = 0; i < sprites.length; i++) {
                quad(last, sprites[i].wrap(buffer), light, colors[i]);
            }
        });
    }

    /^* Amendments の {@code addDisc} と同じ並び・UV・法線の 1 枚 (表は -z)。 ^/
    private static void quad(PoseStack.Pose pose, VertexConsumer builder, int light, int rgb) {
        vertex(pose, builder, -0.5f, 0.5f, 1f, 0f, light, rgb);
        vertex(pose, builder, 0.5f, 0.5f, 0f, 0f, light, rgb);
        vertex(pose, builder, 0.5f, -0.5f, 0f, 1f, light, rgb);
        vertex(pose, builder, -0.5f, -0.5f, 1f, 1f, light, rgb);
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer builder, float x, float y, float u, float v,
            int light, int rgb) {
        builder.addVertex(pose, x, y, 0f);
        builder.setColor(0xFF000000 | rgb);
        builder.setUv(u, v);
        builder.setOverlay(OverlayTexture.NO_OVERLAY);
        builder.setLight(light);
        builder.setNormal(pose, 0f, 0f, -1f);
    }

    /^* Amendments が盤ごとに作った絵 ({@code AmendmentsClient#getRecordMaterial})。引けなければ null。 ^/
    @Nullable
    private static SpriteId recordSprite(Item item) {
        if (!reflected) {
            reflected = true;
            try {
                final Class<?> client = Class.forName("net.mehvahdjukaar.amendments.AmendmentsClient");
                recordSprite = MethodHandles.publicLookup().findStatic(client, "getRecordMaterial",
                        MethodType.methodType(SpriteId.class, Item.class));
            } catch (Throwable t) {
                MusicDiscMaker.LOGGER.debug("Amendments record sprites unavailable", t);
            }
        }
        final MethodHandle handle = recordSprite;
        if (handle == null) {
            return null;
        }
        try {
            return (SpriteId) handle.invoke(item);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
*///?}
