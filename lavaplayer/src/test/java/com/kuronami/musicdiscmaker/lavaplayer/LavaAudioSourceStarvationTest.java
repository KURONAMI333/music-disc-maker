package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.PlaybackFault;
import com.sedmelluq.discord.lavaplayer.format.Pcm16AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

/**
 * <b>データが間に合っていない時に {@link LavaAudioSource#read} が待たないことを、本物の
 * lavaplayer を相手に固定する。</b>
 *
 * <h2>なぜここが待つとゲームが止まるのか</h2>
 * MC はこの {@code read} を<b>単一の "Sound engine" スレッド</b>から引く。同じスレッドが
 * {@code SoundEngine#play} の {@code channelAccess.createHandle(...).join()} も捌くので、
 * ここで待つとバニラの足音・ブロック音を含む全ての効果音と Render thread が道連れで止まる。
 * 「回線が一瞬詰まる」がそのまま「ゲーム全体のフリーズ」になる形だった。
 *
 * <h2>どう餓死させたか</h2>
 * {@link WavFixture} のサイン波 WAV を {@link ThrottledFileServer} で<b>再生に必要な帯域の
 * 1/12</b> で配る。デコードが転送に追いつかないので frame buffer はすぐ干上がり、
 * 「トラックは再生中なのにフレームが無い」= 実機で無音とフリーズを生む状態がそのまま出る。
 *
 * <h2>読んでよい範囲</h2>
 * {@code lavaplayer/} の外は触っていない。
 */
class LavaAudioSourceStarvationTest {

    /** フィクスチャの長さ (秒)。テスト中に配り終わらない長さにしてある。 */
    private static final int FIXTURE_SECONDS = 6;

    /** 再生に必要な帯域 (48kHz stereo 16bit)。 */
    private static final long REALTIME_BYTES_PER_SEC =
            (long) WavFixture.SAMPLE_RATE * WavFixture.CHANNELS * 2;

    /** 配信レート = 必要な帯域の 1/12。デコードは必ず転送待ちになる。 */
    private static final long STARVING_BYTES_PER_SEC = REALTIME_BYTES_PER_SEC / 12;

    /**
     * 1 回の {@code read} が返るまでに許す上限 (ms)。以前の実装はデータが無いと
     * 10,000ms 待っていた。40ms の frame 待ちと JIT・GC の揺れを見込んでも十分に低い。
     */
    private static final long MAX_READ_MS = 1_000L;

    /** 実測ループを回す実時間 (ms)。 */
    private static final long PROBE_WINDOW_MS = 3_000L;

    @TempDir
    private static Path tempDir;

    private static ThrottledFileServer server;

    @BeforeAll
    static void startServer() throws IOException {
        final Path wav = WavFixture.write(tempDir, FIXTURE_SECONDS);
        server = new ThrottledFileServer(wav, STARVING_BYTES_PER_SEC);
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    /**
     * <b>これが今回の回帰テスト。</b> データが届いていない時、{@code read} は待たずに戻ること。
     *
     * <p>しきい値 {@link #MAX_READ_MS} は「実機で許せる待ち時間」ではなく、<b>直そうとしている
     * 挙動 (10 秒) との間に一桁の開きを置いた線</b>。ここが赤くなる = Sound engine スレッドを
     * また掴んでいる、という意味だけを持つ。
     */
    @Test
    void readDoesNotWaitWhenTheDataHasNotArrived() throws Exception {
        final DefaultAudioPlayerManager manager = newManager();
        try {
            final AudioTrack track = loadTrackSync(manager);
            final AudioPlayer player = manager.createPlayer();
            final LavaAudioSource source = new LavaAudioSource(player);
            player.addListener(source);
            player.playTrack(track);

            final byte[] buf = new byte[8192];
            long worstMs = 0L;
            int emptyReads = 0;
            int reads = 0;
            final long until = System.currentTimeMillis() + PROBE_WINDOW_MS;
            while (System.currentTimeMillis() < until) {
                final long startNanos = System.nanoTime();
                final int n = source.read(buf, 0, buf.length);
                final long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                reads++;
                worstMs = Math.max(worstMs, ms);
                if (n == 0) {
                    emptyReads++;
                }
                if (n < 0) {
                    break;
                }
            }
            source.close();

            System.out.println("=== LavaAudioSourceStarvationTest: " + reads + " reads, "
                    + emptyReads + " empty, worst=" + worstMs + "ms ===");
            assertTrue(emptyReads > 0,
                    "餓死させたつもりが 1 度もデータ切れになっていない = テストが何も見ていない"
                            + " (reads=" + reads + ")");
            assertTrue(worstMs < MAX_READ_MS,
                    "read が " + worstMs + "ms 待った = Sound engine スレッドをその間掴んでいる"
                            + " (許容 " + MAX_READ_MS + "ms 未満)");
        } finally {
            manager.shutdown();
        }
    }

    /**
     * <b>待たなくなっても餓死は検出し続けること。</b> 待ち時間で測る形をやめた代わりに時計で測る
     * ので、時計を差し替えれば実時間を使わずに固定できる。
     *
     * <p>データが来ない状態のまま時計だけを進めると、上限を越えた時点で
     * {@link FailureReason#CONNECTION_FAILED} が記録され、ストリームが終わる。
     * これが無いと、回線が死んだディスクは無音を流し続けて利用者に何も伝わらない。
     */
    @Test
    void starvationIsStillDetectedWithTheClock() throws Exception {
        final DefaultAudioPlayerManager manager = newManager();
        try {
            final AudioTrack track = loadTrackSync(manager);
            final AudioPlayer player = manager.createPlayer();
            // 実時間ではなく、こちらが進める時計で測らせる。
            final AtomicLong clock = new AtomicLong(1_000_000L);
            final LavaAudioSource source = new LavaAudioSource(player, clock::get);
            player.addListener(source);
            player.playTrack(track);

            final byte[] buf = new byte[8192];
            int terminal = 0;
            final long until = System.currentTimeMillis() + PROBE_WINDOW_MS;
            while (System.currentTimeMillis() < until) {
                final int n = source.read(buf, 0, buf.length);
                if (n < 0) {
                    terminal = n;
                    break;
                }
                clock.addAndGet(2_000L); // 1 回引くごとに 2 秒進める
            }
            final PlaybackFault fault = source.playbackFault();
            source.close();

            assertTrue(terminal < 0, "餓死したのにストリームが終わっていない");
            assertNotNull(fault, "餓死が理由として記録されていない = 利用者にもログにも何も出ない");
            assertTrue(fault.reason() == FailureReason.CONNECTION_FAILED,
                    "餓死が " + fault.reason() + " に化けている");
            assertTrue(fault.detail().contains("starved"),
                    "餓死だと分かる詳細が残っていない: " + fault.detail());
        } finally {
            manager.shutdown();
        }
    }

    /** mono 48kHz 出力の manager (本番 {@code MusicLoaderImpl} と同じ設定)。 */
    private static DefaultAudioPlayerManager newManager() {
        final DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
        manager.getConfiguration().setOutputFormat(new Pcm16AudioDataFormat(2, 48000, 960, false));
        manager.registerSourceManager(new HttpAudioSourceManager());
        return manager;
    }

    private static AudioTrack loadTrackSync(DefaultAudioPlayerManager manager) {
        final CompletableFuture<AudioTrack> future = new CompletableFuture<>();
        manager.loadItem(new AudioReference(server.url(), null), new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack track) {
                future.complete(track);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                future.complete(playlist.getSelectedTrack());
            }

            @Override
            public void noMatches() {
                future.complete(null);
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                future.completeExceptionally(exception);
            }
        });
        try {
            final AudioTrack track = future.get(30, TimeUnit.SECONDS);
            assertNotNull(track, "フィクスチャ WAV が lavaplayer に認識されなかった");
            return track;
        } catch (final TimeoutException | ExecutionException ex) {
            fail("フィクスチャ WAV のロードに失敗 (テスト用ローカル HTTP サーバ側の問題): " + ex, ex);
            return null; // unreachable
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            fail("ロード待ちが中断された", ex);
            return null; // unreachable
        }
    }
}
