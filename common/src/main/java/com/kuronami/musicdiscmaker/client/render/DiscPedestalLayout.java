package com.kuronami.musicdiscmaker.client.render;

import com.kuronami.musicdiscmaker.block.DiscPedestalBlock;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.List;

/**
 * 台座の盤と曲名の<b>置き場所と姿勢</b>。帯で描き方の骨格が違う 2 系統の BER が、この 1 つを共有する。
 *
 * <h2>座標系</h2>
 * すべての数値は<b>ブロックの左下手前の角を原点とするブロック単位</b> (0.0〜1.0) で書いてある。
 * {@link #poseDisc} / {@link #poseLabel} が先頭で {@code translate(0.5, y, 0.5)} を掛けるので、
 * そこから先の {@code z} は<b>ブロック中心からの前後</b>で、正面 (blockstate の facing 側) が正。
 * ブロック中心から前面までは 0.5 しかないので、{@code z} に 0.5 以上を置くと隣のブロックへはみ出す。
 *
 * <p>曲名は台座下部の木製面に固定し、カメラへ追従させない。
 *
 * <p><b>数値は全部仮</b>。実機で見るまで確定しない (見た目は 設計の確認帯)。
 */
public final class DiscPedestalLayout {

    /** 盤の中心の高さ。 */
    public static final float DISC_Y = 7.5F / 16.0F;

    /** 盤の前後位置 (中心からのずれ)。0 = ブロックの中心線。 */
    public static final float DISC_Z = 0.4F / 16.0F;

    /** 盤の傾き (度)。<b>負で上を向く</b> = 手前に倒れて盤面が斜め上を向く。 */
    public static final float DISC_TILT_DEG = -22.5F;

    /**
     * 盤の拡大率。custom_music_disc の {@code fixed} 変換は scale {@code [1, 1, 1]} で、
     * ItemRenderer も FIXED に一律の縮小を足さない。盤の模型座標へ直接掛かる倍率であり、
     * 透明な余白を除いた可視寸法は個別の盤モデル・texture alpha に従う。
     */
    public static final float DISC_SCALE = 0.75F;

    /** 台座下部の木製面に置く文字の上端。 */
    public static final float LABEL_Y = 2.85F / 16.0F;
    public static final float LABEL_Z = 5.01F / 16.0F;

    /** 木製面の幅へ収める文字倍率。 */
    public static final float LABEL_SCALE = 0.008F;

    /** 曲名とアーティスト名を各1行へ収める文字幅。 */
    public static final int LABEL_MAX_WIDTH = 88;
    public static final int LABEL_LINE_HEIGHT = 11;

    /** 収まらない文字列の末尾に付ける記号。 */
    public static final String LABEL_ELLIPSIS = "...";

    /** 曲名を出す距離 (ブロック) の 2 乗。バニラの看板の縁取りと同じ 16 ブロック。 */
    public static final int LABEL_RANGE_SQR = Mth.square(16);

    /** 曲名の色 (ARGB)。 */
    public static final int LABEL_COLOR = 0xFF342A19;

    /** 背景の四角は描かず、模型の木肌に直接文字を描く。 */
    public static final int LABEL_BACKGROUND = 0;

    private DiscPedestalLayout() {
    }

    /** blockstate の facing を y 回転 (度) に直す。回した後のローカル +Z が正面になる。 */
    public static float yawOf(BlockState state) {
        return -state.getValue(DiscPedestalBlock.FACING).toYRot();
    }

    /** 盤を置く姿勢へ {@link PoseStack} を進める。呼ぶ側が push / pop する。 */
    public static void poseDisc(PoseStack poseStack, float yaw) {
        poseStack.translate(0.5F, DISC_Y, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
        poseStack.translate(0.0F, 0.0F, DISC_Z);
        poseStack.mulPose(Axis.XP.rotationDegrees(DISC_TILT_DEG));
        poseStack.scale(DISC_SCALE, DISC_SCALE, DISC_SCALE);
        // Visible disc pixels span x=0..15; FIXED's Y rotation puts their center half a pixel to the right.
        poseStack.translate(-0.5F / 16.0F, 0.0F, 0.0F);
    }

    /**
     * 曲名を置く姿勢へ {@link PoseStack} を進める。呼ぶ側が push / pop する。
     *
     * <p>{@code y} を反転しているのはフォントの座標が下向きだから (看板と同じ)。
     */
    public static void poseLabel(PoseStack poseStack, float yaw) {
        poseStack.translate(0.5F, LABEL_Y, 0.5F);
        poseStack.mulPose(Axis.YP.rotationDegrees(yaw));
        poseStack.translate(0.0F, 0.0F, LABEL_Z);
        poseStack.scale(LABEL_SCALE, -LABEL_SCALE, LABEL_SCALE);
    }

    /**
     * 曲名を1行目、曲メタにあるアーティスト名を2行目へ置く。
     * 長い曲名を折り返して作者名を押し出さず、各行を同じ読みやすい大きさのまま省略する。
     */
    public static List<FormattedCharSequence> label(Font font, String title, String author) {
        final FormattedCharSequence titleLine = Component.literal(ellipsize(font, title)).getVisualOrderText();
        if (author.isBlank()) {
            return List.of(titleLine);
        }
        return List.of(titleLine, Component.literal(ellipsize(font, author)).getVisualOrderText());
    }

    private static String ellipsize(Font font, String value) {
        if (font.width(value) <= LABEL_MAX_WIDTH) {
            return value;
        }
        return font.plainSubstrByWidth(value, LABEL_MAX_WIDTH - font.width(LABEL_ELLIPSIS)) + LABEL_ELLIPSIS;
    }

    /**
     * 曲名を出す距離まで近づいているか (1.21.11 以上。抽出の段で camera の位置が渡ってくる)。
     *
     * @param pos    台座の位置
     * @param camera camera のワールド座標
     */
    public static boolean withinLabelRange(BlockPos pos, Vec3 camera) {
        return camera.distanceToSqr(Vec3.atCenterOf(pos)) < LABEL_RANGE_SQR;
    }

    /**
     * 曲名を出す距離まで近づいているか (1.21.1 以下。描画の段に camera が渡ってこないので自分で引く)。
     *
     * <p>バニラの看板の縁取り判定 ({@code SignRenderer#isOutlineVisible}) と同じ引き方。
     *
     * @param pos 台座の位置
     */
    public static boolean withinLabelRange(BlockPos pos) {
        final Entity camera = Minecraft.getInstance().getCameraEntity();
        return camera != null && camera.distanceToSqr(Vec3.atCenterOf(pos)) < LABEL_RANGE_SQR;
    }
}
