package com.kuronami.musicdiscmaker.mixin;

//? if >=26.1 && <26.2 {
/*import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.kuronami.musicdiscmaker.compat.amendments.AmendmentsCompat;
import com.kuronami.musicdiscmaker.compat.amendments.AmendmentsDiscState;
import com.kuronami.musicdiscmaker.compat.amendments.AmendmentsDiscSubmitter;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.phys.Vec3;

/^*
 * (非公式) Amendments 26.1.2 のジュークボックスの上で回る盤を、MDM の単曲だけ tint 込みで描く。
 *
 * <p>Amendments は盤ごとに「アイテムの素の絵」から 1 枚絵を作って貼る。MDM の単曲は色を 12 段の
 * tint で乗せるので素の絵は黒く、そのままでは真っ黒な盤になる。抽出の段 (TAIL) で MDM の単曲なら
 * 盤・角度・明るさを状態へ控え ({@link AmendmentsDiscState})、submit の段 (HEAD) で Amendments の盤と
 * 同じ形・同じ位置・同じ回転のマスクを tint 色で出して、Amendments の 1 枚絵を飛ばす。
 * それ以外の盤には何もしない。
 *
 * <p>安全性: {@code @Pseudo} なので Amendments 非導入環境では適用されない。{@code require = 0} なので、
 * Amendments 側の描画が変わって割り込み先が見つからなくても起動は止めない (黒い盤に戻るだけ)。
 * 状態へ持ち物を足す mixin が当たっていなければ何もしない。
 * 確認: Amendments 26.1.2-3.0.x の {@code JukeboxTileRenderer}。
 ^/
@Pseudo
@Mixin(targets = "net.mehvahdjukaar.amendments.client.renderers.JukeboxTileRenderer", remap = false)
public abstract class AmendmentsJukeboxSubmitMixin {

    @Inject(method = "extractRenderState(Lnet/minecraft/world/level/block/entity/JukeboxBlockEntity;Lnet/mehvahdjukaar/amendments/client/renderers/JukeboxRenderState;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/client/renderer/feature/ModelFeatureRenderer$CrumblingOverlay;)V",
            at = @At("TAIL"), require = 0, remap = false)
    private void musicdiscmaker$noteTintedDisc(JukeboxBlockEntity jukebox, @Coerce Object state, float partialTicks,
            Vec3 cameraPosition, ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress, CallbackInfo ci) {
        if (!(state instanceof AmendmentsDiscState holder)) {
            return;
        }
        final ItemStack disc = jukebox.getTheItem();
        if (jukebox.getLevel() == null || !AmendmentsCompat.isCustomDisc(disc)
                || !jukebox.getBlockState().getValue(JukeboxBlock.HAS_RECORD)) {
            holder.musicdiscmaker$setDisc(ItemStack.EMPTY, 0f, 0);
            return;
        }
        // Amendments と同じく、盤の明るさは 1 つ上のブロックから取る。
        holder.musicdiscmaker$setDisc(disc, AmendmentsCompat.jukeboxRotation(jukebox, partialTicks),
                LevelRenderer.getLightCoords(jukebox.getLevel(), jukebox.getBlockPos().above()));
    }

    @Inject(method = "submit(Lnet/mehvahdjukaar/amendments/client/renderers/JukeboxRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void musicdiscmaker$submitTintedDisc(@Coerce Object state, PoseStack pose, SubmitNodeCollector collector,
            CameraRenderState camera, CallbackInfo ci) {
        if (!(state instanceof AmendmentsDiscState holder) || holder.musicdiscmaker$disc().isEmpty()) {
            return;
        }
        AmendmentsDiscSubmitter.submitOnTop(holder.musicdiscmaker$disc(), holder.musicdiscmaker$angle(),
                AmendmentsCompat.VANILLA_DISC_Y, pose, collector, holder.musicdiscmaker$light());
        ci.cancel();
    }
}
*///?}
