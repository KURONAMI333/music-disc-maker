package com.kuronami.musicdiscmaker.client.jacket;

import java.awt.image.BufferedImage;

/**
 * ジャケット画像の端にある単色の帯を切り落とす。
 *
 * <p>YouTube の配信曲 (Topic) のサムネイルは、正方形のジャケットの左右を単色で埋めた 16:9 の画像で、
 * hqdefault はさらに上下に黒帯が付く (2026-10-09 実物を取得して確認)。そのまま出すと正方形の
 * ジャケットが 16:9 で出る (HappyMeek の指摘)。上下の帯を先に、次に左右の帯を見て、両側が
 * ほぼ同じ幅の単色の帯である時だけ切る。普通の MV のように端まで絵がある画像は切らない。
 */
public final class JacketBars {

    /** 帯とみなす色の差 (各チャンネル)。JPEG のにじみを許す幅。 */
    private static final int TOLERANCE = 24;
    /** 帯として切る最小の幅 (辺の長さに対する割合)。 */
    private static final double MIN_BAR = 0.04;
    /** 左右 (上下) の帯の幅の差の許容 (辺の長さに対する割合)。 */
    private static final double MAX_ASYMMETRY = 0.03;
    /** 帯の境目で JPEG がにじむ幅 (px)。 */
    private static final int EDGE_INSET = 3;
    /** 切った後に残す最小の長さ (辺の長さに対する割合)。 */
    private static final double MIN_CONTENT = 0.2;

    private JacketBars() {
    }

    /** 帯を切った画像。切る帯が無ければ入力をそのまま返す。 */
    public static BufferedImage trim(BufferedImage image) {
        int top = 0;
        int bottom = image.getHeight();
        final int[] rows = bars(image, false, 0, image.getWidth(), 0, image.getHeight());
        if (rows != null) {
            top = rows[0];
            bottom = rows[1];
        }
        int left = 0;
        int right = image.getWidth();
        // 上下の帯との境目の行は JPEG でにじむので、左右の帯は境目から少し内側の行で見る。
        final int inset = rows == null ? 0 : Math.min(EDGE_INSET, (bottom - top) / 4);
        final int[] cols = bars(image, true, top + inset, bottom - inset, 0, image.getWidth());
        if (cols != null) {
            left = cols[0];
            right = cols[1];
        }
        if (top == 0 && bottom == image.getHeight() && left == 0 && right == image.getWidth()) {
            return image;
        }
        return image.getSubimage(left, top, right - left, bottom - top);
    }

    /**
     * 一方向の両端の帯を探す。
     *
     * @param vertical 真なら左右の帯 (列を見る)、偽なら上下の帯 (行を見る)
     * @param from     列を見る時は見る行の範囲の始め、行を見る時は見る列の範囲の始め
     * @param to       同じく終わり (含まない)
     * @param start    帯を探す方向の範囲の始め
     * @param end      同じく終わり (含まない)
     * @return [内容の始め, 内容の終わり (含まない)]。切らない時は null
     */
    private static int[] bars(BufferedImage image, boolean vertical, int from, int to, int start, int end) {
        final int length = end - start;
        if (length <= 0 || to - from <= 0) {
            return null;
        }
        // 帯は両側で同じ色 (配信曲の左右の単色・上下の黒帯)。端の色が左右で違う絵 (グラデーション等) は切らない。
        if (!close(edgeColor(image, vertical, from, to, start), edgeColor(image, vertical, from, to, end - 1))) {
            return null;
        }
        final int head = barLength(image, vertical, from, to, start, end, 1);
        final int tail = barLength(image, vertical, from, to, end - 1, start - 1, -1);
        if (head < MIN_BAR * length || tail < MIN_BAR * length
                || Math.abs(head - tail) > MAX_ASYMMETRY * length
                || length - head - tail < MIN_CONTENT * length) {
            return null;
        }
        return new int[] {start + head, end - tail};
    }

    private static int edgeColor(BufferedImage image, boolean vertical, int from, int to, int line) {
        return vertical ? image.getRGB(line, (from + to) / 2) : image.getRGB((from + to) / 2, line);
    }

    /** 端から内側へ、端の色と同じ単色の行 (列) が続く長さ。 */
    private static int barLength(BufferedImage image, boolean vertical, int from, int to,
            int first, int stop, int step) {
        final int reference = edgeColor(image, vertical, from, to, first);
        final int stride = Math.max(1, (to - from) / 64);
        int count = 0;
        for (int line = first; line != stop; line += step) {
            for (int along = from; along < to; along += stride) {
                final int rgb = vertical ? image.getRGB(line, along) : image.getRGB(along, line);
                if (!close(rgb, reference)) {
                    return count;
                }
            }
            count++;
        }
        return count;
    }

    private static boolean close(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) <= TOLERANCE
                && Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)) <= TOLERANCE
                && Math.abs((a & 0xFF) - (b & 0xFF)) <= TOLERANCE;
    }
}
