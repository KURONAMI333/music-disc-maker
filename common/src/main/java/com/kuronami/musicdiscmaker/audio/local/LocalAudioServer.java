package com.kuronami.musicdiscmaker.audio.local;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.audio.LoaderHolder;
import com.kuronami.musicdiscmaker.block.MusicDiscMakerBlockEntity;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.menu.MusicDiscMakerMenu;
import com.kuronami.musicdiscmaker.network.*;
import com.kuronami.musicdiscmaker.platform.Services;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ワールド原本とMinecraft接続の境界。main threadはmenu・個体・世代と受信枠を検査し、
 * IOは専用1ワーカーへ渡す。ACK以前の連投は受理せず、全体で各方向1MiB/sを超えない。
 * 原本は自動削除しない。停止時にcloseAllを呼び、一時受信とファイルロックを解放する。
 */
public final class LocalAudioServer {
    public static final long FILE_LIMIT = 32L * 1024 * 1024;
    private static final long DOWNLOAD_IDLE_TIMEOUT_MS = 60_000;
    /** 上げ直しの同時本数 (全体)。製作機の取込 (uploads) とは別枠で数える。 */
    private static final int MAX_RESTORES = 2;
    /**
     * 「この hash の原本が無い」と答えた記録の寿命。上げ直しは、この記録が残っている hash にだけ
     * 通す (client が勝手な hash を送り付けて store を埋める経路を作らない)。client は missing を
     * 受け取った直後に送るので、実際には数秒で消費される。
     */
    private static final long MISSING_NOTICE_TTL_MS = 10 * 60_000;
    /** 上げ直しの転送が止まったまま残る時間。製作機の取込と同じ 60 秒。 */
    private static final long RESTORE_IDLE_TIMEOUT_MS = 60_000;
    /**
     * 持込音源の尺の上限 (client の LocalFileNormalizer と同じ 2 時間)。client は長い曲を
     * bitrate で 32MiB へ収めるが、改造 client は変換を飛ばせるので、後段の時計計算が
     * 桁あふれしない範囲を server 側でも押さえる。
     */
    private static final long MAX_DURATION_MS = 2L * 60L * 60_000L;
    private static final Map<MinecraftServer, State> STATES = new IdentityHashMap<>();
    private LocalAudioServer() {}
    private static synchronized State state(MinecraftServer server) {
        return STATES.computeIfAbsent(server, State::new);
    }
    /** サーバーが実際に再生通知する対象だけに、短命の取得権限を与える。 */
    public static void allow(ServerPlayer player, CustomTrackData track) {
        if (track == null || !track.isLocal()) return;
        grant(state(player.level().getServer()), player, track.localHash());
    }
    private static void grant(State s, ServerPlayer player, String hash) {
        // 送出経路の thread を継承する呼び出しがありうるので、grants の更新は s で直列化する。
        synchronized (s) {
            Map<String, Long> grants = s.grants.computeIfAbsent(player.getUUID(), key -> new LinkedHashMap<>());
            grants.values().removeIf(expiry -> expiry < now());
            if (grants.size() >= 64) grants.remove(grants.keySet().iterator().next());
            grants.put(hash, now() + 120_000);
        }
    }
    /**
     * FETCH/CHECK を受けてよいか。server が再生を通知した相手 (grant) か、本人が今 server 上で見ている実物に
     * その盤がある時だけ許可する。後者は同じ短命の grant を取り直す。client だけで鳴らす携帯機器 (Traveler's
     * Backpack 等) は、busy で待って取り直す間 (最大120秒) に画面が閉じられるので、画面を開いた時の確認を
     * その間だけ持たせる。根拠は server が見た実物だけなので、見ていない hash は引き続き取れない。
     */
    private static boolean authorized(State s, ServerPlayer player, String hash) {
        Long expires;
        synchronized (s) { expires = s.grants.getOrDefault(player.getUUID(), Map.of()).get(hash); }
        if (expires != null && expires >= now()) return true;
        if (!canView(player, hash)) return false;
        grant(s, player, hash);
        return true;
    }
    public static void observe(ServerPlayer player, ModPayload payload) {
        if (payload instanceof PlayDiscPayload p) allow(player, p.track());
        if (payload instanceof BoomboxPlayPayload p) allow(player, p.track());
        if (payload instanceof com.kuronami.musicdiscmaker.network.GoldenCartPlayPayload p
                && !p.isStop()) allow(player, p.track());
    }
    public static void receive(LocalAudioPayload p, ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        State s = state(server);
        if (p.operation() == LocalAudioPayload.BEGIN || p.operation() == LocalAudioPayload.RESTORE) {
            synchronized (s) {
                long previous = s.requests.getOrDefault(player.getUUID(), 0L);
                if (now() - previous < 250) { error(player, p, "busy"); return; }
                s.requests.put(player.getUUID(), now());
            }
        }
        if (p.operation() == LocalAudioPayload.BEGIN) begin(s, p, player);
        else if (p.operation() == LocalAudioPayload.CHUNK) { if (!appendRestore(s, p, player)) append(s, p, player); }
        else if (p.operation() == LocalAudioPayload.CANCEL) cancel(s, player, p);
        else if (p.operation() == LocalAudioPayload.FETCH) fetch(s, p, player);
        else if (p.operation() == LocalAudioPayload.CHECK) checkCachedCopy(s, p, player);
        else if (p.operation() == LocalAudioPayload.NEXT) next(s, p, player);
        else if (p.operation() == LocalAudioPayload.RESTORE) restore(s, p, player);
    }
    private static MusicDiscMakerMenu maker(ServerPlayer player, int container) {
        if (!(player.containerMenu instanceof MusicDiscMakerMenu menu) || menu.containerId != container
                || !menu.stillValid(player)) return null;
        MusicDiscMakerBlockEntity be = menu.getBlockEntity();
        return !be.isRemoved() && player.level() == be.getLevel()
                && player.level().getBlockEntity(be.getBlockPos()) == be ? menu : null;
    }
    private static void begin(State s, LocalAudioPayload p, ServerPlayer player) {
        MusicDiscMakerMenu menu = maker(player, p.containerId());
        // 拒否は必ず client へ返す。黙って捨てると相手は transferring のまま残る。
        String rejection = LocalServerDecisions.beginRejection(menu != null, p.hash(), p.size(), FILE_LIMIT);
        if (rejection != null) { error(player, p, rejection); return; }
        if (s.uploads.size() >= 2 || s.uploads.containsKey(player.getUUID())) {
            error(player, p, "busy"); return;
        }
        MusicDiscMakerBlockEntity be = menu.getBlockEntity();
        if (!MusicDiscMakerBlockEntity.isBlankDisc(be.getItem(0)) || !be.getItem(1).isEmpty()) {
            error(player, p, "disc"); return;
        }
        Upload u = new Upload(player, menu, p, be.beginLocalImport());
        s.uploads.put(player.getUUID(), u);
        s.io.execute(() -> {
            try {
                s.open();
                s.quota.check(player.getUUID(), p.hash(), p.size());
                LocalAudioStore.Preparation prepared = s.store.prepare(player.getUUID(), p.hash(), p.size(), now());
                u.id = prepared.transferId() == null ? UUID.randomUUID() : prepared.transferId();
                u.storeId = prepared.transferId();
                if (prepared.existing() != null) complete(s, u, prepared.existing());
                else reply(s, u, LocalAudioPayload.READY, 0);
            } catch (Throwable e) { fail(s, u, importFailure(e)); workerFailure(e); }
        });
    }
    private static boolean valid(Upload u) {
        return maker(u.player, u.request.containerId()) == u.menu
                && u.menu.getBlockEntity().localImportGeneration() == u.makerGeneration;
    }
    /**
     * 再生した盤の原本を、検証済みキャッシュを持つ client から受け取り直す。盤も menu も作らない
     * (盤は既にあり、世界中の同じ hash が鳴るようになるだけ)。
     *
     * <p>受け入れる条件は 3 つ。形式 (hash・サイズ)、空き枠と先着 1 本、そしてこの player に
     * 「原本が無い」と答えた記録が残っていること。記録は 1 回で消費する。
     */
    private static void restore(State s, LocalAudioPayload p, ServerPlayer player) {
        final String rejection = LocalServerDecisions.restoreRejection(
                s.restores.size() < MAX_RESTORES, !s.restores.containsKey(player.getUUID()),
                s.restores.values().stream().anyMatch(r -> r.hash.equals(p.hash())),
                p.hash(), p.size(), FILE_LIMIT);
        if (rejection != null) { error(player, p, rejection); return; }
        s.io.execute(() -> {
            try {
                s.open();
                if (s.store.verified(p.hash()) != null) { alreadyStored(s, p, player); return; }
                final Restore r = new Restore(player, p);
                synchronized (s.restores) {
                    // 形式検査は main thread で済ませたが、枠と先着は IO 側で確定させる。
                    if (s.restores.size() >= MAX_RESTORES || s.restores.containsKey(player.getUUID())
                            || s.restores.values().stream().anyMatch(o -> o.hash.equals(p.hash())))
                        throw new IOException("Transfer limit reached");
                    if (!consumeMissing(s, player, p.hash())) { missingAgain(s, p, player); return; }
                    s.quota.check(player.getUUID(), p.hash(), p.size());
                    r.storeId = s.store.begin(player.getUUID(), p.hash(), p.size(), now());
                    s.restores.put(player.getUUID(), r);
                }
                replyRestore(s, r, LocalAudioPayload.RESTORE_READY, 0);
            } catch (Throwable e) {
                s.server.execute(() -> error(player, p, importFailure(e)));
                workerFailure(e);
            }
        });
    }
    /** 既に store にある hash。転送せず完了だけ返す (duplicate upload の prepare と同じ扱い)。 */
    private static void alreadyStored(State s, LocalAudioPayload p, ServerPlayer player) {
        s.server.execute(() -> send(player, LocalAudioPayload.control(LocalAudioPayload.DONE, p.id(), 0, 0,
                p.hash(), p.size(), p.size(), "done")));
    }
    /**
     * 「原本が無い」記録が無い (または古い) 時の返答。client は次の再生で missing を受け直すまで
     * 上げ直しを試さないので、ここで終わらせて良い。
     */
    private static void missingAgain(State s, LocalAudioPayload p, ServerPlayer player) {
        s.server.execute(() -> error(player, p, "missing"));
    }
    /** 上げ直しの CHUNK。この player の restore に属する時だけ処理し、属さなければ取込へ渡す。 */
    private static boolean appendRestore(State s, LocalAudioPayload p, ServerPlayer player) {
        final Restore r = s.restores.get(player.getUUID());
        // 別の転送 (製作機からの取込) の chunk は append へ渡す。同じ player でも同時に走りうる。
        if (r == null || r.storeId == null || !p.id().equals(r.storeId)) return false;
        if (r.player != player || r.cancelled || !r.busy.compareAndSet(false, true)) return true;
        r.touched = now();
        s.io.schedule(() -> {
            final UUID transferId = r.storeId;
            try {
                if (r.cancelled || transferId == null) { discardRestore(s, r); return; }
                s.store.append(player.getUUID(), transferId, p.offset(), p.bytes(), now());
                r.received += p.bytes().length;
                if (r.received == r.size) {
                    Path file = s.store.finish(player.getUUID(), transferId, now(), path -> {
                        // 取込と同じ検査。中身の sha256 一致は store.finish が転送中の digest で見る。
                        var info = LoaderHolder.get().resolveTrustedFile(path);
                        if (info == null || info.durationMs() <= 0 || info.durationMs() > MAX_DURATION_MS)
                            throw new IOException("Invalid audio file");
                    });
                    if (!LocalServerDecisions.storedUnderRequestedHash(r.hash, String.valueOf(file.getFileName())))
                        throw new IOException("Corrupt stored audio");
                    r.storeId = null;
                    // 上げ直した player の容量として数える (製作機からの取込と同じ扱い)。
                    s.quota.record(player.getUUID(), r.hash, r.size);
                    finishRestore(s, r, transferId);
                } else replyRestore(s, r, LocalAudioPayload.ACK, r.received);
            } catch (Throwable e) { failRestore(s, r, transferId, importFailure(e)); workerFailure(e); }
        }, s.delay(true, p.bytes().length), TimeUnit.MILLISECONDS);
        return true;
    }
    private static void replyRestore(State s, Restore r, int op, long offset) {
        s.server.execute(() -> {
            if (!connected(s, r.player) || r.cancelled) {
                r.cancelled = true;
                s.io.execute(() -> discardRestore(s, r));
                return;
            }
            r.busy.set(false);
            send(r.player, LocalAudioPayload.control(op, r.storeId, 0, 0, r.hash, offset, r.size, ""));
        });
    }
    private static void finishRestore(State s, Restore r, UUID transferId) {
        s.server.execute(() -> {
            s.restores.remove(r.player.getUUID(), r);
            if (connected(s, r.player)) send(r.player, LocalAudioPayload.control(LocalAudioPayload.DONE,
                    transferId, 0, 0, r.hash, r.size, r.size, "done"));
        });
    }
    private static void failRestore(State s, Restore r, UUID transferId, String reason) {
        discardRestore(s, r);
        s.server.execute(() -> {
            if (connected(s, r.player)) send(r.player, LocalAudioPayload.control(LocalAudioPayload.ERROR,
                    transferId == null ? r.request : transferId, 0, 0, r.hash, 0, 0, reason));
        });
    }
    private static void discardRestore(State s, Restore r) {
        try { if (r.storeId != null && s.store != null) s.store.cancel(r.player.getUUID(), r.storeId); }
        catch (IOException ignored) { /* store.expire が削除を再試行する */ }
        r.storeId = null;
        s.restores.remove(r.player.getUUID(), r);
    }
    /** この player に「原本が無い」と答えた hash を記録する。TTL を過ぎた記録は捨てる。 */
    private static void rememberMissing(State s, ServerPlayer player, String hash) {
        synchronized (s) {
            Map<String, Long> hashes = s.missing.computeIfAbsent(player.getUUID(), key -> new LinkedHashMap<>());
            final long time = now();
            hashes.values().removeIf(noticed -> time - noticed > MISSING_NOTICE_TTL_MS);
            if (hashes.size() >= 64) hashes.remove(hashes.keySet().iterator().next());
            hashes.put(hash, time);
        }
    }
    /** 上げ直しを受け入れる時に記録を 1 回消費する。無い・古いなら false。 */
    private static boolean consumeMissing(State s, ServerPlayer player, String hash) {
        synchronized (s) {
            Map<String, Long> hashes = s.missing.get(player.getUUID());
            if (hashes == null) return false;
            Long noticed = hashes.remove(hash);
            return noticed != null && now() - noticed <= MISSING_NOTICE_TTL_MS;
        }
    }
    private static void append(State s, LocalAudioPayload p, ServerPlayer player) {
        Upload u = s.uploads.get(player.getUUID());
        // A reconnect receives a new ServerPlayer instance even when it has the same UUID.
        // Do not let packets from that new connection continue the old connection's transfer.
        if (u == null || u.player != player || !p.id().equals(u.id) || !valid(u) || u.cancelled
                || !u.busy.compareAndSet(false, true)) return;
        u.touched = now();
        s.io.schedule(() -> {
            try {
                if (u.cancelled) { discard(s, u); return; }
                s.store.append(player.getUUID(), u.storeId, p.offset(), p.bytes(), now());
                u.received += p.bytes().length;
                if (u.received == u.request.size()) {
                    Path file = s.store.finish(player.getUUID(), u.storeId, now(), path -> {
                        // 拒否はここ (world store へ移す前) で行う。移した後に投げると、原本は消されず
                        // 誰の容量にも数えられないまま残る。
                        var info = LoaderHolder.get().resolveTrustedFile(path);
                        // client の変換が守る2時間上限を server でも確かめる。改造 client は変換を飛ばせ、
                        // WAV/FLAC はヘッダの申告尺をそのまま返すので、巨大な尺で後段の時計計算が桁あふれする。
                        if (info == null || info.durationMs() <= 0 || info.durationMs() > MAX_DURATION_MS)
                            throw new IOException("Invalid audio file");
                    });
                    u.storeId = null;
                    complete(s, u, file);
                } else reply(s, u, LocalAudioPayload.ACK, u.received);
            } catch (Throwable e) { fail(s, u, importFailure(e)); workerFailure(e); }
        }, s.delay(true, p.bytes().length), TimeUnit.MILLISECONDS);
    }
    private static void complete(State s, Upload u, Path file) throws IOException {
        var info = LoaderHolder.get().resolveTrustedFile(file);
        // 新規の upload は finish の検査で弾かれる。ここは既に world store にあった原本 (3.0.x 以前の
        // 保存を含む) を盤にする経路のための同じ上限。
        if (info == null || info.durationMs() <= 0 || info.durationMs() > MAX_DURATION_MS)
            throw new IOException("Invalid audio file");
        String title = info.title();
        // decoderの既定ファイル名（保存ハッシュ）は曲名として表示しない。
        if (title == null || title.isBlank() || title.equals(file.getFileName().toString())
                || title.equals(u.request.hash())) title = u.request.name();
        final CustomTrackData track = CustomTrackData.local(u.request.hash(), title, info.author(), info.durationMs());
        // 完成原本は取消後も残るため、同じ操作で所有容量も必ず計上する。
        s.quota.record(u.player.getUUID(), u.request.hash(), u.request.size());
        if (u.cancelled) { discard(s, u); return; }
        s.server.execute(() -> {
            if (!(connected(s, u.player) && valid(u) && !u.cancelled)) {
                notifyDropped(s, u);
            } else {
                MusicDiscMakerBlockEntity be = u.menu.getBlockEntity();
                be.setResolvedTrack(track, "");
                boolean made = be.createDisc();
                be.resetToNeutral();
                send(u.player, LocalAudioPayload.control(made ? LocalAudioPayload.DONE : LocalAudioPayload.ERROR,
                        u.id, u.request.containerId(), u.request.generation(), u.request.hash(), u.received,
                        u.request.size(), made ? "done" : "disc"));
            }
            s.uploads.remove(u.player.getUUID(), u);
        });
    }
    private static void reply(State s, Upload u, int op, long offset) {
        s.server.execute(() -> {
            if (!connected(s, u.player) || !valid(u) || u.cancelled) {
                notifyDropped(s, u);
                u.cancelled = true;
                s.io.execute(() -> discard(s, u));
                return;
            }
            u.busy.set(false);
            send(u.player, LocalAudioPayload.control(op, u.id, u.request.containerId(), u.request.generation(),
                    u.request.hash(), offset, u.request.size(), ""));
        });
    }
    /** 転送を黙って破棄すると client は送信中のまま止まるので、接続中なら理由を返す (通知は最善 effort)。 */
    private static void notifyDropped(State s, Upload u) {
        String reason = LocalServerDecisions.dropReason(u.cancelled, connected(s, u.player));
        if (reason == null) return;
        try { error(u.player, u.request, reason); }
        catch (RuntimeException ignored) { /* 送信不能でも破棄は進める */ }
    }
    private static void fail(State s, Upload u, String reason) {
        discard(s, u);
        s.server.execute(() -> error(u.player, u.request, reason));
    }
    private static void discard(State s, Upload u) {
        try { if (u.storeId != null && s.store != null) s.store.cancel(u.player.getUUID(), u.storeId); }
        catch (IOException ignored) { /* store.expireが削除を再試行する */ }
        s.server.execute(() -> s.uploads.remove(u.player.getUUID(), u));
    }
    private static void cancel(State s, ServerPlayer player, LocalAudioPayload p) {
        Upload u = s.uploads.get(player.getUUID());
        if (u == null || u.player != player || u.request.containerId() != p.containerId()
                || u.request.generation() != p.generation()) return;
        u.cancelled = true;
        u.menu.getBlockEntity().cancelLocalImport(u.makerGeneration);
        s.io.execute(() -> discard(s, u));
    }
    /** clientだけで再生する携帯機器は、サーバー上で本人が閲覧できる実物から許可を確認する。 */
    private static boolean canView(ServerPlayer player, String hash) {
        int[] budget = {2048};
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (contains(player.getInventory().getItem(i), hash, 0, budget)) return true;
        }
        if (player.containerMenu.stillValid(player)) {
            for (var slot : player.containerMenu.slots) if (contains(slot.getItem(), hash, 0, budget)) return true;
        }
        return false;
    }
    private static boolean contains(net.minecraft.world.item.ItemStack stack, String hash, int depth, int[] budget) {
        if (stack.isEmpty() || depth > 4 || --budget[0] < 0) return false;
        var track = com.kuronami.musicdiscmaker.item.CustomMusicDiscItem.getTrack(stack);
        if (track.isLocal() && track.localHash().equals(hash)) return true;
        for (var disc : com.kuronami.musicdiscmaker.item.AlbumItem.contents(stack).discs())
            if (contains(disc, hash, depth + 1, budget)) return true;
        // アルバムでない stack には null が返る (AA が無い時は常に null)。
        var albumDiscs = com.kuronami.musicdiscmaker.compat.additionaladditions.AdditionalAdditionsAlbumSupport.contents(stack);
        if (albumDiscs != null)
            for (var disc : albumDiscs) if (contains(disc, hash, depth + 1, budget)) return true;
        //? if >=1.21 {
        var contents = stack.get(net.minecraft.core.component.DataComponents.CONTAINER);
        if (contents != null) {
            //? if >=26.1 {
            var iterator = contents.nonEmptyItemCopyStream().iterator();
            //?} else {
            /*var iterator = contents.stream().iterator();
            *///?}
            while (iterator.hasNext() && budget[0] > 0)
                if (contains(iterator.next(), hash, depth + 1, budget)) return true;
        }
        //?}
        return false;
    }
    private static void fetch(State s, LocalAudioPayload p, ServerPlayer player) {
        if (!authorized(s, player, p.hash())) { error(player, p, "missing"); return; }
        if (s.downloads.size() >= 4 || s.downloads.values().stream().anyMatch(d -> d.player == player && d.hash.equals(p.hash()))) {
            error(player, p, "busy"); return;
        }
        Download d = new Download(player, p.hash(), UUID.randomUUID(), p.id(), p.generation());
        s.downloads.put(d.id, d);
        s.io.execute(() -> {
            try {
                s.open();
                Path path = s.store.verified(d.hash);
                if (path == null) { rememberMissing(s, player, d.hash); throw new IOException("Missing audio"); }
                d.size = Files.size(path); d.input = Files.newInputStream(path);
                sendData(s, d);
            } catch (Throwable e) { closeDownload(s, d); s.server.execute(() -> error(player, p, "missing")); workerFailure(e); }
        });
    }
    /** A cache hit is usable only while this world's verified original still exists. */
    private static void checkCachedCopy(State s, LocalAudioPayload p, ServerPlayer player) {
        if (!authorized(s, player, p.hash())) { error(player, p, "missing"); return; }
        if (s.downloads.size() >= 4 || s.downloads.values().stream().anyMatch(d -> d.player == player && d.hash.equals(p.hash()))) {
            error(player, p, "busy"); return;
        }
        Download d = new Download(player, p.hash(), UUID.randomUUID(), p.id(), p.generation());
        s.downloads.put(d.id, d);
        s.io.execute(() -> {
            try {
                s.open();
                Path path = s.store.verified(d.hash);
                if (path == null) { rememberMissing(s, player, d.hash); throw new IOException("Missing audio"); }
                long size = Files.size(path);
                s.server.execute(() -> {
                    if (d.closed) return;
                    d.busy.set(false);
                    send(player, LocalAudioPayload.control(LocalAudioPayload.AVAILABLE, d.request, 0,
                            p.generation(), d.hash, 0, size, ""));
                    closeDownload(s, d);
                });
            } catch (Throwable e) {
                closeDownload(s, d);
                s.server.execute(() -> error(player, p, "missing"));
                workerFailure(e);
            }
        });
    }
    private static void next(State s, LocalAudioPayload p, ServerPlayer player) {
        Download d = s.downloads.get(p.id());
        if (d == null || d.player != player || p.offset() != d.offset || !d.busy.compareAndSet(false, true)) return;
        d.touched = now();
        s.io.schedule(() -> {
            try { if (d.offset == d.size) closeDownload(s, d); else sendData(s, d); }
            catch (Throwable e) { closeDownload(s, d); s.server.execute(() -> send(player, LocalAudioPayload.control(LocalAudioPayload.ERROR,
                    d.request, 0, d.generation, d.hash, 0, 0, "download_failed"))); workerFailure(e); }
        }, s.delay(false, LocalAudioStore.CHUNK_BYTES), TimeUnit.MILLISECONDS);
    }
    private static void sendData(State s, Download d) throws IOException {
        if (d.closed) return;
        long offset = d.offset;
        byte[] bytes = d.input.readNBytes((int) Math.min(LocalAudioStore.CHUNK_BYTES, d.size - offset));
        if (bytes.length == 0) throw new IOException("Incomplete audio");
        d.offset += bytes.length;
        s.server.execute(() -> {
            if (d.closed) return;
            d.busy.set(false);
            send(d.player, new LocalAudioPayload(LocalAudioPayload.DATA, d.id, 0, d.request.getLeastSignificantBits(),
                    d.hash, offset, d.size, "", bytes));
        });
    }
    private static void closeDownload(State s, Download d) {
        d.closed = true;
        try { if (d.input != null) d.input.close(); } catch (IOException ignored) {}
        s.server.execute(() -> s.downloads.remove(d.id, d));
    }
    /** tickは状態だけ検査する。期限切れ・退出・menu交換のIO掃除はワーカーで行う。 */
    public static synchronized void tick(MinecraftServer server) {
        State s = STATES.get(server);
        if (s == null) return;
        for (Upload u : List.copyOf(s.uploads.values())) {
            if (!u.cancelled && (!connected(s, u.player) || !valid(u) || now() - u.touched > 60_000)) {
                u.cancelled = true;
                u.menu.getBlockEntity().cancelLocalImport(u.makerGeneration);
                // 通知は最善 effort。送信失敗が tick ループへ伝播して残りの掃除や
                // サーバー tick を止めないよう飲み込み、slot 解放は必ず進める。
                try { error(u.player, u.request, "expired"); }
                catch (RuntimeException ignored) { /* 送信不能でも解放は行う */ }
                s.io.execute(() -> discard(s, u));
            }
        }
        for (Restore r : List.copyOf(s.restores.values())) {
            if (r.cancelled) continue;
            if (!connected(s, r.player) || now() - r.touched > RESTORE_IDLE_TIMEOUT_MS) {
                r.cancelled = true;
                final UUID transferId = r.storeId;
                // 転送の再開を待っていた client を止める。通知できなくても slot は必ず解放する。
                try {
                    send(r.player, LocalAudioPayload.control(LocalAudioPayload.ERROR,
                            transferId == null ? r.request : transferId, 0, 0, r.hash, 0, 0, "expired"));
                } catch (RuntimeException ignored) { /* 送信不能でも解放は行う */ }
                s.io.execute(() -> discardRestore(s, r));
            }
        }
        for (Download d : List.copyOf(s.downloads.values())) {
            if (d.closed) continue;
            if (!connected(s, d.player)) {
                d.closed = true; s.io.execute(() -> closeDownload(s, d));
            } else if (now() - d.touched > DOWNLOAD_IDLE_TIMEOUT_MS) {
                d.closed = true;
                // FETCH/CHECKの応答が届かなかったclientは最大300秒待っていた。
                // request id とその世代で通知し、同hashの後続受信へ古いtimeoutを当てない。
                try {
                    send(d.player, LocalAudioPayload.control(LocalAudioPayload.ERROR, d.request, 0,
                            d.generation, d.hash, 0, 0, "download_timeout"));
                } catch (RuntimeException ignored) { /* 通知失敗でも入力streamとdownload slotは解放する */ }
                s.io.execute(() -> closeDownload(s, d));
            }
        }
        synchronized (s) {
            s.grants.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
            s.requests.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
            s.missing.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        }
        if (now() - s.lastSweep > 10_000 && s.sweeping.compareAndSet(false, true)) {
            s.lastSweep = now();
            s.io.execute(() -> {
                try { if (s.store != null) s.store.expire(now()); }
                catch (IOException ignored) { /* 次のsweepで予約付きの残骸を再試行する */ }
                finally { s.sweeping.set(false); }
            });
        }
    }
    public static synchronized void closeAll() {
        for (State s : STATES.values()) {
            s.uploads.values().forEach(u -> u.cancelled = true);
            s.restores.values().forEach(r -> r.cancelled = true);
            s.io.execute(() -> {
                for (Restore r : List.copyOf(s.restores.values())) discardRestore(s, r);
                for (Download d : List.copyOf(s.downloads.values())) closeDownload(s, d);
                try { if (s.store != null) s.store.close(); } catch (IOException ignored) {}
            });
            s.io.shutdown();
        }
        STATES.clear();
    }
    /** I/O ワーカーの Error (LinkageError 等) は executor に飲まれて client が expired を待つだけになるので、取込失敗へ畳む。 */
    private static String importFailure(Throwable e) {
        return switch (String.valueOf(e.getMessage())) {
            case "Player audio storage full" -> "quota";
            case "Audio storage full" -> "world_full";
            case "Transfer limit reached" -> "busy";
            default -> "import_failed";
        };
    }
    /** I/O ワーカーの失敗をログに残す。executor の Future は投げ直した例外を飲むので、ここでは投げ直さない。 */
    private static void workerFailure(Throwable e) {
        if (LocalServerDecisions.vmFatal(e))
            MusicDiscMaker.LOGGER.error("Local audio worker hit a VM error ({})", e.getClass().getSimpleName(), e);
        else logUnexpected("worker", e);
    }
    /** 想定内の I/O 失敗 (容量超過・原本なし等) は静かに畳み、Error と実行時例外だけ warn で残す。 */
    private static void logUnexpected(String what, Throwable e) {
        if (e instanceof Error || e instanceof RuntimeException)
            MusicDiscMaker.LOGGER.warn("Local audio {} failed ({})", what, e.getClass().getSimpleName(), e);
    }
    private static void error(ServerPlayer player, LocalAudioPayload p, String reason) {
        send(player, LocalAudioPayload.control(LocalAudioPayload.ERROR, p.id(), p.containerId(), p.generation(), p.hash(), 0, 0, reason));
    }
    private static void send(ServerPlayer player, LocalAudioPayload p) { Services.NETWORK.sendToPlayer(player, p); }
    private static long now() { return System.nanoTime() / 1_000_000; }
    private static boolean connected(State s, ServerPlayer player) {
        return s.server.getPlayerList().getPlayer(player.getUUID()) == player;
    }
    private static final class State {
        final MinecraftServer server;
        final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mdm-local-audio-server"); t.setDaemon(true); return t;
        });
        final Map<UUID, Upload> uploads = new ConcurrentHashMap<>();
        final Map<UUID, Restore> restores = new ConcurrentHashMap<>();
        final Map<UUID, Download> downloads = new ConcurrentHashMap<>();
        final Map<UUID, Map<String, Long>> grants = new HashMap<>();
        final Map<UUID, Long> requests = new HashMap<>();
        /** 「原本が無い」と答えた hash (player → hash → 時刻)。上げ直しの可否はこの記録で決める。 */
        final Map<UUID, Map<String, Long>> missing = new HashMap<>();
        final AtomicBoolean sweeping = new AtomicBoolean();
        long lastSweep;
        LocalAudioStore store; LocalAudioQuota quota;
        long inbound, outbound;
        State(MinecraftServer server) { this.server = server; }
        void open() throws IOException {
            if (store != null) return;
            Path root = server.getWorldPath(LevelResource.ROOT).resolve("music_disc_maker/audio");
            quota = new LocalAudioQuota(root.resolve("owners"), 256L * 1024 * 1024);
            store = new LocalAudioStore(root, new LocalAudioStore.Limits(FILE_LIMIT, 2L * 1024 * 1024 * 1024, 2, 60_000));
        }
        long delay(boolean upload, int bytes) {
            long time = now(), due = Math.max(time, upload ? inbound : outbound);
            long next = due + Math.max(1, (bytes * 1000L + 1048575) / 1048576);
            if (upload) inbound = next; else outbound = next;
            return next - time;
        }
    }
    private static final class Upload {
        final ServerPlayer player; final MusicDiscMakerMenu menu; final LocalAudioPayload request;
        final long makerGeneration; final AtomicBoolean busy = new AtomicBoolean(true);
        volatile UUID id, storeId; volatile boolean cancelled; volatile long touched = now(); long received;
        Upload(ServerPlayer p, MusicDiscMakerMenu menu, LocalAudioPayload request, long generation) {
            player = p; this.menu = menu; this.request = request; makerGeneration = generation;
        }
    }
    private static final class Restore {
        final ServerPlayer player; final String hash; final long size; final UUID request;
        final AtomicBoolean busy = new AtomicBoolean(true);
        volatile UUID storeId; volatile boolean cancelled; volatile long touched = now(); long received;
        Restore(ServerPlayer player, LocalAudioPayload p) {
            this.player = player; this.hash = p.hash(); this.size = p.size(); this.request = p.id();
        }
    }
    private static final class Download {
        final ServerPlayer player; final String hash; final UUID id, request; final long generation;
        final AtomicBoolean busy = new AtomicBoolean(true);
        volatile long touched = now(), offset; long size; InputStream input; volatile boolean closed;
        Download(ServerPlayer p, String hash, UUID id, UUID request, long generation) {
            player = p; this.hash = hash; this.id = id; this.request = request; this.generation = generation;
        }
    }
}
