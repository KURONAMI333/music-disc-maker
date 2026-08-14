package com.kuronami.musicdiscmaker.lavaplayer;

import java.util.ArrayList;
import java.util.List;

/**
 * visitorId を持つ偽 {@link YoutubeSession}。
 *
 * <p><b>「リセットが呼ばれたら成功する」ではなく、値そのものを持つ</b>のが要点。ロード側は
 * 「今の visitorId が YouTube に弾かれているか」だけを見て成否を決め、入れ替えは「値を別のものに
 * する」だけを担う。こうしないと、テストが機構ではなく偽物の都合を確かめることになる。
 */
class FakeYoutubeSession implements YoutubeSession {

    /** YouTube に弾かれている visitorId (実機で「起動時に引いた不運」に当たる)。 */
    static final String POISONED = "visitor-0";

    final List<String> log;

    private final int maxRolls;
    private long generation;

    int rolls;
    String visitorId = POISONED;

    FakeYoutubeSession(int maxRolls) {
        this(new ArrayList<>(), maxRolls);
    }

    FakeYoutubeSession(List<String> log, int maxRolls) {
        this.log = log;
        this.maxRolls = maxRolls;
    }

    @Override
    public long generation() {
        return generation;
    }

    @Override
    public boolean rollIfStale(long seen) {
        if (seen != generation) {
            return true; // 他のロードが既に入れ替えた
        }
        if (rolls >= maxRolls) {
            return false; // 入れ替えの余力切れ
        }
        rolls++;
        generation++;
        visitorId = "visitor-" + rolls;
        log.add("roll");
        return true;
    }
}
