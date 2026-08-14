package com.kuronami.musicdiscmaker.lavaplayer.api;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 再生スレッドで確定した {@link PlaybackFault} を、届け先が付いた順番に関係なく<b>ちょうど 1 回</b>
 * 届ける受け渡し口。
 *
 * <h2>これが要る理由 — ストリームの終わりは失敗より先に来る</h2>
 * lavaplayer の {@code LocalAudioTrackExecutor.execute} は、再生が例外で落ちたとき次の順で動く
 * (2.2.4-fix-j8 の実バイトコード):
 *
 * <pre>
 *   258: frameBuffer.setTerminateOnEmpty()   ← ここで「ストリームは終わった」が公開される
 *   307: ExceptionTools.log(...)             ← "Suspicious exception for playback of ..." の 1 行
 *   313: this.trackException = e
 *   323: listener.onTrackException(...)      ← TrackExceptionEvent はここで初めて配られる
 * </pre>
 *
 * <p>PCM を読んでいる側は 258 の終端をすぐ拾う ({@code AudioPlayer.provide} が terminator を見て
 * {@code activeTrack} を落とし、以後 {@code null} を返す)。つまり<b>読み手がストリームの終わりを
 * 見た時点では、失敗の理由はまだこの世に無い</b>。終端で理由を pull する設計はこの窓を必ず取り落とす
 * — 307 の巨大なスタックトレースを書き終えるまで 323 は来ないので、実機では毎回読み手が先に着く。
 *
 * <p>だから理由は<b>確定した瞬間に push する</b>。届け先がまだ無ければ抱えておき、後から差された
 * 時にその場で流す ({@link #sink})。どちらの順番でも 1 回だけ届く。
 *
 * <p>api パッケージなので bridge classloader が隔離側と mod 側の両方へ同一クラスを渡す。
 * <b>JDK とこのパッケージ以外に依存を持たせないこと</b> (MC を掴まない = headless テストに載る)。
 */
public final class PlaybackFaultRelay {

    /** 最初の 1 件だけを残す (後続は同じ失敗の余波なので上書きさせない)。 */
    private final AtomicReference<PlaybackFault> fault = new AtomicReference<>();
    /** 届け先。{@code null} = まだ誰も受け取りに来ていない。 */
    private final AtomicReference<Consumer<PlaybackFault>> sink = new AtomicReference<>();
    /** 配送済みフラグ。record と sink の両方から flush されるので、ここで 1 回に絞る。 */
    private final AtomicBoolean delivered = new AtomicBoolean(false);

    /**
     * 失敗を記録する。届け先が既にあればその場で配る (再生スレッドから呼ばれる)。
     *
     * @param reason 分類済みの失敗理由
     * @param detail 1 行に畳む技術詳細 ({@code null} 可)
     * @return 記録できたら {@code true} (2 件目以降は {@code false})
     */
    public boolean record(FailureReason reason, String detail) {
        return record(new PlaybackFault(reason, detail));
    }

    /**
     * 失敗を記録する。届け先が既にあればその場で配る。
     *
     * @param broken 記録する失敗 ({@code null} は無視)
     * @return 記録できたら {@code true}
     */
    public boolean record(PlaybackFault broken) {
        if (broken == null || !fault.compareAndSet(null, broken)) {
            return false;
        }
        flush();
        return true;
    }

    /**
     * 届け先を差す。<b>既に失敗が記録済みならその場で流す</b> — これが順番非依存の要点で、
     * ソースを受け取ってから届け先を差すまでの窓で落ちた失敗を取り落とさない。
     *
     * <p>後から差し直すと届け先は置き換わるが、既に配ったものは配り直さない。
     *
     * @param destination 届け先 ({@code null} = 解除)
     */
    public void sink(Consumer<PlaybackFault> destination) {
        sink.set(destination);
        flush();
    }

    /**
     * 記録済みの失敗。まだ壊れていなければ {@code null}。
     *
     * @return 失敗 (未発生なら {@code null})
     */
    public PlaybackFault fault() {
        return fault.get();
    }

    /**
     * 既に届け先へ流したか。pull 側の経路が二重に報告しないための問い合わせ。
     *
     * @return 配送済みなら {@code true}
     */
    public boolean delivered() {
        return delivered.get();
    }

    /** 失敗と届け先が揃っていれば 1 回だけ流す。 */
    private void flush() {
        final PlaybackFault broken = fault.get();
        final Consumer<PlaybackFault> destination = sink.get();
        if (broken == null || destination == null) {
            return;
        }
        if (delivered.compareAndSet(false, true)) {
            destination.accept(broken);
        }
    }
}
