package com.kuronami.musicdiscmaker.client.render;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.BoomboxBlock;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

//? if >=1.21.2 {
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
*///?}
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * ブームボックスの取っ手を、根元の蝶番まわりに回して描く。<b>ここが「取っ手だけ動く」の実体。</b>
 *
 * <h2>なぜ JSON では足りないか</h2>
 * バニラの block model の element 回転は ±45 度 (22.5 度刻み) しか持てないので、90 度倒す動きを
 * 静的な JSON で表せない。<b>倒れ切った姿勢そのものは軸に沿った箱として書ける</b>ので、手に持つ
 * 見た目 ({@code models/item/boombox_playing.json}) は JSON 側で足りている。コードが要るのは
 * <b>途中の角度</b>、つまり倒れる動きだけ。
 *
 * <h2>蝶番の位置</h2>
 * 支柱の<b>後ろ下の角</b> (y = {@value #PIVOT_Y} / z = {@value #PIVOT_Z}) を軸にして後ろへ倒す。
 * 支柱の断面の中心を軸にすると、倒れ切ったときに取っ手の半分が本体天面へ埋まる。
 * 角を軸にすると天面の上にきれいに寝る (実物の Sharp GF-9494 が同じ収まり方をしている)。
 *
 * <h2>ブロックの模型からは取っ手を抜いてある</h2>
 * {@code models/block/boombox.json} は取っ手を持たない {@code item/boombox_body} を親にしている。
 * <b>ここが登録されていない成果物では、取っ手の無いブームボックスが静かに出る</b> — 例外も
 * ログも出ないので、帯ごとの登録漏れは jar の中身ではなく登録経路の側で数える。
 */
public final class BoomboxHandle {

    /** 蝶番の高さ (本体天面)。 */
    public static final float PIVOT_Y = 11.0F;
    /** 蝶番の奥行き (支柱の後面)。 */
    public static final float PIVOT_Z = 9.0F;

    /** 倒れ切ったときの角度 (度)。90 = 天面へ完全に寝る。 */
    public static final float DOWN_DEGREES = 90.0F;

    //? if >=1.21.2 {
    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "textures/item/boombox_3d.png");
    //?} elif >=1.21 {
    /*private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID, "textures/item/boombox_3d.png");
    *///?} else {
    /*private static final ResourceLocation TEXTURE =
            new ResourceLocation(MusicDiscMaker.MODID, "textures/item/boombox_3d.png");
    *///?}

    /**
     * 取っ手を構成する 3 つの箱。座標も UV も {@code models/item/boombox_off.json} の
     * element そのままで、<b>形は 1 テクセルも動かしていない</b>。
     *
     * <p>支柱の {@code down} 面だけ模型に無いものを足してある。立っている間は本体天面と同一平面で
     * 裏を向くので見えないが、倒すと南を向いて露出するため、無いと穴が開く。UV は支柱側面の
     * パッチ (57,0)-(59,3) の一様な銀から、1 モデル単位 = 1 テクセルで切り出した。
     */
    private static final Box[] BOXES = {
            // 左の支柱
            new Box(1, 11, 7, 2, 14, 9)
                    .face(Direction.NORTH, 14.75F, 0.0F, 15.0F, 0.75F)
                    .face(Direction.SOUTH, 14.75F, 0.0F, 15.0F, 0.75F)
                    .face(Direction.WEST, 14.25F, 0.0F, 14.75F, 0.75F)
                    .face(Direction.EAST, 14.25F, 0.0F, 14.75F, 0.75F)
                    .face(Direction.DOWN, 14.25F, 0.0F, 14.5F, 0.5F),
            // 右の支柱
            new Box(14, 11, 7, 15, 14, 9)
                    .face(Direction.NORTH, 14.75F, 0.0F, 15.0F, 0.75F)
                    .face(Direction.SOUTH, 14.75F, 0.0F, 15.0F, 0.75F)
                    .face(Direction.WEST, 14.25F, 0.0F, 14.75F, 0.75F)
                    .face(Direction.EAST, 14.25F, 0.0F, 14.75F, 0.75F)
                    .face(Direction.DOWN, 14.25F, 0.0F, 14.5F, 0.5F),
            // 横棒
            new Box(1, 13, 7, 15, 14, 9)
                    .face(Direction.NORTH, 8.0F, 2.25F, 11.5F, 2.5F)
                    .face(Direction.SOUTH, 8.0F, 2.25F, 11.5F, 2.5F)
                    .face(Direction.UP, 8.0F, 2.5F, 11.5F, 3.0F)
                    .face(Direction.DOWN, 8.0F, 3.0F, 11.5F, 3.5F)
                    .face(Direction.WEST, 11.5F, 2.25F, 12.0F, 2.5F)
                    .face(Direction.EAST, 11.5F, 2.25F, 12.0F, 2.5F),
    };

    private BoomboxHandle() {
    }

    /** 取っ手を描く RenderType。ブロック地図帳ではなく素のテクスチャを直接張る。 */
    public static RenderType renderType() {
        //? if >=1.21.2 {
        return RenderTypes.entitySolid(TEXTURE);
        //?} else {
        /*return RenderType.entitySolid(TEXTURE);
        *///?}
    }

    /**
     * blockstate の {@code y} 回転 (度)。{@code blockstates/boombox.json} の値と一致させる。
     *
     * @param state 設置されているブロックの状態
     * @return 0 / 90 / 180 / 270
     */
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

    /**
     * 取っ手を描く姿勢を積む。呼び元が {@code pushPose} / {@code popPose} を持つ。
     *
     * @param poseStack 変換行列
     * @param yaw       blockstate の {@code y} 回転 (度)
     * @param fall      0 = 立つ / 1 = 倒れる
     */
    public static void pose(PoseStack poseStack, float yaw, float fall) {
        // blockstate の y 回転はブロック中心まわりで、行列としては -y 度に当たる (BlockModelRotation と同じ)
        poseStack.translate(0.5F, 0.5F, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(-yaw));
        poseStack.translate(-0.5F, -0.5F, -0.5F);
        if (fall > 0.0F) {
            poseStack.translate(0.0F, PIVOT_Y / 16.0F, PIVOT_Z / 16.0F);
            poseStack.mulPose(Axis.XP.rotationDegrees(DOWN_DEGREES * fall));
            poseStack.translate(0.0F, -PIVOT_Y / 16.0F, -PIVOT_Z / 16.0F);
        }
    }

    /**
     * 取っ手の面をすべて流し込む。
     *
     * @param pose    いまの変換
     * @param buffer  流し込み先
     * @param light   packed light
     * @param overlay packed overlay
     */
    public static void emit(PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay) {
        for (final Box box : BOXES) {
            box.emit(pose, buffer, light, overlay);
        }
    }

    /** 1 つの箱。座標はモデル単位 (0..16)、UV もモデル単位 (0..16)。 */
    private static final class Box {

        private final float x0;
        private final float y0;
        private final float z0;
        private final float x1;
        private final float y1;
        private final float z1;
        /** {@link Direction} の宣言順 (ordinal) 引き。null = その面を描かない。 */
        private final float[][] uvs = new float[6][];

        private Box(float x0, float y0, float z0, float x1, float y1, float z1) {
            this.x0 = x0;
            this.y0 = y0;
            this.z0 = z0;
            this.x1 = x1;
            this.y1 = y1;
            this.z1 = z1;
        }

        private Box face(Direction direction, float u1, float v1, float u2, float v2) {
            uvs[direction.ordinal()] = new float[] {u1, v1, u2, v2};
            return this;
        }

        private void emit(PoseStack.Pose pose, VertexConsumer buffer, int light, int overlay) {
            for (final Direction direction : Direction.values()) {
                final float[] uv = uvs[direction.ordinal()];
                if (uv == null) {
                    continue;
                }
                final float[] corners = corners(direction);
                final float nx = direction.getStepX();
                final float ny = direction.getStepY();
                final float nz = direction.getStepZ();
                // 4 頂点を (u1,v2) (u2,v2) (u2,v1) (u1,v1) の順で当てる。取っ手のパッチは
                // ほぼ単色なので、面内の向きの取り違えは絵に出ない。
                vertex(pose, buffer, corners[0], corners[1], corners[2], uv[0], uv[3], light, overlay, nx, ny, nz);
                vertex(pose, buffer, corners[3], corners[4], corners[5], uv[2], uv[3], light, overlay, nx, ny, nz);
                vertex(pose, buffer, corners[6], corners[7], corners[8], uv[2], uv[1], light, overlay, nx, ny, nz);
                vertex(pose, buffer, corners[9], corners[10], corners[11], uv[0], uv[1], light, overlay, nx, ny, nz);
            }
        }

        /** 外から見て反時計回りになる 4 頂点 (x,y,z を 4 組)。 */
        private float[] corners(Direction direction) {
            return switch (direction) {
                case DOWN -> new float[] {x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1};
                case UP -> new float[] {x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0};
                case NORTH -> new float[] {x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0};
                case SOUTH -> new float[] {x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1};
                case WEST -> new float[] {x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0};
                case EAST -> new float[] {x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1};
            };
        }
    }

    private static void vertex(PoseStack.Pose pose, VertexConsumer buffer, float x, float y, float z,
            float u, float v, int light, int overlay, float nx, float ny, float nz) {
        //? if >=1.21 {
        buffer.addVertex(pose, x / 16.0F, y / 16.0F, z / 16.0F)
                .setColor(0xFFFFFFFF)
                .setUv(u / 16.0F, v / 16.0F)
                .setOverlay(overlay)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
        //?} else {
        /*buffer.vertex(pose.pose(), x / 16.0F, y / 16.0F, z / 16.0F)
                .color(255, 255, 255, 255)
                .uv(u / 16.0F, v / 16.0F)
                .overlayCoords(overlay)
                .uv2(light)
                .normal(pose.normal(), nx, ny, nz)
                .endVertex();
        *///?}
    }
}
