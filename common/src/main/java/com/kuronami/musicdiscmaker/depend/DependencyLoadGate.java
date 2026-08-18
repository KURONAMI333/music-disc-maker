package com.kuronami.musicdiscmaker.depend;

/**
 * 「一度だけ成功させたい仕事」の掛け金。{@link DependencyManager#load()} の状態機械を、
 * ファイルシステムから切り離してテストできる形に取り出したもの。
 *
 * <p>元の実装は仕事を始める前に {@code loaded = true} を立てていたので、展開が一度でも
 * 失敗すると以降の呼び出しは全部「ロード済み」として素通りし、空の classloader のまま
 * {@code Class.forName} に進んでいた。利用者から見える症状は毎回同じ
 * 「LavaPlayer loader を初期化できない」で、最初の 1 回に投げられた本当の原因は
 * どこにも出てこない。
 *
 * <p>ここが守る不変条件は 2 つ:
 * <ul>
 *   <li><b>成功してからしか掛け金は立たない。</b>失敗が一時的なもの (ウイルス対策の
 *       temp スキャン等) なら、次の呼び出しでそのまま直る</li>
 *   <li><b>再試行は {@code maxAttempts} 回で打ち切る。</b>打ち切った後も黙って素通り
 *       させず、覚えておいた原因を毎回 cause に付けて投げ直す。「黙って空で進む」が
 *       元の欠陥そのものなので、そこにだけは戻さない</li>
 * </ul>
 */
public final class DependencyLoadGate {

    /** 掛け金の下で一度だけ走らせたい仕事。 */
    @FunctionalInterface
    public interface Work {
        void run() throws Exception;
    }

    private final int maxAttempts;

    private boolean succeeded;
    private int attempts;
    private Exception lastFailure;

    public DependencyLoadGate(int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.maxAttempts = maxAttempts;
    }

    /**
     * まだ成功していなければ {@code work} を走らせる。
     *
     * @throws IllegalStateException 今回の試行が失敗した場合、または既に試行回数を
     *         使い切っている場合。どちらも失敗の原因が cause に付く
     */
    public synchronized void runOnce(Work work) {
        if (succeeded) {
            return;
        }
        if (attempts >= maxAttempts) {
            // 再試行はもうしない。ただし原因は毎回添える (呼び出し側のログに出る)。
            throw new IllegalStateException(
                    "Giving up on loading the bundled LavaPlayer dependencies after "
                            + attempts + " failed attempts; see the cause for the original failure",
                    lastFailure);
        }
        attempts++;
        try {
            work.run();
        } catch (final Exception ex) {
            lastFailure = ex;
            throw new IllegalStateException(
                    "Failed to load the bundled LavaPlayer dependencies (attempt "
                            + attempts + " of " + maxAttempts + ")",
                    ex);
        }
        succeeded = true;
        lastFailure = null;
    }

    /** 仕事が成功済みか。 */
    public synchronized boolean succeeded() {
        return succeeded;
    }

    /** これまでに実際に仕事を走らせた回数。 */
    public synchronized int attempts() {
        return attempts;
    }

    /** これ以上の再試行を諦めたか。 */
    public synchronized boolean exhausted() {
        return !succeeded && attempts >= maxAttempts;
    }
}
