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
     * 拒否された時に<b>何もしない</b>後始末。音源も channel も触らずにそのまま置く。
     *
     * <p><b>これを渡してよいのは、期限で必ず回収する呼び出し側だけ。</b> 拒否は「もう鳴らない」の
     * 証明ではない。{@code play} の中でチャンネルを作らずに返り、数 ms 遅れて開栓する経路が
     * 実在する (他 MOD が {@code PlaySoundEvent} を掴んで投げ直す形)。
     * そこで音源を閉じると、遅れて生まれたストリームは<b>死んだソース</b>から
     * 読むので 1 バイトも出せず、しかも {@code close} は理由を残さないため
     * <b>理由の付かない完全な無音</b>になる (実機 2/2 で再現。`_research/FIRST_PLAY_SILENT.md`)。
     *
     * <p>壊さない代わりに、垂れ流しは<b>期限が回収する</b>。
     * {@code ClientPlaybackManager} は install の直後に
     * {@link PlaybackSessions#FIRST_AUDIO_DEADLINE_MS} の期限を張っており、実 PCM が一度も
     * 渡らなければ音源も channel も先読みの枠も畳む。<b>期限を張らない呼び出し側が
     * これを渡すと、鳴らない音源を誰も回収しなくなる</b> — compat 経路 (Create / Sable) は
     * 期限を持たないので、従来どおり {@code voice::stopAndRelease} を渡すこと。
     */
    public static final Runnable KEEP_UNTIL_DEADLINE = () -> {
    };

    /**
     * 音を鳴らし、engine が受理しなかったら後始末をして理由を報告する。
     *
     * <p>呼び出し側は {@code false} が返ったら<b>そこで止めること</b>。特に "Now Playing" は
     * これが {@code true} を返した後にだけ出す (逆にすると、鳴っていないのに再生中と出る)。
     *
     * <p><b>受理の判定 ({@link Engine#playAndConfirm}) は変えていない。</b> 変えたのは
     * 拒否と判定した後の後始末で、そこを呼び出し側に選ばせる ({@link #KEEP_UNTIL_DEADLINE}
     * を渡せるのは期限を張っている側だけ)。
     *
     * @param engine            音を渡す先
     * @param onRejectedCleanup 受理されなかった時の後始末 ({@code voice::stopAndRelease} か
     *                          {@link #KEEP_UNTIL_DEADLINE})
     * @param onRejected        理由の報告先 (session の後始末もここで行う)
     * @return 受理されたなら {@code true}
     */
    public static boolean start(Engine engine, Runnable onRejectedCleanup,
            Consumer<PlaybackFailure> onRejected) {
        if (engine.playAndConfirm()) {
            return true;
        }
        onRejectedCleanup.run();
        onRejected.accept(engine.mutedOut()
                ? PlaybackFailure.soundMuted()
                : PlaybackFailure.soundEngineRejected());
        return false;
    }
}
