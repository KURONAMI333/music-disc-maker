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
 * <h2>周期再送とロード時間の競合</h2>
 * {@link #ACTIVE} への登録は URL ロード完了後 (main thread) にしか起きないため、初回ロードが 1 秒を
 * 超えると次の周期再送が {@link #ACTIVE} 未登録のまま {@link #play} に来る。ここで世代トークンを
 * 使うと「新しい要求が来るたびに前を破棄」する形になり、<b>再送間隔 (1 秒) がロード時間より短い
 * 音源 (ネットワーク経由はほぼ該当) では一度も {@code SoundManager.play()} に届かない</b>
 * (常に後発の世代が先に立ち上がり、先発が完了する頃には後発の後発が既に current を奪っている)。
 * ここで要るのは「後発を勝たせる」世代ではなく「同じロードが進行中なら後発を無視する」
 * {@link #PENDING} の in-flight ガード ({@code PlaybackRequestGate} の judgement と同じ形)。
 *
 * <p>{@link #FAILED_URL} は失敗した URL を plot 座標ごとに記憶し、同じ URL が生きている間は
 * 再送のたびにロードをやり直さない (1 Hz でチャット/ログが埋まるのを防ぐ)。曲が変わる
 * (URL が変わる) か、ロードが成功すればクリアされる。
 */
public final class SableAudioClient {

    private static final ExecutorService POOL = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "music_disc_maker-sable-playback");
        thread.setDaemon(true);
        return thread;
    });

    /** 再生中インスタンスの重複防止。key = plot 座標 (long)。 */
    private static final Map<Long, DiscSoundInstance> ACTIVE = new ConcurrentHashMap<>();

    /** ロード中の要求の URL (in-flight ガード)。key = plot 座標。 */
    private static final Map<Long, String> PENDING = new ConcurrentHashMap<>();

    /** 直近に失敗し報告済みの URL。key = plot 座標。同じ URL の間はロードも報告もやり直さない。 */
    private static final Map<Long, String> FAILED_URL = new ConcurrentHashMap<>();

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
        if (track.url().equals(PENDING.get(actorKey))) {
            return; // 同じ URL を既にロード中 (周期再送)。ロードを重ねない。
        }
        if (track.url().equals(FAILED_URL.get(actorKey))) {
            return; // 同じ URL が直近に失敗済み。曲が変わる/成功するまで繋ぎ直さない。
        }
        PENDING.put(actorKey, track.url());
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
                // in-flight ガードの解除は成否に関わらず必ず行う (残すと以後の再送が全部黙って無視される)。
                PENDING.remove(actorKey, track.url());
                if (resolved == null) {
                    // 無音で終わらせない。理由の分類つきでチャットとログの両方に残す (本体の再生経路と同じ出方)。
                    // 同じ URL は FAILED_URL に記憶し、次の周期再送 (1 秒後) では繋ぎ直さない。
                    FAILED_URL.put(actorKey, track.url());
                    PlaybackFailureReport.report(track, reported);
                    return;
                }
                FAILED_URL.remove(actorKey, track.url());
                if (Minecraft.getInstance().level == null) {
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
                instance.setDirectional(payload.directional());
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
