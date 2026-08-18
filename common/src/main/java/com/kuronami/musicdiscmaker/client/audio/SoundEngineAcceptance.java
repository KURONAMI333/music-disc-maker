package com.kuronami.musicdiscmaker.client.audio;

import java.util.function.Consumer;

/**
 * 「{@code SoundManager.play} に渡したのに鳴らなかった」を捕まえる関門 (MC 非依存)。
 *
 * <h2>これが要る理由 — play は受理しなかったことを教えてくれない</h2>
 * {@code SoundEngine#play} は戻り値を持たない。そして 1.21.1 の実装は<b>8 通りの経路で
 * 黙って捨てる</b>: engine 未ロード / 他 MOD が {@code ClientHooks.playSound} で取り消した /
 * {@code canPlaySound} が偽 / sound event 未登録 / {@code EMPTY_SOUND} / 実効音量が 0 /
 * master gain が 0 / チャンネルを確保できなかった。このうちログに何か出るのは
 * 未登録と {@code EMPTY_SOUND} だけで、それも {@code ONLY_WARN_ONCE} で
 * <b>起動中 1 回しか出ない</b>。音量 0 と master 0 は debug、チャンネル枯渇に至っては
 * 開発環境でしか出ない。
 *
 * <p>呼び出し側はこれを見ずに session を install し、"Now Playing" まで出していた。
 * 症状は報告と完全に一致する — <b>再生中と出るのに鳴らない</b>。しかも同じ URL の再通知は
 * 「既に鳴っている」として弾かれるので、停止か差し替えまでその状態が固定される。
 *
 * <p>受理されたかは engine 側の唯一の観測点で見る。{@code SoundEngine#play} は成功経路の中で
 * {@code instanceToChannel} へ入れてから返るので、{@code SoundManager#isActive} が
 * {@code play} 直後に偽なら捨てられている (ストリーム音源の実データ読み込みだけが非同期で、
 * チャンネルの登録は同期)。
 *
 * <p>{@code Minecraft} を掴まないので headless テストに載る ({@link PlaybackFailure} と同じ
 * seam の切り方)。engine を触る 2 行は {@link Engine} の実装側 ({@link DiscSoundInstance}) にある。
 */
public final class SoundEngineAcceptance {

    private SoundEngineAcceptance() {
    }

    /** 音を engine へ渡す口。 */
    public interface Engine {

        /**
         * engine へ渡し、<b>本当に受理されたか</b>を返す。
         *
         * @return チャンネルが割り当てられたなら {@code true}
         */
        boolean playAndConfirm();

        /**
         * この client の音量設定では最初から鳴りえないか。engine が捨てた理由のうち、
         * <b>利用者が自分で直せる唯一のもの</b>を切り分けるために使う。
         *
         * @return master / Records / この音源自身のいずれかの音量が 0 なら {@code true}
         */
        boolean mutedOut();
    }

    /**
     * 音を鳴らし、engine が受理しなかったら音源を畳んで理由を報告する。
     *
     * <p>呼び出し側は {@code false} が返ったら<b>そこで止めること</b>。特に "Now Playing" は
     * これが {@code true} を返した後にだけ出す (逆にすると、鳴っていないのに再生中と出る)。
     *
     * @param engine     音を渡す先
     * @param voice      受理されなかった時に畳む音源
     * @param onRejected 理由の報告先 (session の後始末もここで行う)
     * @return 受理されたなら {@code true}
     */
    public static boolean start(Engine engine, PlaybackVoice voice, Consumer<PlaybackFailure> onRejected) {
        if (engine.playAndConfirm()) {
            return true;
        }
        // 受理されていない = このインスタンスは二度と鳴らない。掴んだままにすると同時再生数だけ
        // 消費して、次の再通知も「既に鳴っている」で弾かれる。
        voice.stopAndRelease();
        onRejected.accept(engine.mutedOut()
                ? PlaybackFailure.soundMuted()
                : PlaybackFailure.soundEngineRejected());
        return false;
    }
}
