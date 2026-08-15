package com.kuronami.musicdiscmaker.client.audio;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;

/**
 * 「再生スレッドの中で落ちた失敗」を利用者まで運ぶ配線 (MC 非依存の部分だけ)。
 *
 * <h2>これが要る理由 — 経路が 6 本あって、繋ぎ忘れても静かに動く</h2>
 * 失敗の届け先は 2 つある。ソースが理由を確定した瞬間に押し出す push
 * ({@link IAudioSource#onPlaybackFault}) と、ストリーム終端で引く pull
 * ({@code DiscSoundInstance#setFailureSink})。<b>pull だけでは足りない</b> — lavaplayer は
 * 終端を先に公開して例外イベントを後から配るので、終端を見た時点では理由がまだ存在しない
 * (根拠は {@code PlaybackFaultRelay} の javadoc に実バイトコードの順で書いてある)。
 *
 * <p>本体の再生 ({@code ClientPlaybackManager}) は両方繋いでいたが、compat の 6 経路
 * (Create / Sable / Sophisticated Core ×2 loader / Traveler's Backpack ×2 loader) は
 * {@code DiscSoundInstance} を作るだけで<b>どちらも繋いでいなかった</b>。症状は
 * 「再生中と出るのに無音で、理由も曲名も出ない」。繋ぎ忘れても正常系は普通に動くので、
 * 実機で壊れるまで誰も気づかない型の欠陥だった。
 *
 * <p>だから繋ぎ忘れを規律で防がない。{@code DiscSoundInstance} の構築子を package-private に
 * 落とし、{@code compat.*} からは {@link CompatPlayback} 経由でしか作れなくしてある
 * (= 繋がない経路を書くとコンパイルが通らない)。ここはその配線の中身で、
 * {@code Minecraft} を掴まないので headless テストに載る。
 *
 * <p>push と pull は同じ失敗を二重に運びうるので、両方を 1 つの
 * {@link AtomicBoolean} で塞いだ届け先へ流す (最初の 1 件だけが通る)。
 */
public final class CompatFaultWiring {

    private CompatFaultWiring() {
    }

    /**
     * pull 側の届け先を受け取る口。{@code DiscSoundInstance#setFailureSink} をそのまま指す
     * ためだけの関数型で、テストが {@code DiscSoundInstance} を読み込まずに済むようにしてある。
     */
    @FunctionalInterface
    public interface FailureSinkTarget {

        /**
         * ストリーム終端で拾った失敗の届け先を差す。
         *
         * @param sink 失敗の届け先
         */
        void setFailureSink(Consumer<PlaybackFailure> sink);
    }

    /**
     * push (ソース直結) と pull (ストリーム終端) の両方を、1 回しか通さない同じ届け先へ繋ぐ。
     *
     * <p><b>{@code SoundManager.play} より前に呼ぶこと。</b> pull 側の届け先は
     * {@code getCustomStream()} が開栓するときに読まれるので、play の後に差しても
     * そのストリームには載らない。
     *
     * @param source     ロード済みの音源 (push の口を持たない実装なら pull だけが残る)
     * @param instance   pull 側の届け先を差す相手 (通常は {@code DiscSoundInstance::setFailureSink})
     * @param mainThread 報告を実行するスレッド。失敗は再生スレッドから届くので、MC に触る報告は
     *                   ここで main thread へ移す
     * @param report     利用者への報告 (曲名つきの {@code PlaybackFailureReport::report} 等)
     */
    public static void attach(IAudioSource source, FailureSinkTarget instance,
            Executor mainThread, Consumer<PlaybackFailure> report) {
        final AtomicBoolean reported = new AtomicBoolean(false);
        final Consumer<PlaybackFailure> once = failure -> {
            if (failure != null && reported.compareAndSet(false, true)) {
                mainThread.execute(() -> report.accept(failure));
            }
        };
        // push: 理由が確定した瞬間に押し出される。差した時点で既に壊れていればその場で 1 回来る。
        source.onPlaybackFault(fault -> {
            if (fault != null) {
                once.accept(PlaybackFailure.ofReason(fault.reason(), fault.detail()));
            }
        });
        // pull: ストリーム終端で拾った理由。push を持たない実装ではこちらだけが残る。
        instance.setFailureSink(once);
    }
}
