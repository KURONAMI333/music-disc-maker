package com.kuronami.musicdiscmaker.compat.travelersbackpack;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.audio.LoaderHolder;
import com.kuronami.musicdiscmaker.client.audio.DiscSoundInstance;
import com.kuronami.musicdiscmaker.client.audio.PlaybackFailure;
import com.kuronami.musicdiscmaker.client.audio.PlaybackFailureReport;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.network.UrlBlockedException;
import com.kuronami.musicdiscmaker.network.UrlGuard;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import com.tiviacz.travelersbackpack.inventory.menu.BackpackBaseMenu;
import com.tiviacz.travelersbackpack.inventory.upgrades.jukebox.JukeboxUpgrade;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * client 側: Traveler's Backpack の Jukebox Upgrade スロットで MDM の custom disc を LavaPlayer 再生する。
 *
 * <p>TB の再生は完全にローカル ({@code JukeboxWidget.playDiscToPlayer} が {@code Minecraft.getInstance()}
 * を直接叩くだけで、SC のような近隣プレイヤーへの broadcast packet が無い) ので、この compat も同じ
 * 「クリックした本人にしか聞こえない」聞こえ方に揃える。SB のような spatial broadcast はしない
 * (TB 自体の設計がそうなっているだけで、機能を削っているわけではない)。
 *
 * <p>呼び出し元は {@code TravelersBackpackJukeboxMixin} のみ。TB 型 ({@link BackpackBaseMenu}/
 * {@link JukeboxUpgrade}) を参照するのはこのクラスだけなので、mixin が適用されない
 * (= TB 非導入環境) ではこのクラス自体が一切 touch されない。
 */
public final class TravelersBackpackCompatClient {

    private static final ExecutorService POOL = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "music_disc_maker-tb-playback");
        thread.setDaemon(true);
        return thread;
    });

    /** 現在再生中のインスタンス。stop クリックまで保持し続ける (曲の自然終了で再トリガーしない)。 */
    @Nullable
    private static volatile DiscSoundInstance activeInstance;

    private TravelersBackpackCompatClient() {
    }

    /** {@code JukeboxWidget.playDiscToPlayer} の TAIL から呼ばれる。entityId は再生元 (装着者)。 */
    public static void onPlayClicked(int entityId) {
        final ItemStack disc = currentDisc();
        if (disc == null || disc.isEmpty() || !disc.is(ModItems.CUSTOM_MUSIC_DISC.get())) {
            return;
        }
        final CustomTrackData track = disc.get(ModDataComponents.CUSTOM_TRACK.get());
        if (track == null || track.isEmpty()) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        final Entity entity = mc.level.getEntity(entityId);
        if (entity == null) {
            return;
        }
        stopInternal();
        // URL ロードはブロックするので別 thread、SoundManager 操作は main thread (SC compat と同じ配線)。
        POOL.submit(() -> {
            IAudioSource source;
            PlaybackFailure failure = null;
            try {
                UrlGuard.enforce(track.url()); // SSRF 遮断: 内部 IP / 非 http(s) scheme を再生前に弾く
                source = LoaderHolder.get().openStream(track.url(), 0L);
            } catch (final UrlBlockedException blocked) {
                failure = PlaybackFailure.blocked(blocked.reason());
                source = null;
            } catch (final Throwable t) {
                failure = PlaybackFailure.thrown(t);
                source = null;
            }
            final IAudioSource resolved = source;
            // 例外を投げずに null が返った = ローダーが理由を持たない失敗。潰さずここで分類する。
            final PlaybackFailure reported =
                    resolved == null && failure == null ? PlaybackFailure.streamUnavailable() : failure;
            Minecraft.getInstance().execute(() -> {
                if (resolved == null) {
                    // 無音で終わらせない。理由の分類つきでチャットとログの両方に残す (本体の再生経路と同じ出方)。
                    PlaybackFailureReport.report(track, reported);
                    return;
                }
                final DiscSoundInstance instance = new DiscSoundInstance(entity, resolved);
                activeInstance = instance;
                Minecraft.getInstance().getSoundManager().play(instance);
                final String desc = (track.author() != null && !track.author().isBlank())
                        ? track.author() + " - " + track.title()
                        : track.title();
                if (desc != null && !desc.isBlank()) {
                    Minecraft.getInstance().gui.setNowPlaying(Component.literal(desc));
                }
            });
        });
    }

    /** {@code JukeboxWidget.stopDisc} の TAIL から呼ばれる。 */
    public static void onStopClicked() {
        stopInternal();
    }

    private static void stopInternal() {
        final DiscSoundInstance instance = activeInstance;
        if (instance != null) {
            instance.requestStop();
            activeInstance = null;
        }
    }

    /**
     * 現在開いている TB backpack 画面の jukebox upgrade スロットに入っているディスク (無ければ null)。
     * TB の private/継承フィールドへの {@code @Shadow} は使わず、公開 API だけで辿る。
     */
    @Nullable
    private static ItemStack currentDisc() {
        final Minecraft mc = Minecraft.getInstance();
        if (!(mc.player != null && mc.player.containerMenu instanceof BackpackBaseMenu menu)) {
            return null;
        }
        final Optional<JukeboxUpgrade> upgrade =
                menu.getWrapper().getUpgradeManager().getUpgrade(JukeboxUpgrade.class);
        return upgrade.map(u -> u.diskHandler.getStackInSlot(0)).orElse(null);
    }
}
