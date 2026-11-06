package com.kuronami.musicdiscmaker.mixin;

//? if <1.21.2 {
/*import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.kuronami.musicdiscmaker.compat.amendments.AmendmentsJukeboxDisc;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;

/^*
 * (非公式) Amendments のジュークボックスの上で回る盤を、MDM の単曲だけ tint 込みで描く (Fabric 1.20.1 / 1.21.1)。
 *
 * <p>Amendments は盤ごとに「アイテムの素の絵」から 1 枚絵を作って貼る。MDM の単曲は色を 12 段の
 * tint で乗せるので素の絵は黒く、そのままでは真っ黒な盤になる。Amendments が盤の位置・回転・
 * X 90° を pose へ積み終え、絵を引く直前で割り込み、MDM の単曲なら Amendments の盤と同じ形の
 * マスクを tint 色で描いて残り (1 枚絵の貼り付け) を飛ばす ({@link AmendmentsJukeboxDisc})。
 * それ以外の盤には何もしない。
 *
 * <p>Fabric の 1.20.1 / 1.21.1 は実行時に intermediary 名。{@code @Pseudo} の対象は remapJar でも引けないので、
 * intermediary 名を直に書く ({@code BlockEntityRenderer#render} = {@code method_3569}、JukeboxBlockEntity =
 * {@code class_2619}、PoseStack = {@code class_4587}、MultiBufferSource = {@code class_4597}、Item = {@code class_1792}、
 * Material = {@code class_4730}。intermediary は版を跨いで同じ名前で、1.21.1 の mappings で確認)。
 *
 * <p>安全性: {@code @Pseudo} なので Amendments 非導入環境では適用されない。{@code require = 0} なので、
 * Amendments 側の描画が変わって割り込み先が見つからなくても起動は止めない (黒い盤に戻るだけ)。
 * 確認: Amendments 1.20-2.2.x / 1.21-2.2.x (Fabric) の {@code JukeboxTileRenderer}。
 ^/
@Pseudo
@Mixin(targets = "net.mehvahdjukaar.amendments.client.renderers.JukeboxTileRenderer", remap = false)
public abstract class AmendmentsJukeboxRendererMixin {

    @Inject(method = "method_3569(Lnet/minecraft/class_2619;FLnet/minecraft/class_4587;Lnet/minecraft/class_4597;II)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/mehvahdjukaar/amendments/AmendmentsClient;getRecordMaterial(Lnet/minecraft/class_1792;)Lnet/minecraft/class_4730;"),
            cancellable = true, require = 0, remap = false)
    private void musicdiscmaker$drawTintedDisc(JukeboxBlockEntity jukebox, float partialTick, PoseStack pose,
            MultiBufferSource buffers, int packedLight, int packedOverlay, CallbackInfo ci) {
        if (AmendmentsJukeboxDisc.drawOnVanillaJukebox(jukebox, pose, buffers)) {
            ci.cancel();
        }
    }
}
*///?}
