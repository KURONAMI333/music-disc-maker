package com.kuronami.musicdiscmaker.lavaplayer.api;

/**
 * URL 解決の失敗を理由つきで mod 側へ伝える例外。impl (隔離 classloader) が投げ、
 * mod classloader の {@code DiscFabrication} が catch する。api パッケージなので
 * bridge classloader が両側に同一クラスを渡し、型で catch できる。
 */
public class ResolveException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final FailureReason reason;

    public ResolveException(FailureReason reason) {
        super(reason == null ? "UNKNOWN" : reason.name());
        this.reason = reason == null ? FailureReason.UNKNOWN : reason;
    }

    public FailureReason reason() {
        return reason;
    }
}
