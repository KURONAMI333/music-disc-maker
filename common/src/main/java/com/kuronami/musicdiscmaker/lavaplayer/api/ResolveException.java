package com.kuronami.musicdiscmaker.lavaplayer.api;

/**
 * URL 解決の失敗を理由つきで mod 側へ伝える例外。impl (隔離 classloader) が投げ、
 * mod classloader の {@code DiscFabrication} が catch する。api パッケージなので
 * bridge classloader が両側に同一クラスを渡し、型で catch できる。
 */
public class ResolveException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final FailureReason reason;
    private final String detail;

    /**
     * 理由だけを持つ失敗。
     *
     * @param reason 失敗理由 ({@code null} なら {@link FailureReason#UNKNOWN})
     */
    public ResolveException(FailureReason reason) {
        this(reason, null);
    }

    /**
     * 理由と技術詳細を持つ失敗。
     *
     * <p>{@code detail} は<b>ログとチャットの角括弧に出る 1 行</b>。理由だけでは
     * 「非公開・削除済み・存在しない」のような粒度までしか降りられず、利用者が報告に
     * 貼っても原因の特定に使えない。youtube-source の集約例外なら client ごとの理由が
     * ここに入る ({@code ClientFailureDetails})。
     *
     * @param reason 失敗理由 ({@code null} なら {@link FailureReason#UNKNOWN})
     * @param detail 技術詳細 ({@code null} 可)
     */
    public ResolveException(FailureReason reason, String detail) {
        super(message(reason, detail));
        this.reason = reason == null ? FailureReason.UNKNOWN : reason;
        this.detail = detail == null ? "" : detail;
    }

    private static String message(FailureReason reason, String detail) {
        final String name = reason == null ? "UNKNOWN" : reason.name();
        return detail == null || detail.isBlank() ? name : name + ": " + detail;
    }

    public FailureReason reason() {
        return reason;
    }

    /**
     * 技術詳細。無い時は空文字 ({@code null} は返さない)。
     *
     * @return 1 行の技術詳細
     */
    public String detail() {
        return detail;
    }
}
