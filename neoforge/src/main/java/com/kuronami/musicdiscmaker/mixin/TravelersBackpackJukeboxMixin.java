package com.kuronami.musicdiscmaker.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.compat.travelersbackpack.TravelersBackpackCompatClient;

import net.minecraft.world.item.JukeboxSong;

/**
 * (非公式) Traveler's Backpack の Jukebox Upgrade で MDM の custom disc をストリーミング再生する。
 *
 * <p>TB には SC の {@code IDiscHandler} のような拡張点が無い。GUI の再生/停止ボタン
 * ({@code JukeboxWidget.playDiscToPlayer}/{@code stopDisc}、いずれも client 専用・public・
 * 1.21.1〜26.2 で同一シグネチャ) を TAIL で捕まえ、その時点で開いている backpack 画面から
 * {@code JukeboxUpgrade} を公開 API 経由で取得する (実処理は {@link TravelersBackpackCompatClient})。
 *
 * <p>TB の private/継承フィールドへの {@code @Shadow} は使わない。{@code UpgradeWrapperBaseAccessor}
 * (SC Fabric port compat) の javadoc の通り、対象クラスに直接無い継承フィールドの @Shadow は
 * 解決失敗でハードクラッシュした実績があるため、この mixin は TB 型を一切 import しない
 * (メソッド引数はどちらも vanilla の {@link JukeboxSong})。
 *
 * <p>安全性: {@code @Pseudo} で TB 非導入環境では適用されない。inject 本体は try/catch で失敗を
 * 握りつぶす。TB 型に触れるのは呼び出し先の {@link TravelersBackpackCompatClient} だけで、
 * それも mixin が実際に適用された (= TB 導入環境) 時にしか実行されない。
 */
@Pseudo
@Mixin(targets = "com.tiviacz.travelersbackpack.inventory.upgrades.jukebox.JukeboxWidget", remap = false)
public class TravelersBackpackJukeboxMixin {

    @Inject(method = "playDiscToPlayer", at = @At("TAIL"), require = 0, remap = false)
    private void musicdiscmaker$streamCustomDisc(int entityId, JukeboxSong jukeboxSong, CallbackInfo ci) {
        try {
            TravelersBackpackCompatClient.onPlayClicked(entityId);
        } catch (final Throwable t) {
            // fail-soft: TB の内部構造が変わってもクラッシュさせない (無音再生に退化)。
            MusicDiscMaker.LOGGER.debug("TB jukebox streaming hook skipped: {}", t.toString());
        }
    }

    @Inject(method = "stopDisc", at = @At("TAIL"), require = 0, remap = false)
    private void musicdiscmaker$stopCustomDisc(JukeboxSong jukeboxSong, CallbackInfo ci) {
        try {
            TravelersBackpackCompatClient.onStopClicked();
        } catch (final Throwable t) {
            MusicDiscMaker.LOGGER.debug("TB jukebox stop hook skipped: {}", t.toString());
        }
    }
}
