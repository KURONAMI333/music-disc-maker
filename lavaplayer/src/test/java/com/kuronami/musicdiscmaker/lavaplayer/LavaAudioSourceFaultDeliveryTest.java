package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.PlaybackFault;
import com.sedmelluq.discord.lavaplayer.format.Pcm16AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

/**
 * <b>本物の lavaplayer を相手に</b>、再生スレッドの中で落ちた失敗が利用者側まで届くことを確かめる。
 *
 * <h2>なぜこの層が要るのか — 部品は正しいのに経路が繋がっていなかった</h2>
 * 失敗の分類 ({@code FailureClassifier} / {@code PlaybackFailure}) には既に十分な数のテストがあり、
 * 全部緑だった。それでも実機では利用者に一言も出ないまま無音になった。<b>部品が正しくても、
 * 部品の間を繋ぐ順番が現実と食い違っていた</b>のが理由で、その順番は lavaplayer の中にしか無い。
 * だから偽物のソースを並べたテストでは原理的に捕まらない — ここで本物を回す。
 *
 * <p>lavaplayer 2.2.4-fix-j8 の {@code LocalAudioTrackExecutor.execute} は、再生が例外で落ちたとき
 * 次の順で動く (実バイトコードのオフセット):
 *
 * <pre>
 *   258: frameBuffer.setTerminateOnEmpty()   ← 「ストリームは終わった」が先に公開される
 *   307: ExceptionTools.log(...)             ← 巨大なスタックトレースを書く (ここで時間を食う)
 *   323: listener.onTrackException(...)      ← 例外イベントはここでやっと配られる
 * </pre>
 *
 * <p>PCM を読んでいる側は 258 をすぐ拾って終端に着く。つまり<b>ストリームが終わった時点では
 * 失敗の理由がまだ存在しない</b>ので、終端で理由を pull する設計は必ず取り落とす。
 * このテストは終端と理由の到着を別々に待って、後から来た理由がちゃんと届くことを固定する。
 */
class LavaAudioSourceFaultDeliveryTest {

    /** 失敗の到着を待つ上限。lavaplayer のスレッド往復にしては十分に長い。 */
    private static final long AWAIT_SECONDS = 10L;

    /**
     * kura の実機 ({@code latest.log} 2026-08-14 22:54) に出た {@code AllClientsFailedException}
     * のメッセージ。youtube-source が各 client の失敗を自分のメッセージへ連結した形。
     */
    private static final String ALL_CLIENTS_FAILED =
            "(yts.version: 1.18.2) All clients failed to load the item.\n"
            + "\n"
            + "Client [ANDROID_VR] failed: This video requires login.\n"
            + "\tat dev.lavalink.youtube.clients.skeleton.Client.getPlayabilityStatus(Client.java:94)\n"
            + "\n"
            + "Client [WEB] failed: No supported audio streams available, available types: \n"
            + "\tat dev.lavalink.youtube.track.format.TrackFormats.getBestFormat(TrackFormats.java:47)";

    /**
     * <b>これが今回の回帰テスト。</b> 再生スレッドで落ちた失敗が、ストリームの終端より後に確定しても
     * 届け先まで届くこと。
     *
     * <p>{@link LavaAudioSource#read} が {@code -1} を返した後で例外イベントが来る、という
     * 実機と同じ順番をそのまま踏む (順番を作り込んでいるのではなく、本物の lavaplayer が
     * そうするのを待っているだけ)。理由を終端で pull するだけの設計では、ここで永久に何も来ない。
     */
    @Test
    void faultReachesTheSinkEvenWhenTheStreamEndsFirst() throws Exception {
        final DefaultAudioPlayerManager manager = newManager();
        try {
            final AudioPlayer player = manager.createPlayer();
            final LavaAudioSource source = new LavaAudioSource(player);
            player.addListener(source);

            final AtomicReference<PlaybackFault> delivered = new AtomicReference<>();
            final AtomicInteger deliveries = new AtomicInteger();
            final CountDownLatch arrived = new CountDownLatch(1);
            // ClientPlaybackManager がソースを受け取った直後に差すのと同じ形。
            source.onPlaybackFault(fault -> {
                delivered.set(fault);
                deliveries.incrementAndGet();
                arrived.countDown();
            });

            player.playTrack(new ExplodingTrack(ALL_CLIENTS_FAILED));

            // ストリームは終わる。この時点で理由が付いているとは限らない (実機では毎回付いていない)。
            final int read = source.read(new byte[8192], 0, 8192);
            assertEquals(-1, read, "失敗した再生でストリームが終端を返していない");

            assertTrue(arrived.await(AWAIT_SECONDS, TimeUnit.SECONDS),
                    "再生スレッドで落ちた失敗が届け先まで来ない (ストリームの終わりで取り落としている)");
            final PlaybackFault fault = delivered.get();
            assertNotNull(fault, "届いた失敗が空");
            assertEquals(FailureReason.BOT_CHECK, fault.reason(),
                    "実機の login 要求が bot 判定に分類されていない: " + fault.detail());
            assertTrue(fault.detail().contains("All clients failed"),
                    "技術詳細が失われている: " + fault.detail());
            assertFalse(fault.detail().contains("\n"), "詳細が 1 行に畳まれていない");
            assertEquals(1, deliveries.get(), "同じ失敗が複数回配られている");
            // pull 側の口も同じ理由を答えること (push を持たない実装との互換のため残してある経路)。
            assertEquals(FailureReason.BOT_CHECK, source.playbackFault().reason(),
                    "pull の口が理由を持っていない");
        } finally {
            manager.shutdown();
        }
    }

    /**
     * 届け先を差すのが失敗より<b>後</b>になっても取り落とさないこと。
     *
     * <p>ソースを受け取ってから届け先を差すまでには必ず窓がある (別スレッドを跨ぐ)。実機の失敗は
     * 再生開始から 1 秒足らずで来るので、この窓に落ちる方が普通。差した時点で溜まっていれば
     * その場で流す、という規約をここで固定する。
     */
    @Test
    void faultRecordedBeforeTheSinkIsRegisteredIsStillReplayed() throws Exception {
        final DefaultAudioPlayerManager manager = newManager();
        try {
            final AudioPlayer player = manager.createPlayer();
            final LavaAudioSource source = new LavaAudioSource(player);
            player.addListener(source);
            player.playTrack(new ExplodingTrack(ALL_CLIENTS_FAILED));

            // 届け先を差さないまま、失敗が確定するのを待つ。
            final long deadline = System.currentTimeMillis() + AWAIT_SECONDS * 1000L;
            while (source.playbackFault() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(10L);
            }
            assertNotNull(source.playbackFault(), "再生スレッドの失敗が記録されていない");

            final AtomicReference<PlaybackFault> delivered = new AtomicReference<>();
            source.onPlaybackFault(delivered::set);
            assertNotNull(delivered.get(), "既に溜まっていた失敗が、届け先を差しても流れてこない");
            assertEquals(FailureReason.BOT_CHECK, delivered.get().reason(), "遅れて差した届け先で理由が化けている");
        } finally {
            manager.shutdown();
        }
    }

    /** mono 48kHz 出力の manager (本番 {@code MusicLoaderImpl} と同じ設定)。 */
    private static DefaultAudioPlayerManager newManager() {
        final DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
        manager.getConfiguration().setOutputFormat(new Pcm16AudioDataFormat(2, 48000, 960, false));
        return manager;
    }

    /**
     * 再生スレッドの中で必ず落ちるトラック。ネットワークを使わずに、youtube-source が
     * bot 判定で落ちるのと同じ形 (解決には成功し、再生で落ちる) を作る。
     */
    private static final class ExplodingTrack extends BaseAudioTrack {

        private final String message;

        ExplodingTrack(String message) {
            super(new AudioTrackInfo("test", "test", 1000L, "YOYeJn4mz8M", false,
                    "https://example.invalid/watch?v=YOYeJn4mz8M", null, null));
            this.message = message;
        }

        @Override
        public void process(LocalAudioTrackExecutor executor) {
            throw new RuntimeException(message);
        }

        @Override
        public AudioSourceManager getSourceManager() {
            return null;
        }
    }
}
