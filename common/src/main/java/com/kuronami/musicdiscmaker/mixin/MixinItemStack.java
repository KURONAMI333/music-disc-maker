package com.kuronami.musicdiscmaker.mixin;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * custom music disc のツールチップ各行を一定幅で折り返す。曲名は disc の表示名 (tooltip 先頭行) や
 * 補足行に入るため、長い曲名 (全角 30 文字超など) では既定のツールチップが画面幅レベルに横長になる。
 * 全テキスト行 (名前・アーティスト・長さ) を {@link #MAX_WIDTH}px で複数行へ折り返し、バニラの
 * ツールチップ折り返し慣行に近づける。
 *
 * <p>{@code getTooltipLines} は vanilla の基底経路で Fabric/Forge が最終的に通るため、単一の common mixin で
 * 足りる (client 専用: font 幅計算に client font を使う)。custom disc 以外・折り返し不要な短い行は素通し、
 * 短い行は元 Component をそのまま残して sub-span のスタイルを保つ。
 */
@Mixin(ItemStack.class)
public abstract class MixinItemStack {

    /** 折り返し最大幅 (px)。バニラのツールチップ折り返し慣行に合わせた目安。 */
    private static final int MAX_WIDTH = 200;

    @Inject(method = "getTooltipLines", at = @At("RETURN"), cancellable = true)
    private void musicdiscmaker$wrapDiscTooltip(@Nullable Player player, TooltipFlag flag,
            CallbackInfoReturnable<List<Component>> cir) {
        final ItemStack self = (ItemStack) (Object) this;
        if (!(self.getItem() instanceof CustomMusicDiscItem)) {
            return;
        }
        final List<Component> original = cir.getReturnValue();
        if (original == null || original.isEmpty()) {
            return;
        }
        final Font font = Minecraft.getInstance().font;
        boolean wrappedAny = false;
        final List<Component> out = new ArrayList<>(original.size());
        for (final Component line : original) {
            if (font.width(line) <= MAX_WIDTH) {
                out.add(line); // 収まる行は原型のまま (sub-span スタイル保持)
                continue;
            }
            wrappedAny = true;
            // 単一スタイル行を語境界で折り返す。各行のトップスタイルを再適用して色を保つ。
            for (final FormattedText part : font.getSplitter().splitLines(line, MAX_WIDTH, line.getStyle())) {
                out.add(Component.literal(part.getString()).setStyle(line.getStyle()));
            }
        }
        if (wrappedAny) {
            cir.setReturnValue(out);
        }
    }
}
