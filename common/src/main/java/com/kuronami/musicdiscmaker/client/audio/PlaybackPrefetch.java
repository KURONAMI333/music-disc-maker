package com.kuronami.musicdiscmaker.client.audio;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;

/**
 * 次に鳴る曲を先に開いておく置き場 (MC 非依存)。アルバムの曲間に空く 5〜10 秒の無音を消すためのもの。
 *
 * <h2>なぜ「開いておく」だけで効くのか</h2>
 * 無音の内訳は (a) URL 解決 (ネットワーク) と (b) MC が再生開始時に 4 秒分の PCM を引き切るまで
 * 戻らないこと ({@code Channel.attachBufferStream} の {@code pumpBuffers(4)}) の 2 つ。
 * {@code MusicLoaderImpl#beginPlayback} は開いた時点で {@code playTrack} まで撃つので、
 * lavaplayer のデコード専用スレッドが既定 251 frame (5 秒) まで自走して満杯で止まる。
 * MC が要求する 4 秒 = 200 frame &lt; 251 なので、<b>事前に開いたソースは初回 pump をメモリから
 * 満たせる</b>。空回しして捨てる仕掛けは要らない。
 *
 * <h2>これは<b>キャッシュであって世代管理ではない</b> (最重要)</h2>
 * {@link #claim} は「ロード中だからスキップしろ」を<b>絶対に返さない</b>。ヒットしなければ
 * {@code null} を返し、呼び出し側は従来どおり普通のロードを走らせる。戻り値の型を
 * {@link IAudioSource} 一本にしてあるのは、その分岐を型として書けなくするため。
 *
 * <p>ここを「トークン付きの in-flight ロード」にすると、{@link LivePlaybackRegistry} で過去に出した
 * <b>「解決が再送間隔より遅いと永久に着地しない = 永久に無音」</b>と同型のバグになる。
 * 最悪ケースは重複ロード 1 本 (= 先読みを入れる前の挙動) で、それは許容できる。無音は許容できない。
 *
 * <h2>寿命を {@link #EXPIRY_MS} で閉じる理由 — 60 秒リーパー</h2>
 * lavaplayer の {@code AudioPlayerLifecycleManager} は 10 秒ごとに走査し、{@code provide} されない
 * player を 60 秒 ({@code DEFAULT_CLEANUP_THRESHOLD}) で {@code CLEANUP} 停止させる
 * ({@code lastRequestTime} を打つのは {@code startTrack} と {@code provide} だけ)。
 * <b>殺された先読みを掴んで鳴らすと {@code read()} が即 {@code -1} を返し、MC は「尺ゼロの曲」として
 * 扱う = 完全無音・復帰なし</b>で、元のギャップより悪い。だから 60 秒より十分手前で捨てる。
 * {@code apm.setPlayerCleanupThreshold()} で閾値を緩める道は採らない (孤児 player の安全網)。
 *
 * @param <K> 音源をまとめる鍵 (強化版ジュークボックスなら座標)
 */
public final class PlaybackPrefetch<K> {

    /**
     * 先読みを抱えていられる上限 (ms)。60 秒リーパーに殺される前に必ず手放すための値で、
     * 掴む側の lead time (15 秒程度) の倍を取ってある。
     */
    public static final long EXPIRY_MS = 30_000L;

    /**
     * 同時に抱えていられる先読みの上限 (鍵の数)。1 本あたり frame 251 × 3840 バイト ≒ 964KB +
     * デコーダ状態 + HTTP 接続 1 本を掴む。{@link com.kuronami.musicdiscmaker.client.audio.DiscSoundInstance}
     * 側の発火条件 (可聴範囲内で actively 再生中・残り {@code PREFETCH_LEAD_MS}=15 秒以内) があるので
     * 定常状態の実数は小さいはずだが、それは呼び出し側の運用条件であってこのクラス自身の保証では
     * ないので上限が無いこと自体を塞ぐ。値は実測ではなく推測 — ジュークボックスを密集させた展示等の
     * worst case でも上限 × 単価がメモリを食い潰さない範囲、かつ通常プレイの定常同時数には
     * まず届かない桁、を狙って選んだ余裕値。
     */
    public static final int MAX_CONCURRENT_PREFETCH = 16;

    /**
     * 1 回の先読みの引換券。{@link #begin} が発行し、{@link #deliver} で照合する。
     *
     * <p><b>この id は「ロードするかどうか」の判断には一切使われない</b> — 届いたソースを
     * 受け取るか捨てるかを決めるためだけのもの。取り消し済みのスロットへ古いロードが
     * 着地して、期限切れ寸前のソースが新しい枠に化けるのを防ぐ。
     *
     * @param <T> 音源をまとめる鍵の型 (外側の {@code K} と同じもの)
     * @param key 音源をまとめる鍵
     * @param url 先読みしている曲の URL
     * @param id  この先読みの通し番号
     */
    public record Ticket<T>(T key, String url, long id) {
    }

    /** 鍵 1 つ分の先読み。{@code source} が {@code null} の間はロード中。 */
    private static final class Slot {

        private final String url;
        private final long id;
        private final long deadlineMs;
        /**
         * 書き込み = ロードスレッド ({@link #deliver}) / 読み出し = main thread ({@link #claim})。
         * <b>{@code volatile} が要る</b> — 書き込みは {@code ConcurrentHashMap} へ入れた後に起きるので、
         * map 経由では公開されない。可視性が無いと main thread が {@code null} を読んで
         * 「まだロード中」の枝へ落ち、<b>枠は既に取り除いた後・{@code deliver} は成功を返した後</b>に
         * なるので、どちらも閉じない持ち主不在のソースが残る。
         */
        @Nullable
        private volatile IAudioSource source;

        private Slot(String url, long id, long deadlineMs) {
            this.url = url;
            this.id = id;
            this.deadlineMs = deadlineMs;
        }
    }

    private final Map<K, Slot> slots = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final LongSupplier clockMs;

    /**
     * @param clockMs 現在時刻 (ms)。テストは実時間を待たずに期限切れを作るために差し替える
     */
    public PlaybackPrefetch(LongSupplier clockMs) {
        this.clockMs = clockMs;
    }

    /**
     * この鍵で {@code url} の先読みを始めてよいか決め、始めるなら枠を予約する。
     *
     * <p>同じ URL を既に抱えている (ロード中・完了済みのどちらでも) なら {@code null} を返す。
     * 呼び出し側は毎 tick 呼んでよい。別の URL を抱えていたらそれを閉じてから新しい枠を作る
     * (アルバム差し替え・曲送りで「次の曲」が変わった場合)。
     *
     * <p>既に {@link #MAX_CONCURRENT_PREFETCH} 本を抱えている状態で新しい鍵の先読みを頼まれたら
     * 始めない (miss 扱い)。呼び出し側は {@link #claim} が {@code null} を返すのと同じ道をたどり、
     * 従来どおり普通のロードが走るだけで無音にはならない。既存の鍵の曲差し替え (枠を再利用するだけ
     * で総数は増えない) は上限の対象にしない。
     *
     * @param key 音源をまとめる鍵
     * @param url 先読みしたい曲の URL
     * @return 発行された引換券。始めないなら {@code null}
     */
    @Nullable
    public Ticket<K> begin(K key, String url) {
        sweep();
        final Slot existing = slots.get(key);
        if (existing != null) {
            if (existing.url.equals(url)) {
                return null; // 同じ曲を既に抱えている (ロード中でも完了済みでも重ねない)
            }
            discard(slots.remove(key)); // 次に鳴る曲が変わった (枠の再利用なので上限には当たらない)
        } else if (slots.size() >= MAX_CONCURRENT_PREFETCH) {
            return null; // 上限に達しているので新規の先読みを始めない (miss 扱い = 従来どおりロードが走る)
        }
        final long now = clockMs.getAsLong();
        final Slot slot = new Slot(url, ids.incrementAndGet(), now + EXPIRY_MS);
        slots.put(key, slot);
        return new Ticket<>(key, url, slot.id);
    }

    /**
     * 先読みのロードが終わったことを届ける。
     *
     * <p>受け取れなかった場合 ({@code false}) は<b>呼び出し側がそのソースを閉じること</b>。
     * 取り消し済み・期限切れ・別の URL に差し替え済みのどれかで、ここで抱えると漏れる。
     *
     * @param ticket {@link #begin} が発行した引換券
     * @param source 開けたソース。開けなかったなら {@code null} (枠だけ解放する)
     * @return 受け取ったなら {@code true}
     */
    public boolean deliver(Ticket<K> ticket, @Nullable IAudioSource source) {
        sweep();
        final Slot slot = slots.get(ticket.key());
        if (slot == null || slot.id != ticket.id() || slot.source != null) {
            return false; // 取り消し済み / 期限切れで掃除済み / 二重配達
        }
        if (source == null) {
            slots.remove(ticket.key(), slot); // 先読みの失敗は黙って枠を空ける (報告は本番のロードが出す)
            return false;
        }
        slot.source = source;
        return true;
    }

    /**
     * 温めておいたソースを受け取る。<b>ヒットしなければ {@code null}</b> — 呼び出し側は
     * 従来どおり普通のロードを走らせること。「ロード中だからスキップ」は決して返さない。
     *
     * <p>ヒットしてもしなくても、この鍵の先読みはここで手放す。ロード中だった先読みは
     * 引換券の照合が外れるので、後から届いた分は {@link #deliver} が拒否する。
     *
     * <p>{@code startOffsetMs != 0} を必ず外すのは、先読みが常に頭 (0ms) から開くから。
     * chunk 再入の再送やシーク要求に当てると<b>途中から鳴るはずの曲が頭から鳴る</b>。
     * アルバムの曲送り ({@code advanceAlbumTrack}) は必ず {@code startPlayback(0L)} なので、
     * この条件で落ちる正当なヒットは無い。
     *
     * @param key           音源をまとめる鍵
     * @param url           これから鳴らす曲の URL
     * @param startOffsetMs 再生開始位置 (ms)
     * @return 温めておいたソース。無ければ {@code null}
     */
    @Nullable
    public IAudioSource claim(K key, String url, long startOffsetMs) {
        final Slot slot = slots.remove(key);
        if (slot == null) {
            return null;
        }
        if (slot.source == null) {
            return null; // まだロード中 = キャッシュミス。届いた分は deliver が捨てる
        }
        if (startOffsetMs != 0L || !slot.url.equals(url) || clockMs.getAsLong() >= slot.deadlineMs) {
            slot.source.close();
            return null;
        }
        return slot.source;
    }

    /**
     * この鍵の先読みを捨てる (停止・ディスク交換・ブロック撤去・一時停止・後方シーク)。
     * ロード中なら引換券が外れるので、後から届いた分は {@link #deliver} が拒否する。
     *
     * @param key 音源をまとめる鍵
     */
    public void drop(K key) {
        discard(slots.remove(key));
    }

    /** 全ての先読みを捨てる (ワールド退出等)。 */
    public void dropAll() {
        for (final Map.Entry<K, Slot> entry : slots.entrySet()) {
            final Slot slot = entry.getValue();
            if (slots.remove(entry.getKey(), slot)) {
                discard(slot);
            }
        }
    }

    /**
     * いま抱えている先読みの URL (テストと診断用)。
     *
     * @param key 音源をまとめる鍵
     * @return 抱えている URL。無ければ {@code null}
     */
    @Nullable
    public String slotUrl(K key) {
        final Slot slot = slots.get(key);
        return slot == null ? null : slot.url;
    }

    /**
     * 期限切れの先読みを閉じる。main thread ({@link #begin}) とロードスレッド ({@link #deliver}) の
     * 両方から走るので、取り除けた側だけが閉じる (二重に {@code discard} を撃たない)。
     */
    private void sweep() {
        final long now = clockMs.getAsLong();
        for (final Map.Entry<K, Slot> entry : slots.entrySet()) {
            final Slot slot = entry.getValue();
            if (now >= slot.deadlineMs && slots.remove(entry.getKey(), slot)) {
                discard(slot);
            }
        }
    }

    /** 枠を手放す。ロード中 ({@code source == null}) なら閉じるものは無い。 */
    private static void discard(@Nullable Slot slot) {
        if (slot != null && slot.source != null) {
            slot.source.close();
        }
    }
}
