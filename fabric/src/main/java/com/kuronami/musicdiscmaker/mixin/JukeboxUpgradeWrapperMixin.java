package com.kuronami.musicdiscmaker.mixin;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.compat.sophisticatedcore.SophisticatedCoreCompat;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.p3pp3rf1y.sophisticatedcore.api.IStorageWrapper;

/**
 * (非公式) Sophisticated Backpacks Fabric port の jukebox upgrade で MDM の custom disc を
 * ストリーミング再生させる (1.20.1)。port には公式の {@code IDiscHandler} API が無いので、port の
 * {@code JukeboxUpgradeWrapper.playDisc} (private) に TAIL 割り込みする。
 *
 * <p>port は playDisc 内で無音 jukebox_song を {@code StorageSoundHandler} に storageUuid で登録する。
 * TAIL で「その後に」MDM のストリーミング packet を送り、client で同じ storageUuid に上書き登録すると
 * MDM の音が後勝ちで鳴る。port の停止 (stopStorageSound(uuid)) / 曲送り / keep-alive はそのまま効く。
 *
 * <p>安全性の正確な範囲:
 * <ul>
 *   <li>{@code @Pseudo}: port 非導入環境では対象クラスが無いので適用されない (= 無害にスキップ)。
 *   <li>inject 本体の try/catch + {@code require = 0}: 実行時に streaming ロジックが失敗しても握りつぶす。
 *   <li><b>ただし @Shadow / @Accessor の解決失敗は mixin 適用時のハードクラッシュで、上記 try/catch では防げない。</b>
 *       port が内部構造 (フィールド名/メソッド名) を変えるとクラッシュする。
 *       本 mixin は特定 port バージョンの内部に依存するので、<b>port が更新されたら追従が必要</b>。
 *       (検証済みの対象 = sophisticatedcore Fabric port 1.20.1-1.2.7.15.166。)
 * </ul>
 */
@Pseudo
@Mixin(targets = "net.p3pp3rf1y.sophisticatedcore.upgrades.jukebox.JukeboxUpgradeWrapper", remap = false)
public abstract class JukeboxUpgradeWrapperMixin {

    @Shadow(remap = false)
    @Nullable
    private BlockPos posPlaying;

    @Shadow(remap = false)
    @Nullable
    private Entity entityPlaying;

    @Shadow(remap = false)
    @Nullable
    private Level levelPlaying;

    @Shadow(remap = false)
    public abstract ItemStack getDisc();

    @Inject(method = "playDisc", at = @At("TAIL"), require = 0, remap = false)
    private void musicdiscmaker$streamCustomDisc(CallbackInfo ci) {
        try {
            final ItemStack disc = getDisc();
            if (disc.isEmpty() || !disc.is(ModItems.CUSTOM_MUSIC_DISC.get())) {
                return;
            }
            final CustomTrackData track = CustomMusicDiscItem.getTrack(disc);
            if (track.isEmpty()) {
                return;
            }
            final Level level = entityPlaying != null ? entityPlaying.level() : levelPlaying;
            if (!(level instanceof ServerLevel serverLevel)) {
                return;
            }
            final IStorageWrapper storageWrapper =
                    ((UpgradeWrapperBaseAccessor) (Object) this).musicdiscmaker$getStorageWrapper();
            storageWrapper.getContentsUuid().ifPresent(uuid -> {
                if (entityPlaying != null) {
                    SophisticatedCoreCompat.streamToNearby(
                            serverLevel, entityPlaying.position(), uuid, track, entityPlaying.getId());
                } else if (posPlaying != null) {
                    SophisticatedCoreCompat.streamToNearby(
                            serverLevel, Vec3.atCenterOf(posPlaying), uuid, track, -1);
                }
            });
        } catch (final Throwable t) {
            // fail-soft: port の内部構造が変わってもクラッシュさせない (無音再生に退化)。
            MusicDiscMaker.LOGGER.debug("SB Fabric port streaming hook skipped: {}", t.toString());
        }
    }
}
