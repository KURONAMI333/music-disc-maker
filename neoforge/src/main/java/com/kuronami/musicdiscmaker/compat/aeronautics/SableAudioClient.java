package com.kuronami.musicdiscmaker.compat.aeronautics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

/**
 * client 側: Create Aeronautics の物理 sub-level に載った MDM 音源ブロックの custom disc を LavaPlayer 再生する。
 * server の {@link SableServerAudio} が sub-level tracking player へ送る {@link SubLevelPlayDiscPayload} を受けて、
 * plot 座標に張り付いた {@link SableSubLevelAnchor} で変換式追従再生する。
 *
 * <p>この class の Sable 参照 ({@link SableSubLevelAnchor} 経由) は {@link #play} が呼ばれた時のみ
 * class-load される。{@link #play} は Sable が送った payload 受信時にしか呼ばれないので、Sable 非導入環境では
 * 到達しない。{@code CreateAudioClient} (捕獲式) と同型。
 *
 * <p>同一 plot 座標への再送 (server は sub-level tracking player 全員へ周期送信し、late-tracking を拾う) は
 * {@link #ACTIVE} で dedup する。解体・再組立で plot 座標が変わると別 key になり張り替わる。
 */
public final class SableAudioClient {

    private static final ExecutorService POOL = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "music_disc_maker-sable-playback");
        thread.setDaemon(true);
        return thread;
    });

    /** 再生中インスタンスの重複防止。key = plot 座標 (long)。 */
    private static final Map<Long, DiscSoundInstance> ACTIVE = new ConcurrentHashMap<>();

    private SableAudioClient() {
    }

    public static void play(SubLevelPlayDiscPayload payload) {
        final CustomTrackData track = payload.track();
        if (track == null || track.isEmpty()) {
            return;
        }
        final long actorKey = payload.plotPos().asLong();
        final DiscSoundInstance previous = ACTIVE.get(actorKey);
        if (previous != null && !previous.isStopped()) {
            return; // 同じ plot 座標で既に再生中 (周期再送 / late-tracking 再送) はスキップ。
        }
        // 診断 (debug 既定 off): server の送信ログが出るのにこれが出なければ payload が client へ届いていない
        // (sub-level tracking の解決漏れ / 配送) を疑う。両方出るのに無音なら SableSubLevelAnchor の座標変換側。
        MusicDiscMaker.LOGGER.debug("Sable sub-level 再生を受信: plotPos={}", payload.plotPos());
        // URL ロードはブロックするので別 thread、SoundManager 操作は main thread。
        POOL.submit(() -> {
            IAudioSource source;
            try {
                UrlGuard.enforce(track.url()); // SSRF 遮断: 内部 IP / 非 http(s) scheme を再生前に弾く
                source = LoaderHolder.get().openStream(track.url(), payload.startOffsetMs());
            } catch (final UrlBlockedException blocked) {
                MusicDiscMaker.LOGGER.warn("Sable sub-level 再生 URL を拒否 ({}): {}", blocked.reason(), track.url());
                source = null;
            } catch (final Throwable t) {
                MusicDiscMaker.LOGGER.warn("Sable sub-level 用ストリーム生成に失敗 ({}): {}", track.url(), t.toString());
                source = null;
            }
            final IAudioSource resolved = source;
            Minecraft.getInstance().execute(() -> {
                if (resolved == null || Minecraft.getInstance().level == null) {
                    return;
                }
                final SableSubLevelAnchor anchor = new SableSubLevelAnchor(payload.plotPos());
                // plot が client に無い (sub-level 未 tracking) 場合は再生しない (無音より安全)。
                if (!anchor.isValid()) {
                    resolved.close();
                    return;
                }
                final DiscSoundInstance instance = new DiscSoundInstance(
                        anchor, resolved, payload.rangeBlocks(), payload.volumePercent(), null);
                ACTIVE.put(actorKey, instance);
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
}
