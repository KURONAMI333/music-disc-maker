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
 *
 * <h2>飛行中の keep-alive は捨てる</h2>
 * 世代トークンは「着地したロードのうちどれを採るか」しか決めない。撃ち直しそのものは止めないので、
 * server の keep-alive 間隔 ({@code BoomboxPlayback.HEARTBEAT_MS}) より URL 解決が遅い曲では、
 * まだ何も鳴っていない = 上の dedup が素通りする窓に keep-alive が入り、ロードを畳んで撃ち直す。
 * それが解決より速く繰り返されると<b>永久に音が立たない</b>。そこで飛行中のロードを覚えておき、
 * 同じロード (URL と持ち主が同じ) の再送はここで捨てる。
 *
 * <p>この窓の間の音量・指向性の変更は着地に載らないが、着地後いちばん最初の keep-alive で
 * アンカーへ入る (最大 1 keep-alive ぶん遅れる)。
 *
 * <p><b>{@code PENDING} の読み書きは main thread からだけ</b> ({@link PlaybackSessions} と同じ約束)。
 * 受信ハンドラも {@code onLoaded} の再入も main thread に居るので、この対は崩れない。
 */
public final class BoomboxClientPlayback {

    private record Playing(String url, BoomboxAnchor anchor, DiscSoundInstance instance) {
    }

    /** 飛行中のロードの中身。keep-alive が「これと同じロード」かを判別するのに使う。 */
    private record Pending(String url, int ownerEntityId) {
    }

    private static final Map<UUID, Playing> ACTIVE = new ConcurrentHashMap<>();
    private static final PlaybackSessions<UUID> SESSIONS = new PlaybackSessions<>();
    /**
     * 個体ごとの飛行中のロード。server は client の着地を知らないまま
     * {@code BoomboxPlayback.HEARTBEAT_MS} 間隔で撃ち続けるので、これが無いと URL 解決が
     * その間隔より遅い曲で keep-alive が毎回ロードを畳んで撃ち直し、永久に音が立たない。
     * 世代トークンと役割が違う (あちらは着地の採否・こちらは撃ち直しの抑止)。
     */
    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();

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
        // 同じロードが飛行中の keep-alive は捨てる。ここで撃ち直すと、URL 解決が heartbeat より
        // 遅い曲では毎回そのロードを畳んで最初からやり直し = 永久に音が立たない。
        // 停止は PENDING を落とすので、停止 → 同一 URL 再開はこの分岐に落ちない (再開は通る)。
        if (new Pending(track.url(), ownerEntityId).equals(PENDING.get(boomboxId))) {
            return;
        }
        stop(boomboxId);
        final long token = SESSIONS.begin(boomboxId);
        PENDING.put(boomboxId, new Pending(track.url(), ownerEntityId));
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
        // この世代の飛行は終わった (成否・着地の可否は問わない)。追い越された古いロードでは
        // 触らない — PENDING に載っているのは後続の要求のものなので、消すと撃ち直しが復活する。
        if (SESSIONS.isCurrent(boomboxId, token)) {
            PENDING.remove(boomboxId);
        }
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
        PENDING.remove(boomboxId);
        final Playing playing = ACTIVE.remove(boomboxId);
        if (playing != null) {
            playing.instance().requestStop();
            Minecraft.getInstance().getSoundManager().stop(playing.instance());
        }
    }

    /** 切断時の一括停止 ({@link ClientPlaybackManager#stopAll()} から呼ばれる)。 */
    public static void stopAll() {
        SESSIONS.cancelAll();
        PENDING.clear();
        ACTIVE.values().forEach(playing -> {
            playing.instance().requestStop();
            Minecraft.getInstance().getSoundManager().stop(playing.instance());
        });
        ACTIVE.clear();
    }
}
