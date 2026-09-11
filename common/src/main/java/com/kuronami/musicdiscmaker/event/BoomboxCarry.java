package com.kuronami.musicdiscmaker.event;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.component.BoomboxContents;
//? if >=1.21 {
import com.kuronami.musicdiscmaker.register.ModDataComponents;
//?}
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 「ブームボックスがまだ鳴り続ける場所」の定義。<b>継続の境界はここ 1 箇所だけで決まる。</b>
 *
 * <h2>境界</h2>
 * <ul>
 *   <li><b>鳴る</b>: プレイヤーのインベントリ (ホットバー / メイン / オフハンド)。持ち替えても
 *       スロットを動かしても鳴り続ける — KURONAMI333 裁定 2026-09-07「担いでいようといまいと
 *       インベントリにあれば動作はする」</li>
 *   <li><b>鳴る</b> 🔸: <b>カーソルに掴んでいるスタック</b>。インベントリ画面でスタックを持ち上げて
 *       いる間はどのスロットにも属さないので、スロット走査だけだと数秒の空白で止まる。
 *       「インベントリの中にある」の自然な解釈としてカーソルも含めた
 *       (MDM_DECISIONS 未裁定 1「カーソルに掴んだスタックも鳴ってよい場所に含める」は
 *       「インベントリ全体で鳴る」に吸収済み)</li>
 *   <li><b>止まる</b>: 地面 (ドロップ) / チェスト / エンダーチェスト / バックパック / 死亡ドロップ /
 *       クラフト格子。いずれもここの集合に現れないので、走査で見つからなくなり停止する</li>
 * </ul>
 *
 * <p>この集合を<b>明示的に列挙してある</b>のが要点。バニラの {@code inventoryTick} が呼ばれるか
 * どうかに継続条件を委ねると、「どこで鳴るか」がバニラの都合の副作用になり、カーソルスタックの
 * ような境界例を原理的に扱えない。
 *
 * <p>走査は {@link Container} の口 ({@code getContainerSize} / {@code getItem}) で行う。
 * {@code Inventory} の内部フィールド ({@code items} / {@code offhand}) は帯によって形が違うが、
 * {@link Container} は 5 帯とも同じ。
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
     *
     * @param player 対象のプレイヤー
     * @return 鳴ってよい場所にあるブームボックス (0 個なら空リスト)
     */
    public static List<ItemStack> carried(Player player) {
        final List<ItemStack> found = new ArrayList<>();
        final Container inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            add(found, inventory.getItem(slot));
        }
        add(found, player.containerMenu.getCarried());
        return found;
    }

    /**
     * その機体 (識別子) のブームボックスを、鳴ってよい場所から探す。
     *
     * @param player   対象のプレイヤー
     * @param boomboxId 機体の識別子
     * @return 見つかったスタック。無ければ {@code null}
     */
    @Nullable
    public static ItemStack find(Player player, long boomboxId) {
        for (final ItemStack stack : carried(player)) {
            if (idOf(stack) == boomboxId) {
                return stack;
            }
        }
        return null;
    }

    /**
     * ブームボックスのアイテムか。
     *
     * @param stack 対象
     * @return ブームボックスなら {@code true}
     */
    public static boolean isBoombox(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModItems.BOOMBOX.get());
    }

    /**
     * そのスタックの機体識別子。未採番なら {@link BoomboxContents#UNASSIGNED}。
     *
     * @param stack 対象
     * @return 識別子
     */
    public static long idOf(ItemStack stack) {
        //? if >=1.21 {
        return stack.getOrDefault(ModDataComponents.BOOMBOX_CONTENTS.get(), BoomboxContents.EMPTY).id();
        //?} else {
        /*return BoomboxContents.of(stack).id();
        *///?}
    }

    private static void add(List<ItemStack> found, ItemStack stack) {
        if (isBoombox(stack)) {
            found.add(stack);
        }
    }
}
