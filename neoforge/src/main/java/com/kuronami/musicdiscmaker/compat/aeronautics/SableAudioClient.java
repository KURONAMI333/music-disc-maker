package com.kuronami.musicdiscmaker.compat.aeronautics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.audio.LoaderHolder;
import com.kuronami.musicdiscmaker.client.audio.DiscSoundInstance;
import com.kuronami.musicdiscmaker.client.audio.PlaybackFailure;
import com.kuronami.musicdiscmaker.client.audio.PlaybackFailureReport;
import com.kuronami.musicdiscmaker.client.audio.PlaybackGenerations;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.OpenStreamResult;
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
 * <p>同一 plot 座標への再送 (server は sub-level tracking player 全員へ {@code SWEEP_INTERVAL}=20 tick (1 秒) 毎に
 * 周期送信し、late-tracking を拾う) は {@link #ACTIVE} で dedup する。解体・再組立で plot 座標が変わると
 * 別 key になり張り替わる。
 *
 * <p>{@link #ACTIVE} への登録は URL ロード完了後 (main thread) にしか起きないため、初回ロードが 1 秒を
 * 超えると次の周期再送が {@link #ACTIVE} 未登録のまま {@link #play} に来て、2 本目のロードが並走しうる
 * (Create の捕獲式のような「actor 1 回きりの送信」ではなく、Sable は継続的に送り続ける方式のため)。
 * {@link #GENERATIONS} は plot 座標ごとにこの並走を検出し、後発のロードだけを完了させる。
 */
public final class SableAudioClient {

    private static final ExecutorService POOL = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "music_disc_maker-sable-playback");
        thread.setDaemon(true);
        return thread;
    });

    /** 再生中インスタンスの重複防止。key = plot 座標 (long)。 */
    private static final Map<Long, DiscSoundInstance> ACTIVE = new ConcurrentHashMap<>();

    /**
     * plot 座標ごとの再生世代。{@link #ACTIVE} はロード完了までその plot の再生を持たないので、
     * 周期再送 (1 秒毎) が初回ロード完了前に届くと dedup が効かず、並走したロードの両方が音源を
     * 登録してしまう。世代を進めて、完了時点で最新でないロードは鳴らさずに閉じる。
     */
    private static final PlaybackGenerations<Long> GENERATIONS = new PlaybackGenerations<>();

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
        final int generation = GENERATIONS.begin(actorKey);
        // 診断 (debug 既定 off): server の送信ログが出るのにこれが出なければ payload が client へ届いていない
        // (sub-level tracking の解決漏れ / 配送) を疑う。両方出るのに無音なら SableSubLevelAnchor の座標変換側。
        MusicDiscMaker.LOGGER.debug("Sable sub-level 再生を受信: plotPos={}", payload.plotPos());
        // URL ロードはブロックするので別 thread、SoundManager 操作は main thread。
        POOL.submit(() -> {
            IAudioSource source = null;
            PlaybackFailure failure = null;
            try {
                UrlGuard.enforce(track.url()); // SSRF 遮断: 内部 IP / 非 http(s) scheme を再生前に弾く
                // 理由つきで開く。null 判定 1 つに潰すと、DNS 失敗も年齢制限も bot 判定も同じ文面になる。
                final OpenStreamResult result = LoaderHolder.get().openStreamDetailed(track.url(), payload.startOffsetMs());
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
                if (Minecraft.getInstance().level == null) {
                    resolved.close();
                    return;
                }
                // ロード中に別の周期再送が先に完了して登録済み / 更に新しい再送が来ていたら、この古いロードは破棄する。
                if (!GENERATIONS.isCurrent(actorKey, generation)) {
                    resolved.close();
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
