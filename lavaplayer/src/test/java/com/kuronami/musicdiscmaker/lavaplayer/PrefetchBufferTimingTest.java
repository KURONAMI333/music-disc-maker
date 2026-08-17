package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sedmelluq.discord.lavaplayer.format.Pcm16AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;

/**
 * <b>先読み (prefetch) が前提にしている数字を、本物の lavaplayer を相手に実測する。</b>
 *
 * <h2>検証している前提</h2>
 * {@code PlaybackPrefetch} / {@code DiscSoundInstance} は次を前提にしている
 * (バイトコードを読んだだけの推論で、実測はしていなかった):
 * <ul>
 *   <li>lavaplayer の frame buffer は既定 251 frame (≒5秒) で、専用スレッドが
 *       {@code provide()} の呼ばれ方と無関係に自走してそこまで溜める</li>
 *   <li>MC が再生開始時に要求するのは 4 秒分 = mono/48kHz/16bit で {@link #TARGET_BYTES} byte
 *       ({@code Channel.attachBufferStream} の {@code pumpBuffers(4)})</li>
 *   <li>251 &gt; 200 (4秒分の frame 数) なので、事前に開いて温めておいたソースは
 *       初回の {@link #TARGET_BYTES} byte をメモリから即座に返せる</li>
 * </ul>
 *
 * <h2>音源をどう安定させたか</h2>
 * 本物の YouTube 越しに測ると、ネットワーク側のノイズ (CDN の距離・回線状況) が支配的になって
 * 「先読みの効果」を測れない。ここでは {@link WavFixture} が生成したサイン波 WAV を
 * {@link ThrottledFileServer} (127.0.0.1 だけの HTTP サーバ) 経由で配信し、<b>配信レートを
 * 意図的に「等速 = 1 秒の音声を転送するのに 1 秒かかる」に絞る</b>。これは測定したネットワーク値
 * ではなく<b>再現性のために選んだ合成モデル</b>で、「ダウンロードが再生にギリギリ追いつく」という
 * 無音が起きる典型条件の下限を模している。実際のネットワークがこれより遅ければ、実際に必要な
 * lead time はここで測った値より長くなる (本番が 15 秒を使っているのはその安全マージン)。
 *
 * <h2>読んでよい範囲</h2>
 * {@code lavaplayer/} の外は一切触っていない。
 */
class PrefetchBufferTimingTest {

    /** MC が再生開始時に読み切る量 = mono/48kHz/16bit で 4 秒分 ({@code pumpBuffers(4)})。 */
    private static final int TARGET_BYTES = 384_000;

    /** WAV フィクスチャの長さ (秒)。最大 sleep (15秒) + 読み切りの余裕を見て確保。 */
    private static final int FIXTURE_SECONDS = 30;

    /** 配信レート = フィクスチャ自身の byte rate (48kHz stereo 16bit) = 等速配信。 */
    private static final long REALTIME_BYTES_PER_SEC =
            (long) WavFixture.SAMPLE_RATE * WavFixture.CHANNELS * 2;

    /** 1 回の read ループが応答しなくなった場合の安全弁 (実際のバグ検出用、しきい値ではない)。 */
    private static final long READ_SAFETY_TIMEOUT_MS = 20_000L;

    @TempDir
    private static Path tempDir;

    private static ThrottledFileServer server;

    @BeforeAll
    static void startServer() throws IOException {
        final Path wav = WavFixture.write(tempDir, FIXTURE_SECONDS);
        server = new ThrottledFileServer(wav, REALTIME_BYTES_PER_SEC);
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    /**
     * <b>本題:</b> cold (開いてすぐ読む) と warm (放置してから読む) の所要時間を比べる。
     *
     * <p>しきい値での fail は置かない (環境で揺れる)。唯一の assert は「warm は cold より速い」
     * という、方向だけを見る緩い比較。数字そのものは stdout に出す (人が読んで lead time の
     * 妥当性を判断する材料)。
     */
    @Test
    void coldVsWarmReadTime() throws Exception {
        final List<Long> coldMs = repeat(3, () -> measureReadMillis(0L));
        final List<Long> warm5Ms = repeat(3, () -> measureReadMillis(5_000L));
        final List<Long> warm15Ms = repeat(2, () -> measureReadMillis(15_000L));

        final long coldMedian = median(coldMs);
        final long warm5Median = median(warm5Ms);
        final long warm15Median = median(warm15Ms);

        System.out.println("=== PrefetchBufferTimingTest: cold vs warm (384,000 byte = 4秒分の read 所要時間) ===");
        System.out.println("cold      (delay 0s)  : " + coldMs + "  median=" + coldMedian + "ms");
        System.out.println("warm      (delay 5s)  : " + warm5Ms + "  median=" + warm5Median + "ms");
        System.out.println("warm      (delay 15s) : " + warm15Ms + "  median=" + warm15Median + "ms");

        assertTrue(warm5Median < coldMedian,
                "5秒温めても cold より速くなっていない (先読みが効いていない可能性): "
                        + "cold=" + coldMedian + "ms warm5=" + warm5Median + "ms");
        assertTrue(warm15Median < coldMedian,
                "15秒温めても cold より速くなっていない (先読みが効いていない可能性): "
                        + "cold=" + coldMedian + "ms warm15=" + warm15Median + "ms");
    }

    /**
     * <b>頭打ち点の実測:</b> 放置時間を振って、251 frame (≒5秒) で読み時間が頭打ちになる様子を見る。
     * 「lead time は何秒あれば足りるか」を推論でなく実測で示すためのもの。assert は無し
     * (人が読む表を stdout に出すだけ)。
     */
    @Test
    void readTimePlateausAsBufferFills() throws Exception {
        final int[] delaysSec = {0, 2, 4, 5, 6, 8, 12};
        final StringBuilder table = new StringBuilder();
        table.append("=== PrefetchBufferTimingTest: 放置秒数 → 384,000 byte 読み切りの所要時間 ===\n");
        for (final int delaySec : delaysSec) {
            final long ms = measureReadMillis(delaySec * 1000L);
            table.append(String.format("delay=%3ds -> read=%5dms%n", delaySec, ms));
        }
        System.out.print(table);
    }

    /**
     * ソースを 1 本開き、{@code delayMs} だけ放置してから {@link #TARGET_BYTES} byte を読み切る
     * までの経過時間 (ms) を返す。放置中の sleep 自体は計測に含めない。
     */
    private static long measureReadMillis(long delayMs) throws Exception {
        final DefaultAudioPlayerManager manager = newManager();
        try {
            final AudioTrack track = loadTrackSync(manager);
            final AudioPlayer player = manager.createPlayer();
            final LavaAudioSource source = new LavaAudioSource(player);
            player.addListener(source);
            player.playTrack(track);

            if (delayMs > 0L) {
                Thread.sleep(delayMs);
            }

            final long startNanos = System.nanoTime();
            readExact(source, TARGET_BYTES);
            final long elapsedNanos = System.nanoTime() - startNanos;
            source.close();
            return TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        } finally {
            manager.shutdown();
        }
    }

    /** {@code target} byte を読み切るまでループする。安全弁を超えたら実際のバグとして fail する。 */
    private static void readExact(LavaAudioSource source, int target) {
        final byte[] buf = new byte[8192];
        int total = 0;
        final long deadline = System.currentTimeMillis() + READ_SAFETY_TIMEOUT_MS;
        while (total < target) {
            final int n = source.read(buf, 0, Math.min(buf.length, target - total));
            if (n < 0) {
                fail("読み切る前にストリームが終端した (total=" + total + "/" + target + ")");
            }
            total += n;
            if (System.currentTimeMillis() > deadline) {
                fail("読み切りが " + READ_SAFETY_TIMEOUT_MS + "ms で終わらない (total=" + total + "/" + target + ")"
                        + " — lavaplayer 側のフリーズ等、タイミングでなく実際の不具合を疑う");
            }
        }
    }

    private static AudioTrack loadTrackSync(AudioPlayerManager apm) throws Exception {
        final CompletableFuture<AudioTrack> future = new CompletableFuture<>();
        apm.loadItem(new AudioReference(server.url(), null), new AudioLoadResultHandler() {
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
        final AudioTrack track;
        try {
            track = future.get(15, TimeUnit.SECONDS);
        } catch (final TimeoutException | ExecutionException ex) {
            fail("フィクスチャ WAV のロードに失敗 (テスト用ローカル HTTP サーバ側の問題): " + ex, ex);
            return null; // unreachable
        }
        assertNotNull(track, "フィクスチャ WAV が lavaplayer に認識されなかった (WavContainerProbe が通っていない)");
        return track;
    }

    /** mono 48kHz 出力の manager (本番 {@code MusicLoaderImpl} と同じ設定)。 */
    private static DefaultAudioPlayerManager newManager() {
        final DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
        manager.getConfiguration().setOutputFormat(new Pcm16AudioDataFormat(2, 48000, 960, false));
        manager.registerSourceManager(new HttpAudioSourceManager());
        return manager;
    }

    private static List<Long> repeat(int times, ThrowingLongSupplier supplier) throws Exception {
        final List<Long> results = new ArrayList<>(times);
        for (int i = 0; i < times; i++) {
            results.add(supplier.get());
        }
        return results;
    }

    private static long median(List<Long> values) {
        final List<Long> sorted = new ArrayList<>(values);
        sorted.sort(Long::compareTo);
        return sorted.get(sorted.size() / 2);
    }

    @FunctionalInterface
    private interface ThrowingLongSupplier {
        long get() throws Exception;
    }
}
