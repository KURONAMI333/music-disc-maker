package com.kuronami.musicdiscmaker.client.audio;

import com.kuronami.musicdiscmaker.audio.LoaderHolder;
import com.kuronami.musicdiscmaker.audio.local.LocalAudioStore;
import com.kuronami.musicdiscmaker.audio.local.LocalAudioServer;
import com.kuronami.musicdiscmaker.menu.MusicDiscMakerMenu;
import com.kuronami.musicdiscmaker.network.LocalAudioPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/**
 * 選択したMP3だけを読み、元のパスを送らず取り込む。ネットワークはACKで1chunkずつ進む。
 * ダウンロードは2本まで共有し、内容を検査したキャッシュのPathだけをdecoderへ渡す。
 */
public final class LocalAudioClient {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mdm-local-audio-client"); t.setDaemon(true); return t;
    });
    private static final Map<String, Receive> RECEIVING = new ConcurrentHashMap<>();
    private static volatile Upload upload;
    private static volatile long connectionGeneration;
    private static long operation;
    private static LocalAudioStore cache;
    private static volatile String status = "select";
    private static volatile int percent;
    private LocalAudioClient() {}
    public static Component status() {
        Upload u = upload;
        return Component.translatable("gui.music_disc_maker.mp3." + status,
                "confirm".equals(status) && u != null ? u.name : percent);
    }
    public static boolean uploading() { return upload != null; }
    /** OS選択は画面スレッド、ファイル検査はワーカー。選択後の共有曲名を確認してから開始する。 */
    public static void select(MusicDiscMakerMenu menu) {
        if (upload != null) return;
        String selected;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var filters = stack.mallocPointer(1); filters.put(stack.UTF8("*.mp3")).flip();
            selected = TinyFileDialogs.tinyfd_openFileDialog(Component.translatable("gui.music_disc_maker.mp3.picker_title").getString(), "", filters, "MP3", false);
        }
        if (selected == null) return;
        long generation = ++operation;
        Upload u = new Upload(menu, generation, connectionGeneration);
        upload = u; status = "checking";
        IO.execute(() -> {
            Path temporary = null;
            try {
                Path input = Path.of(selected);
                if (!Files.isRegularFile(input) || Files.size(input) <= 0 || Files.size(input) > LocalAudioServer.FILE_LIMIT)
                    throw new IOException("Invalid file size");
                Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("music_disc_maker/imports");
                Files.createDirectories(directory);
                temporary = Files.createTempFile(directory, "selected-", ".mp3");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                long size = 0;
                try (InputStream in = Files.newInputStream(input); OutputStream out = Files.newOutputStream(temporary)) {
                    byte[] buffer = new byte[16384];
                    for (int n; (n = in.read(buffer)) != -1;) {
                        size += n;
                        if (size > LocalAudioServer.FILE_LIMIT) throw new IOException("File too large");
                        digest.update(buffer, 0, n); out.write(buffer, 0, n);
                    }
                }
                var info = LoaderHolder.get().resolveTrustedFile(temporary);
                if (info == null) throw new IOException("Invalid MP3");
                String name = input.getFileName().toString().replaceAll("(?i)\\.mp3$", "");
                u.hash = HexFormat.of().formatHex(digest.digest()); u.size = size;
                String title = info.title();
                u.name = (title == null || title.isBlank() || title.equals(temporary.getFileName().toString())) ? name : title;
                u.name = u.name.substring(0, Math.min(256, u.name.length())).replaceAll("[\\p{Cntrl}]", "");
                u.path = temporary; temporary = null;
                Minecraft.getInstance().execute(() -> {
                    if (!live(u)) { cleanup(u); return; }
                    status = "confirm";
                });
            } catch (Exception e) { if (live(u)) { status = "import_failed"; upload = null; } cleanup(u); }
            finally { if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) {} }
        });
    }
    public static Component description() {
        Upload u = upload;
        return u != null && "confirm".equals(status)
                ? Component.translatable("gui.music_disc_maker.mp3.share", u.name)
                : Component.translatable("gui.music_disc_maker.mp3.notice");
    }
    public static void confirm() {
        Upload u = upload;
        if (u == null || !"confirm".equals(status) || !live(u)) return;
        status = "transferring"; percent = 0;
        Services.NETWORK.sendToServer(LocalAudioPayload.control(LocalAudioPayload.BEGIN, LocalAudioPayload.NONE,
                u.menu.containerId, u.generation, u.hash, 0, u.size, u.name));
    }
    public static void cancel(MusicDiscMakerMenu menu) {
        Upload u = upload;
        if (u == null || u.menu != menu) return;
        upload = null; status = "select";
        Services.NETWORK.sendToServer(LocalAudioPayload.control(LocalAudioPayload.CANCEL, u.id,
                menu.containerId, u.generation, u.hash, 0, 0, ""));
        cleanup(u);
    }
    private static boolean live(Upload u) {
        return upload == u && u.connection == connectionGeneration;
    }
    public static void receive(LocalAudioPayload p) {
        if (p.operation() == LocalAudioPayload.DATA) { data(p); return; }
        Receive r = RECEIVING.get(p.hash());
        if (p.operation() == LocalAudioPayload.ERROR && r != null && (r.request.equals(p.id()) || p.id().equals(r.id))) {
            RECEIVING.remove(p.hash(), r); IO.execute(() -> abort(r));
            r.future.completeExceptionally(new IOException(p.name())); return;
        }
        Upload u = upload;
        if (u == null || p.containerId() != u.menu.containerId || p.generation() != u.generation) return;
        if (p.operation() == LocalAudioPayload.READY || p.operation() == LocalAudioPayload.ACK) {
            if (u.sending || p.offset() != u.sent) return;
            u.id = p.id(); u.sending = true;
            IO.execute(() -> {
                try {
                    if (!live(u)) return;
                    if (u.input == null) u.input = Files.newInputStream(u.path);
                    byte[] bytes = u.input.readNBytes((int) Math.min(16384, u.size - u.sent));
                    if (bytes.length == 0) throw new IOException("Incomplete MP3");
                    long offset = u.sent; u.sent += bytes.length;
                    Minecraft.getInstance().execute(() -> {
                        u.sending = false;
                        if (!live(u)) return;
                        percent = (int) (100 * u.sent / u.size);
                        Services.NETWORK.sendToServer(new LocalAudioPayload(LocalAudioPayload.CHUNK, u.id,
                                u.menu.containerId, u.generation, "", offset, 0, "", bytes));
                    });
                } catch (IOException e) { Minecraft.getInstance().execute(() -> { cancel(u.menu); status = "import_failed"; }); }
            });
        } else if (p.operation() == LocalAudioPayload.DONE || p.operation() == LocalAudioPayload.ERROR) {
            upload = null; status = p.operation() == LocalAudioPayload.DONE ? "done" : p.name(); cleanup(u);
        }
    }
    /** 待機は再生ワーカーだけ。main/render threadから呼ばない。 */
    public static Path obtain(String hash) throws Exception {
        CompletableFuture<Path> result = new CompletableFuture<>();
        long connection = connectionGeneration;
        IO.execute(() -> {
            try {
                ensureCache();
                Path found = cache.verified(hash);
                if (found != null) { result.complete(found); return; }
                Receive existing = RECEIVING.get(hash);
                if (existing != null) { existing.future.whenComplete((v, e) -> forward(result, v, e)); return; }
                if (RECEIVING.size() >= 2) throw new IOException("Audio downloads busy");
                Receive r = new Receive(hash, connection);
                RECEIVING.put(hash, r);
                r.future.whenComplete((v, e) -> forward(result, v, e));
                Minecraft.getInstance().execute(() -> {
                    if (connection != connectionGeneration) { r.future.completeExceptionally(new IOException("Disconnected")); return; }
                    Services.NETWORK.sendToServer(LocalAudioPayload.control(LocalAudioPayload.FETCH, r.request, 0, 0, hash, 0, 0, ""));
                });
            } catch (Exception e) { result.completeExceptionally(e); }
        });
        try { return result.get(300, TimeUnit.SECONDS); }
        catch (TimeoutException e) {
            Receive r = RECEIVING.remove(hash);
            if (r != null) { r.future.completeExceptionally(e); IO.execute(() -> abort(r)); }
            throw e;
        }
    }
    private static void forward(CompletableFuture<Path> result, Path value, Throwable e) {
        if (e == null) result.complete(value); else result.completeExceptionally(e);
    }
    private static void data(LocalAudioPayload p) {
        Receive r = RECEIVING.get(p.hash());
        if (r == null || r.connection != connectionGeneration || r.busy || p.generation() != r.request.getLeastSignificantBits()) return;
        r.busy = true;
        IO.execute(() -> {
            try {
                ensureCache();
                if (r.connection != connectionGeneration) throw new IOException("Disconnected");
                if (r.id == null) { r.id = p.id(); r.storeId = cache.begin(r.request, p.hash(), p.size(), now()); }
                if (!r.id.equals(p.id()) || p.offset() != r.offset) throw new IOException("Invalid audio chunk");
                cache.append(r.request, r.storeId, p.offset(), p.bytes(), now());
                r.offset += p.bytes().length;
                if (r.offset == p.size()) {
                    Path file = cache.finish(r.request, r.storeId, now(), path -> {
                        if (LoaderHolder.get().resolveTrustedFile(path) == null) throw new IOException("Invalid MP3");
                    });
                    r.storeId = null; RECEIVING.remove(p.hash(), r); r.future.complete(file);
                }
                Minecraft.getInstance().execute(() -> {
                    r.busy = false;
                    if (r.connection != connectionGeneration) return;
                    Services.NETWORK.sendToServer(LocalAudioPayload.control(LocalAudioPayload.NEXT, p.id(), 0, 0, "", r.offset, 0, ""));
                });
            } catch (Exception e) { RECEIVING.remove(p.hash(), r); abort(r); r.future.completeExceptionally(e); }
        });
    }
    private static void ensureCache() throws IOException {
        if (cache == null) cache = new LocalAudioStore(Minecraft.getInstance().gameDirectory.toPath()
                .resolve("music_disc_maker/audio-cache"), new LocalAudioStore.Limits(LocalAudioServer.FILE_LIMIT,
                2L * 1024 * 1024 * 1024, 2, 90_000));
    }
    private static void abort(Receive r) {
        try { if (cache != null && r.storeId != null) cache.cancel(r.request, r.storeId); } catch (IOException ignored) {}
    }
    private static void cleanup(Upload u) {
        IO.execute(() -> {
            try { if (u.input != null) u.input.close(); if (u.path != null) Files.deleteIfExists(u.path); }
            catch (IOException ignored) {}
        });
    }
    public static void disconnect() {
        connectionGeneration++;
        Upload u = upload; upload = null; if (u != null) cleanup(u);
        for (Receive r : RECEIVING.values()) { r.future.completeExceptionally(new IOException("Disconnected")); IO.execute(() -> abort(r)); }
        RECEIVING.clear(); status = "select";
    }
    private static long now() { return System.nanoTime() / 1_000_000; }
    private static final class Upload {
        final MusicDiscMakerMenu menu; final long generation, connection;
        volatile String hash = "", name = ""; volatile Path path;
        long size, sent; UUID id = LocalAudioPayload.NONE; InputStream input; boolean sending;
        Upload(MusicDiscMakerMenu menu, long generation, long connection) { this.menu = menu; this.generation = generation; this.connection = connection; }
    }
    private static final class Receive {
        final String hash; final long connection; final UUID request = UUID.randomUUID();
        final CompletableFuture<Path> future = new CompletableFuture<>();
        UUID id, storeId; long offset; volatile boolean busy;
        Receive(String hash, long connection) { this.hash = hash; this.connection = connection; }
    }
}
