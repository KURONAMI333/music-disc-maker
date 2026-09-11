package com.kuronami.musicdiscmaker.client;

import com.kuronami.musicdiscmaker.color.DiscDye;
import com.kuronami.musicdiscmaker.color.DiscPalette;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.DiscDyeData;
import com.kuronami.musicdiscmaker.component.TrackKey;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;

import net.minecraft.world.item.ItemStack;

/**
 * 明示 face モデルの {@code tintindex 0..11} を確定盤面の色へ結ぶ。
 *
 * <p>盤面 5 段とアクセント 7 段を別々の白マスクとして持つ。{@code item/generated} の
 * layer 上限を通らず、確定した色相回転と全 12 段をそのまま描く。
 */
public final class DiscDyeTint {

    /** 明示モデルが使う最大 tintindex + 1。 */
    public static final int LAYERS = 12;

    private static final int WHITE = 0xFFFFFFFF;
    private static final DiscDye[] DYES = DiscDye.values();
    private static final int DYE_COUNT = DYES.length;

    /** 未染色の単曲。盤面 5 段 + アクセント 7 段。 */
    private static final int[] DEFAULT_CUSTOM = opaque(new int[] {
            0x040711, 0x070F22, 0x081127, 0x0E2041, 0x122C53,
            0x062225, 0x0A373B, 0x0C4449, 0x0F575D, 0x17808A, 0x188C96, 0x1C9DA9
    });

    private static final int[][] CUSTOM_CACHE = new int[DYE_COUNT * DYE_COUNT][];
    private static final int[][] AUTO_ACCENT_CACHE = new int[DYE_COUNT][];

    private DiscDyeTint() {
    }

    /** stack と tintindex に対応する ARGB。範囲外は白を返す。 */
    public static int color(ItemStack stack, int tintIndex) {
        if (tintIndex < 0 || tintIndex >= LAYERS) {
            return WHITE;
        }
        final DiscDyeData dye = CustomMusicDiscItem.getDye(stack);
        final int[] colors;
        if (dye != null) {
            colors = colors(stack, dye);
        } else {
            final CustomTrackData track = CustomMusicDiscItem.getTrack(stack);
            colors = track.isEmpty() ? DEFAULT_CUSTOM : automaticColors(track);
        }
        return colors[tintIndex];
    }

    /**
     * 未染色の単曲。盤面は確定青のまま、曲の正規化hashを16染料へ写したアクセントを使う。
     * 同じ曲は全clientで同じ色になり、明示染色があればこの経路へは来ない。
     */
    private static int[] automaticColors(CustomTrackData track) {
        final int index = TrackKey.automaticDyeIndex(track);
        final int[] cached = AUTO_ACCENT_CACHE[index];
        if (cached != null) {
            return cached;
        }
        final DiscPalette.Board board = DiscPalette.defaultBoard();
        final DiscPalette.Accent accent = DiscPalette.automaticAccent(DYES[index], board);
        final int[] built = combine(board, accent);
        AUTO_ACCENT_CACHE[index] = built;
        return built;
    }

    /** 染色した単曲の盤面 5 段 + アクセント 7 段。 */
    public static int[] colors(DiscDyeData dye) {
        if (dye.board() == null || dye.accent() == null) {
            throw new IllegalArgumentException("部分染色の描画には元ディスクの stack が必要");
        }
        final int key = dye.board().ordinal() * DYE_COUNT + dye.accent().ordinal();
        final int[] cached = CUSTOM_CACHE[key];
        if (cached != null) {
            return cached;
        }
        final DiscPalette.Board board = DiscPalette.board(dye.board());
        final DiscPalette.Accent accent = DiscPalette.accent(dye.accent(), board);
        final int[] built = combine(board, accent);
        CUSTOM_CACHE[key] = built;
        return built;
    }

    private static int[] colors(ItemStack stack, DiscDyeData dye) {
        if (dye.board() != null && dye.accent() != null) {
            return colors(dye);
        }
        final DiscPalette.Board board = dye.board() == null
                ? DiscPalette.defaultBoard()
                : DiscPalette.board(dye.board());
        if (dye.accent() != null) {
            return combine(board, DiscPalette.accent(dye.accent(), board));
        }

        // 右側が未指定なら、元の未染色アクセントをRGBのまま残す。
        final CustomTrackData track = CustomMusicDiscItem.getTrack(stack);
        final int[] original = track.isEmpty() ? DEFAULT_CUSTOM : automaticColors(track);
        final int[] built = original.clone();
        for (int i = 0; i < board.size(); i++) {
            built[i] = opaque(board.step(i));
        }
        return built;
    }

    private static int[] combine(DiscPalette.Board board, DiscPalette.Accent accent) {
        final int[] built = new int[LAYERS];
        for (int i = 0; i < board.size(); i++) {
            built[i] = opaque(board.step(i));
        }
        for (int i = 0; i < accent.size(); i++) {
            built[board.size() + i] = opaque(accent.step(i));
        }
        return built;
    }

    private static int[] opaque(int[] rgb) {
        final int[] result = new int[rgb.length];
        for (int i = 0; i < rgb.length; i++) {
            result[i] = opaque(rgb[i]);
        }
        return result;
    }

    private static int opaque(int rgb) {
        return 0xFF000000 | rgb;
    }
}
