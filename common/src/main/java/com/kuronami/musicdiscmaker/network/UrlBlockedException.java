package com.kuronami.musicdiscmaker.network;

/**
 * {@link UrlGuard#enforce(String)} が URL を拒否したときに投げる。
 * 拒否理由 ({@link UrlGuard.Reason}) を保持するので、上位で理由別の失敗表示に振り分けられる。
 */
public final class UrlBlockedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UrlGuard.Reason reason;

    public UrlBlockedException(UrlGuard.Reason reason, String url) {
        super("URL blocked by SSRF guard (" + reason + "): " + url);
        this.reason = reason;
    }

    public UrlGuard.Reason reason() {
        return reason;
    }
}
