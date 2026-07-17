package com.kuronami.musicdiscmaker.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;

/**
 * (非公式) Sophisticated Core Fabric port の {@code UpgradeWrapperBase.storageWrapper}
 * (protected final、public getter 無し) を読むためのアクセサ。
 *
 * <p>{@code storageWrapper} は親クラスに宣言されているため、{@code JukeboxUpgradeWrapper} を
 * 対象にした {@code @Shadow} では「target class に見つからない」で失敗する。親クラスを対象にした
 * {@code @Accessor} 経由で取り、{@code JukeboxUpgradeWrapper} インスタンスをこの interface にキャストして使う。
 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeWrapperBase", remap = false)
public interface UpgradeWrapperBaseAccessor {

    @Accessor(value = "storageWrapper", remap = false)
    IStorageWrapper musicdiscmaker$getStorageWrapper();
}
