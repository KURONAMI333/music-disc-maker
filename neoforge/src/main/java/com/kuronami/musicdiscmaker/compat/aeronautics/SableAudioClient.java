package com.kuronami.musicdiscmaker.compat.aeronautics;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.audio.LoaderHolder;
import com.kuronami.musicdiscmaker.client.audio.CompatPlayback;
import com.kuronami.musicdiscmaker.client.audio.DiscSoundInstance;
import com.kuronami.musicdiscmaker.client.audio.LivePlaybackRegistry;
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
 * 周期送信し、late-tracking を拾う) の捌き方は {@link LivePlaybackRegistry} が持つ。解体・再組立で
 * plot 座標が変わると別 key になり張り替わる。
 *
 * <h2>周期再送は「捨てる」のではなく「現在値として取り込む」</h2>
 * server は 1 秒ごとに<b>その時点の</b>指向性・範囲・音量を載せて送ってくる。以前はここで
 * 「同じ plot 座標で再生中なら早期 return」していたので、2 回目以降の payload が一度も読まれず、
 * <b>GUI で設定を変えても Sable に載った音源にだけ永久に反映されなかった</b>。同じ URL の再送は
 * 鳴らし直さずに値だけ押し込む ({@link LivePlaybackRegistry.Decision#LIVE_UPDATE})。
 * 本体の強化版ジュークボックスが {@code DiscSoundInstance#tick} で client 側 BE を再読して
 * 追従する経路は {@code StaticAnchor} 限定なので、Sable anchor はそちらに入らない。
 *
 * <p>世代トークンを使わない理由 (再送間隔がロード時間より短いと一度も鳴らなくなる) と、
 * in-flight ガード・失敗 URL の記憶も {@link LivePlaybackRegistry} の javadoc にある。
 */
public final class SableAudioClient {

    private static final ExecutorService POOL = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "music_disc_maker-sable-playback");
        thread.setDaemon(true);
        return thread;
    });

    /** plot 座標ごとの再生スロット。周期再送の値取り込み・in-flight ガード・失敗記憶を持つ。 */
    private static final LivePlaybackRegistry<Long> SLOTS = new LivePlaybackRegistry<>();

    private SableAudioClient() {
    }

    public static void play(SubLevelPlayDiscPayload payload) {
        final CustomTrackData track = payload.track();
        if (track == null || track.isEmpty()) {
            return;
        }
        final long actorKey = payload.plotPos().asLong();
        // 周期再送は「捨てる」のではなく「現在値として取り込む」。同じ曲が鳴っていれば
        // ここで指向性・範囲・音量が反映され、ロードには進まない。
        if (SLOTS.request(actorKey, track.url(), payload.rangeBlocks(), payload.volumePercent(),
                payload.directional()) != LivePlaybackRegistry.Decision.LOAD) {
            return;
        }
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
                SLOTS.loadFinished(actorKey, track.url());
                if (resolved == null) {
                    // 無音で終わらせない。理由の分類つきでチャットとログの両方に残す (本体の再生経路と同じ出方)。
                    // 同じ URL は記憶し、次の周期再送 (1 秒後) では繋ぎ直さない。
                    SLOTS.loadFailed(actorKey, track.url());
                    PlaybackFailureReport.report(track, reported);
                    return;
                }
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
                // CompatPlayback が失敗の届け先 (push+pull) を繋いだインスタンスを返す。ここで
                // 構築子を直接呼べないのは意図的 (繋ぎ忘れをコンパイルで止める。CompatPlayback の javadoc)。
                final DiscSoundInstance instance = CompatPlayback.wired(
                        anchor, resolved, payload.rangeBlocks(), payload.volumePercent(),
                        payload.directional(), track);
                // ロード中に曲が変わっていたら鳴らさずに捨てる (遅れて完了した古い曲で上書きしない)。
                if (!SLOTS.install(actorKey, track.url(), instance)) {
                    instance.requestStop();
                    return;
                }
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
