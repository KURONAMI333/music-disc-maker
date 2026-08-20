package com.kuronami.musicdiscmaker.client.audio;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jetbrains.annotations.Nullable;

/**
 * 同じ音源の同じ失敗を二度報告しないための覚え書き (報告先は画面のラベルとログ)。
 *
 * <h2>これが要る理由</h2>
 * 再生スレッドの中で落ちた失敗は、ストリームが終わるたびに 1 件ずつ上がってくる。ラジオは
 * 終端を瞬断とみなして再接続を試みるので、bot 判定のように<b>何度試しても同じ理由で落ちる</b>
 * 失敗は、上限に達するまで同じ行をチャットへ積む。理由が分かるようになったことで、逆に
 * 画面が埋まって読めなくなる。
 *
 * <p>覚えるのは音源ごとの<b>直近のラベル</b> ({@link PlaybackFailure#label()}) だけ。理由が
 * 変わった時 (bot 判定 → 回線断) は別の情報なので通す。忘れるのは停止した時だけ — 再接続の
 * たびに忘れると、それは何も抑えていないのと同じになる。
 *
 * <p>{@code Minecraft} を掴まないので headless テストに載る ({@link PlaybackFailure} と同じ
 * seam の切り方)。
 */
public final class PlaybackFailureNotices {

    /** 音源キー (BlockPos 等) → 直近に報告したラベル。 */
    private final Map<Object, String> lastLabel = new ConcurrentHashMap<>();

    /**
     * 報告してよいか。よければ「報告した」ものとして記録する。
     *
     * @param key     音源キー ({@code null} なら覚えずに常に許可する = 素通し)
     * @param failure 報告しようとしている失敗 ({@code null} なら常に許可する)
     * @return 初めての失敗、または直近と違う失敗なら {@code true}
     */
    public boolean shouldReport(@Nullable Object key, @Nullable PlaybackFailure failure) {
        if (key == null || failure == null) {
            return true;
        }
        final String label = failure.label();
        return !label.equals(lastLabel.put(key, label));
    }

    /**
     * その音源の記憶を捨てる (停止・撤去時)。次に同じ失敗が起きたら改めて報告される。
     *
     * @param key 音源キー ({@code null} 可)
     */
    public void forget(@Nullable Object key) {
        if (key != null) {
            lastLabel.remove(key);
        }
    }

    /** 全部忘れる (ワールド離脱等)。 */
    public void forgetAll() {
        lastLabel.clear();
    }
}
