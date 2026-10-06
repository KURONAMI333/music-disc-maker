package com.kuronami.musicdiscmaker.audio.local;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * 明示的に持ち込んだ音源の分割受信と永続化。音声本体はアイテムに入れない。
 *
 * <p>既定のインスタンスはワールド原本を一つだけ所有し、全メソッドをIOワーカーから呼ぶ。Minecraftの
 * menu/再生権限検査は呼び出し側の責務。この層は送信者、順序、容量、内容を検証する。
 * ファイル名はSHA-256だけから作り、クライアントのパスを一切受け取らない。
 * 完了原本は未ロードのチェストからも参照されうるため、自動削除しない。clientCache factory
 * だけが再取得可能なクライアント複製を破損修復・容量整理の対象にする。
 */
public final class LocalAudioStore implements AutoCloseable {
    public static final int CHUNK_BYTES = 16 * 1024;

    /** 数値は呼び出し側で選ぶ。試験値を製品の既定値と混同しない。 */
    public record Limits(long fileBytes, long totalBytes, int transfers, long idleMillis) {
        public Limits {
            if (fileBytes < 1 || totalBytes < fileBytes || transfers < 1 || idleMillis < 1) {
                throw new IllegalArgumentException("Invalid audio storage limits");
            }
        }
    }

    /** 検査が失敗した場合は例外を投げる。成功するまで公開原本にはならない。 */
    @FunctionalInterface
    public interface Validator { void validate(Path file) throws IOException; }

    /** 既存原本なら転送不要。transferIdとexistingの片方だけが非nullになる。 */
    public record Preparation(UUID transferId, Path existing) {}

    private final Path root;
    private final Path incoming;
    private final Limits limits;
    /** True only for disposable client copies; world-owned originals use the preserving default. */
    private final boolean recoverableCache;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Map<UUID, Upload> uploads = new HashMap<>();
    private long storedBytes;
    private long reservedBytes;
    private boolean closed;

    public LocalAudioStore(Path root, Limits limits) throws IOException {
        this(root, limits, false);
    }

    /** A client-only store that can discard corrupt entries and evict old entries at its quota. */
    public static LocalAudioStore clientCache(Path root, Limits limits) throws IOException {
        return new LocalAudioStore(root, limits, true);
    }

    private LocalAudioStore(Path root, Limits limits, boolean recoverableCache) throws IOException {
        this.limits = limits;
        this.recoverableCache = recoverableCache;
        Files.createDirectories(root);
        this.root = root.toRealPath();
        incoming = this.root.resolve("incoming");
        Files.createDirectories(incoming);
        if (Files.isSymbolicLink(incoming)) throw new IOException("Invalid incoming directory");
        lockChannel = FileChannel.open(this.root.resolve(".lock"), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        FileLock acquired;
        try {
            acquired = lockChannel.tryLock();
            if (acquired == null) throw new IOException("Audio store already open");
        } catch (IOException | OverlappingFileLockException e) {
            lockChannel.close();
            throw new IOException("Audio store already open", e);
        }
        lock = acquired;
        try {
            // 排他所有を得てから、前回の異常終了で残った未確定データだけ片付ける。
            try (var parts = Files.list(incoming)) {
                for (Path part : parts.toList()) {
                    if (part.getFileName().toString().matches("upload-.*\\.part")) Files.delete(part);
                }
            }
            try (var entries = Files.list(this.root)) {
                for (Path entry : entries.toList()) {
                    // hash名(新形式)と hash.mp3(MP3専用期の旧形式)を同一の原本として数える。
                    if (entry.getFileName().toString().matches("[a-f0-9]{64}(\\.mp3)?")) {
                        if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                                || (recoverableCache && Files.size(entry) > limits.fileBytes())) {
                            if (!recoverableCache) throw new IOException("Invalid audio store entry");
                            Files.delete(entry);
                            continue;
                        }
                        storedBytes = Math.addExact(storedBytes, Files.size(entry));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            lock.release();
            lockChannel.close();
            throw e;
        }
    }

    /** 転送開始の入口。既存曲の再利用なら満杯でも追加容量を要求しない。 */
    public synchronized Preparation prepare(UUID owner, String hash, long size, long nowMillis) throws IOException {
        ensureOpen();
        if (owner == null || size <= 0 || size > limits.fileBytes()) throw new IOException("File too large or empty");
        Path existing = verified(hash);
        if (existing != null) {
            if (Files.size(existing) != size) throw new IOException("Stored audio size mismatch");
            return new Preparation(null, existing);
        }
        return new Preparation(begin(owner, hash, size, nowMillis), null);
    }

    /** 1送信者1転送。nowMillisは経過時間用の単調時計を渡す。 */
    public synchronized UUID begin(UUID owner, String hash, long size, long nowMillis) throws IOException {
        ensureOpen();
        checkHash(hash);
        if (owner == null || size <= 0 || size > limits.fileBytes()) throw new IOException("File too large or empty");
        expire(nowMillis);
        if (uploads.size() >= limits.transfers()
                || uploads.values().stream().anyMatch(u -> u.owner.equals(owner))) {
            throw new IOException("Transfer limit reached");
        }
        // 受信中の一時ファイルも予約する。重複音源でも検査前に容量を無制限に使わせない。
        while (size > limits.totalBytes() - storedBytes - reservedBytes) {
            if (!recoverableCache || !evictOldestCacheEntry()) throw new IOException("Audio storage full");
        }
        UUID id = UUID.randomUUID();
        Path path = Files.createTempFile(incoming, "upload-", ".part");
        try {
            OutputStream out = Files.newOutputStream(path, StandardOpenOption.WRITE);
            uploads.put(id, new Upload(owner, hash, size, path, out, nowMillis));
            reservedBytes += size;
            return id;
        } catch (IOException e) {
            Files.deleteIfExists(path);
            throw e;
        }
    }

    /** offsetが一致する小分けデータだけ受け取る。不正転送は取り消して予約も解放する。 */
    public synchronized void append(UUID owner, UUID id, long offset, byte[] bytes, long nowMillis) throws IOException {
        Upload upload = require(owner, id);
        if (upload.failed || nowMillis - upload.touched >= limits.idleMillis()
                || bytes == null || bytes.length == 0 || bytes.length > CHUNK_BYTES
                || offset != upload.received || bytes.length > upload.size - upload.received) {
            discard(id, upload);
            throw new IOException("Invalid or expired audio chunk");
        }
        try {
            upload.out.write(bytes);
            upload.digest.update(bytes);
            upload.received += bytes.length;
            upload.touched = nowMillis;
        } catch (IOException e) {
            discard(id, upload);
            throw e;
        }
    }

    /** ハッシュ・音声検査後にだけ完成原本として公開する。 */
    public synchronized Path finish(UUID owner, UUID id, long nowMillis, Validator validator) throws IOException {
        Upload upload = require(owner, id);
        try {
            upload.out.close();
            if (upload.failed || nowMillis - upload.touched >= limits.idleMillis() || upload.received != upload.size
                    || !HexFormat.of().formatHex(upload.digest.digest()).equals(upload.hash)) {
                throw new IOException("Incomplete or corrupt audio upload");
            }
            validator.validate(upload.path);
            Path destination = root.resolve(upload.hash);
            Path existing = existingStoredPath(upload.hash);
            if (existing != null) {
                // 既存ファイルの破損を成功扱いしない。
                Path ok = verified(upload.hash);
                if (ok != null) return ok;
                // Cache mode may have removed a corrupt entry in verified(); its replacement
                // upload is now the repair, while authoritative stores still throw above.
                if (existingStoredPath(upload.hash) != null) throw new IOException("Corrupt stored audio");
            }
            // 同一ファイルシステム内。ATOMIC_MOVE不可なら部分原本を作らず失敗させる。
            Files.move(upload.path, destination, StandardCopyOption.ATOMIC_MOVE);
            storedBytes += upload.size;
            return destination;
        } finally {
            discard(id, upload);
        }
    }

    /** ハッシュまで検査した原本。欠損はnull、world storeの破損・リンクは例外。IOワーカー専用。 */
    public synchronized Path verified(String hash) throws IOException {
        ensureOpen();
        checkHash(hash);
        Path path = existingStoredPath(hash);
        if (path == null) return null;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limits.fileBytes()) {
            return discardCorruptCacheEntry(path, "Invalid stored audio");
        }
        MessageDigest digest = sha256();
        try (var in = Files.newInputStream(path)) {
            byte[] bytes = new byte[CHUNK_BYTES];
            for (int n; (n = in.read(bytes)) >= 0;) digest.update(bytes, 0, n);
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(hash)) {
            return discardCorruptCacheEntry(path, "Corrupt stored audio");
        }
        if (recoverableCache) {
            // Use filesystem age as a small LRU signal. Failure to update it must not invalidate a hit.
            try { Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis())); }
            catch (IOException ignored) { }
        }
        return path;
    }

    public synchronized void cancel(UUID owner, UUID id) throws IOException {
        discard(id, require(owner, id));
    }

    /** 呼び出し側が定期的にも呼ぶ。新しい転送が無くても一時容量を解放する。 */
    public synchronized void expire(long nowMillis) throws IOException {
        ensureOpen();
        for (var entry : Map.copyOf(uploads).entrySet()) {
            if (entry.getValue().failed || nowMillis - entry.getValue().touched >= limits.idleMillis()) {
                discard(entry.getKey(), entry.getValue());
            }
        }
    }

    public synchronized long storedBytes() { return storedBytes; }
    public synchronized long reservedBytes() { return reservedBytes; }

    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        IOException failure = null;
        for (var entry : Map.copyOf(uploads).entrySet()) {
            try { discard(entry.getKey(), entry.getValue()); }
            catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        }
        closed = true;
        try { lock.release(); } catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        try { lockChannel.close(); } catch (IOException e) { if (failure == null) failure = e; else failure.addSuppressed(e); }
        if (failure != null) throw failure;
    }

    private Upload require(UUID owner, UUID id) throws IOException {
        ensureOpen();
        Upload upload = uploads.get(id);
        if (upload == null || !upload.owner.equals(owner)) throw new IOException("Unknown audio transfer");
        return upload;
    }

    private void discard(UUID id, Upload upload) throws IOException {
        upload.failed = true;
        upload.out.close();
        Files.deleteIfExists(upload.path);
        // 削除失敗時は予約と追跡を残し、expireで再試行する。容量を空いたことにしない。
        if (uploads.remove(id) != null) reservedBytes -= upload.size;
    }

    private void ensureOpen() throws IOException { if (closed) throw new IOException("Audio store closed"); }

    /**
     * hash名(拡張子なし)を優先し、無ければMP3専用期の {@code hash.mp3} を旧名として認める。
     * 旧名で既に保存済みの曲も参照・再利用できるようにするための互換経路。
     */
    private Path existingStoredPath(String hash) {
        Path modern = root.resolve(hash);
        if (Files.exists(modern, LinkOption.NOFOLLOW_LINKS)) return modern;
        Path legacy = root.resolve(hash + ".mp3");
        return Files.exists(legacy, LinkOption.NOFOLLOW_LINKS) ? legacy : null;
    }

    /** Delete only an entry inside a recoverable client cache. Authoritative stores retain failures. */
    private Path discardCorruptCacheEntry(Path path, String message) throws IOException {
        if (!recoverableCache) throw new IOException(message);
        long size = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ? Files.size(path) : 0;
        Files.deleteIfExists(path);
        storedBytes -= size;
        return null;
    }

    /** Free quota only in a client cache; complete world originals are never auto-evicted. */
    private boolean evictOldestCacheEntry() throws IOException {
        record Candidate(Path path, long modified) {}
        var candidates = new java.util.ArrayList<Candidate>();
        try (var entries = Files.list(root)) {
            for (Path entry : entries.toList()) {
                if (!entry.getFileName().toString().matches("[a-f0-9]{64}(\\.mp3)?")
                        || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) continue;
                candidates.add(new Candidate(entry, Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS).toMillis()));
            }
        }
        candidates.sort(java.util.Comparator.comparingLong(Candidate::modified));
        for (Candidate candidate : candidates) {
            long size = Files.size(candidate.path());
            Files.delete(candidate.path());
            storedBytes -= size;
            return true;
        }
        return false;
    }

    private static void checkHash(String hash) throws IOException {
        if (hash == null || !hash.matches("[a-f0-9]{64}")) throw new IOException("Invalid audio id");
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    private static final class Upload {
        final UUID owner;
        final String hash;
        final long size;
        final Path path;
        final OutputStream out;
        final MessageDigest digest = sha256();
        long received;
        long touched;
        boolean failed;

        Upload(UUID owner, String hash, long size, Path path, OutputStream out, long now) {
            this.owner = owner; this.hash = hash; this.size = size; this.path = path; this.out = out; touched = now;
        }
    }
}
