package com.kuronami.musicdiscmaker.compat.sophisticatedcore;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.audio.LoaderHolder;
import com.kuronami.musicdiscmaker.client.audio.DiscSoundInstance;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.network.UrlBlockedException;
import com.kuronami.musicdiscmaker.network.UrlGuard;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.p3pp3rf1y.sophisticatedcore.upgrades.jukebox.StorageSoundHandler;

/**
 * client 側 (Forge 1.20.1): backpack(storageUuid) 位置で MDM の custom disc を LavaPlayer 再生する。
 * SC の {@link StorageSoundHandler} に storageUuid で登録するので、SC が送る停止でこの音声も止まる。
 *
 * <p>SC 参照は {@link #play} 内のみ。{@link #play} は SC が送った {@link BackpackPlayDiscPayload} 受信時に
 * しか呼ばれないので、SC 非導入環境では到達しない。
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
        POOL.submit(() -> {
            IAudioSource source;
            try {
                UrlGuard.enforce(track.url()); // SSRF 遮断: 内部 IP / 非 http(s) scheme を再生前に弾く
                source = LoaderHolder.get().openStream(track.url(), 0L);
            } catch (final UrlBlockedException blocked) {
                MusicDiscMaker.LOGGER.warn("SB jukebox 再生 URL を拒否 ({}): {}", blocked.reason(), track.url());
                source = null;
            } catch (final Throwable t) {
                MusicDiscMaker.LOGGER.warn("SB jukebox 用ストリーム生成に失敗 ({}): {}", track.url(), t.toString());
                source = null;
            }
            final IAudioSource resolved = source;
            Minecraft.getInstance().execute(() -> {
                if (resolved == null) {
                    return;
                }
                final Entity entity = (payload.entityId() >= 0 && Minecraft.getInstance().level != null)
                        ? Minecraft.getInstance().level.getEntity(payload.entityId())
                        : null;
                final DiscSoundInstance instance = entity != null
                        ? new DiscSoundInstance(entity, resolved)
                        : new DiscSoundInstance(payload.pos(), resolved);
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
