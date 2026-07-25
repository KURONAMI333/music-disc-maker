package com.kuronami.musicdiscmaker.gametest;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.audio.LoaderHolder;
import com.kuronami.musicdiscmaker.audio.cache.AudioCacheFormat;
import com.kuronami.musicdiscmaker.audio.cache.AudioCacheGate;
import com.kuronami.musicdiscmaker.audio.cache.AudioCachePolicy;
import com.kuronami.musicdiscmaker.audio.cache.AudioCacheStore;
import com.kuronami.musicdiscmaker.audio.cache.AudioCacheWriter;
import com.kuronami.musicdiscmaker.audio.cache.CachedAudioSource;
import com.kuronami.musicdiscmaker.audio.cache.TeeAudioSource;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.IMusicLoader;
import com.kuronami.musicdiscmaker.lavaplayer.api.IOpusDecoder;
import com.kuronami.musicdiscmaker.lavaplayer.api.IOpusEncoder;
import com.kuronami.musicdiscmaker.lavaplayer.api.TrackInfo;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 音源ローカルキャッシュの headless テスト。
 *
 * <p><b>ここで固定できること</b>: コンテナの往復 (実 Opus codec を通す)・完走判定・シーク位置・
 * 破損ファイルの扱い・LRU の対象・キー規約・除外判定。<b>固定できないこと</b>: 実際に音が鳴るか、
 * オフラインで鳴り続けるか (client 起動が要る = kura 実機帯)。
 *
 * <p>キャッシュの実体クラスは MC の API を参照しないので、一時ディレクトリを渡してそのまま動く。
 * ネットワークには一切出ない (PCM は合成し、Opus は同梱 native をそのまま使う)。
 *
 * <p>codec を使うテストは専用 batch へ隔離してある。GameTest は同一 batch 内で並列に走るので、
 * {@code DependencyManager.load()} (プロセス全体で 1 回きり) を複数テストが同時に踏まないようにする。
 */
@GameTestHolder(MusicDiscMaker.MODID)
public class AudioCacheGameTests {

    private static final String TEMPLATE = "empty8x3x8";
    /** codec (= 隔離 classloader の native) を触るテストの batch。 */
    private static final String CODEC_BATCH = "audio_cache_codec";

    private static final String URL = "https://example.invalid/cache-fixture";
    /** 合成音の尺。20ms フレーム 150 個ぶん。 */
    private static final long TRACK_MS = 3_000L;

    // ── キー規約と除外判定 (codec 不要) ──────────────────────────────────

    /**
     * キーはビートマップと同じ規約 (URL 文字列の SHA-1 hex)。同じ曲の {@code .beat} と
     * {@code .audio} が同じ名前で隣り合うことが P4 との整合の前提なので、値そのものを固定する。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void audioCacheKeyIsSha1OfUrl(GameTestHelper helper) {
        // echo -n "https://example.invalid/cache-fixture" | sha1sum
        final String expected = "6ff941e49457a3af9c8784cce437450540dd9afc";
        final String actual = AudioCacheStore.keyOf(URL);
        if (actual.length() != 40 || !actual.matches("[0-9a-f]{40}")) {
            helper.fail("キーが SHA-1 hex ではない: " + actual);
        }
        if (!expected.equals(actual)) {
            helper.fail("キーが期待値と違う: " + actual + " (期待 " + expected + ")");
        }
        // 前後の空白は無視される (BeatMaps と同じく trim してからハッシュする)
        if (!actual.equals(AudioCacheStore.keyOf("  " + URL + "  "))) {
            helper.fail("trim の扱いがビートマップと揃っていない");
        }
        helper.succeed();
    }

    /** kura 裁定の除外: SoundCloud だけ。YouTube ほかは対象。ラジオと尺不明も対象外。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void audioCacheExcludesOnlySoundCloudAndEndlessStreams(GameTestHelper helper) {
        expectCacheable(helper, "https://www.youtube.com/watch?v=abcdefghijk", 200_000L, false, true);
        expectCacheable(helper, "https://artist.bandcamp.com/track/song", 200_000L, false, true);
        expectCacheable(helper, "https://vimeo.com/123456", 200_000L, false, true);
        expectCacheable(helper, "https://example.invalid/song.mp3", 200_000L, false, true);
        expectCacheable(helper, "https://soundcloud.com/artist/track", 200_000L, false, false);
        expectCacheable(helper, "https://m.soundcloud.com/artist/track", 200_000L, false, false);
        expectCacheable(helper, "https://cf-media.sndcdn.com/abc.128.mp3", 200_000L, false, false);
        // "notsoundcloud.com" を巻き込まない (ドメイン境界で見ること)
        expectCacheable(helper, "https://notsoundcloud.com/track", 200_000L, false, true);
        // ラジオ / LIVE は終端が無いので完走判定ができない
        expectCacheable(helper, "https://radio.invalid/stream", 0L, true, false);
        expectCacheable(helper, "https://example.invalid/unknown", 0L, false, false);
        helper.succeed();
    }

    private static void expectCacheable(GameTestHelper helper, String url, long durationMs,
            boolean radio, boolean expected) {
        if (AudioCachePolicy.cacheable(url, durationMs, radio) != expected) {
            helper.fail("キャッシュ可否の判定が違う (" + url + "): 期待 " + expected);
        }
    }

    // ── 置き場・LRU・排他 (codec 不要) ───────────────────────────────────

    /**
     * 上限超過の削除は {@code .audio} だけを見る。同じディレクトリに同居しうる
     * ビートマップ ({@code .beat}) を巻き込むと、ビート連動が黙って再解析地獄に落ちる。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void audioCachePruneKeepsBeatMapsAndDropsOldest(GameTestHelper helper) {
        try {
            final Path dir = tempDir("prune");
            final AudioCacheStore store = new AudioCacheStore(dir);
            final Path old = write(dir, "aaa" + AudioCacheStore.EXTENSION, 600);
            final Path fresh = write(dir, "bbb" + AudioCacheStore.EXTENSION, 600);
            final Path beat = write(dir, "aaa.beat", 600);
            final Path part = write(dir, "ccc" + AudioCacheStore.PART_EXTENSION, 600);
            touch(old, 1_000L);
            touch(beat, 1_000L);
            touch(part, 1_000L);
            touch(fresh, 9_000_000L);

            store.prune(1000L); // 2 つの .audio (1200 byte) が上限 1000 を超えている

            if (Files.exists(old)) {
                helper.fail("いちばん古い .audio が消えていない");
            }
            if (!Files.exists(fresh)) {
                helper.fail("新しい .audio まで消えた");
            }
            if (!Files.exists(beat)) {
                helper.fail("ビートマップ (.beat) を巻き込んで消した");
            }
            if (!Files.exists(part)) {
                helper.fail("書き込み途中 (.part) を prune が消した");
            }
            helper.succeed();
        } catch (final IOException ex) {
            helper.fail("prune テストが IO で失敗: " + ex);
        }
    }

    /**
     * 命中のたびに更新時刻を今にする。これが無いと LRU が実質 FIFO になり、
     * <b>いちばんよく聴く曲から消える</b> ({@code JacketCache} が踏んでいる)。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void audioCacheHitTouchesForLru(GameTestHelper helper) {
        try {
            final Path dir = tempDir("touch");
            final AudioCacheStore store = new AudioCacheStore(dir);
            final String key = AudioCacheStore.keyOf(URL);
            final Path file = write(dir, key + AudioCacheStore.EXTENSION, 16);
            touch(file, 1_000L);

            if (store.hit(key) == null) {
                helper.fail("あるはずのキャッシュに命中しない");
            }
            if (Files.getLastModifiedTime(file).toMillis() <= 1_000L) {
                helper.fail("命中しても更新時刻が古いまま (LRU が FIFO になる)");
            }
            if (store.hit(AudioCacheStore.keyOf("https://example.invalid/absent")) != null) {
                helper.fail("無いキャッシュに命中した");
            }
            helper.succeed();
        } catch (final IOException ex) {
            helper.fail("touch テストが IO で失敗: " + ex);
        }
    }

    /** 書き込み権は 1 つだけ。既にキャッシュがある曲は書き直さない。取った権は必ず返る。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void audioCacheClaimIsExclusive(GameTestHelper helper) {
        try {
            final Path dir = tempDir("claim");
            final AudioCacheStore store = new AudioCacheStore(dir);
            final String key = AudioCacheStore.keyOf(URL);

            if (!store.claim(key)) {
                helper.fail("最初の claim が取れない");
            }
            if (store.claim(key)) {
                helper.fail("同じ key の claim が二重に取れた (同じ曲を 2 箇所で同時再生した時)");
            }
            store.release(key);
            if (!store.claim(key)) {
                helper.fail("discard の後で claim が取れない (権が返っていない)");
            }
            store.release(key);

            write(dir, key + AudioCacheStore.EXTENSION, 16);
            if (store.claim(key)) {
                helper.fail("既にキャッシュ済みの曲で claim が取れた (無駄に書き直す)");
            }
            helper.succeed();
        } catch (final IOException ex) {
            helper.fail("claim テストが IO で失敗: " + ex);
        }
    }

    /** クラッシュで取り残された {@code .part} は次のセッションで掃除される。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void audioCacheSweepsStaleParts(GameTestHelper helper) {
        try {
            final Path dir = tempDir("sweep");
            final Path stale = write(dir, "dead" + AudioCacheStore.PART_EXTENSION, 32);
            final Path keep = write(dir, "live" + AudioCacheStore.EXTENSION, 32);
            final AudioCacheStore store = new AudioCacheStore(dir);

            store.sweepStalePartsOnce();

            if (Files.exists(stale)) {
                helper.fail("取り残された .part が掃除されていない");
            }
            if (!Files.exists(keep)) {
                helper.fail("掃除が .audio まで消した");
            }
            helper.succeed();
        } catch (final IOException ex) {
            helper.fail("sweep テストが IO で失敗: " + ex);
        }
    }

    /** 壊れたファイルは読まずに捨て、ネットワークへ落ちられるようにする。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = CODEC_BATCH)
    public static void audioCacheDropsCorruptFile(GameTestHelper helper) {
        try {
            final Path dir = tempDir("corrupt");
            final Path file = dir.resolve("corrupt" + AudioCacheStore.EXTENSION);
            Files.write(file, new byte[] {'N', 'O', 'P', 'E', 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});

            final CachedAudioSource source = CachedAudioSource.open(file, decoder(), 0L);

            if (source != null) {
                source.close();
                helper.fail("壊れたキャッシュを開いてしまった");
            }
            if (Files.exists(file)) {
                helper.fail("壊れたキャッシュが残っている (毎回同じ失敗を繰り返す)");
            }
            helper.succeed();
        } catch (final IOException ex) {
            helper.fail("破損テストが IO で失敗: " + ex);
        }
    }

    // ── 実 codec を通す往復 (batch 隔離) ─────────────────────────────────

    /**
     * <b>この機能の本筋</b>: 頭から最後まで通した再生が確定し、次の再生がそのファイルから鳴る。
     * ネットワーク経路の {@link IAudioSource} を合成 PCM で模し、tee を通して書き、読み直す。
     * 同梱 native の Opus が実際に動くこと (P0B の可用性ゲート) もここで初めて確かめられる。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = CODEC_BATCH)
    public static void audioCacheRoundTripsFullPlayback(GameTestHelper helper) {
        try {
            final Path dir = tempDir("roundtrip");
            final AudioCacheStore store = new AudioCacheStore(dir);
            final String key = AudioCacheStore.keyOf(URL);
            if (!store.claim(key)) {
                helper.fail("claim が取れない");
                return;
            }
            final byte[] pcm = synthesizePcm(TRACK_MS);
            drainThroughTee(store, key, TRACK_MS, pcm);

            final Path file = store.hit(key);
            if (file == null) {
                helper.fail("完走した再生がキャッシュに確定していない (この機能の主フロー)");
                return;
            }
            if (Files.size(file) <= AudioCacheFormat.HEADER_BYTES) {
                helper.fail("キャッシュファイルが空 (ヘッダだけ)");
            }
            // 圧縮できていること: 生 PCM より十分小さい
            if (Files.size(file) >= pcm.length / 4L) {
                helper.fail("圧縮が効いていない: " + Files.size(file) + " byte / 生 " + pcm.length);
            }

            final CachedAudioSource source = CachedAudioSource.open(file, decoder(), 0L);
            if (source == null) {
                helper.fail("確定したキャッシュを読み直せない");
                return;
            }
            try {
                if (source.sampleRate() != AudioCacheFormat.SAMPLE_RATE
                        || source.channels() != AudioCacheFormat.CHANNELS
                        || source.bitsPerSample() != AudioCacheFormat.BITS_PER_SAMPLE
                        || source.bigEndian()) {
                    helper.fail("キャッシュ経路の PCM 形式がネットワーク経路と違う");
                }
                final byte[] decoded = drain(source);
                // Opus は非可逆かつ先読み (pre-skip) があるので長さは厳密一致しない。
                // 「ほぼ同じ尺が返る」ことと「無音でない」ことを見る。
                final long ratio = decoded.length * 100L / pcm.length;
                if (ratio < 95 || ratio > 105) {
                    helper.fail("読み直した尺が合わない: " + decoded.length + " / " + pcm.length
                            + " byte (" + ratio + "%)");
                }
                if (rms(decoded, 0, decoded.length) < 1000.0) {
                    helper.fail("読み直した音が無音同然 (バッファの取り違え等)");
                }
            } finally {
                source.close();
            }
            helper.succeed();
        } catch (final IOException ex) {
            helper.fail("往復テストが IO で失敗: " + ex);
        }
    }

    /**
     * 途中で切れた再生はキャッシュしない。{@code -1} は「曲の終わり」と「回線が詰まって諦めた」を
     * 区別できないので、受け取った PCM の長さでも縛る。ここが緩むと<b>切り詰められた曲が
     * 恒久的に切り詰められたまま鳴る</b> (rot より悪い)。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = CODEC_BATCH)
    public static void audioCacheDiscardsTruncatedPlayback(GameTestHelper helper) {
        final Path dir;
        try {
            dir = tempDir("truncated");
        } catch (final IOException ex) {
            helper.fail("一時ディレクトリを作れない: " + ex);
            return;
        }
        final AudioCacheStore store = new AudioCacheStore(dir);

        // ① 終端は来たが尺の半分しか流れていない (回線が詰まって諦めた形)
        final String shortKey = AudioCacheStore.keyOf(URL + "#short");
        if (!store.claim(shortKey)) {
            helper.fail("claim が取れない");
            return;
        }
        drainThroughTee(store, shortKey, TRACK_MS, synthesizePcm(TRACK_MS / 2));
        if (store.hit(shortKey) != null) {
            helper.fail("尺の半分しか流れていない再生をキャッシュした");
            return;
        }

        // ② 尺は足りているが終端を見ていない (再生中に止められた形)
        final String openKey = AudioCacheStore.keyOf(URL + "#open");
        if (!store.claim(openKey)) {
            helper.fail("claim が取れない");
            return;
        }
        final IOpusEncoder encoder = encoder();
        final AudioCacheWriter writer = AudioCacheWriter.open(
                store, openKey, URL, TRACK_MS, 0L, encoder, () -> true);
        if (writer == null) {
            helper.fail("書き手を開けない (Opus encoder が使えない)");
            return;
        }
        final byte[] pcm = synthesizePcm(TRACK_MS);
        writer.accept(pcm, 0, pcm.length);
        writer.close(); // endOfStream を呼ばずに閉じる
        awaitSettled(store, openKey);
        if (store.hit(openKey) != null) {
            helper.fail("終端を見ていない再生をキャッシュした");
            return;
        }

        // 権が返っていること (返っていないと以後この曲は二度とキャッシュされない)
        if (!store.claim(shortKey) || !store.claim(openKey)) {
            helper.fail("捨てた後に書き込み権が返っていない");
            return;
        }
        store.release(shortKey);
        store.release(openKey);
        helper.succeed();
    }

    /**
     * シーク位置から読める。20ms 固定フレームなので位置はフレーム単位で厳密に決まる。
     * これが崩れると途中参加同期と GUI シークバーが退行する。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = CODEC_BATCH)
    public static void audioCacheSeeksToRequestedOffset(GameTestHelper helper) {
        try {
            final Path dir = tempDir("seek");
            final AudioCacheStore store = new AudioCacheStore(dir);
            final String key = AudioCacheStore.keyOf(URL + "#seek");
            if (!store.claim(key)) {
                helper.fail("claim が取れない");
                return;
            }
            // 前半 1.5 秒が無音、後半 1.5 秒がフルスケールの合成音。
            final byte[] pcm = synthesizePcm(TRACK_MS);
            java.util.Arrays.fill(pcm, 0, pcm.length / 2, (byte) 0);
            drainThroughTee(store, key, TRACK_MS, pcm);

            final Path file = store.hit(key);
            if (file == null) {
                helper.fail("確定していない");
                return;
            }
            assertLevelAt(helper, file, 0L, false);          // 頭 = 無音側
            assertLevelAt(helper, file, TRACK_MS * 3 / 4, true);  // 2.25 秒 = 音の側
            // 尺を越えたシークは何も返さない (曲が終わっている)
            final CachedAudioSource past = CachedAudioSource.open(file, decoder(), TRACK_MS * 4);
            if (past == null) {
                helper.fail("尺を越えたシークで開けない");
                return;
            }
            try {
                if (past.read(new byte[1024], 0, 1024) != -1) {
                    helper.fail("尺を越えたシークで音が返った");
                }
            } finally {
                past.close();
            }
            helper.succeed();
        } catch (final IOException ex) {
            helper.fail("シークテストが IO で失敗: " + ex);
        }
    }

    private static void assertLevelAt(GameTestHelper helper, Path file, long offsetMs, boolean loud) {
        final CachedAudioSource source = CachedAudioSource.open(file, decoder(), offsetMs);
        if (source == null) {
            helper.fail("シーク先で開けない (" + offsetMs + "ms)");
            return;
        }
        try {
            final byte[] buf = new byte[48000]; // 0.5 秒ぶん
            int read = 0;
            while (read < buf.length) {
                final int n = source.read(buf, read, buf.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            // 先頭 20ms は Opus のフレーム境界とデコーダの立ち上がりが混じるので外す。
            final int skip = Math.min(read, 4000);
            final double level = rms(buf, skip, read - skip);
            if (loud && level < 1000.0) {
                helper.fail(offsetMs + "ms が無音 (シークが効いていない): rms=" + (long) level);
            }
            if (!loud && level > 500.0) {
                helper.fail(offsetMs + "ms が無音でない (シーク位置がずれている): rms=" + (long) level);
            }
        } finally {
            source.close();
        }
    }

    // ── 入口の判断 (ネットワークに出たかどうかまで固定する) ────────────────

    /**
     * <b>cache-first</b>: 命中したらネットワークに一切触らない。network-first にすると、
     * 外部が壊れている間は {@code loadTrackSync} の 30 秒タイムアウトを毎回食い、この機能の
     * 存在意義が消える。偽 loader が呼ばれたかどうかで直接固定する。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = CODEC_BATCH)
    public static void audioCacheHitNeverTouchesNetwork(GameTestHelper helper) {
        try {
            final Path dir = tempDir("gate-hit");
            final AudioCacheStore store = new AudioCacheStore(dir);
            final CustomTrackData track = track(URL, TRACK_MS);
            final String key = AudioCacheStore.keyOf(URL);
            if (!store.claim(key)) {
                helper.fail("claim が取れない");
                return;
            }
            drainThroughTee(store, key, TRACK_MS, synthesizePcm(TRACK_MS));
            if (store.hit(key) == null) {
                helper.fail("前提のキャッシュができていない");
                return;
            }

            final RecordingLoader loader = new RecordingLoader();
            final IAudioSource source = AudioCacheGate.open(loader, store, track, 0L, 0L, () -> true);
            if (source == null) {
                helper.fail("命中しているのに開けない");
                return;
            }
            source.close();
            if (loader.openStreamCalls != 0) {
                helper.fail("命中しているのにネットワークへ出た (cache-first が壊れている)");
            }
            // シークつきの命中も同じ (途中参加でもネットワークに出ない)
            final IAudioSource seeked = AudioCacheGate.open(loader, store, track, 1_000L, 0L, () -> true);
            if (seeked == null) {
                helper.fail("シークつきで命中しない");
                return;
            }
            seeked.close();
            if (loader.openStreamCalls != 0) {
                helper.fail("シークつき命中でネットワークへ出た");
            }
            helper.succeed();
        } catch (final IOException ex) {
            helper.fail("cache-first テストが IO で失敗: " + ex);
        }
    }

    /**
     * <b>頭からの再生だけ写し取る</b>。シーク・リピート折返し・chunk 再入・途中参加はすべて
     * 非ゼロ offset で来るので、これを書くと<b>頭が欠けたファイル</b>が恒久化する。
     * 判定を 1 行のガードに預けたままにせず、ここで固定する。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void audioCacheWritesOnlyFromTheStart(GameTestHelper helper) {
        final CustomTrackData track = track(URL, 200_000L);
        if (!AudioCacheGate.writable(track, 0L)) {
            helper.fail("頭からの再生が書き込み対象になっていない");
        }
        if (AudioCacheGate.writable(track, 1L) || AudioCacheGate.writable(track, 60_000L)) {
            helper.fail("途中からの再生を書き込み対象にしている (頭が欠けたファイルができる)");
        }
        // 尺が信用できないものは書かない (98% 判定が無効になる)
        if (AudioCacheGate.writable(track(URL, 1L), 0L)) {
            helper.fail("極端に短い申告尺を受け入れている (打ち切られた音声が完走扱いになる)");
        }
        if (AudioCacheGate.writable(track(URL, Long.MAX_VALUE), 0L)) {
            helper.fail("極端に長い申告尺を受け入れている (必要バイト数の計算が桁あふれする)");
        }
        helper.succeed();
    }

    /**
     * 完走しない書き込みが続いたら諦める。申告尺が実体より長いディスクやリピート再生で、
     * <b>再生のたびに 1 曲ぶん圧縮して書いては捨てる</b>のを止めるため。
     */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void audioCacheStopsRetryingAfterRepeatedFailures(GameTestHelper helper) {
        try {
            final Path dir = tempDir("attempts");
            final AudioCacheStore store = new AudioCacheStore(dir);
            final String key = AudioCacheStore.keyOf(URL);
            for (int i = 0; i < 3; i++) {
                if (!store.claim(key)) {
                    helper.fail((i + 1) + " 回目の claim が取れない (上限が早すぎる)");
                    return;
                }
                store.abandon(key);
            }
            if (store.claim(key)) {
                helper.fail("失敗が続いても書き込みを繰り返している");
            }
            helper.succeed();
        } catch (final IOException ex) {
            helper.fail("試行回数テストが IO で失敗: " + ex);
        }
    }

    /** 確定と同時に上限を効かせる (commit → prune がつながっていること)。 */
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, batch = CODEC_BATCH)
    public static void audioCachePrunesOnCommit(GameTestHelper helper) {
        try {
            final Path dir = tempDir("commit-prune");
            final AudioCacheStore store = new AudioCacheStore(dir);
            Files.createDirectories(dir);
            final Path old = write(dir, "old" + AudioCacheStore.EXTENSION, 40_960);
            touch(old, 1_000L);

            final String key = AudioCacheStore.keyOf(URL + "#prune");
            if (!store.claim(key)) {
                helper.fail("claim が取れない");
                return;
            }
            // 上限 32KB: 古い 40KB + 新しく焼いたぶんで超えるので、古い方が落ちる
            drainThroughTee(store, key, TRACK_MS, synthesizePcm(TRACK_MS), 32_768L);

            if (!Files.isRegularFile(store.fileFor(key))) {
                helper.fail("確定していない");
                return;
            }
            if (Files.exists(old)) {
                helper.fail("確定時に上限を効かせていない (prune が commit につながっていない)");
            }

            // 上限を 1 曲ぶんより小さくしても、焼いた端から自分を消してキャッシュが常に空、にはしない
            final String tiny = AudioCacheStore.keyOf(URL + "#tiny");
            if (!store.claim(tiny)) {
                helper.fail("claim が取れない");
                return;
            }
            drainThroughTee(store, tiny, TRACK_MS, synthesizePcm(TRACK_MS), 1024L);
            if (!Files.isRegularFile(store.fileFor(tiny))) {
                helper.fail("上限が小さいと、確定した当のファイルを消してしまう");
            }
            helper.succeed();
        } catch (final IOException ex) {
            helper.fail("commit→prune テストが IO で失敗: " + ex);
        }
    }

    private static CustomTrackData track(String url, long durationMs) {
        return new CustomTrackData(url, "fixture", "fixture", durationMs, "", false);
    }

    /** {@code openStream} が呼ばれた回数を数えるだけの偽 loader。 */
    private static final class RecordingLoader implements IMusicLoader {

        int openStreamCalls;

        @Override
        public TrackInfo resolve(String url) {
            return null;
        }

        @Override
        public IAudioSource openStream(String url, long startMs) {
            openStreamCalls++;
            return null;
        }

        @Override
        public IOpusDecoder openOpusDecoder(int sampleRate, int channels) {
            return decoder();
        }

        @Override
        public IOpusEncoder openOpusEncoder(int sampleRate, int channels, int frameSamples) {
            return encoder();
        }
    }

    // ── 補助 ────────────────────────────────────────────────────────────

    /** ネットワーク経路を模した合成ソースを tee 越しに最後まで引き、書き手が畳まれるまで待つ。 */
    private static void drainThroughTee(AudioCacheStore store, String key, long durationMs, byte[] pcm) {
        drainThroughTee(store, key, durationMs, pcm, 0L);
    }

    private static void drainThroughTee(AudioCacheStore store, String key, long durationMs, byte[] pcm,
            long maxBytes) {
        final AudioCacheWriter writer = AudioCacheWriter.open(
                store, key, URL, durationMs, maxBytes, encoder(), () -> true);
        if (writer == null) {
            throw new IllegalStateException("Opus encoder が使えない");
        }
        try (IAudioSource tee = new TeeAudioSource(new SyntheticSource(pcm), writer)) {
            final byte[] buf = new byte[8192];
            while (tee.read(buf, 0, buf.length) >= 0) {
                // 引き切る (-1 まで読むことで tee が終端を観測する)
            }
        }
        awaitSettled(store, key);
    }

    /** 書き込みスレッドが確定/破棄を終える (= 権を返す) まで待つ。 */
    private static void awaitSettled(AudioCacheStore store, String key) {
        for (int i = 0; i < 200; i++) {
            if (Files.isRegularFile(store.fileFor(key))) {
                return; // 確定した (rename は atomic なので、見えた時点で完成品)
            }
            if (!Files.exists(store.partFor(key)) && store.claim(key)) {
                store.release(key); // 破棄されて権も返った
                return;
            }
            try {
                Thread.sleep(25L);
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static byte[] drain(IAudioSource source) {
        final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        int n;
        while ((n = source.read(buf, 0, buf.length)) >= 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    /** 440Hz + 110Hz の合成 mono S16LE。 */
    private static byte[] synthesizePcm(long durationMs) {
        final int samples = (int) (durationMs * AudioCacheFormat.SAMPLE_RATE / 1000L);
        final byte[] pcm = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            final double t = (double) i / AudioCacheFormat.SAMPLE_RATE;
            final double v = 0.5 * Math.sin(2 * Math.PI * 440 * t) + 0.3 * Math.sin(2 * Math.PI * 110 * t);
            final short s = (short) (v * 20000);
            pcm[i * 2] = (byte) (s & 0xFF);
            pcm[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        return pcm;
    }

    private static double rms(byte[] pcm, int off, int len) {
        final int samples = len / 2;
        if (samples <= 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 0; i < samples; i++) {
            final int p = off + i * 2;
            final short s = (short) ((pcm[p] & 0xFF) | (pcm[p + 1] << 8));
            sum += (double) s * s;
        }
        return Math.sqrt(sum / samples);
    }

    private static IOpusEncoder encoder() {
        return LoaderHolder.get().openOpusEncoder(
                AudioCacheFormat.SAMPLE_RATE, AudioCacheFormat.CHANNELS, AudioCacheFormat.FRAME_SAMPLES);
    }

    private static IOpusDecoder decoder() {
        return LoaderHolder.get().openOpusDecoder(
                AudioCacheFormat.SAMPLE_RATE, AudioCacheFormat.CHANNELS);
    }

    private static Path tempDir(String name) throws IOException {
        final Path dir = Files.createTempDirectory("mdm-audio-cache-" + name);
        dir.toFile().deleteOnExit();
        return dir;
    }

    private static Path write(Path dir, String name, int bytes) throws IOException {
        Files.createDirectories(dir);
        final Path file = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file)) {
            out.write(new byte[bytes]);
        }
        return file;
    }

    private static void touch(Path file, long millis) throws IOException {
        Files.setLastModifiedTime(file, FileTime.fromMillis(millis));
    }

    /** メモリ上の PCM を返すだけのソース (ネットワーク経路の代役)。 */
    private static final class SyntheticSource implements IAudioSource {

        private final byte[] pcm;
        private int pos;

        SyntheticSource(byte[] pcm) {
            this.pcm = pcm;
        }

        @Override
        public int sampleRate() {
            return AudioCacheFormat.SAMPLE_RATE;
        }

        @Override
        public int channels() {
            return AudioCacheFormat.CHANNELS;
        }

        @Override
        public int bitsPerSample() {
            return AudioCacheFormat.BITS_PER_SAMPLE;
        }

        @Override
        public boolean bigEndian() {
            return false;
        }

        @Override
        public int read(byte[] dst, int off, int len) {
            if (pos >= pcm.length) {
                return -1;
            }
            final int n = Math.min(len, pcm.length - pos);
            System.arraycopy(pcm, pos, dst, off, n);
            pos += n;
            return n;
        }

        @Override
        public void close() {
            pos = pcm.length;
        }
    }
}
