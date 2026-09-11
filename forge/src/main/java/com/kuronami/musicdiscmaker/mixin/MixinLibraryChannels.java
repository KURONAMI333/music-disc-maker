package com.kuronami.musicdiscmaker.mixin;

import com.kuronami.musicdiscmaker.client.audio.StreamingChannelPool;
import com.mojang.blaze3d.audio.Library;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@link Library#init} が作る二つの channel pool の配分を変更する。
 *
 * <p>OpenAL source の総数は増やさず、通常効果音のための最低 8 source を残した上で、
 * streaming pool を最大 72 source（MDM 64 + BGM 等 8）へ広げる。引数だけを変更するため
 * reload / device change のたびに新しい pool へ同じ配分が適用され、古い pool を保持しない。
 */
@Mixin(Library.class)
public abstract class MixinLibraryChannels {

    @Unique
    private StreamingChannelPool.Allocation mdm$allocation;
    @Unique
    private int mdm$channelCount;
    @Unique
    private int mdm$staticLimit;

    /** init 自身が照会した値を再利用し、二重の ALC 照会と失敗窓を作らない。 */
    @Inject(method = "getChannelCount", at = @At("RETURN"))
    private void mdm$captureChannelCount(CallbackInfoReturnable<Integer> callback) {
        this.mdm$channelCount = callback.getReturnValue();
        this.mdm$allocation = StreamingChannelPool.allocate(this.mdm$channelCount);
    }

    /** static pool の構築引数。別 MOD が既に小さくした値は増やさない。 */
    @ModifyArg(
            method = "init",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/audio/Library$CountingChannelPool;<init>(I)V",
                    ordinal = 0),
            index = 0)
    private int mdm$allocateStaticPool(int currentLimit) {
        if (this.mdm$allocation == null) {
            return currentLimit;
        }
        this.mdm$staticLimit = StreamingChannelPool.staticLimit(currentLimit, this.mdm$allocation);
        return this.mdm$staticLimit;
    }

    /** streaming pool の構築引数。物理上限内で、static 側も譲られた既存拡張は保持する。 */
    @ModifyArg(
            method = "init",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/audio/Library$CountingChannelPool;<init>(I)V",
                    ordinal = 1),
            index = 0)
    private int mdm$allocateStreamingPool(int currentLimit) {
        final StreamingChannelPool.Allocation allocation = this.mdm$allocation;
        if (allocation == null) {
            return currentLimit;
        }
        final int available = Math.max(0, this.mdm$channelCount - this.mdm$staticLimit);
        this.mdm$allocation = null;
        return StreamingChannelPool.streamingLimit(currentLimit, allocation, available);
    }

}
