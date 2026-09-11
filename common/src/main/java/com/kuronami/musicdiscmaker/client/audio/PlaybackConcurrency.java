package com.kuronami.musicdiscmaker.client.audio;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 「いま client で鳴っている custom disc は何本か」を<b>1 箇所で数える</b>口 (MC 非依存)。
 *
 * <h2>これが要る理由</h2>
 * OpenAL の streaming channel の<b>プールは 1 つ</b>で、MDM の全再生経路と
 * BGM・バニラ盤・環境音が共有する。MDM はこのプールを拡張するが、実 pool を越えた再生は
 * Minecraft が警告なしに落とすので、数え漏らすと「無言で鳴らない」になる。
 *
 * <p>再生の経路は鍵の型で分かれている ({@code PlaybackSessions<BlockPos>} = 金ジューク /
 * {@code PlaybackSessions<Long>} = ブームボックス) が、<b>プールは分かれていない</b>。
 * 経路ごとに {@link PlaybackSessions#sweep()} を見て上限と比べると、実効上限が経路の数だけ
 * 増えて、同じ pool へ上限を越えてねじ込むことになる。
 *
 * <p>鳴っているブームボックス1台も pool を1本消費し、金ジュークと枠を共有する。
 * その「1 箇所」がここ。
 *
 * <h2>登録は所有者が明示的に行う (構築子で自動登録しない)</h2>
 * {@link PlaybackSessions} の構築子で自分を登録すると、テストが作った使い捨てのインスタンスが
 * 全部ここに溜まり、{@link #sweepAll()} が<b>テストの数だけ増える</b>。登録するのは
 * 実運用の各経路 ({@link ClientPlaybackManager} と {@code BoomboxClientPlayback}) だけにして、
 * テストは自分の {@link PlaybackConcurrency} を作る。
 */
public final class PlaybackConcurrency {

    /** 実運用の口。client プロセスに 1 つ。 */
    private static final PlaybackConcurrency CLIENT = new PlaybackConcurrency();

    /**
     * 数え上げの対象。登録は起動時の数本だけなので、読みが支配的な
     * {@link CopyOnWriteArrayList} で足りる (数え上げは main thread、登録は static 初期化)。
     */
    private final List<PlaybackSessions<?>> registered = new CopyOnWriteArrayList<>();
    private final List<java.util.function.IntSupplier> otherCounters = new CopyOnWriteArrayList<>();

    /** 実運用の口を返す。 */
    public static PlaybackConcurrency client() {
        return CLIENT;
    }

    /**
     * 数え上げの対象に加える。同じインスタンスを 2 回登録すると 2 重に数えるので、既に
     * 入っていれば何もしない。
     *
     * @param sessions 再生セッションの容れ物
     */
    public void register(PlaybackSessions<?> sessions) {
        if (sessions != null && !registered.contains(sessions)) {
            registered.add(sessions);
        }
    }

    /** Register a non-URL voice owner in the same streaming channel budget. */
    public void registerCounter(java.util.function.IntSupplier counter) {
        java.util.Objects.requireNonNull(counter, "counter");
        if (!otherCounters.contains(counter)) otherCounters.add(counter);
    }

    /**
     * 全経路の自然終了を掃除してから、鳴っている音源の合計を返す。
     *
     * <p><b>掃除は書き込み操作</b>なので、ブームボックスのロード完了が金ジュークの
     * {@code playingUrl} を落としうる。落ちるのは既に停止している音源の分だけで、
     * その鍵での後続判定は掃除の有無に関わらず「読み直す」に落ちる
     * ({@link PlaybackSessions#sweep()} の javadoc)。
     *
     * @return 掃除後に鳴っている音源の総数
     */
    public int sweepAll() {
        int total = 0;
        for (final PlaybackSessions<?> sessions : registered) {
            total += sessions.sweep();
        }
        for (final java.util.function.IntSupplier counter : otherCounters) {
            total += Math.max(0, counter.getAsInt());
        }
        return total;
    }
}
