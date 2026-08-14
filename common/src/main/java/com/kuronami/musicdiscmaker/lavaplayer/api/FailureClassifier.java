package com.kuronami.musicdiscmaker.lavaplayer.api;

import java.util.Locale;

/**
 * 例外・エラーメッセージを {@link FailureReason} へ落とす<b>唯一の</b>分類器。
 *
 * <h2>ここに置く理由</h2>
 * 失敗は 2 つの時点で来る — URL を解決する同期経路と、再生スレッドの中で落ちる非同期経路。
 * どちらも投げてくるのは lavaplayer / youtube-source の型で、判別できるのは<b>英語固定の
 * メッセージだけ</b>。分類器を 2 つ持つと、片方だけに新しい語句が足された瞬間に、同じ失敗が
 * 経路によって違う分類になる。だから 1 つに閉じる。
 *
 * <p>置き場が api パッケージなのは、隔離 classloader の側 (lavaplayer impl) と mod 側の
 * 両方から同一クラスとして見える必要があるため。<b>JDK とこのパッケージ以外に依存を
 * 持たせないこと</b>。
 */
public final class FailureClassifier {

    /** 原因のたどり方の上限 (循環した cause chain で回り続けないための歯止め)。 */
    private static final int MAX_CAUSE_DEPTH = 12;

    private FailureClassifier() {
    }

    /**
     * 投げられた例外を分類する。まず自分のメッセージで判定し、既定値
     * ({@link FailureReason#CONNECTION_FAILED}) にしか落ちなかった時だけ原因の連鎖をたどる。
     *
     * <p>「既定値に落ちた時だけ」なのは、既存の分類結果を変えないため — どこかの語句に
     * 一致したメッセージは、その時点で確定する。
     *
     * @param thrown 分類したい例外 ({@code null} 可)
     * @return 分類結果。判別できない失敗は {@link FailureReason#CONNECTION_FAILED}
     */
    public static FailureReason classify(Throwable thrown) {
        Throwable current = thrown;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            final FailureReason reason = classifyMessage(current.getMessage());
            if (reason != FailureReason.CONNECTION_FAILED) {
                return reason;
            }
            final Throwable next = current.getCause();
            current = next == current ? null : next;
        }
        return FailureReason.CONNECTION_FAILED;
    }

    /**
     * エラーメッセージ 1 本を分類する。lavaplayer / YouTube のメッセージは英語固定なので
     * 語句一致で判別する。判別できない失敗は接続失敗として扱う。
     *
     * <p>youtube-source の {@code AllClientsFailedException} は、各 client の失敗理由を
     * <b>自分のメッセージに連結して</b>持つ (「All clients failed to load the item.」の後ろに
     * 「Client [ANDROID_VR] failed: This video requires login.」が続く)。つまり bot 判定は
     * この 1 本の中に現れるので、ここで拾える。
     *
     * @param message 例外のメッセージ ({@code null} 可)
     * @return 分類結果。判別できない失敗は {@link FailureReason#CONNECTION_FAILED}
     */
    public static FailureReason classifyMessage(String message) {
        final String m = message == null ? "" : message.toLowerCase(Locale.ROOT);
        // YouTube の bot 判定は「ログイン要求」として現れる (datacenter IP でよく起きる)。
        // youtube-source は "This video requires login." を投げ、UI 文言は "sign in to
        // confirm you're not a bot"。接続失敗ではないので age より前に専用分類する。
        if (m.contains("requires login") || m.contains("sign in to confirm")
                || m.contains("not a bot")) {
            return FailureReason.BOT_CHECK;
        }
        // 年齢固有の語句のみ (bare "age" は message/page 等を誤って拾うため使わない)。
        // youtube-source は "This video requires age verification." を投げる ("requires
        // login" とは別語句なので BOT_CHECK と衝突しない)。
        if (m.contains("confirm your age") || m.contains("verify your age")
                || m.contains("age verification")
                || m.contains("age-restricted") || m.contains("age restricted")
                || m.contains("inappropriate for some users")) {
            return FailureReason.AGE_RESTRICTED;
        }
        if (m.contains("region") || m.contains("country") || m.contains("not available in your")
                || m.contains("blocked it in your")) {
            return FailureReason.REGION_LOCKED;
        }
        if (m.contains("private") || m.contains("removed") || m.contains("deleted")
                || m.contains("no longer available") || m.contains("does not exist")
                || m.contains("unavailable") || m.contains("terminated")) {
            return FailureReason.PRIVATE_OR_REMOVED;
        }
        return FailureReason.CONNECTION_FAILED;
    }
}
