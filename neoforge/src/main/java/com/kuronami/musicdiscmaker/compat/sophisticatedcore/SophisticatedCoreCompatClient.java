package com.kuronami.musicdiscmaker.compat.sophisticatedcore;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.kuronami.musicdiscmaker.audio.LoaderHolder;
import com.kuronami.musicdiscmaker.client.audio.DiscSoundInstance;
import com.kuronami.musicdiscmaker.client.audio.PlaybackFailure;
import com.kuronami.musicdiscmaker.client.audio.PlaybackFailureReport;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.OpenStreamResult;
import com.kuronami.musicdiscmaker.network.UrlBlockedException;
import com.kuronami.musicdiscmaker.network.UrlGuard;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.p3pp3rf1y.sophisticatedcore.upgrades.jukebox.StorageSoundHandler;

/**
 * client 側: backpack(storageUuid) 位置で MDM の custom disc を LavaPlayer 再生する。
 * 通常の jukebox 再生 ({@code ClientPlaybackManager}) と違い、SC の {@link StorageSoundHandler} に
 * storageUuid で登録するので、SC が送る停止 (StopDiscPlaybackPayload → stopStorageSound) で
 * この DiscSoundInstance も自動的に止まる。
 *
 * <p>この class の SC 参照 ({@link StorageSoundHandler}) は {@link #play} が呼ばれた時のみ
 * class-load される。{@link #play} は SC が送った {@link BackpackPlayDiscPayload} 受信時にしか
 * 呼ばれないので、SC 非導入環境では到達しない。
 */
public final class SophisticatedCoreCompatClient {

    private static final ExecutorService POOL = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "music_disc_maker-sc-playback");
        thread.setDaemon(true);
        return thread;
    });

    private SophisticatedCoreCompatClient() {
    }

    public static void play(BackpackPlayDiscPayload payload) {
        final CustomTrackData track = payload.track();
        if (track == null || track.isEmpty()) {
            return;
        }
        // URL ロードはブロックするので別 thread、SoundManager 操作は main thread。
        POOL.submit(() -> {
            IAudioSource source = null;
            PlaybackFailure failure = null;
            try {
                UrlGuard.enforce(track.url()); // SSRF 遮断: 内部 IP / 非 http(s) scheme を再生前に弾く
                // 理由つきで開く。null 判定 1 つに潰すと、DNS 失敗も年齢制限も bot 判定も同じ文面になる。
                final OpenStreamResult result = LoaderHolder.get().openStreamDetailed(track.url(), 0L);
                source = result.source();
                failure = result.isOk() ? null
                        : PlaybackFailure.ofReason(result.reason(), result.detail());
            } catch (final UrlBlockedException blocked) {
                failure = PlaybackFailure.blocked(blocked.reason());
            } catch (final Throwable t) {
                failure = PlaybackFailure.thrown(t);
            }
            final IAudioSource resolved = source;
            final PlaybackFailure reported = failure;
            Minecraft.getInstance().execute(() -> {
                if (resolved == null) {
                    // 無音で終わらせない。理由の分類つきでチャットとログの両方に残す (本体の再生経路と同じ出方)。
                    PlaybackFailureReport.report(track, reported);
                    return;
                }
                // entityId>=0 ならその entity (backpack を背負うプレイヤー等) に追従、なければ固定 pos。
                final Entity entity = (payload.entityId() >= 0 && Minecraft.getInstance().level != null)
                        ? Minecraft.getInstance().level.getEntity(payload.entityId())
                        : null;
                final DiscSoundInstance instance = entity != null
                        ? new DiscSoundInstance(entity, resolved)
                        : new DiscSoundInstance(payload.pos(), resolved);
                // SC の storageUuid 管理に載せる (= SC の停止がこの音声を止める)。play 内部で SoundManager.play 済み。
                StorageSoundHandler.playStorageSound(payload.storageUuid(), instance);
                final String desc = (track.author() != null && !track.author().isBlank())
                        ? track.author() + " - " + track.title()
                        : track.title();
                if (desc != null && !desc.isBlank()) {
                    Minecraft.getInstance().gui.setNowPlaying(Component.literal(desc));
                }
            });
        });
    }
}
