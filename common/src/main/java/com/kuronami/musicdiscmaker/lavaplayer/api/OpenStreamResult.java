package com.kuronami.musicdiscmaker.lavaplayer.api;

/**
 * {@link IMusicLoader#openStreamDetailed} の戻り値。開けたソースか、開けなかった理由の
 * どちらか一方を持つ。
 *
 * <h2>これが要る理由</h2>
 * {@link IMusicLoader#openStream} は失敗の種類を<b>すべて {@code null} に潰して</b>返す。
 * impl 側は {@link ResolveException} で理由を持っているのに、境界を越える前に捨てていた。
 * その結果、DNS 失敗も年齢制限も YouTube の bot 判定も、利用者の画面には同じ 1 文
 * (「ストリーム取得失敗」) として出ていた。理由を境界の向こうへ運ぶのがこの型の役目。
 *
 * <p>api パッケージなので bridge classloader が隔離側と mod 側の両方に同一クラスを渡す。
 * <b>JDK とこのパッケージ以外に依存を持たせないこと</b> ({@code lavaplayer-api} サブプロジェクトが
 * MC も common も引かずにこのパッケージだけをコンパイルする)。
 *
 * @param source 開けた PCM ソース。失敗時は {@code null}
 * @param reason 開けなかった理由。成功時は {@link FailureReason#UNKNOWN} が入る (読まないこと)
 * @param detail ログ・チャットに添える技術詳細。無い時は空文字 ({@code null} にはならない)
 */
public record OpenStreamResult(IAudioSource source, FailureReason reason, String detail) {

    /** {@code null} を正規化する (呼び出し側が {@code detail} の null 判定を書かなくて済む)。 */
    public OpenStreamResult {
        reason = reason == null ? FailureReason.UNKNOWN : reason;
        detail = detail == null ? "" : detail;
    }

    /**
     * 成功。{@code source} が {@code null} なら失敗 ({@link FailureReason#UNKNOWN}) として扱う
     * — 「成功なのに中身が無い」を呼び出し側へ渡さない。
     *
     * @param source 開けた PCM ソース
     * @return 結果
     */
    public static OpenStreamResult ok(IAudioSource source) {
        return source == null ? failed(FailureReason.UNKNOWN, null)
                : new OpenStreamResult(source, FailureReason.UNKNOWN, "");
    }

    /**
     * 失敗。
     *
     * @param reason 開けなかった理由 ({@code null} なら {@link FailureReason#UNKNOWN})
     * @param detail 技術詳細 ({@code null} 可)
     * @return 結果
     */
    public static OpenStreamResult failed(FailureReason reason, String detail) {
        return new OpenStreamResult(null, reason, detail);
    }

    /**
     * 開けたか。
     *
     * @return ソースを持っていれば {@code true}
     */
    public boolean isOk() {
        return source != null;
    }
}
