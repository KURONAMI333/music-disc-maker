package com.kuronami.musicdiscmaker.compat.pocketjukebox;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.audio.LoaderHolder;
import com.kuronami.musicdiscmaker.client.audio.CompatPlayback;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * client 側: Additional Additions の携帯ジュークボックス (Pocket Jukebox) で MDM の custom disc を
 * LavaPlayer 再生する。**このクラスは AA の型を一切参照しない** (呼び出し元の
 * {@code PocketJukeboxPlayerMixin} が vanilla の型だけを渡す)。
 *
 * <h2>AA 側の構造</h2>
 * 携帯ジュークボックスの再生状態は AA の client 専用 singleton {@code PocketJukeboxPlayer.INSTANCE}
 * にしか無い (server は advancement を撃つだけ・packet 無し)。押した本人の client が状態も音源も
 * 全部持つので、この compat も packet を一切使わない。蓄音機で踏んだ「BE を消した後は停止を送れず、
 * 既に聴いているクライアントが鳴り続ける」という穴は、そもそも聴き手が自分しかいないので発生しない。
 *
 * <h2>入口 3 つ</h2>
 * <ul>
 *   <li>{@link #reconcile} — AA の {@code tick()} の出口から毎 client tick。担当トラックの張り替え</li>
 *   <li>{@link #holdsAdvance} — AA の {@code startNextTrack()} の入口。真なら曲送りを保留させる</li>
 *   <li>{@link #stop} — AA の {@code stop()} の入口。寿命の切れ目は全部ここに合流する</li>
 * </ul>
 *
 * <h2>寿命の切れ目</h2>
 * AA の {@code canContinuePlaying()} が毎 tick 見ている条件 (プレイヤーが除去された / 死亡した /
 * 携帯ジュークボックスが手にもインベントリにも無い = ドロップ・受け渡し / level が無い =
 * 切断・ワールド退出 / 機能が config で無効) は<b>全て {@code stop()} に合流する</b>ので、
 * {@link #stop} を塞いでおけば個別に見張る必要が無い。インベントリ内の移動は AA が
 * {@code Inventory.contains} で許容しているので鳴り続ける (AA の意図どおり)。
 * 拾い直した別のプレイヤーの client では {@code play()} が走っていないので何も鳴らない。
 */
public final class PocketJukeboxClient {

    private static final ExecutorService POOL = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "music_disc_maker-pocket-jukebox-playback");
        thread.setDaemon(true);
        return thread;
    });

    /** 携帯ジュークボックスは client にひとつだけ (AA の singleton) なので鍵は固定。 */
    private static final String SLOT = "pocket_jukebox";

    /**
     * ロード中の要求を後勝ちで打ち消すための世代。{@link PocketJukeboxAdvance} だけでは足りない
     * (担当の張り替えは main thread、ロードの完了は別 thread なので、担当照合の前に
     * {@code SoundManager.play} まで走ってしまう窓がある)。バックパック系と同じ配線。
     */
    private static final PlaybackGenerations<String> GENERATIONS = new PlaybackGenerations<>();

    private static final PocketJukeboxAdvance ADVANCE =
            new PocketJukeboxAdvance(System::currentTimeMillis);

    private PocketJukeboxClient() {
    }

    /**
     * AA の {@code PocketJukeboxPlayer.tick()} の出口から毎 client tick 呼ばれる。
     *
     * @param playing      AA が再生中と思っているか
     * @param currentTrack AA の現在トラック番号 (-1 = まだ無い)
     * @param pocket       携帯ジュークボックスの stack ({@code null} 可)
     */
    public static void reconcile(boolean playing, int currentTrack, @Nullable ItemStack pocket) {
        if (!playing || currentTrack < 0 || pocket == null || pocket.isEmpty()) {
            stop();
            return;
        }
        final CustomTrackData track = PocketJukeboxTracks.mdmTrackAt(pocket, currentTrack);
        final String url = track == null ? "" : track.url();
        if (ADVANCE.handles(currentTrack, url)) {
            return;
        }

        // ここから担当の張り替え。MDM ディスクでない (バニラ / 他 MOD) とラジオは stream=false で
        // 担当だけ記録する = 保留しないので、AA の従来の挙動がそのまま残る。
        final boolean stream = PocketJukeboxTracks.streamable(track);
        final Minecraft mc = Minecraft.getInstance();
        final Player carrier = mc.player;
        if (stream && (mc.level == null || carrier == null)) {
            // 音源を張る先がまだ無い。担当を記録すると二度と再試行しないので、次の tick に回す。
            return;
        }

        GENERATIONS.invalidate(SLOT);
        final PocketJukeboxAdvance.Voice previous = ADVANCE.assign(currentTrack, url, stream);
        if (previous != null) {
            previous.stop();
        }
        if (stream) {
            beginLoad(currentTrack, url, track, carrier);
        }
    }

    /** AA の {@code startNextTrack()} の入口。真を返すと呼び出し元が曲送りを取り消す。 */
    public static boolean holdsAdvance(int currentTrack) {
        return ADVANCE.holdsAdvance(currentTrack);
    }

    /** AA の {@code stop()} の入口。ロード中の要求も含めて全部落とす。 */
    public static void stop() {
        if (ADVANCE.isIdle()) {
            return;
        }
        GENERATIONS.invalidate(SLOT);
        final PocketJukeboxAdvance.Voice voice = ADVANCE.release();
        if (voice != null) {
            voice.stop();
        }
    }

    /**
     * URL の解決はブロックするので別 thread、{@code SoundManager} 操作は main thread
     * (バックパック系 compat と同じ配線)。
     */
    private static void beginLoad(int trackIndex, String url, CustomTrackData track, Player carrier) {
        final int generation = GENERATIONS.begin(SLOT);
        POOL.submit(() -> {
            IAudioSource source = null;
            PlaybackFailure failure = null;
            try {
                UrlGuard.enforce(url); // SSRF 遮断: 内部 IP / 非 http(s) scheme を再生前に弾く
                final OpenStreamResult result = LoaderHolder.get().openStreamDetailed(url, 0L);
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
            Minecraft.getInstance().execute(
                    () -> onLoaded(trackIndex, url, track, carrier, generation, resolved, reported));
        });
    }

    /** main thread: ロード結果を受けて音源を張る (または諦めて曲送りを AA に返す)。 */
    private static void onLoaded(int trackIndex, String url, CustomTrackData track, Player carrier,
            int generation, @Nullable IAudioSource resolved, @Nullable PlaybackFailure reported) {
        if (resolved == null) {
            // 無音で終わらせない。理由の分類つきでチャットとログの両方に残す (本体の再生経路と同じ出方)。
            PlaybackFailureReport.report(track, reported);
            // 保留を続けると「1 曲目で固まって二度と進まない」になるので、曲送りを AA に返す。
            ADVANCE.giveUp(trackIndex, url);
            return;
        }
        // ロード中に停止された / 次の再生が来ていたら、この音源は鳴らさずに捨てる。
        if (!GENERATIONS.isCurrent(SLOT, generation)) {
            resolved.close();
            return;
        }
        // ストリーム終端 (read == -1) は再生スレッドから来る。ここではフラグを立てるだけにする。
        final AtomicBoolean ended = new AtomicBoolean(false);
        // CompatPlayback が失敗の届け先 (push+pull) を繋いだインスタンスを返す。ここで構築子を
        // 直接呼べないのは意図的 (繋ぎ忘れをコンパイルで止める。CompatPlayback の javadoc)。
        final DiscSoundInstance instance =
                CompatPlayback.carried(carrier, resolved, track, () -> ended.set(true));
        if (!ADVANCE.attach(trackIndex, url, new InstanceVoice(instance, ended), track.durationMs())) {
            instance.requestStop();
            return;
        }
        Minecraft.getInstance().getSoundManager().play(instance);
        setNowPlaying(track);
    }

    /**
     * AA は無音ディスクの description ("Custom Music Disc") を出してしまうので、実際の曲名で上書きする
     * (バックパック系 compat と同じ文面)。
     */
    private static void setNowPlaying(CustomTrackData track) {
        final String desc = track.author() != null && !track.author().isBlank()
                ? track.author() + " - " + track.title()
                : track.title();
        if (desc != null && !desc.isBlank()) {
            Minecraft.getInstance().gui.setNowPlaying(Component.literal(desc));
        }
    }

    /**
     * {@link DiscSoundInstance} を {@link PocketJukeboxAdvance.Voice} に見せるアダプタ。
     *
     * <p>生存判定は 2 つの独立した信号の論理積にしてある。{@code ended} はストリームが最後まで流れた
     * (または途中で落ちた) 時の確定信号で、{@code isStopped()} は音源側の自己停止
     * (アンカー消滅・{@code requestStop}) を拾う。どちらか一方だけでは取り落とす経路がある。
     */
    private record InstanceVoice(DiscSoundInstance instance, AtomicBoolean ended)
            implements PocketJukeboxAdvance.Voice {

        @Override
        public boolean isPlaying() {
            return !ended.get() && !instance.isStopped();
        }

        @Override
        public void stop() {
            instance.stopAndRelease();
        }
    }
}
