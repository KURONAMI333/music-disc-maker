package com.kuronami.musicdiscmaker.client.audio;

/**
 * 開き直してよい再生かを決める門。有限のネット音源を、通常の再生経路で鳴らす時だけ通す。
 *
 * <p>何を「一時的な失敗」とみなすかは呼び出し側が決める (即時の再試行は回線系 2 つ、
 * 時間を置く再試行はそれに bot 判定と拒否を足す)。ここは経路の条件だけを持つ。
 */
final class FiniteOpenRetryPolicy {
    private FiniteOpenRetryPolicy() {}

    static boolean mayRetry(boolean radio, boolean local, boolean extensionSelected,
            boolean regularOnly, boolean retryable) {
        return !radio && !local && (regularOnly || !extensionSelected)
                && retryable;
    }
}
