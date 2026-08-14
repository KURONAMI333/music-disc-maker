package com.kuronami.musicdiscmaker.gametest;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.lavaplayer.api.FailureClassifier;
import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.PlaybackFault;
import com.kuronami.musicdiscmaker.lavaplayer.api.PlaybackFaultRelay;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 再生スレッドで確定した失敗が<b>届け先まで届く順番</b>の固定。
 *
 * <h2>ここが要る理由 — 分類のテストが全部緑でも無音のままだった</h2>
 * 失敗の分類には既に十分な数のテストがあり、全部緑だった。それでも実機では利用者に一言も
 * 出ないまま無音になった。<b>部品は正しく、部品の間を繋ぐ順番だけが現実と食い違っていた</b>
 * のが理由で、既存のテストは偽ソースが「終端と同時に理由も持っている」形しか作っていなかった
 * ため原理的に捕まえられなかった。現実にはその順番は起きない。
 *
 * <p>lavaplayer は再生が例外で落ちたとき、frame buffer の終端を先に公開し、例外イベントを
 * 後から配る (実バイトコードの順は {@link PlaybackFaultRelay} の javadoc)。つまり
 * <b>ストリームが終わった時点で理由はまだ無い</b>。ここでは理由が終端より後に来る順番を明示的に
 * 作り、それでも届くことを固定する。
 *
 * <p>本物の lavaplayer を相手にした検証は {@code :lavaplayer} の
 * {@code LavaAudioSourceFaultDeliveryTest} が持つ (隔離 classloader の中は GameTest から
 * 触れない)。こちらは mod 側の受け口の規約 — 他バージョン帯へ写経するときに壊れるのはこの面。
 */
@GameTestHolder(MusicDiscMaker.MODID)
public class PlaybackFaultDeliveryGameTests {

    private static final String TEMPLATE = "empty8x3x8";

    /**
     * <b>これが今回の回帰テスト。</b> ストリームが終わった<b>後</b>に確定した理由が届くこと。
     *
     * <p>終端で {@code playbackFault()} を pull するだけの設計はここで落ちる — 引いた時点では
     * まだ {@code null} なので、その後いくら理由が付いても誰も見に来ない。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void faultConfirmedAfterTheStreamEndedStillReaches(GameTestHelper helper) {
        final RelaySource source = new RelaySource();
        final AtomicReference<PlaybackFault> delivered = new AtomicReference<>();
        final AtomicInteger deliveries = new AtomicInteger();
        source.onPlaybackFault(fault -> {
            delivered.set(fault);
            deliveries.incrementAndGet();
        });

        // 1) ストリームが終わる。lavaplayer は例外を配る前に buffer を terminate するので、
        //    読み手はここに着く — この時点で理由はまだ存在しない。
        helper.assertTrue(source.read(new byte[64], 0, 64) == -1, "終端が返っていない");
        helper.assertTrue(source.playbackFault() == null,
                "終端の時点で理由が付いている = 現実に起きない順番でテストしている");
        helper.assertTrue(delivered.get() == null, "理由が確定する前に何かが届いている");

        // 2) 例外イベントが遅れて着く (実機では終端の数 ms 後)。
        source.relay().record(FailureClassifier.classify(new RuntimeException(REQUIRES_LOGIN)),
                "AllClientsFailedException: " + REQUIRES_LOGIN);

        helper.assertTrue(delivered.get() != null,
                "ストリームの終わりより後に確定した失敗が届いていない (= 実機で無音になる形)");
        helper.assertTrue(delivered.get().reason() == FailureReason.BOT_CHECK,
                "届いた理由が " + delivered.get().reason() + " に化けている");
        helper.assertTrue(deliveries.get() == 1, "同じ失敗が複数回届いている: " + deliveries.get());
        helper.succeed();
    }

    /**
     * 届け先を差すのが失敗より<b>後</b>でも取り落とさないこと。
     *
     * <p>ソースを受け取ってから届け先を差すまでには必ずスレッドを跨ぐ窓があり、実機の失敗は
     * 再生開始から 1 秒足らずで来るのでこの窓に落ちる方が普通。どちらの順番でも 1 回だけ届く、
     * が受け口の規約。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void faultConfirmedBeforeTheSinkIsRegisteredIsReplayed(GameTestHelper helper) {
        final RelaySource source = new RelaySource();
        source.relay().record(FailureReason.BOT_CHECK, "requires login");

        final AtomicInteger deliveries = new AtomicInteger();
        final AtomicReference<PlaybackFault> delivered = new AtomicReference<>();
        source.onPlaybackFault(fault -> {
            delivered.set(fault);
            deliveries.incrementAndGet();
        });
        helper.assertTrue(delivered.get() != null, "既に溜まっていた失敗が届け先を差しても流れてこない");
        helper.assertTrue(deliveries.get() == 1, "replay で複数回届いている");

        // 2 件目の失敗は最初の余波なので上書きしない。差し直しても配り直さない。
        helper.assertFalse(source.relay().record(FailureReason.CONNECTION_FAILED, "後続"),
                "2 件目の失敗が最初を上書きしている");
        source.onPlaybackFault(fault -> deliveries.incrementAndGet());
        helper.assertTrue(deliveries.get() == 1, "届け先を差し直すと配送済みの失敗が再送されている");
        helper.assertTrue(source.relay().delivered(), "配送済みフラグが立っていない");
        helper.assertTrue(delivered.get().reason() == FailureReason.BOT_CHECK,
                "最初の理由が後続で上書きされている");
        helper.succeed();
    }

    /**
     * push の口を持たない実装が壊れないこと ({@code false} を返し、pull の経路が残る)。
     * v3 の派生ソース・キャッシュ済み PCM を返す実装はこちら側に居る。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void sourcesWithoutThePushHookFallBackToPull(GameTestHelper helper) {
        final IAudioSource silent = new PullOnlySource();
        final AtomicInteger deliveries = new AtomicInteger();
        silent.onPlaybackFault(fault -> deliveries.incrementAndGet());
        helper.assertTrue(deliveries.get() == 0, "押し出す口が無いのに配っている");
        helper.assertTrue(silent.playbackFault() == null, "既定実装が失敗を捏造している");

        // 分類の根拠になった例外から詳細を採ること。lavaplayer は再生スレッドの例外を包んで配るので、
        // 一番外側の文面はどの失敗でも同じ定型文になる = そのまま出すと報告の役に立たない。
        final Throwable wrapped = new RuntimeException("Something broke when playing the track.",
                new RuntimeException(REQUIRES_LOGIN));
        helper.assertTrue(FailureClassifier.classify(wrapped) == FailureReason.BOT_CHECK,
                "包まれた bot 判定を拾えていない");
        helper.assertTrue(FailureClassifier.blamed(wrapped).getMessage().contains("requires login"),
                "詳細が包み紙の定型文から採られている: " + FailureClassifier.blamed(wrapped).getMessage());
        helper.assertTrue(FailureClassifier.blamed(null) == null, "null で壊れている");
        helper.succeed();
    }

    /** kura の実機ログに出た bot 判定の文面 (client ごとの理由が連結された形の要点部分)。 */
    private static final String REQUIRES_LOGIN =
            "(yts.version: 1.18.2) All clients failed to load the item. "
            + "Client [ANDROID_VR] failed: This video requires login.";

    /**
     * {@code LavaAudioSource} と同じ形のソース — 理由を {@link PlaybackFaultRelay} に預け、
     * 終端は理由と無関係に返す。隔離 classloader の中の本物は GameTest から触れないので、
     * <b>順番だけを本物と同じにした</b>ものをここに置く。
     */
    private static final class RelaySource implements IAudioSource {

        private final PlaybackFaultRelay relay = new PlaybackFaultRelay();

        PlaybackFaultRelay relay() {
            return relay;
        }

        @Override
        public int sampleRate() {
            return 48000;
        }

        @Override
        public int channels() {
            return 1;
        }

        @Override
        public int bitsPerSample() {
            return 16;
        }

        @Override
        public boolean bigEndian() {
            return false;
        }

        @Override
        public int read(byte[] dst, int off, int len) {
            return -1; // terminator が先に着いた状態 (理由はまだ無い)
        }

        @Override
        public PlaybackFault playbackFault() {
            return relay.fault();
        }

        @Override
        public void onPlaybackFault(Consumer<PlaybackFault> sink) {
            relay.sink(sink);
        }

        @Override
        public void close() {
        }
    }

    /** push の口を持たない実装 (api の既定実装をそのまま使う)。 */
    private static final class PullOnlySource implements IAudioSource {

        @Override
        public int sampleRate() {
            return 48000;
        }

        @Override
        public int channels() {
            return 1;
        }

        @Override
        public int bitsPerSample() {
            return 16;
        }

        @Override
        public boolean bigEndian() {
            return false;
        }

        @Override
        public int read(byte[] dst, int off, int len) {
            return -1;
        }

        @Override
        public void close() {
        }
    }
}
