package com.kuronami.musicdiscmaker.lavaplayer;

/**
 * YouTube セッション (visitorId) を入れ替える口。
 *
 * <h2>なぜこれが要るのか — 待っても直らない、入れ替えると直る</h2>
 * youtube-source は {@code YoutubeAccessTokenTracker} が最初に取った visitorId を抱え続け、
 * 公開の破棄手段を持たない。YouTube がその visitorId を弾き始めた瞬間から、<b>同じ
 * source manager を使う限り全 URL が失敗する</b>。MDM は manager をゲームセッション中ずっと
 * 保持するので、起動後に一度弾かれると再起動まで何も鳴らない。
 *
 * <p>同一 manager で 5 回試す対照実験 (2026-08-14):
 *
 * <pre>
 *   2000ms 待って再試行するだけ ......... 回復 0 / 10
 *   tracker を差し替えてから再試行 ...... 回復 9 / 10
 * </pre>
 *
 * <p>効くのは待つことではなく visitorId の入れ替え。だから再試行は<b>必ず</b>この口とセットで回す。
 *
 * <h2>世代 (generation)</h2>
 * 入れ替えは manager 全体に効くので、同時に走っている他のロードにも影響する。呼び出し側は
 * 「自分の試行を始めた時点の {@link #generation()}」を握っておき、それを
 * {@link #rollIfStale(long)} へ渡す。既に他のスレッドが入れ替えていれば二重に入れ替えず、
 * 「もう新しくなっている」とだけ答える (連鎖的な入れ替えを防ぐ)。
 */
interface YoutubeSession {

    /** 現在のセッション世代。入れ替わるたびに増える。 */
    long generation();

    /**
     * {@code seen} の世代がまだ現役なら visitorId を入れ替える。
     *
     * @param seen 呼び出し側が試行開始時に見た世代
     * @return 次の試行が新しいセッションで走るなら {@code true}
     *         (自分が入れ替えた / 他が既に入れ替えた)。入れ替えの余力が尽きていれば {@code false}
     */
    boolean rollIfStale(long seen);

    /** YouTube source manager が登録できなかった時の何もしない実装 (再試行は起こらない)。 */
    YoutubeSession NONE = new YoutubeSession() {

        @Override
        public long generation() {
            return 0L;
        }

        @Override
        public boolean rollIfStale(long seen) {
            return false;
        }
    };
}
