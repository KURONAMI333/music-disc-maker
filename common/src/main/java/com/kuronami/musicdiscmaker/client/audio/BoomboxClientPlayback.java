package com.kuronami.musicdiscmaker.client.audio;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.audio.LoaderHolder;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.network.UrlBlockedException;
import com.kuronami.musicdiscmaker.network.UrlGuard;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/**
 * client 側: 手持ちブームボックスの再生統括。key は音源 entity の id。
 *
 * <p>{@link ClientPlaybackManager} と分けてあるのは key 空間が違うため (向こうは全ての map が
 * 音源 BlockPos)。停止だけは {@code stopAll()} 経由でまとめて掃除する。
 *
 * <p>dedup は <b>URL だけ</b>で行う。server の keep-alive は毎回進んだ offset を載せてくるので、
 * offset を判定に入れると 1 秒ごとに再ストリームが走る。曲を変えたら URL が変わるので拾える。
 */
public final class BoomboxClientPlayback {

    private record Playing(String url, BoomboxAnchor anchor, DiscSoundInstance instance) {
    }

    private static final Map<Integer, Playing> ACTIVE = new ConcurrentHashMap<>();
    /** ロード中の要求 (entityId → url)。遅延ロード完了時に「まだ望まれているか」を見る。 */
    private static final Map<Integer, String> WANTED = new ConcurrentHashMap<>();

    private static final ExecutorService POOL = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "music_disc_maker-boombox-playback");
        thread.setDaemon(true);
        return thread;
    });

    private BoomboxClientPlayback() {
    }

    /**
     * 再生 / keep-alive の受信。同じ曲なら生存時刻を更新するだけで、ストリームには触らない。
     */
    public static void play(int entityId, CustomTrackData track, long startOffsetMs, int rangeBlocks,
            int volumePercent, boolean directional) {
        if (track == null || track.isEmpty()) {
            return;
        }
        final Playing current = ACTIVE.get(entityId);
        if (current != null && !current.instance().isStopped() && current.url().equals(track.url())) {
            current.anchor().refresh();
            return;
        }
        if (track.url().equals(WANTED.get(entityId))) {
            return; // 同じ曲を今ロード中 (keep-alive が重ならないように)
        }
        stop(entityId);
        WANTED.put(entityId, track.url());
        POOL.submit(() -> {
            IAudioSource source;
            try {
                UrlGuard.enforce(track.url()); // SSRF 遮断 (他プレイヤーの client を踏み台にさせない)
                source = LoaderHolder.get().openStream(track.url(), startOffsetMs);
            } catch (final UrlBlockedException blocked) {
                MusicDiscMaker.LOGGER.warn("ブームボックスの再生 URL を拒否 ({}): {}", blocked.reason(), track.url());
                source = null;
            } catch (final Throwable t) {
                MusicDiscMaker.LOGGER.warn("ブームボックス用ストリーム生成に失敗 ({}): {}", track.url(), t.toString());
                source = null;
            }
            final IAudioSource resolved = source;
            Minecraft.getInstance().execute(() -> onLoaded(entityId, track, rangeBlocks, volumePercent,
                    directional, resolved));
        });
    }

    private static void onLoaded(int entityId, CustomTrackData track, int rangeBlocks, int volumePercent,
            boolean directional, IAudioSource resolved) {
        // 2 引数版。ロードが並んだ時、先に終わった古い方が新しい要求のエントリを消さないようにする。
        final boolean stillWanted = WANTED.remove(entityId, track.url());
        if (resolved == null) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        final Entity entity = mc.level != null ? mc.level.getEntity(entityId) : null;
        if (!stillWanted || entity == null) {
            resolved.close(); // ロード中に停止要求 / 持ち主が視界から消えた
            return;
        }
        // 自然終了したインスタンスを掃除してから、同時再生上限をブロック起点の再生と共有する。
        // OpenAL の streaming チャンネルは 2〜8 本しかなくバニラ BGM とも奪い合う。溢れると
        // ログも出ずに無言で鳴らないので、上限は必ずどこかで効かせる。
        ACTIVE.entrySet().removeIf(e -> e.getValue().instance().isStopped());
        final int total = ClientPlaybackManager.get().activeCount() + ACTIVE.size();
        if (total >= com.kuronami.musicdiscmaker.Config.maxConcurrent()) {
            MusicDiscMaker.LOGGER.info("同時再生上限に達したためブームボックスの再生をスキップ: entity {}", entityId);
            resolved.close();
            return;
        }
        final BoomboxAnchor anchor = new BoomboxAnchor(entity);
        final DiscSoundInstance instance =
                new DiscSoundInstance(anchor, resolved, rangeBlocks, volumePercent, null);
        instance.setDirectional(directional);
        ACTIVE.put(entityId, new Playing(track.url(), anchor, instance));
        mc.getSoundManager().play(instance);
        final String desc = (track.author() != null && !track.author().isBlank())
                ? track.author() + " - " + track.title()
                : track.title();
        if (desc != null && !desc.isBlank()) {
            mc.gui.setNowPlaying(Component.literal(desc));
        }
    }

    public static void stop(int entityId) {
        WANTED.remove(entityId);
        final Playing playing = ACTIVE.remove(entityId);
        if (playing != null) {
            playing.instance().requestStop();
            Minecraft.getInstance().getSoundManager().stop(playing.instance());
        }
    }

    /** 切断時の一括停止 ({@link ClientPlaybackManager#stopAll()} から呼ばれる)。 */
    public static void stopAll() {
        WANTED.clear();
        ACTIVE.values().forEach(playing -> {
            playing.instance().requestStop();
            Minecraft.getInstance().getSoundManager().stop(playing.instance());
        });
        ACTIVE.clear();
    }
}
