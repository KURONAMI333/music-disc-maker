package com.kuronami.musicdiscmaker.client.audio;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.kuronami.musicdiscmaker.Config;
import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.network.UrlBlockedException;
import com.kuronami.musicdiscmaker.network.UrlGuard;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/**
 * client 側: ブームボックスの再生統括。<b>key はアイテム個体の UUID</b>。
 *
 * <p>{@link ClientPlaybackManager} と分けてあるのは key 空間が違うため (向こうは全ての map が
 * 音源 BlockPos)。停止だけは {@code stopAll()} 経由でまとめて掃除する。
 *
 * <h2>dedup は世代トークンで行う</h2>
 * 「同じ URL が鳴っているか」で dedup すると、停止 → 同一 URL で即再開したときに飛行中の古い
 * ロードが勝って新しい要求を握り潰す。{@link PlaybackSessions} の世代トークンに乗せることで
 * 「最新の要求が勝つ」を原理的に保証する — ブロック起点の再生と同じ機構。
 *
 * <p>keep-alive (同じ URL の再送) ではストリームに触らず、音量・指向性だけを
 * {@link BoomboxAnchor} へ書き込む。{@code DiscSoundInstance#tick} がそれを読むので、GUI の変更が
 * <b>鳴らし直さずに</b>反映される。
 */
public final class BoomboxClientPlayback {

    private record Playing(String url, BoomboxAnchor anchor, DiscSoundInstance instance) {
    }

    private static final Map<UUID, Playing> ACTIVE = new ConcurrentHashMap<>();
    private static final PlaybackSessions<UUID> SESSIONS = new PlaybackSessions<>();

    private static final ExecutorService POOL = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "music_disc_maker-boombox-playback");
        thread.setDaemon(true);
        return thread;
    });

    private BoomboxClientPlayback() {
    }

    /**
     * 再生 / keep-alive の受信。同じ曲なら生存時刻と設定を更新するだけで、ストリームには触らない。
     *
     * @param ownerEntityId 音の出どころ (持ち主) の entity id。持ち替えでは変わらない
     */
    public static void play(UUID boomboxId, int ownerEntityId, CustomTrackData track, long startOffsetMs,
            int volumePercent, boolean directional) {
        if (boomboxId == null || track == null || track.isEmpty()) {
            return;
        }
        final Playing current = ACTIVE.get(boomboxId);
        if (current != null && !current.instance().isStopped() && current.url().equals(track.url())) {
            current.anchor().refresh(volumePercent, directional);
            return;
        }
        stop(boomboxId);
        final long token = SESSIONS.begin(boomboxId);
        POOL.submit(() -> {
            IAudioSource source;
            try {
                UrlGuard.enforce(track.url()); // SSRF 遮断 (他プレイヤーの client を踏み台にさせない)
                source = ClientAudioStreams.open(track, startOffsetMs);
            } catch (final UrlBlockedException blocked) {
                MusicDiscMaker.LOGGER.warn("ブームボックスの再生 URL を拒否 ({}): {}", blocked.reason(), track.url());
                source = null;
            } catch (final Throwable t) {
                MusicDiscMaker.LOGGER.warn("ブームボックス用ストリーム生成に失敗 ({}): {}", track.url(), t.toString());
                source = null;
            }
            final IAudioSource resolved = source;
            Minecraft.getInstance().execute(() -> onLoaded(boomboxId, ownerEntityId, track, volumePercent,
                    directional, resolved, token));
        });
    }

    private static void onLoaded(UUID boomboxId, int ownerEntityId, CustomTrackData track, int volumePercent,
            boolean directional, IAudioSource resolved, long token) {
        if (resolved == null) {
            return;
        }
        // 世代が追い越されている = 停止済み、または後続の要求が来た。孤児を作らずここで捨てる。
        if (SESSIONS.onLoadComplete(boomboxId, token) == PlaybackSessions.LoadOutcome.DISCARD) {
            resolved.close();
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        final Entity entity = mc.level != null ? mc.level.getEntity(ownerEntityId) : null;
        if (entity == null) {
            resolved.close(); // 持ち主が視界から消えた
            return;
        }
        // 自然終了したインスタンスを掃除してから、同時再生上限をブロック起点の再生と共有する。
        // OpenAL の streaming チャンネルは 2〜8 本しかなくバニラ BGM とも奪い合う。溢れると
        // ログも出ずに無言で鳴らないので、上限は必ずどこかで効かせる。
        ACTIVE.entrySet().removeIf(e -> e.getValue().instance().isStopped());
        final int total = ClientPlaybackManager.get().activeCount() + ACTIVE.size();
        if (total >= Config.maxConcurrent()) {
            MusicDiscMaker.LOGGER.info("同時再生上限に達したためブームボックスの再生をスキップ: {}", boomboxId);
            SESSIONS.cancel(boomboxId);
            resolved.close();
            return;
        }
        final BoomboxAnchor anchor = new BoomboxAnchor(entity, volumePercent,
                BoomboxContents.RANGE_BLOCKS, directional);
        final DiscSoundInstance instance = new DiscSoundInstance(anchor, resolved,
                BoomboxContents.RANGE_BLOCKS, volumePercent, null);
        instance.setDirectional(directional);
        // 生きたインスタンスを黙って上書きしない (世代トークンがあれば到達しないが、未知の経路への歯止め)。
        final Playing previous = ACTIVE.put(boomboxId, new Playing(track.url(), anchor, instance));
        if (previous != null) {
            previous.instance().requestStop();
            mc.getSoundManager().stop(previous.instance());
        }
        mc.getSoundManager().play(instance);
        final String desc = (track.author() != null && !track.author().isBlank())
                ? track.author() + " - " + track.title()
                : track.title();
        if (desc != null && !desc.isBlank()) {
            mc.gui.setNowPlaying(Component.literal(desc));
        }
    }

    public static void stop(UUID boomboxId) {
        SESSIONS.cancel(boomboxId);
        final Playing playing = ACTIVE.remove(boomboxId);
        if (playing != null) {
            playing.instance().requestStop();
            Minecraft.getInstance().getSoundManager().stop(playing.instance());
        }
    }

    /** 切断時の一括停止 ({@link ClientPlaybackManager#stopAll()} から呼ばれる)。 */
    public static void stopAll() {
        SESSIONS.cancelAll();
        ACTIVE.values().forEach(playing -> {
            playing.instance().requestStop();
            Minecraft.getInstance().getSoundManager().stop(playing.instance());
        });
        ACTIVE.clear();
    }
}
