package com.kuronami.musicdiscmaker.client.audio;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.audio.LoaderHolder;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/**
 * client 側の再生統括。jukebox 位置ごとに 1 再生を管理する。
 * URL ロードはブロックするので別 thread、SoundManager 操作は main thread。
 */
public final class ClientPlaybackManager {

    private static final ClientPlaybackManager INSTANCE = new ClientPlaybackManager();

    public static ClientPlaybackManager get() {
        return INSTANCE;
    }

    private final Map<BlockPos, DiscSoundInstance> active = new ConcurrentHashMap<>();
    private final Set<BlockPos> wanted = ConcurrentHashMap.newKeySet();
    private final ExecutorService pool = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "music_disc_maker-playback");
        thread.setDaemon(true);
        return thread;
    });

    private ClientPlaybackManager() {
    }

    public void startPlayback(BlockPos pos, CustomTrackData track) {
        stopPlayback(pos);
        if (track == null || track.isEmpty()) {
            return;
        }
        final BlockPos key = pos.immutable();
        wanted.add(key);

        pool.submit(() -> {
            IAudioSource source;
            try {
                source = LoaderHolder.get().openStream(track.url());
            } catch (final Throwable t) {
                MusicDiscMaker.LOGGER.warn("再生用ストリーム生成に失敗 ({}): {}", track.url(), t.toString());
                source = null;
            }
            final IAudioSource resolved = source;
            Minecraft.getInstance().execute(() -> {
                if (resolved == null) {
                    wanted.remove(key);
                    return;
                }
                if (!wanted.contains(key)) {
                    resolved.close(); // ロード中に停止要求済み
                    return;
                }
                // 自然終了したインスタンスを掃除 (MAX_CONCURRENT を不当に消費させない)
                active.entrySet().removeIf(e -> e.getValue().isStopped());
                if (active.size() >= com.kuronami.musicdiscmaker.Config.MAX_CONCURRENT.get()) {
                    MusicDiscMaker.LOGGER.info("同時再生上限に達したため再生をスキップ: {}", key);
                    resolved.close();
                    wanted.remove(key);
                    return;
                }
                final DiscSoundInstance instance = new DiscSoundInstance(key, resolved);
                active.put(key, instance);
                Minecraft.getInstance().getSoundManager().play(instance);
                // vanilla disc と同じ "Now Playing: ..." overlay を出す
                final String desc = (track.author() != null && !track.author().isBlank())
                        ? track.author() + " - " + track.title()
                        : track.title();
                if (desc != null && !desc.isBlank()) {
                    Minecraft.getInstance().gui.setNowPlaying(net.minecraft.network.chat.Component.literal(desc));
                }
            });
        });
    }

    public void stopPlayback(BlockPos pos) {
        final BlockPos key = pos.immutable();
        wanted.remove(key);
        final DiscSoundInstance instance = active.remove(key);
        if (instance != null) {
            instance.requestStop();
            Minecraft.getInstance().getSoundManager().stop(instance);
        }
    }

    public void stopAll() {
        wanted.clear();
        active.values().forEach(instance -> {
            instance.requestStop();
            Minecraft.getInstance().getSoundManager().stop(instance);
        });
        active.clear();
    }
}
