package com.kuronami.musicdiscmaker.lavaplayer;

import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.http.YoutubeAccessTokenTracker;

/**
 * 本物の {@link YoutubeAudioSourceManager} の visitorId を入れ替える {@link YoutubeSession}。
 *
 * <p>入れ替えの実体は tracker の差し替え 1 行。{@code YoutubeAccessTokenTracker} は取得した
 * visitorId を自分の中に抱えていて捨てる手段が無いので、<b>tracker ごと新品に替える</b>ことで
 * 次のリクエストから新しい visitorId を取り直させる。
 */
final class YoutubeTokenSession implements YoutubeSession {

    private static final Logger LOGGER = LoggerFactory.getLogger(YoutubeTokenSession.class);

    /** 一気に許す入れ替え回数。 */
    static final int BURST = 8;
    /** 1 個補充するのにかかる時間。持続で 8 回/分。 */
    static final long REFILL_INTERVAL_MS = 7_500L;

    private final YoutubeAudioSourceManager youtube;
    private final RollBudget budget;

    private volatile long generation;

    YoutubeTokenSession(YoutubeAudioSourceManager youtube, LongSupplier clockMs) {
        this(youtube, new RollBudget(BURST, REFILL_INTERVAL_MS, clockMs));
    }

    YoutubeTokenSession(YoutubeAudioSourceManager youtube, RollBudget budget) {
        this.youtube = youtube;
        this.budget = budget;
    }

    @Override
    public long generation() {
        return generation;
    }

    @Override
    public synchronized boolean rollIfStale(long seen) {
        if (seen != generation) {
            // 別スレッドのロードが既に入れ替えた後。ここで重ねて入れ替えると、
            // 同時に走っている他の再生の足元まで動かして連鎖的に失敗させる。
            return true;
        }
        if (!budget.take()) {
            LOGGER.warn("YouTube セッションの入れ替えが上限に達した (これ以上は再試行しない)");
            return false;
        }
        try {
            youtube.getContextFilter().setTokenTracker(
                    new YoutubeAccessTokenTracker(youtube.getHttpInterfaceManager()));
        } catch (final Throwable t) {
            LOGGER.warn("YouTube セッションの入れ替えに失敗", t);
            return false;
        }
        generation++;
        LOGGER.info("YouTube セッションを入れ替えた (世代 {})", generation);
        return true;
    }
}
