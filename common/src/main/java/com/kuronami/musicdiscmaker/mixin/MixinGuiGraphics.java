package com.kuronami.musicdiscmaker.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.kuronami.musicdiscmaker.client.jacket.JacketClientTooltip;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;

/**
 * ジャケット画像をツールチップの末尾へ並べ替える。vanilla / 各ローダーは {@code getTooltipImage} 由来の
 * 画像コンポーネントをアイテム名の直後 (index 1) に挿入するため、既定では曲名とアーティストの間に出る。
 * テキスト全行 (曲名・アーティスト・長さ) の下に置くため、描画直前に画像コンポーネントを末尾へ移す。
 *
 * <p>両ローダーとも最終的にこの {@code renderTooltipInternal} を通るので単一実装で足りる
 * (両ローダー officialMojangMappings)。ジャケットを含まないツールチップには影響しない
 * (探索でヒットしなければ元の list をそのまま返す)。
 *
 * <p>元 list を破壊的に変更せず、並べ替えが要る時だけ可変コピーを作って差し替える。NeoForge は
 * {@code ClientHooks.gatherTooltipComponentsFromElements} が {@code Stream.toList()} (不変) を渡すため、
 * in-place の add/remove は {@code UnsupportedOperationException} でツールチップ描画をクラッシュさせる。
 */
@Mixin(GuiGraphics.class)
public abstract class MixinGuiGraphics {

    @ModifyVariable(method = "renderTooltipInternal", at = @At("HEAD"), argsOnly = true, index = 2)
    private List<ClientTooltipComponent> musicdiscmaker$moveJacketToTooltipEnd(
            List<ClientTooltipComponent> components) {
        final int size = components.size();
        for (int i = 0; i < size - 1; i++) { // 既に末尾なら触らない
            if (components.get(i) instanceof JacketClientTooltip) {
                final List<ClientTooltipComponent> reordered = new ArrayList<>(components);
                reordered.add(reordered.remove(i)); // 末尾へ移動 (可変コピー上で)
                return reordered;
            }
        }
        return components;
    }
}
