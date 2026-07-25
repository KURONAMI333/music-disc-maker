package com.kuronami.musicdiscmaker.audio.cache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.MusicDiscMaker;

/**
 * キャッシュファイルの置き場と後始末。<b>MC の API を一切参照しない</b>ので headless テストから
 * 一時ディレクトリを渡してそのまま動かせる (client 実挙動は起動しないと見えないが、命名・LRU・
 * 排他・commit の状態機械はここで機械的に固定できる)。
 *
 * <h2>置き場</h2>
 * {@code <game dir>/music_disc_maker/cache/<sha1(url)>.audio}
 *
 * <p>ビートマップ ({@code .beat}) と<b>同じディレクトリ・同じ sha1 命名</b>にしてある。
 * シングルプレイでは統合サーバのディレクトリ = ゲームディレクトリなので、同じ曲の
 * {@code .beat} と {@code .audio} が自然に隣り合う。マルチプレイでは {@code .beat} は
 * サーバ側・{@code .audio} は各 client 側に散る (解析は server 権威・音声キャッシュは
 * client ごとの再生履歴なので、この非対称は仕様)。
 *
 * <p><b>掃除は自分の拡張子だけを見る。</b> 上限超過の削除で {@code .beat} を巻き込むと、
 * ビート連動が黙って再解析地獄に落ちる。逆に {@code BeatMaps#prune} も {@code .beat} しか見ない。
 */
public final class AudioCacheStore {

    public static final String EXTENSION = ".audio";
    /** 書き込み途中のファイル。完走を確認できた時だけ {@link #EXTENSION} へ rename する。 */
    public static final String PART_EXTENSION = ".audio.part";

    /** 1 つの曲を写し取るのに許す回数 (1 セッション内)。 */
    private static final int MAX_ATTEMPTS = 3;

    private final Path dir;
    /** いま書き込み中の key。同じ曲を 2 箇所で同時再生しても書き手は 1 つに絞る。 */
    private final Set<String> writing = ConcurrentHashMap.newKeySet();
    /**
     * key ごとの「完走しなかった書き込み」の回数。上限に達したら以後このセッションでは書かない。
     *
     * <p>これが無いと静かなコスト源になる: ディスクの申告尺が実体より長い場合
     * (再アップロード後の古いディスク・細工された component) や、リピート再生で曲尾より先に
     * ストリームが切られる場合、<b>再生のたびに 1 曲ぶんを圧縮して書いては捨てる</b>ことになる。
     * {@code BeatMaps} が同じ理由で同じ形の歯止めを持っている。
     */
    private final Map<String, Integer> failedAttempts = new ConcurrentHashMap<>();
    private volatile boolean sweptStaleParts;

    public AudioCacheStore(Path dir) {
        this.dir = dir;
    }

    public Path directory() {
        return dir;
    }

    /**
     * URL → ファイル名。{@code BeatMaps} と同じ規約 (トリムした URL 文字列の SHA-1 hex)。
     * ディスクが持つ URL は解決済みの正規 URI ({@code TrackInfo.uri}) なので、同じ曲は必ず同じキーになる。
     * ハッシュを名前にすることで、攻撃者が仕込んだ URL 由来のパストラバーサルも塞がる。
     */
    public static String keyOf(String url) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-1");
            final byte[] hash = digest.digest(url.trim().getBytes(StandardCharsets.UTF_8));
            final StringBuilder sb = new StringBuilder(hash.length * 2);
            for (final byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (final NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-1 が使えない", ex);
        }
    }

    public Path fileFor(String key) {
        return dir.resolve(key + EXTENSION);
    }

    public Path partFor(String key) {
        return dir.resolve(key + PART_EXTENSION);
    }

    /**
     * キャッシュ命中ならファイルを返し、更新時刻を今にする (= LRU の touch)。
     *
     * <p>touch を省くと更新時刻 = 作成時刻のままなので LRU が実質 FIFO になり、
     * <b>いちばんよく聴く曲から消える</b>。{@code JacketCache} はこれを踏んでいる。
     */
    @Nullable
    public Path hit(String key) {
        final Path file = fileFor(key);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (final IOException ignored) {
            // touch できなくても再生はできる (LRU の並びが古いままになるだけ)
        }
        return file;
    }

    /**
     * この key の書き込み権を取る。既にキャッシュがある / 他の再生が書いている時は {@code false}。
     * 取れたら必ず {@link #commit} / {@link #release} / {@link #abandon} のどれかで返すこと。
     */
    public boolean claim(String key) {
        if (Files.isRegularFile(fileFor(key))) {
            return false;
        }
        if (failedAttempts.getOrDefault(key, 0) >= MAX_ATTEMPTS) {
            return false;
        }
        if (!writing.add(key)) {
            return false;
        }
        try {
            Files.createDirectories(dir);
            sweepStalePartsOnce();
        } catch (final IOException ex) {
            writing.remove(key);
            return false;
        }
        return true;
    }

    /** 書き込み途中のファイルを捨てて権を返す (失敗として数えない)。 */
    public void release(String key) {
        deleteQuietly(partFor(key));
        writing.remove(key);
    }

    /**
     * 完走しなかった書き込みを捨てて権を返し、失敗として数える。
     * {@link #MAX_ATTEMPTS} 回で以後このセッションはこの曲を書かない。
     */
    public void abandon(String key) {
        failedAttempts.merge(key, 1, Integer::sum);
        release(key);
    }

    /**
     * 書き込み途中のファイルを確定させて権を返す。
     *
     * <p>まず {@code ATOMIC_MOVE} を試す — 「途中まで書けたファイルがキャッシュとして読まれる」窓を
     * 作らないため。対応していないファイルシステムでは通常の置換に落ちる (その場合の窓は、
     * 直前に flush/close 済みのファイルを名前だけ差し替える一瞬なので実務上は無視できる)。
     *
     * @return 確定できたら {@code true}
     */
    public boolean commit(String key, long maxBytes) {
        final Path part = partFor(key);
        final Path file = fileFor(key);
        try {
            try {
                Files.move(part, file, StandardCopyOption.ATOMIC_MOVE);
            } catch (final AtomicMoveNotSupportedException ex) {
                Files.move(part, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (final IOException ex) {
            // 失敗として数えない。ここまで来た録り込みは完走している = 失敗の原因はディスク側
            // (一時的なロック・ウイルス対策・容量不足) で、回数で打ち切る対象ではない。
            MusicDiscMaker.LOGGER.warn("音源キャッシュの確定に失敗 ({}): {}", file, ex.toString());
            release(key);
            return false;
        }
        failedAttempts.remove(key);
        writing.remove(key);
        prune(maxBytes, key);
        return true;
    }

    /**
     * 上限を超えていたら古い順に消す。<b>{@code .audio} だけを見る</b>
     * (同じディレクトリにビートマップ {@code .beat} が同居しうる)。
     *
     * @param maxBytes 0 以下なら無制限 (何もしない)
     */
    public void prune(long maxBytes) {
        prune(maxBytes, null);
    }

    /**
     * @param protectedKey 消さずに残す key。確定直後の呼び出しで自分自身を指す
     *                     — 上限を 1 曲ぶんより小さくした時に「焼いた端から自分を消す」
     *                     (= キャッシュが常に空) にならないようにする。
     *                     上限を 1 曲ぶんだけ超えるが、空よりは望ましい。
     */
    private void prune(long maxBytes, @Nullable String protectedKey) {
        if (maxBytes <= 0L) {
            return;
        }
        final String keep = protectedKey == null ? null : protectedKey + EXTENSION;
        final List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(EXTENSION)).forEach(files::add);
        } catch (final IOException ex) {
            return;
        }
        long total = 0L;
        for (final Path p : files) {
            total += sizeOf(p);
        }
        if (total <= maxBytes) {
            return;
        }
        files.sort(Comparator.comparingLong(AudioCacheStore::modifiedAt));
        for (final Path p : files) {
            if (total <= maxBytes) {
                break;
            }
            if (keep != null && p.getFileName().toString().equals(keep)) {
                continue;
            }
            final long size = sizeOf(p);
            if (deleteQuietly(p)) {
                total -= size;
                MusicDiscMaker.LOGGER.debug("音源キャッシュを上限超過で削除: {}", p.getFileName());
            }
        }
    }

    /**
     * クラッシュ・強制終了で取り残された {@code .part} を 1 セッションに 1 回だけ掃除する。
     * 現在書き込み中のものは触らない。
     */
    public void sweepStalePartsOnce() {
        if (sweptStaleParts) {
            return;
        }
        sweptStaleParts = true;
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(PART_EXTENSION))
                    .filter(p -> !writing.contains(keyOfPart(p)))
                    .forEach(AudioCacheStore::deleteQuietly);
        } catch (final IOException ignored) {
            // 掃除できなくても実害は無い (次の書き込みで上書きされる)
        }
    }

    private static String keyOfPart(Path part) {
        final String name = part.getFileName().toString();
        return name.substring(0, name.length() - PART_EXTENSION.length());
    }

    private static boolean deleteQuietly(Path p) {
        try {
            return Files.deleteIfExists(p);
        } catch (final IOException ex) {
            return false; // 読み出し中でロックされている等。次回消せる
        }
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (final IOException ex) {
            return 0L;
        }
    }

    private static long modifiedAt(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (final IOException ex) {
            return 0L;
        }
    }
}
