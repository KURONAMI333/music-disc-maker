package com.kuronami.musicdiscmaker.client.audio;

import com.kuronami.musicdiscmaker.audio.LoaderHolder;
import com.kuronami.musicdiscmaker.audio.local.LocalAudioStore;
import com.kuronami.musicdiscmaker.audio.local.LocalAudioServer;
import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.ResolveException;
import com.kuronami.musicdiscmaker.lavaplayer.api.TrackInfo;
import com.kuronami.musicdiscmaker.menu.MusicDiscMakerMenu;
import com.kuronami.musicdiscmaker.network.LocalAudioPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
//? if >=26.3 {
/*import java.nio.ByteBuffer;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.system.MemoryUtil;
*///?} else {
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
//?}
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 選択した音声・動画ファイルの音声だけを取り込む。元のパスは送らず、内容を検査した
 * バイト列だけを送る。保存形式でない入力 (動画・ADTS・Ogg 等) は client 側で音声のみの
 * MKA へ normalize してから転送する — 映像バイトは転送・保存へ載らない。
 * ネットワークはACKで1chunkずつ進む。ダウンロードは2本まで共有し、内容を検査した
 * キャッシュのPathだけをdecoderへ渡す。
 */
public final class LocalAudioClient {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mdm-local-audio-client"); t.setDaemon(true); return t;
    });
    /** 持ち込み入力の上限。normalizer (LocalFileNormalizer.MAX_INPUT_BYTES) と同じ 2GiB で先に弾く。 */
    private static final long MAX_IMPORT_BYTES = 2L * 1024 * 1024 * 1024;
    // upload スロットと接続世代は main thread だけが書き換える。ガード規則を headless 試験が
    // 直接駆動できるよう、MC 非依存の Session へ分離する。
    private static final Session SESSION = new Session();
    private static long operation;
    private static LocalAudioStore cache;
    private static volatile String status = "select";
    private static volatile int percent;
    private LocalAudioClient() {}
    public static Component status() {
        Upload u = SESSION.upload;
        return Component.translatable("gui.music_disc_maker.local." + status,
                "confirm".equals(status) && u != null ? u.name : percent);
    }
    public static boolean uploading() { return SESSION.upload != null; }
    /** 待機中以外の案内文がある時だけ画面へ出す (idle の "select" は行を空ける)。 */
    public static boolean hasStatus() { return SESSION.upload != null || !"select".equals(status); }
    /** OS選択は画面スレッド、ファイル検査・変換は専用ワーカー。選択後の共有曲名を確認してから開始する。 */
    //? if >=26.3 {
    /*private static final ConcurrentMap<Long, SdlPicker> PICKERS = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong NEXT_PICKER_ID = new java.util.concurrent.atomic.AtomicLong();
    // One native trampoline for the client lifetime: never free a callback while SDL invokes it.
    private static final SDL_DialogFileCallback PICKER_CALLBACK =
            SDL_DialogFileCallback.create((id, filelist, filter) -> {
                final SdlPicker picker = PICKERS.remove(id);
                if (picker != null) picker.onResult(filelist);
            });
    private static SdlPicker activePicker;
    private static long pickerEpoch;

    public static void select(MusicDiscMakerMenu menu) {
        if (SESSION.upload != null || activePicker != null) return;
        final SdlPicker picker = new SdlPicker(menu, NEXT_PICKER_ID.incrementAndGet(),
                SESSION.connectionGeneration, pickerEpoch);
        activePicker = picker;
        PICKERS.put(picker.id, picker);
        try {
            picker.open();
        } catch (Throwable failure) {
            PICKERS.remove(picker.id);
            activePicker = null;
            picker.freeFilters();
            status = LocalImportDecisions.failureStatus(failure);
        }
    }

    /^* SDL3の非同期picker。filterはcallback到達まで保持し、結果はmain threadで検証する。 ^/
    private static final class SdlPicker {
        private final MusicDiscMakerMenu menu;
        private final long id;
        private final long connection;
        private final long epoch;
        private volatile boolean cancelled;
        private final java.util.concurrent.atomic.AtomicBoolean filtersFreed = new java.util.concurrent.atomic.AtomicBoolean();
        private final SDL_DialogFileFilter.Buffer filters = SDL_DialogFileFilter.calloc(2);
        private final ByteBuffer name = MemoryUtil.memUTF8(
                Component.translatable("gui.music_disc_maker.local.filter_desc").getString());
        private final ByteBuffer pattern = MemoryUtil.memUTF8(
                Arrays.stream(LocalImportDecisions.PICKER_PATTERNS)
                        .filter(p -> !p.equals("*.*"))
                        .map(p -> p.substring(2))
                        .collect(java.util.stream.Collectors.joining(";")));
        private final ByteBuffer allName = MemoryUtil.memUTF8("All files");
        private final ByteBuffer allPattern = MemoryUtil.memUTF8("*");

        private SdlPicker(MusicDiscMakerMenu menu, long id, long connection, long epoch) {
            this.menu = menu;
            this.id = id;
            this.connection = connection;
            this.epoch = epoch;
            filters.get(0).set(name, pattern);
            filters.get(1).set(allName, allPattern);
        }

        private void open() {
            SDLDialog.SDL_ShowOpenFileDialog(PICKER_CALLBACK, id,
                    Minecraft.getInstance().getWindow().handle(), filters, (CharSequence) null, false);
        }

        private void onResult(long filelist) {
            String selected = null;
            final boolean error = filelist == 0L;
            boolean failed = error;
            try {
                if (!failed) {
                    final long first = MemoryUtil.memGetAddress(filelist);
                    if (first != 0L) selected = MemoryUtil.memUTF8(first);
                }
            } catch (RuntimeException malformedPath) {
                failed = true;
            } finally {
                // SDL's filter lifetime ends when it calls us. The path string is copied first.
                freeFilters();
            }
            final String path = selected;
            final boolean pickerFailed = failed;
            Minecraft.getInstance().execute(() -> finish(path, pickerFailed));
        }

        private void freeFilters() {
            if (!filtersFreed.compareAndSet(false, true)) return;
            filters.free();
            MemoryUtil.memFree(name);
            MemoryUtil.memFree(pattern);
            MemoryUtil.memFree(allName);
            MemoryUtil.memFree(allPattern);
        }

        private void finish(String selected, boolean error) {
            if (activePicker == this) activePicker = null;
            if (cancelled || pickerEpoch != epoch || SESSION.connectionGeneration != connection
                    || Minecraft.getInstance().player == null
                    || Minecraft.getInstance().player.containerMenu != menu) return;
            if (error) {
                status = "import_failed";
            } else if (selected != null && SESSION.upload == null) {
                startSelected(menu, selected);
            }
        }
    }
    *///?} else {
    public static void select(MusicDiscMakerMenu menu) {
        if (SESSION.upload != null) return;
        String selected;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var filters = stack.mallocPointer(LocalImportDecisions.PICKER_PATTERNS.length);
            for (String pattern : LocalImportDecisions.PICKER_PATTERNS) filters.put(stack.UTF8(pattern));
            filters.flip();
            selected = TinyFileDialogs.tinyfd_openFileDialog(
                    Component.translatable("gui.music_disc_maker.local.picker_title").getString(), "", filters,
                    Component.translatable("gui.music_disc_maker.local.filter_desc").getString(), false);
        }
        if (selected != null) startSelected(menu, selected);
    }
    //?}

    private static void startSelected(MusicDiscMakerMenu menu, String selected) {
        long generation = ++operation;
        Upload u = new Upload(menu, generation, SESSION.connectionGeneration);
        SESSION.upload = u; status = "checking";
        Thread worker = new Thread(() -> prepare(u, selected), "mdm-local-audio-import");
        worker.setDaemon(true);
        u.worker = worker;
        worker.start();
    }
    /**
     * 選択ファイルを転送できる形へ整える (専用ワーカー上)。内容 probe が分岐を決める
     * — 拡張子は一切見ない:
     * <ul>
     *   <li>転送上限内で保存形式 (MP3/WAV/FLAC/音声のみMKA) と分かれば、バイト列を
     *       そのまま送る (= これまでの MP3 pass-through と同じ経路)。</li>
     *   <li>保存形式でない (動画・ADTS・Ogg 等) か転送上限超過なら、音声だけ decode して
     *       mono 48kHz Opus の MKA へ normalize して送る。</li>
     * </ul>
     * 変換は中断 (interrupt) をキャンセルとして観測し、失敗時は一時ファイルを残さない。
     */
    private static void prepare(Upload u, String selected) {
        Path staged = null;
        try {
            final Path input = Path.of(selected);
            if (!Files.isRegularFile(input))
                throw new ResolveException(FailureReason.UNKNOWN, "input file is not readable");
            final long inputSize = Files.size(input);
            if (inputSize <= 0)
                throw new ResolveException(FailureReason.UNKNOWN, "input file is empty");
            if (inputSize > MAX_IMPORT_BYTES)
                throw new ResolveException(FailureReason.UNKNOWN, "input file exceeds the size limit");
            final Path directory = Minecraft.getInstance().gameDirectory.toPath().resolve("music_disc_maker/imports");
            Files.createDirectories(directory);
            TrackInfo info = null;
            long size = -1L;
            String hash = null;
            if (inputSize <= LocalAudioServer.FILE_LIMIT) {
                staged = Files.createTempFile(directory, "selected-", ".bin");
                final MessageDigest digest = MessageDigest.getInstance("SHA-256");
                // Files.size() is only a snapshot. Bound the bytes actually copied so a growing
                // input cannot exceed either the import ceiling or the server's per-file limit.
                final long copyLimit = Math.min(MAX_IMPORT_BYTES, LocalAudioServer.FILE_LIMIT);
                size = 0;
                try (InputStream in = Files.newInputStream(input); OutputStream out = Files.newOutputStream(staged)) {
                    byte[] buffer = new byte[16384];
                    for (int n; (n = in.read(buffer)) != -1;) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("interrupted");
                        if (n > copyLimit - size)
                            throw new ResolveException(FailureReason.UNKNOWN, "input file exceeds the size limit");
                        size += n;
                        digest.update(buffer, 0, n); out.write(buffer, 0, n);
                    }
                }
                hash = HexFormat.of().formatHex(digest.digest());
                try {
                    info = LoaderHolder.get().resolveTrustedFile(staged);
                } catch (Exception probe) {
                    info = null; // 保存形式でない = normalize 側の広い入力 probe で再判定する
                }
                if (info != null) { u.path = staged; staged = null; }
            }
            if (info == null) {
                // 取消済みの遅延ワーカーが idle 表示を「変換中」へ戻さないよう live を見る。
                // 判定と書込みは main thread の queue へ載せ、取消・切断・新しい選択と直列化する。
                Minecraft.getInstance().execute(() -> SESSION.ifLive(u, () -> status = "converting"));
                final Path mka = directory.resolve("normalized-" + UUID.randomUUID() + ".mka");
                // コピー済みなら凍結した側 (pass-through と同じ入力) を decode する。
                info = LoaderHolder.get().normalizeLocalFile(staged != null ? staged : input, mka);
                if (info == null) throw new IOException("Normalization unavailable");
                u.path = mka;
                size = Files.size(mka);
                hash = sha256(mka);
            }
            u.hash = hash; u.size = size;
            final String title = info.title();
            u.name = (title == null || title.isBlank() || title.equals(u.path.getFileName().toString()))
                    ? LocalImportDecisions.displayName(input.getFileName().toString()) : title;
            u.name = u.name.substring(0, Math.min(256, u.name.length())).replaceAll("[\\p{Cntrl}]", "");
            Minecraft.getInstance().execute(() -> {
                if (!SESSION.live(u)) { cleanup(u); return; }
                status = "confirm";
            });
        } catch (Throwable e) {
            // impl jar 破損等の Error でもスロット・一時ファイル・状態を残さない。
            // スロット解放と status 書込みも main thread の queue へ載せて取消・切断と直列化する
            // (queue が死んでいる時だけ直接畳み、塞いだスロットを残さない)。
            final Runnable settle = () -> {
                if (SESSION.clearIfCurrent(u)) status = LocalImportDecisions.failureStatus(e);
            };
            try {
                Minecraft.getInstance().execute(settle);
            } catch (Throwable queueDead) {
                settle.run();
            }
            cleanup(u);
        } finally {
            if (staged != null) try { Files.deleteIfExists(staged); } catch (IOException ignored) {}
        }
    }
    private static String sha256(Path file) throws IOException {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[16384];
                for (int n; (n = in.read(buffer)) != -1;) digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
    public static void confirm() {
        Upload u = SESSION.upload;
        if (u == null || !"confirm".equals(status) || !SESSION.live(u)) return;
        status = "transferring"; percent = 0;
        Services.NETWORK.sendToServer(LocalAudioPayload.control(LocalAudioPayload.BEGIN, LocalAudioPayload.NONE,
                u.menu.containerId, u.generation, u.hash, 0, u.size, u.name));
    }
    /** この画面が持つ転送だけを止める。操作なし・別 menu の転送・連打は何もしない。 */
    public static void cancel(MusicDiscMakerMenu menu) {
        //? if >=26.3 {
        /*if (activePicker != null && activePicker.menu == menu) {
            activePicker.cancelled = true;
            pickerEpoch++;
            status = "select";
        }
        *///?}
        Upload u = SESSION.upload;
        if (u == null) { status = "select"; return; }
        if (u.menu != menu) return;
        abort(u, "select");
    }
    /**
     * まだスロットにある操作だけを止める。取消し済み・新しい操作へ切替済みの upload が
     * 遅れて失敗通知に来ても、ここで落として新しい操作と状態表示を守る。
     */
    private static void abort(Upload u, String newStatus) {
        if (!SESSION.clearIfCurrent(u)) return;
        status = newStatus;
        Services.NETWORK.sendToServer(LocalAudioPayload.control(LocalAudioPayload.CANCEL, u.id,
                u.menu.containerId, u.generation, u.hash, 0, 0, ""));
        cleanup(u);
    }
    public static void receive(LocalAudioPayload p) {
        if (p.operation() == LocalAudioPayload.DATA) { data(p); return; }
        if (p.operation() == LocalAudioPayload.ERROR) {
            Receive r = SESSION.failReceive(p.hash(), p.id(), p.generation(), SESSION.connectionGeneration);
            if (r != null) {
                IO.execute(() -> abort(r));
                r.future.completeExceptionally(new IOException(p.name()));
                return;
            }
        }
        Receive r = SESSION.receive(p.hash());
        if (p.operation() == LocalAudioPayload.AVAILABLE && r != null && r.cachedPath != null
                && r.request.equals(p.id()) && p.generation() == r.request.getLeastSignificantBits()) {
            if (SESSION.removeReceive(p.hash(), r)) r.future.complete(r.cachedPath);
            return;
        }
        Upload u = SESSION.upload;
        if (u == null || p.containerId() != u.menu.containerId || p.generation() != u.generation) return;
        if (p.operation() == LocalAudioPayload.READY || p.operation() == LocalAudioPayload.ACK) {
            if (u.sending || p.offset() != u.sent) return;
            u.id = p.id(); u.sending = true;
            IO.execute(() -> {
                try {
                    if (!SESSION.live(u)) return;
                    if (u.input == null) u.input = Files.newInputStream(u.path);
                    byte[] bytes = u.input.readNBytes((int) Math.min(16384, u.size - u.sent));
                    if (bytes.length == 0) throw new IOException("Incomplete audio");
                    long offset = u.sent; u.sent += bytes.length;
                    Minecraft.getInstance().execute(() -> {
                        u.sending = false;
                        if (!SESSION.live(u)) return;
                        percent = (int) (100 * u.sent / u.size);
                        Services.NETWORK.sendToServer(new LocalAudioPayload(LocalAudioPayload.CHUNK, u.id,
                                u.menu.containerId, u.generation, "", offset, 0, "", bytes));
                    });
                } catch (IOException e) { Minecraft.getInstance().execute(() -> abort(u, "import_failed")); }
            });
        } else if (p.operation() == LocalAudioPayload.DONE || p.operation() == LocalAudioPayload.ERROR) {
            SESSION.upload = null; status = p.operation() == LocalAudioPayload.DONE ? "done" : p.name(); cleanup(u);
        }
    }
    /** 待機は再生ワーカーだけ。main/render threadから呼ばない。 */
    /** 転送枠が空くのを待つ上限。超えたら従来どおり再生失敗として扱う。 */
    private static final long BUSY_RETRY_DEADLINE_MS = 120_000L;

    /**
     * 共有音源をキャッシュから、無ければサーバーから取得する (再生スレッドから呼ぶ)。
     *
     * <p>サーバーの同時転送枠 (全体4本) と自分の受信枠 (2本) は意図した上限で、埋まっている時は
     * {@code busy} が返る。同じ曲を5人以上が同時に聞き始めると後ろの人がこれを受けるので、
     * 失敗にせず間を空けて取り直す。再生位置は呼び出し側が待ち時間ぶん進めて開く。
     */
    public static Path obtain(String hash) throws Exception {
        final long connection = SESSION.connectionGeneration;
        final long deadline = System.currentTimeMillis() + BUSY_RETRY_DEADLINE_MS;
        long delay = 500L;
        while (true) {
            try {
                return obtainOnce(hash);
            } catch (ExecutionException e) {
                if (!isBusy(e.getCause()) || connection != SESSION.connectionGeneration
                        || System.currentTimeMillis() + delay > deadline) throw e;
            }
            Thread.sleep(delay + ThreadLocalRandom.current().nextLong(delay / 2 + 1));
            delay = Math.min(delay * 2, 4_000L);
        }
    }

    private static boolean isBusy(Throwable cause) {
        return cause instanceof IOException io
                && ("busy".equals(io.getMessage()) || "Audio downloads busy".equals(io.getMessage()));
    }

    private static Path obtainOnce(String hash) throws Exception {
        CompletableFuture<Path> result = new CompletableFuture<>();
        long connection = SESSION.connectionGeneration;
        AtomicReference<Session.ReceiveSlot> owned = new AtomicReference<>();
        IO.execute(() -> {
            try {
                if (connection != SESSION.connectionGeneration) throw new IOException("Disconnected");
                ensureCache();
                if (connection != SESSION.connectionGeneration) throw new IOException("Disconnected");
                Path found = cache.verified(hash);
                Session.ReceiveSlot slot = SESSION.acquireReceive(hash, connection, found);
                owned.set(slot);
                Receive r = slot.receive();
                r.future.whenComplete((v, e) -> forward(result, v, e));
                if (!slot.created()) return;
                Minecraft.getInstance().execute(() -> {
                    if (connection != SESSION.connectionGeneration || SESSION.receive(hash) != r) {
                        SESSION.removeReceive(hash, r);
                        r.future.completeExceptionally(new IOException("Disconnected"));
                        return;
                    }
                    int operation = r.requestOperation();
                    Services.NETWORK.sendToServer(LocalAudioPayload.control(operation, r.request, 0,
                            r.request.getLeastSignificantBits(), hash, 0, 0, ""));
                });
            } catch (Exception e) {
                Session.ReceiveSlot slot = owned.get();
                if (slot != null && slot.created() && SESSION.removeReceive(hash, slot.receive())) {
                    Receive r = slot.receive();
                    IO.execute(() -> abort(r));
                    r.future.completeExceptionally(e);
                } else result.completeExceptionally(e);
            }
        });
        try { return result.get(300, TimeUnit.SECONDS); }
        catch (TimeoutException e) {
            Session.ReceiveSlot slot = owned.get();
            if (slot != null && slot.created() && SESSION.timeoutReceive(hash, slot.receive(), e)) {
                Receive r = slot.receive();
                // 同じhashの後続callerも共有futureを待っている。ownerのtimeoutで全員を解放する。
                IO.execute(() -> abort(r));
            }
            throw e;
        }
    }
    private static void forward(CompletableFuture<Path> result, Path value, Throwable e) {
        if (e == null) result.complete(value); else result.completeExceptionally(e);
    }
    private static void data(LocalAudioPayload p) {
        Receive r = SESSION.receive(p.hash());
        if (r == null || !r.acceptData(p.id(), p.generation(), p.offset(), SESSION.connectionGeneration)) return;
        IO.execute(() -> {
            try {
                ensureCache();
                if (r.connection != SESSION.connectionGeneration) throw new IOException("Disconnected");
                if (r.storeId == null) r.storeId = cache.begin(r.request, p.hash(), p.size(), now());
                if (!r.id.equals(p.id()) || p.offset() != r.offset) throw new IOException("Invalid audio chunk");
                cache.append(r.request, r.storeId, p.offset(), p.bytes(), now());
                r.offset += p.bytes().length;
                if (r.offset == p.size()) {
                    Path file = cache.finish(r.request, r.storeId, now(), path -> {
                        if (LoaderHolder.get().resolveTrustedFile(path) == null) throw new IOException("Invalid audio file");
                    });
                    r.storeId = null;
                    if (!SESSION.completeReceive(p.hash(), r, file)) throw new IOException("Disconnected");
                }
                Minecraft.getInstance().execute(() -> {
                    r.busy = false;
                    if (r.connection != SESSION.connectionGeneration) return;
                    Services.NETWORK.sendToServer(LocalAudioPayload.control(LocalAudioPayload.NEXT, p.id(), 0, 0, "", r.offset, 0, ""));
                });
            } catch (Exception e) { SESSION.removeReceive(p.hash(), r); abort(r); r.future.completeExceptionally(e); }
        });
    }
    private static void ensureCache() throws IOException {
        if (cache == null) cache = LocalAudioStore.clientCache(Minecraft.getInstance().gameDirectory.toPath()
                .resolve("music_disc_maker/audio-cache"), new LocalAudioStore.Limits(LocalAudioServer.FILE_LIMIT,
                2L * 1024 * 1024 * 1024, 2, 90_000));
    }
    private static void abort(Receive r) {
        try { if (cache != null && r.storeId != null) cache.cancel(r.request, r.storeId); } catch (IOException ignored) {}
    }
    private static void cleanup(Upload u) {
        // 変換・複写中のワーカーには interrupt で止まってもらう (normalizer が中断を観測する)。
        // 走り終えたワーカーへの interrupt は無害。
        final Thread worker = u.worker;
        if (worker != null) worker.interrupt();
        IO.execute(() -> {
            try { if (u.input != null) u.input.close(); if (u.path != null) Files.deleteIfExists(u.path); }
            catch (IOException ignored) {}
        });
    }
    public static void disconnect() {
        //? if >=26.3 {
        /*if (activePicker != null) activePicker.cancelled = true;
        pickerEpoch++;
        *///?}
        List<Receive> stale = SESSION.disconnectReceives();
        Upload u = SESSION.upload; SESSION.upload = null; if (u != null) cleanup(u);
        for (Receive r : stale) { r.future.completeExceptionally(new IOException("Disconnected")); IO.execute(() -> abort(r)); }
        status = "select";
    }
    private static long now() { return System.nanoTime() / 1_000_000; }
    /**
     * upload スロットと接続世代。main thread だけが書き換える。MC 型を持たないので
     * headless 試験が {@code new Session()} で規則を直接駆動できる。
     */
    static final class Session {
        volatile Upload upload;
        volatile long connectionGeneration;
        private final Map<String, Receive> receives = new HashMap<>();
        record ReceiveSlot(Receive receive, boolean created) {}
        /** このセッションがまだ所有する upload か (取消・切断・新旧切替で false)。 */
        boolean live(Upload u) {
            return upload == u && u.connection == connectionGeneration;
        }
        /**
         * まだ所有する upload 由来の遅延処理だけを通す。worker からの UI 更新は
         * main thread の queue 経由でここへ来るので、取消・切断・新しい選択との競合は
         * queue の順序で直列化される (check-then-act の隙間を残さない)。
         */
        void ifLive(Upload u, Runnable action) {
            if (live(u)) action.run();
        }
        /** スロットにあるその upload だけを外す。別操作・済み操作への遅延到着は no-op。 */
        boolean clearIfCurrent(Upload u) {
            if (upload != u) return false;
            upload = null;
            return true;
        }
        /** Atomically reject old connection work before it can publish a receive under a reused hash. */
        synchronized ReceiveSlot acquireReceive(String hash, long connection, Path cachedPath) throws IOException {
            if (connection != connectionGeneration) throw new IOException("Disconnected");
            Receive existing = receives.get(hash);
            if (existing != null) return new ReceiveSlot(existing, false);
            if (receives.size() >= 2) throw new IOException("Audio downloads busy");
            Receive created = new Receive(hash, connection, cachedPath);
            receives.put(hash, created);
            return new ReceiveSlot(created, true);
        }
        synchronized Receive receive(String hash) { return receives.get(hash); }
        synchronized boolean removeReceive(String hash, Receive receive) { return receives.remove(hash, receive); }
        /** Remove only the timing-out owner and settle the shared future so joined callers also wake. */
        synchronized boolean timeoutReceive(String hash, Receive receive, Throwable cause) {
            if (!receives.remove(hash, receive)) return false;
            receive.future.completeExceptionally(cause);
            return true;
        }
        /** Publish a completed file only if this exact receive still owns the hash in this connection. */
        synchronized boolean completeReceive(String hash, Receive receive, Path file) {
            if (receive.connection != connectionGeneration || !receives.remove(hash, receive)) return false;
            receive.future.complete(file);
            return true;
        }
        /** request/transfer IDと世代が現在の接続・hashの受信に一致するERRORだけを閉じる。 */
        synchronized Receive failReceive(String hash, UUID responseId, long responseGeneration,
                                          long currentConnection) {
            if (currentConnection != connectionGeneration) return null;
            Receive receive = receives.get(hash);
            if (receive == null || receive.connection != currentConnection
                    || responseGeneration != receive.request.getLeastSignificantBits()
                    || responseId == null
                    || (!receive.request.equals(responseId) && !responseId.equals(receive.id))) return null;
            return receives.remove(hash, receive) ? receive : null;
        }
        synchronized List<Receive> disconnectReceives() {
            connectionGeneration++;
            List<Receive> stale = List.copyOf(receives.values());
            receives.clear();
            return stale;
        }
    }
    static final class Upload {
        final MusicDiscMakerMenu menu; final long generation, connection;
        volatile String hash = "", name = ""; volatile Path path; volatile Thread worker;
        // NONE (転送前の仮 id) と同値。直定数にするのは headless 試験で payload クラスを引かないため。
        long size, sent; UUID id = new UUID(0, 0); InputStream input; boolean sending;
        Upload(MusicDiscMakerMenu menu, long generation, long connection) { this.menu = menu; this.generation = generation; this.connection = connection; }
    }
    static final class Receive {
        final String hash; final long connection; final UUID request = UUID.randomUUID();
        final Path cachedPath;
        final CompletableFuture<Path> future = new CompletableFuture<>();
        volatile UUID id; UUID storeId; volatile long offset; volatile boolean busy;
        Receive(String hash, long connection, Path cachedPath) {
            this.hash = hash; this.connection = connection; this.cachedPath = cachedPath;
        }
        /** Claim only the first server transfer ID for this request and one chunk at a time. */
        synchronized boolean acceptData(UUID transferId, long responseGeneration, long responseOffset,
                                        long currentConnection) {
            if (connection != currentConnection || responseGeneration != request.getLeastSignificantBits()
                    || transferId == null || responseOffset != offset || busy
                    || (id != null && !id.equals(transferId))) return false;
            if (id == null) id = transferId;
            busy = true;
            return true;
        }
        int requestOperation() { return cachedPath == null ? LocalAudioPayload.FETCH : LocalAudioPayload.CHECK; }
    }
}
