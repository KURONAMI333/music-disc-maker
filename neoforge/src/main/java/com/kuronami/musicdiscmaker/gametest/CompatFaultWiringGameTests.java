package com.kuronami.musicdiscmaker.gametest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.client.audio.CompatFaultWiring;
import com.kuronami.musicdiscmaker.client.audio.PlaybackFailure;
import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.PlaybackFault;
import com.kuronami.musicdiscmaker.lavaplayer.api.PlaybackFaultRelay;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * compat 経路 (Create / Sable / Sophisticated Core / Traveler's Backpack) で、再生スレッドの中で
 * 落ちた失敗が利用者まで届くことを固定する。
 *
 * <h2>これが要る理由</h2>
 * compat の 6 経路は {@code DiscSoundInstance} を作るだけで、push ({@code onPlaybackFault}) も
 * pull ({@code setFailureSink}) も差していなかった。症状は「再生中と出るのに無音で、理由も
 * 曲名も出ない」。<b>繋ぎ忘れても正常系は普通に動く</b>ので、実機で壊れるまで気づけない。
 *
 * <p>繋ぎ忘れそのものは、このテストではなく<b>コンパイル</b>が止める
 * ({@code DiscSoundInstance} の構築子が package-private で、{@code compat.*} からは
 * {@code CompatPlayback} 経由でしか作れない)。ここが固定するのはその中身 —
 * 2 つの届け先が繋がっていること、二重に報告しないこと、報告が main thread に載ること。
 */
@GameTestHolder(MusicDiscMaker.MODID)
public class CompatFaultWiringGameTests {

    private static final String TEMPLATE = "empty8x3x8";

    /**
     * <b>これが今回の回帰テスト。</b> 押し出された失敗 (push) が届くこと。
     *
     * <p>lavaplayer は終端を先に公開して例外イベントを後から配るので、pull だけの配線は
     * この窓を毎回取り落とす (詳細は {@code PlaybackFaultDeliveryGameTests})。compat の
     * 6 経路はその pull すら差していなかった。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void faultPushedFromThePlaybackThreadReachesTheUser(GameTestHelper helper) {
        final RelaySource source = new RelaySource();
        final Wiring wiring = wire(source);

        helper.assertTrue(wiring.pullSink.get() != null,
                "pull の届け先が差されていない (ストリーム終端で拾った理由が捨てられる)");

        source.relay.record(FailureReason.BOT_CHECK, "requires login");

        helper.assertTrue(wiring.reports.size() == 1,
                "push された失敗が届いていない (実機では無音のまま何も出ない): " + wiring.reports.size());
        helper.assertTrue(wiring.reports.get(0).kind() == PlaybackFailure.Kind.BOT_CHECK,
                "届いた理由が化けている: " + wiring.reports.get(0).kind());
        helper.assertTrue(wiring.hops == 1, "報告が main thread へ移されていない (hops=" + wiring.hops + ")");
        helper.succeed();
    }

    /** push と pull の両方が同じ失敗を運んでも、利用者に出るのは 1 回だけであること。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void pushAndPullOfTheSameFaultReportOnlyOnce(GameTestHelper helper) {
        final RelaySource source = new RelaySource();
        final Wiring wiring = wire(source);

        source.relay.record(FailureReason.CONNECTION_FAILED, "read timed out");
        // 終端を見た側も同じ理由を引いてくる (push を持つソースでは必ず重なる)。
        wiring.pullSink.get().accept(PlaybackFailure.ofReason(FailureReason.CONNECTION_FAILED, "read timed out"));

        helper.assertTrue(wiring.reports.size() == 1,
                "同じ失敗が二重に報告されている (チャットに同じ行が 2 回出る): " + wiring.reports.size());
        helper.succeed();
    }

    /**
     * push の口を持たないソースでは、pull だけで届くこと。
     * v3 の派生ソース・キャッシュ済み PCM を返す実装がこちら側に居る。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void sourcesWithoutThePushHookStillReportThroughPull(GameTestHelper helper) {
        final Wiring wiring = wire(new PullOnlySource());

        helper.assertTrue(wiring.reports.isEmpty(), "何も起きていないのに報告が出ている");
        wiring.pullSink.get().accept(PlaybackFailure.ofReason(FailureReason.PRIVATE_OR_REMOVED, "removed"));

        helper.assertTrue(wiring.reports.size() == 1,
                "push を持たないソースで pull の報告まで落ちている: " + wiring.reports.size());
        helper.assertTrue(wiring.reports.get(0).kind() == PlaybackFailure.Kind.PRIVATE_OR_REMOVED, "理由が化けている");
        helper.succeed();
    }

    /** 届け先を差す前に既に壊れていた場合も取り落とさないこと (差した瞬間に 1 回流れる)。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void aFaultThatHappenedBeforeWiringIsReplayed(GameTestHelper helper) {
        final RelaySource source = new RelaySource();
        source.relay.record(FailureReason.BOT_CHECK, "requires login");

        final Wiring wiring = wire(source);
        helper.assertTrue(wiring.reports.size() == 1,
                "配線より前に確定していた失敗が流れてこない: " + wiring.reports.size());
        helper.succeed();
    }

    /** 配線の結果をまとめて読むための受け皿。 */
    private static final class Wiring {
        final List<PlaybackFailure> reports = new ArrayList<>();
        final AtomicReference<Consumer<PlaybackFailure>> pullSink = new AtomicReference<>();
        int hops;
    }

    /** {@code CompatPlayback} が本番で行うのと同じ引数で配線する。 */
    private static Wiring wire(IAudioSource source) {
        final Wiring wiring = new Wiring();
        CompatFaultWiring.attach(source, wiring.pullSink::set, runnable -> {
            wiring.hops++;
            runnable.run();
        }, wiring.reports::add);
        return wiring;
    }

    /** {@code LavaAudioSource} と同じ形のソース (理由を relay に預け、終端は理由と無関係に返す)。 */
    private static final class RelaySource implements IAudioSource {

        private final PlaybackFaultRelay relay = new PlaybackFaultRelay();

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
