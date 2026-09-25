package com.kuronami.musicdiscmaker.audio.local;

import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * プレイヤーごとの持込所有履歴。原本と同じワールド内に原子的に保存し、退出でリセットしない。
 * 同じ所有者の再利用は無料、他の所有者には同じ原本でも全量を計上する。IOワーカー専用。
 */
public final class LocalAudioQuota {
    private final Path root;
    private final long maximum;
    public LocalAudioQuota(Path root, long maximum) throws IOException {
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) throw new IOException("Invalid quota directory");
        this.root = root.toRealPath();
        this.maximum = maximum;
    }
    public void check(UUID player, String hash, long size) throws IOException {
        checkHash(hash);
        Map<String, Long> entries = read(player);
        if (entries.containsKey(hash)) {
            if (entries.get(hash) != size) throw new IOException("Audio size mismatch");
            return;
        }
        long used = 0;
        for (long value : entries.values()) used = Math.addExact(used, value);
        if (size <= 0 || size > maximum - used) throw new IOException("Player audio storage full");
    }
    /** 原本が検査・保存された後、ディスクを作る前に確定する。失敗したらディスクを消費しない。 */
    public void record(UUID player, String hash, long size) throws IOException {
        checkHash(hash);
        check(player, hash, size);
        Map<String, Long> entries = read(player);
        if (entries.putIfAbsent(hash, size) != null) return;
        StringBuilder data = new StringBuilder();
        entries.forEach((key, value) -> data.append(key).append(' ').append(value).append('\n'));
        Path temporary = Files.createTempFile(root, "quota-", ".part");
        try {
            Files.writeString(temporary, data, StandardCharsets.UTF_8);
            Files.move(temporary, root.resolve(player + ".txt"), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private Map<String, Long> read(UUID player) throws IOException {
        Path file = root.resolve(player + ".txt");
        Map<String, Long> result = new TreeMap<>();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return result;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 8 * 1024 * 1024)
            throw new IOException("Invalid quota ledger");
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] fields = line.split(" ");
            if (fields.length != 2) throw new IOException("Invalid quota ledger");
            checkHash(fields[0]);
            try {
                long size = Long.parseLong(fields[1]);
                if (size <= 0 || result.putIfAbsent(fields[0], size) != null) throw new IOException("Invalid quota ledger");
            } catch (NumberFormatException e) { throw new IOException("Invalid quota ledger", e); }
        }
        return result;
    }
    private static void checkHash(String hash) throws IOException {
        if (hash == null || !hash.matches("[a-f0-9]{64}")) throw new IOException("Invalid audio id");
    }
}
