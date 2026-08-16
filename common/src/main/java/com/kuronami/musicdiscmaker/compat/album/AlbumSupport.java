package com.kuronami.musicdiscmaker.compat.album;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.MusicDiscMaker;

import net.minecraft.world.item.ItemStack;

/**
 * 「複数のディスクを束ねた 1 アイテム」(Additional Additions のアルバム等) を common から扱うための
 * optional アダプタ。**このクラスは他 MOD の型を一切参照しない**。実装は loader 側の compat が
 * {@link #install} で差し込み、他 MOD の型はそこに閉じ込める ({@code AlbumPlaybackMirror} と
 * {@code AdditionalAdditionsCompat} の分離と同じ流儀)。
 *
 * <p>未導入環境では {@link #ABSENT} のまま = {@code isAlbum} が常に false・{@code contents} が常に
 * null になるので、呼び出し側は追加のガード無しで従来動作に落ちる。
 *
 * <p>差し込みは {@code volatile} + swap で、headless テストが fake を入れて {@code finally} で戻せる
 * ようにしてある ({@code Services.swapNetwork} と同じ理由: アルバム item を供給する MOD は
 * {@code compileOnly} = テスト runtime に存在しない)。
 */
public final class AlbumSupport {

    /** アルバム item の識別と中身の取り出し。実装は loader 側 compat のみが持つ。 */
    public interface Provider {

        /** この stack が「複数ディスクを束ねたアルバム」か。 */
        boolean isAlbum(ItemStack stack);

        /** アルバムの中身 (トラック順の disc stack)。アルバムでなければ null。 */
        @Nullable
        List<ItemStack> contents(ItemStack stack);
    }

    /** アルバムを供給する MOD が無い時の実装。 */
    private static final Provider ABSENT = new Provider() {

        @Override
        public boolean isAlbum(ItemStack stack) {
            return false;
        }

        @Override
        public List<ItemStack> contents(ItemStack stack) {
            return null;
        }
    };

    private static volatile Provider provider = ABSENT;

    private AlbumSupport() {
    }

    /**
     * 実装を差し込み、差し替え前の実装を返す。loader 側の compat が「その MOD がロードされている時だけ」
     * 呼ぶ。テストは戻り値を {@code finally} で戻すこと。
     */
    public static Provider install(Provider replacement) {
        final Provider previous = provider;
        provider = replacement == null ? ABSENT : replacement;
        return previous;
    }

    public static boolean isAlbum(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            return provider.isAlbum(stack);
        } catch (final Throwable t) {
            disable(t);
            return false;
        }
    }

    /** アルバムの中身。アルバムでなければ null。 */
    @Nullable
    public static List<ItemStack> contents(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        try {
            return provider.contents(stack);
        } catch (final Throwable t) {
            disable(t);
            return null;
        }
    }

    /** アルバムのトラック数。アルバムでなければ 0。 */
    public static int trackCount(ItemStack stack) {
        final List<ItemStack> items = contents(stack);
        return items == null ? 0 : items.size();
    }

    /**
     * アルバムの index 番目のディスク。アルバムでない・index が範囲外なら {@link ItemStack#EMPTY}。
     */
    public static ItemStack trackAt(ItemStack stack, int index) {
        final List<ItemStack> items = contents(stack);
        if (items == null || index < 0 || index >= items.size()) {
            return ItemStack.EMPTY;
        }
        final ItemStack track = items.get(index);
        return track == null ? ItemStack.EMPTY : track;
    }

    /** アダプタが例外を投げたら以後は「アルバム非対応」に固定する (毎 tick のログ汚染を防ぐ)。 */
    private static void disable(Throwable t) {
        if (provider != ABSENT) {
            provider = ABSENT;
            MusicDiscMaker.LOGGER.error("[MDM] album compat disabled due to an error", t);
        }
    }
}
