package com.kuronami.musicdiscmaker.lavaplayer.api;

/**
 * 再生が<b>始まった後</b>に壊れた理由。{@link IAudioSource#playbackFault()} が返す。
 *
 * <h2>これが要る理由</h2>
 * {@link OpenStreamResult} が運ぶのは「開けなかった」失敗だけで、これは同期的な解決の失敗しか
 * 見ていない。実際に一番よく起きるのは<b>解決には成功し、再生スレッドの中で落ちる</b>形で、
 * その経路には戻り値が存在しない (呼び出し側はとっくに返っている)。理由を持たないまま
 * ストリームが終わると、利用者から見ると「再生中と出るのに鳴らない・完全な無音」になる。
 *
 * <p>失敗の分類は<b>隔離 classloader の側で済ませる</b>。再生スレッドで飛んでくる例外は
 * lavaplayer / youtube-source 側の型なので、mod 側では {@code instanceof} も
 * {@code getClass()} も使えない。境界を越えるのは {@link FailureReason} と短い文字列だけにする。
 *
 * <p>api パッケージなので bridge classloader が隔離側と mod 側の両方に同一クラスを渡す。
 * <b>JDK とこのパッケージ以外に依存を持たせないこと</b>。
 *
 * @param reason 分類済みの失敗理由 ({@code null} なら {@link FailureReason#UNKNOWN})
 * @param detail ログ・チャットの角括弧に添える技術詳細。1 行に畳んで切り詰められる
 *               ({@code null} なら空文字。再生スレッドの例外メッセージはスタックトレースを
 *               丸ごと抱えていることがあり、そのままチャットへ流すと 1 行に収まらない)
 */
public record PlaybackFault(FailureReason reason, String detail) {

    /** チャット 1 行に載せる技術詳細の上限。 */
    private static final int MAX_DETAIL_CHARS = 120;

    /** {@code null} を正規化し、詳細を 1 行へ畳む。 */
    public PlaybackFault {
        reason = reason == null ? FailureReason.UNKNOWN : reason;
        detail = flatten(detail);
    }

    /** 改行・タブを空白へ潰し、長すぎる詳細を切り詰める。 */
    private static String flatten(String raw) {
        if (raw == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(raw.length());
        boolean space = false;
        for (int i = 0; i < raw.length(); i++) {
            final char c = raw.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || c == ' ') {
                space = sb.length() > 0;
                continue;
            }
            if (space) {
                sb.append(' ');
                space = false;
            }
            sb.append(c);
        }
        final String text = sb.toString();
        return text.length() <= MAX_DETAIL_CHARS ? text : text.substring(0, MAX_DETAIL_CHARS) + "…";
    }
}
