package com.kuronami.musicdiscmaker.component;

import com.kuronami.musicdiscmaker.color.DiscDye;
import org.jetbrains.annotations.Nullable;

/**
 * ディスクに焼き付ける染色データ。<b>持つのは色相 (= 染料) だけ</b>で、彩度と明度は
 * {@code DiscPalette} が盤面から計算する (2026-09-06 KURONAMI333 裁定)。
 *
 * <p><b>このクラスは MC を一切参照しない。</b>シリアライズは帯ごとに置き場が違うので、
 * 既存の作法に合わせて外側で行う:
 * <ul>
 *   <li>1.21 以降 — {@code ModDataComponents.DISC_DYE} (DataComponent)。指定した側の id を永続化</li>
 *   <li>1.20.1 — {@code CustomMusicDiscItem} の item NBT (キー {@code dye})。
 *       {@code CustomTrackData} が {@code track} キーを使うのと同じ作法</li>
 * </ul>
 *
 * <p><b>古いディスクの扱い</b>: v2 で作られたディスクはこのデータを持たない。
 * 「component/NBT が無い」= {@code null} = <b>染色されていない</b> であって、既定の染料で
 * 盤面まで染めた状態ではない。呼ぶ側は確定青盤面と曲から決める自動アクセントへ落とすこと。
 * ({@code DiscPalette.board(BLUE)} の支配色は {@code #2b37ad} で、確定した盤面の
 * {@code #0e2041} とは別物。「無染色」を「青で染色」に読み替えると見た目が変わる。)
 */
public record DiscDyeData(@Nullable DiscDye board, @Nullable DiscDye accent) {

    public DiscDyeData {
        if (board == null && accent == null) {
            throw new IllegalArgumentException("board/accent の少なくとも片方が必要 (未染色は DiscDyeData 自体を持たないこと)");
        }
    }

    @Nullable
    public String boardId() {
        return board == null ? null : board.id();
    }

    @Nullable
    public String accentId() {
        return accent == null ? null : accent.id();
    }

    /**
     * id 文字列から復元する。空は未指定側として扱い、有効な側だけを残す。
     * 非空の未知 id が1つでもあれば破損データとして全体を {@code null} (= 未染色) へ落とす。
     */
    public static DiscDyeData fromIds(String boardId, String accentId) {
        final DiscDye b = DiscDye.byId(boardId);
        final DiscDye a = DiscDye.byId(accentId);
        if (isUnknown(boardId, b) || isUnknown(accentId, a)) {
            return null;
        }
        return ofNullable(b, a);
    }

    private static boolean isUnknown(String id, @Nullable DiscDye dye) {
        return id != null && !id.isEmpty() && dye == null;
    }

    /** 両方が空なら未染色、それ以外は片側を含む染色データを返す。 */
    @Nullable
    public static DiscDyeData ofNullable(@Nullable DiscDye board, @Nullable DiscDye accent) {
        return board == null && accent == null ? null : new DiscDyeData(board, accent);
    }

    /** 入力の色へ、実際に置かれた染料だけを上書きする。 */
    @Nullable
    public static DiscDyeData withOverrides(@Nullable DiscDyeData original,
            @Nullable DiscDye boardOverride, @Nullable DiscDye accentOverride) {
        final DiscDye board = boardOverride != null
                ? boardOverride
                : original == null ? null : original.board();
        final DiscDye accent = accentOverride != null
                ? accentOverride
                : original == null ? null : original.accent();
        return ofNullable(board, accent);
    }
}
