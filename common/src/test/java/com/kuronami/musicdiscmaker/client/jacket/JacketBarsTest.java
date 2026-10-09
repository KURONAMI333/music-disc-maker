package com.kuronami.musicdiscmaker.client.jacket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

/**
 * 配信曲のサムネイル (正方形のジャケット + 左右の単色 + hqdefault の上下の黒帯) は正方形に戻し、
 * 端まで絵のある画像は切らない (2026-10-09 実物のサムネイルで確認した形を合成で固定)。
 */
class JacketBarsTest {

    @Test
    void squareArtWithPillarsAndLetterboxBecomesSquare() {
        final BufferedImage image = new BufferedImage(480, 360, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, 480, 360);
        g.setColor(new Color(0x2c3740));
        g.fillRect(0, 45, 480, 270);
        g.setPaint(new GradientPaint(105, 45, Color.CYAN, 375, 315, Color.MAGENTA));
        g.fillRect(105, 45, 270, 270);
        g.dispose();
        final BufferedImage trimmed = JacketBars.trim(image);
        assertEquals(270, trimmed.getWidth());
        assertEquals(270, trimmed.getHeight());
    }

    @Test
    void fullBleedPicturesAndSideGradientsStay() {
        final BufferedImage picture = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = picture.createGraphics();
        // 左が暗い青・右が明るい紫のグラデーション (端の色が左右で違う)。
        g.setPaint(new GradientPaint(0, 0, new Color(0x1a2a5a), 320, 0, new Color(0xc8a0d0)));
        g.fillRect(0, 0, 320, 180);
        g.dispose();
        assertSame(picture, JacketBars.trim(picture));
    }

    @Test
    void oneSidedDarkEdgeStays() {
        final BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        final Graphics2D g = image.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, 320, 180);
        g.setColor(Color.ORANGE);
        g.fillRect(40, 0, 280, 180);
        g.dispose();
        assertSame(image, JacketBars.trim(image));
    }
}
