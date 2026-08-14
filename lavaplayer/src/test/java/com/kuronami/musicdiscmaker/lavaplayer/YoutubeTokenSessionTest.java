package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.AndroidVr;
import dev.lavalink.youtube.http.YoutubeHttpContextFilter;

/**
 * <b>本物の youtube-source を相手に</b>、visitorId の入れ替えが実際に起きることを確かめる。
 *
 * <p>ネットワークは使わない — {@code YoutubeAccessTokenTracker} の構築は manager を抱えるだけで、
 * visitorId を取りに行くのは最初に使われた時 (遅延)。だから「tracker が差し替わったか」は
 * オフラインで確かめられる。
 */
class YoutubeTokenSessionTest {

    /** 入れ替えると tracker が新品に差し替わり、世代が進むこと。 */
    @Test
    void rollingReplacesTheTokenTracker() throws Exception {
        final YoutubeAudioSourceManager youtube = newManager();
        try {
            final YoutubeTokenSession session =
                    new YoutubeTokenSession(youtube, new RollBudget(8, 7_500L, System::currentTimeMillis));
            final Object before = tokenTracker(youtube);
            assertEquals(0L, session.generation(), "初期世代が 0 でない");

            assertTrue(session.rollIfStale(session.generation()), "入れ替えが通らない");

            final Object after = tokenTracker(youtube);
            assertNotNull(after, "tracker が差し込まれていない");
            assertNotSame(before, after, "tracker が差し替わっていない (visitorId は抱えられたまま)");
            assertEquals(1L, session.generation(), "世代が進んでいない");
        } finally {
            youtube.shutdown();
        }
    }

    /**
     * 他のロードが既に入れ替えた後なら、重ねて入れ替えないこと。
     *
     * <p>入れ替えは manager 全体に効くので、同時に走っている別のジュークボックスの足元まで動く。
     * 失敗するたびに各スレッドが入れ替えると、互いの visitorId を奪い合って連鎖的に失敗する。
     */
    @Test
    void aRollAlreadyDoneByAnotherLoadIsNotRepeated() throws Exception {
        final YoutubeAudioSourceManager youtube = newManager();
        try {
            final YoutubeTokenSession session =
                    new YoutubeTokenSession(youtube, new RollBudget(8, 7_500L, System::currentTimeMillis));
            final long seen = session.generation();
            assertTrue(session.rollIfStale(seen), "1 回目の入れ替えが通らない");
            final Object rolled = tokenTracker(youtube);

            // 同じ世代しか見ていない 2 本目 (= 並行して失敗した別のロード)
            assertTrue(session.rollIfStale(seen), "既に新しいのに「入れ替えられない」と答えている");

            assertSame(rolled, tokenTracker(youtube), "同じ世代に対して二重に入れ替えている");
            assertEquals(1L, session.generation(), "世代が二重に進んでいる");
        } finally {
            youtube.shutdown();
        }
    }

    /** 入れ替えの上限に達したら断ること (失敗のたびに無条件で叩かない)。 */
    @Test
    void rollingStopsWhenTheBudgetRunsOut() {
        final YoutubeAudioSourceManager youtube = newManager();
        try {
            final AtomicLong now = new AtomicLong(0L);
            final YoutubeTokenSession session =
                    new YoutubeTokenSession(youtube, new RollBudget(2, 7_500L, now::get));

            assertTrue(session.rollIfStale(session.generation()), "1 回目");
            assertTrue(session.rollIfStale(session.generation()), "2 回目");
            assertFalse(session.rollIfStale(session.generation()), "上限を超えて入れ替えている");
            assertEquals(2L, session.generation(), "断ったのに世代が進んでいる");

            // 時間が経てば 1 個ずつ戻る
            now.addAndGet(7_500L);
            assertTrue(session.rollIfStale(session.generation()), "補充された分が使えない");
        } finally {
            youtube.shutdown();
        }
    }

    /** 補充は経過時間ぶんだけ、上限は burst で頭打ちになること。 */
    @Test
    void budgetRefillsOverTimeAndIsCappedAtBurst() {
        final AtomicLong now = new AtomicLong(1_000L);
        final RollBudget budget = new RollBudget(2, 1_000L, now::get);

        assertTrue(budget.take());
        assertTrue(budget.take());
        assertFalse(budget.take(), "空のはずのバケツから取れている");

        now.addAndGet(10_000L); // 長く空けても burst 分までしか戻らない
        assertTrue(budget.take());
        assertTrue(budget.take());
        assertFalse(budget.take(), "burst を超えて溜まっている");
    }

    private static YoutubeAudioSourceManager newManager() {
        return new YoutubeAudioSourceManager(new AndroidVr());
    }

    private static Object tokenTracker(YoutubeAudioSourceManager youtube) throws Exception {
        final Field field = YoutubeHttpContextFilter.class.getDeclaredField("tokenTracker");
        field.setAccessible(true);
        return field.get(youtube.getContextFilter());
    }
}
