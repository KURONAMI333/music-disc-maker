package com.kuronami.musicdiscmaker.compat.create;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.audio.LoaderHolder;
import com.kuronami.musicdiscmaker.client.audio.CompatPlayback;
import com.kuronami.musicdiscmaker.client.audio.DiscSoundInstance;
import com.kuronami.musicdiscmaker.client.audio.PlaybackFailure;
import com.kuronami.musicdiscmaker.client.audio.PlaybackFailureReport;
import com.kuronami.musicdiscmaker.client.audio.SoundEngineAcceptance;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.OpenStreamResult;
import com.kuronami.musicdiscmaker.network.UrlBlockedException;
import com.kuronami.musicdiscmaker.network.UrlGuard;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/**
 * client 側: Create contraption に載った MDM 音源ブロックの custom disc を LavaPlayer 再生する。
 * server の {@link CreateAudioMovementBehaviour#startMoving} が送る {@link ContraptionPlayDiscPayload}
 * を受けて、entityId から {@code AbstractContraptionEntity} を解決し {@link ContraptionAnchor} で追従再生する。
 *
 * <p>この class の Create 参照 ({@link AbstractContraptionEntity}/{@link ContraptionAnchor}) は
 * {@link #play} が呼ばれた時のみ class-load される。{@link #play} は Create が送った payload 受信時に
 * しか呼ばれないので、Create 非導入環境では到達しない。{@code SophisticatedCoreCompatClient} と同型。
 */
public final class CreateAudioClient {

    private static final ExecutorService POOL = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "music_disc_maker-create-playback");
        thread.setDaemon(true);
        return thread;
    });

    /** 再生中インスタンスの重複防止。key = contraption entityId + local pos。 */
    private static final Map<Long, DiscSoundInstance> ACTIVE = new ConcurrentHashMap<>();

    private CreateAudioClient() {
    }

    private static long key(int entityId, long localPosLong) {
        return (((long) entityId) << 32) ^ (localPosLong & 0xFFFFFFFFL);
    }

    public static void play(ContraptionPlayDiscPayload payload) {
        final CustomTrackData track = payload.track();
        if (track == null || track.isEmpty()) {
            return;
        }
        // 診断 (debug 既定 off): startMoving の送信ログが出るのにこれが出なければ payload が client へ
        // 届いていない (tracker 未確立/配送) を疑う。両方出るのに無音なら ContraptionAnchor の座標/capture 側。
        MusicDiscMaker.LOGGER.debug("Received Create contraption playback: entityId={} localPos={}",
                payload.contraptionEntityId(), payload.localPos());
        final long actorKey = key(payload.contraptionEntityId(), payload.localPos().asLong());
        final DiscSoundInstance previous = ACTIVE.get(actorKey);
        if (previous != null && !previous.isStopped()) {
            return; // 同じ actor で既に再生中 (payload 再送) はスキップ。
        }
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
                final Entity entity = Minecraft.getInstance().level.getEntity(payload.contraptionEntityId());
                if (!(entity instanceof AbstractContraptionEntity contraption)) {
                    resolved.close();
                    return; // contraption が既に消えている等。
                }
                final ContraptionAnchor anchor = new ContraptionAnchor(contraption, payload.localPos());
                // CompatPlayback が失敗の届け先 (push+pull) を繋いだインスタンスを返す。ここで
                // 構築子を直接呼べないのは意図的 (繋ぎ忘れをコンパイルで止める。CompatPlayback の javadoc)。
                final DiscSoundInstance instance = CompatPlayback.wired(
                        anchor, resolved, payload.rangeBlocks(), payload.volumePercent(),
                        payload.directional(), track);
                ACTIVE.put(actorKey, instance);
                // play は受理しなかったことを戻り値で返さない (SoundEngineAcceptance の javadoc)。
                // 見ずに進むと、鳴っていないのに "Now Playing" が出たまま固定される。
                if (!SoundEngineAcceptance.start(instance, instance,
                        rejected -> PlaybackFailureReport.report(track, rejected))) {
                    ACTIVE.remove(actorKey, instance); // 鳴っていない席を残さない (次の payload が弾かれる)
                    return;
                }
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
