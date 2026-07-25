package com.kuronami.musicdiscmaker.event;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 「ブームボックスがまだ鳴り続ける場所」の定義。<b>継続の境界はここ 1 箇所だけで決まる。</b>
 *
 * <h2>境界 (kura 裁定 + 🔸)</h2>
 * <ul>
 *   <li><b>鳴る</b>: ホットバー / メインインベントリ / オフハンド。持ち替えても・スロットを
 *       動かしても鳴り続ける (これが再設計の主目的)。</li>
 *   <li><b>鳴る</b> 🔸: <b>カーソルに掴んでいるスタック</b>。インベントリ画面でスタックを持ち上げて
 *       いる間はどのスロットにも属さないので、スロット走査だけだと数秒の空白で止まってしまう。
 *       「インベントリの中にある」の自然な解釈としてカーソルも含めた。</li>
 *   <li><b>止まる</b>: 地面 (ドロップ) / チェスト / エンダーチェスト / バックパック / 死亡ドロップ /
 *       防具スロット / クラフト格子。いずれもここの集合に現れないので、走査で見つからなくなり
 *       停止する。</li>
 * </ul>
 *
 * <p>この集合を明示的に列挙してあるのが要点。以前の実装はバニラの {@code inventoryTick} が呼ばれる
 * かどうかに継続条件を委ねていて、「どこで鳴るか」がバニラの都合の副作用になっていた。カーソル
 * スタックのような境界例はその方式では原理的に扱えない。
 */
public final class BoomboxCarry {

    private BoomboxCarry() {
    }

    /**
     * いま鳴り続けてよい場所にあるブームボックスのスタックを列挙する。
     *
     * <p>{@code containerMenu} は常に非 null (画面を閉じている間は {@code inventoryMenu})。
     * 画面を閉じるときバニラがカーソルのスタックをインベントリへ戻すか投げるので、ここで拾い
     * 損ねる経路は無い。
     */
    public static List<ItemStack> carried(Player player) {
        final List<ItemStack> found = new ArrayList<>();
        collect(found, player.getInventory().items);
        collect(found, player.getInventory().offhand);
        add(found, player.containerMenu.getCarried());
        return found;
    }

    /** そのアイテム個体 (UUID) のブームボックスを、鳴ってよい場所から探す。無ければ null。 */
    @Nullable
    public static ItemStack find(Player player, UUID boomboxId) {
        for (final ItemStack stack : carried(player)) {
            if (boomboxId.equals(stack.get(ModDataComponents.BOOMBOX_ID.get()))) {
                return stack;
            }
        }
        return null;
    }

    /** ブームボックスのアイテムか。 */
    public static boolean isBoombox(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModItems.BOOMBOX.get());
    }

    private static void collect(List<ItemStack> found, Iterable<ItemStack> slots) {
        for (final ItemStack stack : slots) {
            add(found, stack);
        }
    }

    private static void add(List<ItemStack> found, ItemStack stack) {
        if (isBoombox(stack)) {
            found.add(stack);
        }
    }
}
