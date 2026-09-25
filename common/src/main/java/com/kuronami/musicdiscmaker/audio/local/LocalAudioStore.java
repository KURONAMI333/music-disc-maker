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
 * <p>ワールドごとに一つだけ所有し、全メソッドをIOワーカーから呼ぶ。Minecraftの
 * menu/再生権限検査は呼び出し側の責務。この層は送信者、順序、容量、内容を検証する。
 * ファイル名はSHA-256だけから作り、クライアントのパスを一切受け取らない。
 * 完了原本は未ロードのチェストからも参照されうるため、自動削除しない。
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
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final Map<UUID, Upload> uploads = new HashMap<>();
    private long storedBytes;
    private long reservedBytes;
    private boolean closed;

    public LocalAudioStore(Path root, Limits limits) throws IOException {
        this.limits = limits;
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
                    if (entry.getFileName().toString().matches("[a-f0-9]{64}\\.mp3")) {
                        if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                            throw new IOException("Invalid audio store entry");
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
        if (size > limits.totalBytes() - storedBytes - reservedBytes) throw new IOException("Audio storage full");
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

    /** ハッシュ・MP3検査後にだけ完成原本として公開する。 */
    public synchronized Path finish(UUID owner, UUID id, long nowMillis, Validator validator) throws IOException {
        Upload upload = require(owner, id);
        try {
            upload.out.close();
            if (upload.failed || nowMillis - upload.touched >= limits.idleMillis() || upload.received != upload.size
                    || !HexFormat.of().formatHex(upload.digest.digest()).equals(upload.hash)) {
                throw new IOException("Incomplete or corrupt audio upload");
            }
            validator.validate(upload.path);
            Path destination = root.resolve(upload.hash + ".mp3");
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                // 既存ファイルの破損を成功扱いしない。欠損修復は別の明示操作にする。
                if (verified(upload.hash) == null) throw new IOException("Corrupt stored audio");
            } else {
                // 同一ファイルシステム内。ATOMIC_MOVE不可なら部分原本を作らず失敗させる。
                Files.move(upload.path, destination, StandardCopyOption.ATOMIC_MOVE);
                storedBytes += upload.size;
            }
            return destination;
        } finally {
            discard(id, upload);
        }
    }

    /** ハッシュまで検査した原本。欠損はnull、破損・リンクは例外。IOワーカー専用。 */
    public synchronized Path verified(String hash) throws IOException {
        ensureOpen();
        checkHash(hash);
        Path path = root.resolve(hash + ".mp3");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limits.fileBytes()) {
            throw new IOException("Invalid stored audio");
        }
        MessageDigest digest = sha256();
        try (var in = Files.newInputStream(path)) {
            byte[] bytes = new byte[CHUNK_BYTES];
            for (int n; (n = in.read(bytes)) >= 0;) digest.update(bytes, 0, n);
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(hash)) throw new IOException("Corrupt stored audio");
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
