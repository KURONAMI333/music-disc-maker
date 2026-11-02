package com.kuronami.musicdiscmaker.component;

import java.util.function.IntPredicate;

import org.jetbrains.annotations.Nullable;

/**
 * 収納の上限を超えた時の規則。<b>ItemStack も codec も知らない</b>ので JUnit で直接試せる。
 * 実 byte 数は呼び出し側が実 codec で測り、判定だけを predicate で渡す。
 *
 * <p>2つの規則を持つ。挿入は「入らないなら入らないだけ」、開く時は「収まる分だけを残し、
 * 残りはプレイヤーへ返す」。どちらもプレイヤーへ文を出さない (2026-11-02 本人指示
 * 「文を出すべきではない というか、文を出さないとわからないのがおかしい」)。
 */
public final class StorageOverflow {

    /** 保存順の末尾 {@code kept} 件を残し、先頭 {@code returned} 件を機器の外へ出す分割。 */
    public record Split(int kept, int returned) {
        public Split {
            if (kept < 0 || returned < 0) throw new IllegalArgumentException("negative split");
        }

        public int total() {
            return kept + returned;
        }
    }

    private StorageOverflow() {
    }

    /**
     * 保存順の<b>先頭から外へ出す件数</b>を最小にする分割点を選ぶ。既存の albumPlan の探索順
     * (先頭から1件ずつ外して試し、最初に収まった所で止める) をそのまま規則にしたもの。
     * 全部そのまま収まるなら何も外さない。
     *
     * <p>1件も収まらなければ全件を外へ出し、空でも収まらなければ {@code null}。外へ出す1件ずつは
     * 単独でプレイヤーへ渡せなければならない (同期できない1件を無理に外すと、その1件ごと壊れる)。
     *
     * @param entries   保存件数
     * @param fits      <b>末尾</b> {@code kept} 件を残した機器が同期予算に収まるか
     * @param droppable {@code index} 番目を単独でプレイヤーへ渡せるか
     * @return 分割。縮められる分割が無ければ {@code null}
     */
    public static @Nullable Split split(int entries, IntPredicate fits, IntPredicate droppable) {
        if (entries < 0) throw new IllegalArgumentException("negative entries");
        if (fits.test(entries)) return new Split(entries, 0);
        for (int returned = 1; returned <= entries; returned++) {
            if (!droppable.test(returned - 1)) return null;
            if (fits.test(entries - returned)) return new Split(entries - returned, returned);
        }
        // 全件を外した空の機器が予算に収まる時だけ、全件返す。
        return fits.test(0) ? new Split(0, entries) : null;
    }

    /**
     * 挿入1件を受け入れる件数か。{@code limit} は「これ以上は入らない」件数。
     * byte 側の判定は実 codec の測定結果 ({@link AlbumStorageBudget}) が持つ。
     */
    public static boolean acceptsInsert(int storedEntries, int limit) {
        return storedEntries < limit;
    }

    /**
     * 拒否をプレイヤーへ伝える文言。容量超過は文を出さないので {@code null}。
     * encode/decode の失敗だけが従来どおりの文言を持つ。
     */
    public static @Nullable String failureKey(boolean capacityExceeded) {
        return capacityExceeded ? null : AlbumStorageBudget.SYNC_FAILED_KEY;
    }
}
