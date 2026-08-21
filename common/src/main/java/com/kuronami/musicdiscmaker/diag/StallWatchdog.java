package com.kuronami.musicdiscmaker.diag;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.kuronami.musicdiscmaker.MusicDiscMaker;

/**
 * <b>一時的な計測器（出荷する機能ではない。原因を特定したら丸ごと外す）。</b>
 *
 * <p>「ゲームが固まる」の原因を推論でなく実測で名指しするための停止ウォッチドッグ。
 * 監視対象のスレッドが自分で心拍（{@link #beatClient()} / {@link #beatServer()}）を打ち、
 * 別のデーモンスレッドが心拍の途切れを検知して、<b>止まった瞬間にそのスレッドがどこにいたか</b>を
 * スタックトレースごと WARN で出す。
 *
 * <p>心拍の置き場所:
 * <ul>
 * <li>client = {@code Minecraft#runTick(boolean)} の HEAD（毎フレーム。ポーズ中も回る）</li>
 * <li>server = {@code MinecraftServer#waitUntilNextTick()} の HEAD（server ループ 1 周に 1 回）。
 * {@code tickServer} でなく待ち側に置いているのは、{@code IntegratedServer#tickServer} が
 * ポーズ中に {@code super.tickServer} を呼ばないため（ポーズのたびに偽の停止が出る）。
 * {@code runServer} のループは {@code waitUntilNextTick} を無条件に毎周呼ぶ。</li>
 * </ul>
 *
 * <p>出力は 1 停止につき 1 レコードの複数行 WARN（latest.log 上で分断されないように)。
 * 相関の取り方: 停止開始時刻を {@code Track switch} / {@code Stream ended} /
 * {@code Filled ... silence} の行と突き合わせる。
 *
 * <p>閾値は {@code -Dmusicdiscmaker.stallwatchdog.ms=<数値>} で上書きできる（0 以下で無効化）。
 */
public final class StallWatchdog {

    /** これ以上心拍が途切れたら「停止」とみなす。 */
    private static final long THRESHOLD_MS = thresholdMs();
    /** 監視スレッドのポーリング間隔。 */
    private static final long POLL_MS = 25L;
    /** 同一の停止が続いている間の再ダンプ間隔（どこまで進んだかを見るため）。 */
    private static final long REDUMP_INTERVAL_MS = 2_000L;
    /** 同一スタックパターンをこの回数まではフルダンプする。以降は 1 行に間引く。 */
    private static final int FULL_DUMPS_PER_PATTERN = 3;
    /** 間引き中でも、この間隔が空いたら 1 度フルダンプし直す。 */
    private static final long PATTERN_REDUMP_MS = 60_000L;
    /** パターン別集計の定期サマリ間隔。 */
    private static final long SUMMARY_INTERVAL_MS = 60_000L;
    /** スタックの同一判定に使う上位フレーム数。 */
    private static final int SIGNATURE_FRAMES = 20;
    /** 監視対象スレッドのフレーム上限（join / park しか見えないと判定できないので深く出す）。 */
    private static final int WATCHED_MAX_FRAMES = 256;
    /** 参考スレッド（Sound engine 等）のフレーム上限。 */
    private static final int EXTRA_MAX_FRAMES = 24;
    /** 1 ダンプに添える参考スレッドの上限。 */
    private static final int MAX_EXTRA_THREADS = 8;
    /** パターン表の上限（際限なく増やさない）。 */
    private static final int MAX_PATTERNS = 200;
    /** 定期サマリに並べるパターン数の上限（多い順）。 */
    private static final int SUMMARY_TOP_N = 10;

    private static final String WATCHDOG_THREAD_NAME = "music_disc_maker-stall-watchdog";
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private static final Watch CLIENT = new Watch("client");
    private static final Watch SERVER = new Watch("server");
    private static final Watch[] WATCHES = { CLIENT, SERVER };

    /** 以下 3 つは watchdog スレッドだけが触る（同期不要）。 */
    private static final Map<String, Pattern> PATTERNS = new HashMap<>();
    private static int nextPatternId = 1;
    private static int eventsSinceSummary;

    static {
        if (THRESHOLD_MS > 0L) {
            final Thread thread = new Thread(StallWatchdog::watchdogLoop, WATCHDOG_THREAD_NAME);
            thread.setDaemon(true);
            thread.setPriority(Thread.MAX_PRIORITY); // 眠っているだけなので優先度は検知遅れの低減に使う
            thread.start();
            MusicDiscMaker.LOGGER.warn(
                    "[stall-watchdog] 一時的な計測器を起動した (閾値 {}ms / 再ダンプ {}ms)。特定後に外すこと",
                    THRESHOLD_MS, REDUMP_INTERVAL_MS);
        }
    }

    private StallWatchdog() {
    }

    /** client のメインスレッド（Render thread）の心拍。毎フレーム呼ばれる前提なので極力軽くする。 */
    public static void beatClient() {
        beat(CLIENT);
    }

    /** server スレッドの心拍。server ループ 1 周に 1 回。 */
    public static void beatServer() {
        beat(SERVER);
    }

    private static void beat(Watch watch) {
        // 先に時刻、後からスレッド。逆順だと watchdog が「スレッドは在るが心拍 0」を巨大な停止と誤読する。
        watch.lastBeatNanos = System.nanoTime();
        if (watch.thread != Thread.currentThread()) {
            watch.thread = Thread.currentThread();
        }
    }

    private static long thresholdMs() {
        final String raw = System.getProperty("musicdiscmaker.stallwatchdog.ms");
        if (raw == null || raw.isBlank()) {
            return 250L;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return 250L;
        }
    }

    private static void watchdogLoop() {
        long lastSummaryNanos = System.nanoTime();
        while (true) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            final long now = System.nanoTime();
            for (Watch watch : WATCHES) {
                try {
                    check(watch, now);
                } catch (Throwable t) { // 計測器がゲームを落とさないこと
                    MusicDiscMaker.LOGGER.warn("[stall-watchdog] 監視中に例外", t);
                }
            }
            if (now - lastSummaryNanos >= toNanos(SUMMARY_INTERVAL_MS)) {
                lastSummaryNanos = now;
                printSummary();
            }
        }
    }

    private static void check(Watch watch, long now) {
        final Thread thread = watch.thread;
        if (thread == null) {
            return; // まだ一度も心拍が来ていない
        }
        if (!thread.isAlive()) {
            // world を抜けた等で監視対象が消えた。次の心拍で再登録される。
            watch.thread = null;
            watch.stalled = false;
            return;
        }
        final long lastBeat = watch.lastBeatNanos;
        final long elapsedMs = (now - lastBeat) / 1_000_000L;

        if (elapsedMs >= THRESHOLD_MS) {
            if (!watch.stalled) {
                watch.stalled = true;
                watch.stallStartBeatNanos = lastBeat;
                watch.stallStartEpochMs = System.currentTimeMillis() - elapsedMs;
                watch.lastDumpNanos = 0L;
                watch.dumpsInStall = 0;
                watch.pattern = null;
            }
            if (watch.lastDumpNanos == 0L || now - watch.lastDumpNanos >= toNanos(REDUMP_INTERVAL_MS)) {
                watch.lastDumpNanos = now;
                watch.dumpsInStall++;
                eventsSinceSummary++;
                dump(watch, thread, elapsedMs, now);
            }
        } else if (watch.stalled) {
            watch.stalled = false;
            final long totalMs = (lastBeat - watch.stallStartBeatNanos) / 1_000_000L;
            if (watch.pattern != null && totalMs > watch.pattern.maxStallMs) {
                watch.pattern.maxStallMs = totalMs;
            }
            MusicDiscMaker.LOGGER.warn(
                    "[stall-watchdog] {} \"{}\" 復帰: 合計 {}ms 停止 (開始 {} / この停止の dump {} 回 / パターン {})",
                    watch.role, thread.getName(), totalMs, TIME_FMT.format(Instant.ofEpochMilli(watch.stallStartEpochMs)),
                    watch.dumpsInStall, watch.pattern == null ? "不明" : "#" + watch.pattern.id);
        }
    }

    private static void dump(Watch watch, Thread thread, long elapsedMs, long now) {
        final ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        final ThreadInfo watched = infoWithLocks(bean, thread.threadId());
        if (watched == null) {
            return; // ダンプ直前に消えた
        }

        final String signature = signatureOf(watched);
        final Pattern pattern = patternFor(signature, watched);
        pattern.count++;
        if (watch.pattern == null) {
            watch.pattern = pattern;
        }
        if (elapsedMs > pattern.maxStallMs) {
            pattern.maxStallMs = elapsedMs;
        }

        final boolean full = pattern.count <= FULL_DUMPS_PER_PATTERN
                || now - pattern.lastFullDumpNanos >= toNanos(PATTERN_REDUMP_MS);
        if (!full) {
            // 間引き: スタックは出さないが回数は数える
            MusicDiscMaker.LOGGER.warn(
                    "[stall-watchdog] {} \"{}\" 停止 {}ms 継続中 — パターン #{} と同じ (通算 {} 回目 / スタック省略) 開始 {} top={}",
                    watch.role, watched.getThreadName(), elapsedMs, pattern.id, pattern.count,
                    TIME_FMT.format(Instant.ofEpochMilli(watch.stallStartEpochMs)), pattern.top);
            return;
        }
        pattern.lastFullDumpNanos = now;

        final StringBuilder sb = new StringBuilder(4096);
        sb.append('\n');
        sb.append("========== MDM stall-watchdog (一時的な計測器) ==========\n");
        sb.append("停止中: ").append(watch.role).append(" \"").append(watched.getThreadName()).append("\" — ")
                .append(elapsedMs).append("ms 経過 (まだ復帰していない)\n");
        sb.append("停止開始: ").append(TIME_FMT.format(Instant.ofEpochMilli(watch.stallStartEpochMs)))
                .append("   閾値: ").append(THRESHOLD_MS).append("ms")
                .append("   この停止での dump: ").append(watch.dumpsInStall).append(" 回目\n");
        sb.append("パターン #").append(pattern.id).append(" (このパターン通算 ").append(pattern.count)
                .append(" 回目 / 最長 ").append(pattern.maxStallMs).append("ms)\n");

        appendThread(sb, watched, WATCHED_MAX_FRAMES);

        for (ThreadInfo extra : extraThreads(bean, watched)) {
            appendThread(sb, extra, EXTRA_MAX_FRAMES);
        }
        sb.append("========================================================");
        MusicDiscMaker.LOGGER.warn(sb.toString());
    }

    /**
     * 参考スレッド: 停止の相手方になりうるものを名前で拾う。
     * ロック保持者が判明していれば必ず含める（{@code park} 系は保持者が出ないので名前拾いが本体になる）。
     */
    private static List<ThreadInfo> extraThreads(ThreadMXBean bean, ThreadInfo watched) {
        final List<Long> wanted = new ArrayList<>();
        final long ownerId = watched.getLockOwnerId();
        if (ownerId >= 0L && ownerId != watched.getThreadId()) {
            wanted.add(ownerId);
        }
        try {
            final ThreadInfo[] names = bean.getThreadInfo(bean.getAllThreadIds(), 0); // 名前だけ = 安い
            for (ThreadInfo info : names) {
                if (info == null || wanted.size() >= MAX_EXTRA_THREADS) {
                    continue;
                }
                final long id = info.getThreadId();
                if (id == watched.getThreadId() || wanted.contains(id)) {
                    continue;
                }
                if (isInteresting(info.getThreadName())) {
                    wanted.add(id);
                }
            }
        } catch (Throwable t) {
            MusicDiscMaker.LOGGER.warn("[stall-watchdog] 参考スレッドの列挙に失敗", t);
        }
        if (wanted.isEmpty()) {
            return List.of();
        }
        final long[] ids = new long[wanted.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = wanted.get(i);
        }
        final List<ThreadInfo> result = new ArrayList<>(ids.length);
        for (ThreadInfo info : bean.getThreadInfo(ids, true, true)) {
            if (info != null) {
                result.add(info);
            }
        }
        return result;
    }

    private static boolean isInteresting(String name) {
        if (name == null || name.equals(WATCHDOG_THREAD_NAME)) {
            return false; // 自分のスタックは要らない
        }
        // 反対側のメインスレッドも見る（client の停止が server 待ち、あるいはその逆でありうる）
        if (name.equals("Render thread") || name.equals("Server thread")) {
            return true;
        }
        if (name.startsWith("Sound engine") || name.startsWith("Sound executor")) {
            return true;
        }
        if (name.startsWith("music_disc_maker-")) {
            return true;
        }
        final String lower = name.toLowerCase(Locale.ROOT);
        return lower.contains("lava"); // LavaPlayer の lava-daemon-pool-* 等
    }

    private static ThreadInfo infoWithLocks(ThreadMXBean bean, long threadId) {
        try {
            final ThreadInfo[] infos = bean.getThreadInfo(new long[] { threadId }, true, true);
            return infos.length > 0 ? infos[0] : null;
        } catch (Throwable t) {
            MusicDiscMaker.LOGGER.warn("[stall-watchdog] ThreadInfo の取得に失敗", t);
            return null;
        }
    }

    private static void appendThread(StringBuilder sb, ThreadInfo info, int maxFrames) {
        sb.append("--- \"").append(info.getThreadName()).append("\" id=").append(info.getThreadId())
                .append(' ').append(info.getThreadState());
        if (info.isInNative()) {
            sb.append(" (native)");
        }
        if (info.isSuspended()) {
            sb.append(" (suspended)");
        }
        sb.append(" ---\n");

        final LockInfo lock = info.getLockInfo();
        if (lock != null) {
            sb.append("    待っている対象: ").append(lock).append('\n');
            final String owner = info.getLockOwnerName();
            if (owner != null) {
                sb.append("    その保持者: \"").append(owner).append("\" id=").append(info.getLockOwnerId()).append('\n');
            } else {
                sb.append("    その保持者: 不明 (park / 条件待ちは所有者を持たない。相手は下の参考スレッドを見る)\n");
            }
        }

        final StackTraceElement[] stack = info.getStackTrace();
        final MonitorInfo[] monitors = info.getLockedMonitors();
        final int shown = Math.min(stack.length, maxFrames);
        for (int i = 0; i < shown; i++) {
            sb.append("    at ").append(stack[i]).append('\n');
            for (MonitorInfo monitor : monitors) {
                if (monitor.getLockedStackDepth() == i) {
                    sb.append("    - locked ").append(monitor).append('\n');
                }
            }
        }
        if (shown < stack.length) {
            sb.append("    ... 以下 ").append(stack.length - shown).append(" フレーム省略 (全 ")
                    .append(stack.length).append(" フレーム)\n");
        }
        final LockInfo[] held = info.getLockedSynchronizers();
        if (held.length > 0) {
            sb.append("    保持中の synchronizer:\n");
            for (LockInfo one : held) {
                sb.append("      - ").append(one).append('\n');
            }
        }
    }

    private static String signatureOf(ThreadInfo info) {
        final StackTraceElement[] stack = info.getStackTrace();
        final StringBuilder sb = new StringBuilder(256);
        final int n = Math.min(stack.length, SIGNATURE_FRAMES);
        for (int i = 0; i < n; i++) {
            final StackTraceElement e = stack[i];
            sb.append(e.getClassName()).append('#').append(e.getMethodName()).append(':')
                    .append(e.getLineNumber()).append('|');
        }
        return sb.toString();
    }

    private static Pattern patternFor(String signature, ThreadInfo info) {
        Pattern pattern = PATTERNS.get(signature);
        if (pattern != null) {
            return pattern;
        }
        final StackTraceElement[] stack = info.getStackTrace();
        final String top = stack.length > 0 ? stack[0].toString() : "(スタック無し)";
        if (PATTERNS.size() >= MAX_PATTERNS) {
            // 表が飽和した。id 0 = 「その他」に集約して増殖を止める。
            return PATTERNS.computeIfAbsent("", k -> new Pattern(0, "(パターン表が飽和)"));
        }
        pattern = new Pattern(nextPatternId++, top);
        PATTERNS.put(signature, pattern);
        return pattern;
    }

    private static void printSummary() {
        if (eventsSinceSummary == 0) {
            return;
        }
        final StringBuilder sb = new StringBuilder(512);
        sb.append("[stall-watchdog] 直近 ").append(SUMMARY_INTERVAL_MS / 1000L).append(" 秒の停止検知 ")
                .append(eventsSinceSummary).append(" 件。パターン別の通算 (多い順 上位 ")
                .append(SUMMARY_TOP_N).append(" / 全 ").append(PATTERNS.size()).append(" 種):");
        final List<Pattern> ranked = new ArrayList<>(PATTERNS.values());
        ranked.sort((a, b) -> Long.compare(b.count, a.count));
        for (Pattern pattern : ranked.subList(0, Math.min(SUMMARY_TOP_N, ranked.size()))) {
            sb.append("\n    #").append(pattern.id).append(" x").append(pattern.count)
                    .append(" (最長 ").append(pattern.maxStallMs).append("ms) at ").append(pattern.top);
        }
        eventsSinceSummary = 0;
        MusicDiscMaker.LOGGER.warn(sb.toString());
    }

    private static long toNanos(long millis) {
        return millis * 1_000_000L;
    }

    /** 監視対象 1 本ぶんの状態。{@code thread} / {@code lastBeatNanos} 以外は watchdog スレッド専用。 */
    private static final class Watch {

        private final String role;

        private volatile Thread thread;
        private volatile long lastBeatNanos;

        private boolean stalled;
        private long stallStartBeatNanos;
        private long stallStartEpochMs;
        private long lastDumpNanos;
        private int dumpsInStall;
        private Pattern pattern;

        private Watch(String role) {
            this.role = role;
        }
    }

    /** 同一スタックの間引きと回数集計。 */
    private static final class Pattern {

        private final int id;
        private final String top;

        private long count;
        private long maxStallMs;
        private long lastFullDumpNanos;

        private Pattern(int id, String top) {
            this.id = id;
            this.top = top;
        }
    }
}
