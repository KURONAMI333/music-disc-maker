package com.kuronami.musicdiscmaker.gametest;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mojang.authlib.GameProfile;
import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.audio.local.LocalAudioServer;
import com.kuronami.musicdiscmaker.audio.local.LocalAudioStore;
import com.kuronami.musicdiscmaker.block.MusicDiscMakerBlockEntity;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.menu.MusicDiscMakerMenu;
import com.kuronami.musicdiscmaker.network.LocalAudioPayload;
import com.kuronami.musicdiscmaker.network.ModPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.platform.services.INetworkHelper;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
//? if <1.21.11 {
/*import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
*///?}

/**
 * local MP3 の server 側転送境界 ({@link LocalAudioServer}) の同時実行と失敗時掃除を固定する。
 *
 * <p>この面は通常 build では一切見えない: payload は {@link CapturingNetwork} でしか観測できず、
 * 失敗は「次に再生が必要になった時にだけ音が出ない」として後で表面化する。fetch/download の世代・
 * request対応、upload/download枠の解放、エラー応答を実server経路の振る舞いで固定する。
 */
public final class LocalAudioTransferGameTests {
    private static final BlockPos MAKER_POS = new BlockPos(3, 1, 3);

    private LocalAudioTransferGameTests() {
    }

    /**
     * 1 listener へ同一 tick に届いた別 hash の FETCH が、BEGIN 用の 250ms 連投制限に
     * 巻き込まれて busy にならないこと。同時 download は 4 本までで、超過・無許可・重複は
     * 引き続き拒否されること。
     */
    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", batch = "localAudioFetch", timeoutTicks = 200)
    */
    //?}
    public static void sameTickFetchOfDistinctHashesIsNotThrottled(GameTestHelper helper) {
        final FakePlayer listener = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "local-audio-fetch"));
        final CapturingNetwork network = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(network);
        try {
            final List<String> hashes = audioFixtures(helper, 5);
            final List<UUID> granted = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                LocalAudioServer.allow(listener,
                        CustomTrackData.local(hashes.get(i), "track-" + i, "fixture", 60_000));
                final UUID request = UUID.randomUUID();
                granted.add(request);
                LocalAudioServer.receive(fetch(request, hashes.get(i)), listener);
            }
            LocalAudioServer.allow(listener,
                    CustomTrackData.local(hashes.get(4), "track-4", "fixture", 60_000));
            final UUID overLimit = UUID.randomUUID();
            LocalAudioServer.receive(fetch(overLimit, hashes.get(4)), listener);
            final UUID ungranted = UUID.randomUUID();
            LocalAudioServer.receive(fetch(ungranted,
                    "0000000000000000000000000000000000000000000000000000000000000000"), listener);
            final UUID duplicate = UUID.randomUUID();
            LocalAudioServer.receive(fetch(duplicate, hashes.get(0)), listener);

            final List<LocalAudioPayload> errors = network.of(LocalAudioPayload.class).stream()
                    .map(CapturingNetwork.Sent::payload).map(LocalAudioPayload.class::cast)
                    .filter(p -> p.operation() == LocalAudioPayload.ERROR).toList();
            for (final UUID request : granted) {
                final String reason = errors.stream().filter(p -> request.equals(p.id()))
                        .map(LocalAudioPayload::name).findFirst().orElse(null);
                helper.assertTrue(reason == null,
                        "同一 tick の別 hash FETCH が " + reason + " で拒否された"
                                + " (download 枠に空きがある限り受理される設計)");
            }
            helper.assertTrue(errors.stream()
                            .anyMatch(p -> overLimit.equals(p.id()) && "busy".equals(p.name())),
                    "同時4本を超える5本目の FETCH が busy で止まらなかった");
            helper.assertTrue(errors.stream()
                            .anyMatch(p -> ungranted.equals(p.id()) && "missing".equals(p.name())),
                    "許可の無い hash への FETCH が missing で拒否されなかった");
            helper.assertTrue(errors.stream()
                            .anyMatch(p -> duplicate.equals(p.id()) && "busy".equals(p.name())),
                    "転送中の同一 hash への重複 FETCH が dedup で止まらなかった");
            helper.assertTrue(errors.size() == 3, "ERROR 応答がちょうど3件でない: " + errors.size());
        } finally {
            Services.swapNetwork(previous);
            listener.discard();
        }
        helper.succeed();
    }

    /**
     * メニュー無し・不正 hash の BEGIN が黙って捨てられず ERROR で応答されること。
     * 応答が無いと client は transferring のまま残る。
     */
    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", batch = "localAudioBegin", timeoutTicks = 200)
    */
    //?}
    public static void invalidBeginIsAnsweredWithError(GameTestHelper helper) {
        final MusicDiscMakerBlockEntity maker = maker(helper);
        final BlockPos absolute = helper.absolutePos(MAKER_POS);
        // BEGIN は 250ms の per-player 入口制限があるので、各腕は別 player で打つ。
        final FakePlayer playerA = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "local-audio-begin-a"));
        final FakePlayer playerB = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "local-audio-begin-b"));
        playerA.setPos(absolute.getX() + 0.5D, absolute.getY() + 0.5D, absolute.getZ() + 1.5D);
        playerB.setPos(absolute.getX() + 0.5D, absolute.getY() + 0.5D, absolute.getZ() + 1.5D);
        final CapturingNetwork network = new CapturingNetwork();
        final INetworkHelper previous = Services.swapNetwork(network);
        try {
            // (a) メニューを開いていない状態の BEGIN → disc
            LocalAudioServer.receive(begin(999, 1L, hashOf("no-menu")), playerA);
            // (b) メニューは有効だが hash が形式違反の BEGIN → disc
            playerB.containerMenu = new MusicDiscMakerMenu(55, playerB.getInventory(), maker);
            LocalAudioServer.receive(LocalAudioPayload.control(LocalAudioPayload.BEGIN,
                    LocalAudioPayload.NONE, 55, 2L, "not-a-hex-hash", 0, 1024, "fixture"), playerB);
            final List<LocalAudioPayload> errors = network.of(LocalAudioPayload.class).stream()
                    .map(CapturingNetwork.Sent::payload).map(LocalAudioPayload.class::cast)
                    .filter(p -> p.operation() == LocalAudioPayload.ERROR).toList();
            helper.assertTrue(errors.stream()
                            .anyMatch(p -> p.generation() == 1L && "disc".equals(p.name())),
                    "メニューの無い BEGIN が disc で応答されなかった");
            helper.assertTrue(errors.stream()
                            .anyMatch(p -> p.generation() == 2L && "disc".equals(p.name())),
                    "hash 不正の BEGIN が disc で応答されなかった");
            helper.assertTrue(errors.size() == 2, "BEGIN 拒否の応答がちょうど2件でない: " + errors.size());
        } finally {
            Services.swapNetwork(previous);
            playerA.discard();
            playerB.discard();
        }
        helper.succeed();
    }

    /**
     * 期限切れ upload の解放が、ERROR 通知の送信成功に依存しないこと。送信が投げても
     * upload slot と store 側の仮転送が解放され、次の BEGIN が受理されること。
     */
    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", batch = "localAudioUpload", timeoutTicks = 400)
    */
    //?}
    public static void expiredUploadsReleaseTheirSlotWhenNotificationFails(GameTestHelper helper) {
        final MinecraftServer server = helper.getLevel().getServer();
        final MusicDiscMakerBlockEntity maker = maker(helper);
        maker.setItem(MusicDiscMakerBlockEntity.SLOT_INPUT, new ItemStack(ModItems.BLANK_DISC.get()));
        final BlockPos absolute = helper.absolutePos(MAKER_POS);
        final FakePlayer uploaderA = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "local-audio-a"));
        final FakePlayer uploaderB = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "local-audio-b"));
        uploaderA.setPos(absolute.getX() + 0.5D, absolute.getY() + 0.5D, absolute.getZ() + 1.5D);
        uploaderB.setPos(absolute.getX() + 0.5D, absolute.getY() + 0.5D, absolute.getZ() + 1.5D);
        final FakePlayer uploaderC = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "local-audio-c"));
        uploaderC.setPos(absolute.getX() + 0.5D, absolute.getY() + 0.5D, absolute.getZ() + 1.5D);
        final CapturingNetwork network = new CapturingNetwork();
        final ThrowingSendNetwork throwing = new ThrowingSendNetwork();
        final INetworkHelper[] original = new INetworkHelper[1];
        final Path incoming = server.getWorldPath(LevelResource.ROOT)
                .resolve("music_disc_maker").resolve("audio").resolve("incoming");
        final boolean[] fakePlayerRegistered = new boolean[3];
        final AtomicBoolean cleanupDone = new AtomicBoolean();
        helper.testInfo.addListener(new GameTestListener() {
            @Override public void testStructureLoaded(GameTestInfo info) { }
            @Override public void testPassed(GameTestInfo info, GameTestRunner runner) {
                cleanupExpiredUploadTest(server, new ServerPlayer[]{uploaderA, uploaderB, uploaderC},
                        original, fakePlayerRegistered, cleanupDone);
            }
            @Override public void testFailed(GameTestInfo info, GameTestRunner runner) {
                cleanupExpiredUploadTest(server, new ServerPlayer[]{uploaderA, uploaderB, uploaderC},
                        original, fakePlayerRegistered, cleanupDone);
            }
            @Override public void testAddedForRerun(GameTestInfo oldInfo, GameTestInfo newInfo,
                    GameTestRunner runner) {
                cleanupExpiredUploadTest(server, new ServerPlayer[]{uploaderA, uploaderB, uploaderC},
                        original, fakePlayerRegistered, cleanupDone);
            }
        });
        helper.startSequence()
                .thenExecute(() -> guardedStep(original, () -> {
                    original[0] = Services.swapNetwork(network);
                    fakePlayerRegistered[0] = true;
                    registerConnectedFakePlayer(server.getPlayerList(), uploaderA);
                    fakePlayerRegistered[1] = true;
                    registerConnectedFakePlayer(server.getPlayerList(), uploaderB);
                    fakePlayerRegistered[2] = true;
                    registerConnectedFakePlayer(server.getPlayerList(), uploaderC);
                    uploaderA.containerMenu = new MusicDiscMakerMenu(71, uploaderA.getInventory(), maker);
                    LocalAudioServer.receive(begin(71, 1L, hashOf("upload-a")), uploaderA);
                }))
                // io worker の prepare と server.execute 経由の READY を実到着で待つ。
                // GameTestServer の tick は実時間でほぼ0のため、固定 idle では足りない。
                .thenWaitUntil(() -> waitingStep(original,
                        () -> assertReady(helper, network, uploaderA, 1L)))
                .thenExecute(() -> guardedStep(original,
                        () -> expireUploadWithFailedSend(helper, uploaderA, server, network, throwing, 1)))
                .thenExecute(() -> guardedStep(original, () -> {
                    uploaderB.containerMenu = new MusicDiscMakerMenu(72, uploaderB.getInventory(), maker);
                    LocalAudioServer.receive(begin(72, 2L, hashOf("upload-b")), uploaderB);
                }))
                .thenWaitUntil(() -> waitingStep(original,
                        () -> assertReady(helper, network, uploaderB, 2L)))
                .thenExecute(() -> guardedStep(original,
                        () -> expireUploadWithFailedSend(helper, uploaderB, server, network, throwing, 2)))
                // 仮受信ファイルが消える = discard が io worker で走り切った。
                // map からの remove はその直後に server.execute へ積まれる (数 tick 遅れる)。
                .thenWaitUntil(() -> waitingStep(original,
                        () -> assertIncomingEmpty(helper, incoming)))
                .thenIdle(8)
                .thenExecute(() -> guardedStep(original, () -> {
                    // 漏れた slot は全 player の BEGIN を止めるので、第3の player で触る。
                    uploaderC.containerMenu = new MusicDiscMakerMenu(73, uploaderC.getInventory(), maker);
                    LocalAudioServer.receive(begin(73, 3L, hashOf("upload-c")), uploaderC);
                    assertNoError(helper, network, uploaderC, 3L,
                            "stranded upload が map に残っている (receive が即座に拒否した)");
                }))
                .thenWaitUntil(() -> waitingStep(original,
                        () -> assertReady(helper, network, uploaderC, 3L)))
                .thenExecute(() -> guardedStep(original, () -> {
                    try {
                        assertNoError(helper, network, uploaderC, 3L, "3つ目の BEGIN が ERROR で拒否された");
                        helper.assertTrue(throwing.attempted.size() == 2,
                                "期限切れ通知の送信失敗を2回踏んでいない: " + throwing.attempted.size());
                        helper.assertTrue(throwing.attempted.stream()
                                        .allMatch(p -> p instanceof LocalAudioPayload local
                                                && local.operation() == LocalAudioPayload.ERROR
                                                && "expired".equals(local.name())),
                                "期限切れの掃除が意図しない payload を送った: "
                                        + throwing.attempted.size());
                    } finally {
                        cleanupExpiredUploadTest(server, new ServerPlayer[]{uploaderA, uploaderB, uploaderC},
                                original, fakePlayerRegistered, cleanupDone);
                    }
                }))
                .thenSucceed();
    }

    /**
     * 実際のserver FETCHでDATAを送った後に60秒idle timeoutを発火し、ERRORがFETCH request UUID・
     * hash・generationを保持すること。同じgenerationで再FETCHした後、次のreadをIO境界で失敗させ、
     * download_failedもそのrequest UUID/hash/generationを保持すること。
     *
     * <p>GameTestServerはtickが実時間をほとんど消費しないため、実秒待ちの代わりに実際に作られた
     * Downloadのtouchedだけを閾値より過去へ設定する。timeout分岐、payload生成、network送信、close/removal
     * は製品のLocalAudioServer.tick/NEXT経路を通す。FakePlayerは接続identity判定を成立させるため、
     * このGameTestの間だけPlayerListのUUID lookupへ登録し、最後に除去する。
     */
    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", batch = "localAudioDownload", timeoutTicks = 700)
    */
    //?}
    public static void downloadTimeoutAndReadFailureKeepRequestGeneration(GameTestHelper helper) {
        final MinecraftServer server = helper.getLevel().getServer();
        final FakePlayer listener = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), "local-audio-download-timeout"));
        final CapturingNetwork network = new CapturingNetwork();
        final INetworkHelper[] previous = new INetworkHelper[1];
        final boolean[] fakePlayerRegistered = {false};
        final AtomicBoolean cleanupDone = new AtomicBoolean();
        final UUID timeoutRequest = UUID.randomUUID();
        final UUID failedReadRequest = UUID.randomUUID();
        final long generation = 37L;
        final String hash = largeAudioFixture(helper);
        final int[] dataCount = {0};

        // GameTestの失敗・timeout listenerはfinal stepまで進まないケースも含め、残ったglobal stateを戻す。
        helper.testInfo.addListener(new GameTestListener() {
            @Override public void testStructureLoaded(GameTestInfo info) { }
            @Override public void testPassed(GameTestInfo info, GameTestRunner runner) {
                cleanupDownloadTest(server, listener, previous, fakePlayerRegistered, cleanupDone);
            }
            @Override public void testFailed(GameTestInfo info, GameTestRunner runner) {
                cleanupDownloadTest(server, listener, previous, fakePlayerRegistered, cleanupDone);
            }
            @Override public void testAddedForRerun(GameTestInfo oldInfo, GameTestInfo newInfo,
                    GameTestRunner runner) {
                cleanupDownloadTest(server, listener, previous, fakePlayerRegistered, cleanupDone);
            }
        });

        helper.startSequence()
                .thenExecute(() -> guardedStep(previous, () -> {
                    previous[0] = Services.swapNetwork(network);
                    fakePlayerRegistered[0] = true;
                    registerConnectedFakePlayer(server.getPlayerList(), listener);
                    LocalAudioServer.allow(listener,
                            CustomTrackData.local(hash, "timeout-fixture", "fixture", 60_000));
                    LocalAudioServer.receive(fetch(timeoutRequest, generation, hash), listener);
                }))
                .thenWaitUntil(() -> waitingStep(previous,
                        () -> assertDataReceived(helper, network, listener, hash, dataCount)))
                .thenExecute(() -> guardedStep(previous, () -> {
                    expireDownloadWithoutWaiting(server, timeoutRequest);
                    assertDownloadError(helper, network, listener, timeoutRequest, generation,
                            hash, "download_timeout");
                }))
                .thenWaitUntil(() -> waitingStep(previous,
                        () -> assertDownloadRemoved(helper, server, timeoutRequest)))
                .thenExecute(() -> guardedStep(previous, () ->
                        LocalAudioServer.receive(fetch(failedReadRequest, generation, hash), listener)))
                .thenWaitUntil(() -> waitingStep(previous,
                        () -> assertAdditionalDataReceived(helper, network, listener, hash, dataCount)))
                .thenExecute(() -> guardedStep(previous, () -> {
                    final DownloadHandle handle = findDownload(server, failedReadRequest);
                    replaceDownloadInputWithFailure(handle.download());
                    LocalAudioServer.receive(LocalAudioPayload.control(LocalAudioPayload.NEXT,
                            handle.transferId(), 0, generation, hash, handle.offset(), 0, ""), listener);
                }))
                .thenWaitUntil(() -> waitingStep(previous, () ->
                        assertDownloadError(helper, network, listener, failedReadRequest, generation,
                                hash, "download_failed")))
                .thenWaitUntil(() -> waitingStep(previous,
                        () -> assertDownloadRemoved(helper, server, failedReadRequest)))
                .thenExecute(() -> guardedStep(previous, () -> {
                    try {
                        assertDownloadError(helper, network, listener, timeoutRequest, generation,
                                hash, "download_timeout");
                        assertDownloadError(helper, network, listener, failedReadRequest, generation,
                                hash, "download_failed");
                    } finally {
                        cleanupDownloadTest(server, listener, previous, fakePlayerRegistered, cleanupDone);
                    }
                }))
                .thenSucceed();
    }

    /** READY が捕獲されるまで待つ。READY = begin→prepare→server.execute 経路の完走を示す。 */
    private static void assertReady(GameTestHelper helper, CapturingNetwork network,
            ServerPlayer player, long generation) {
        final List<CapturingNetwork.Sent> sent = network.of(LocalAudioPayload.class);
        final boolean ready = sent.stream().anyMatch(s -> s.payload() instanceof LocalAudioPayload p
                && p.operation() == LocalAudioPayload.READY && p.generation() == generation
                && s.player() == player);
        helper.assertTrue(ready, "READY未受信 gen=" + generation + " payloads=" + sent.size()
                + " io=" + describeIoThread());
    }

    private static void assertDataReceived(GameTestHelper helper, CapturingNetwork network,
            ServerPlayer player, String hash, int[] observedCount) {
        assertAdditionalDataReceived(helper, network, player, hash, observedCount);
    }

    private static void assertAdditionalDataReceived(GameTestHelper helper, CapturingNetwork network,
            ServerPlayer player, String hash, int[] observedCount) {
        final long count = network.of(LocalAudioPayload.class).stream()
                .filter(s -> s.player() == player && s.payload() instanceof LocalAudioPayload p
                        && p.operation() == LocalAudioPayload.DATA && hash.equals(p.hash()))
                .count();
        helper.assertTrue(count > observedCount[0], "実server FETCHのDATAが届いていない。hash=" + hash
                + " count=" + count + " io: " + describeIoThread());
        observedCount[0] = (int) count;
    }

    private static void assertDownloadError(GameTestHelper helper, CapturingNetwork network,
            ServerPlayer player, UUID request, long generation, String hash, String reason) {
        final LocalAudioPayload error = network.of(LocalAudioPayload.class).stream()
                .filter(s -> s.player() == player && s.payload() instanceof LocalAudioPayload p
                        && p.operation() == LocalAudioPayload.ERROR && request.equals(p.id()))
                .map(CapturingNetwork.Sent::payload).map(LocalAudioPayload.class::cast)
                .findFirst().orElse(null);
        helper.assertTrue(error != null, "request=" + request + " のERRORが未送信: " + describe(
                network.of(LocalAudioPayload.class)) + " io: " + describeIoThread());
        helper.assertTrue(error.generation() == generation,
                reason + " ERRORがgenerationを保持していない: " + error.generation());
        helper.assertTrue(hash.equals(error.hash()), reason + " ERRORがhashを保持していない: " + error.hash());
        helper.assertTrue(reason.equals(error.name()), "ERROR reasonが " + reason + " ではない: " + error.name());
    }

    private static void expireDownloadWithoutWaiting(MinecraftServer server, UUID request) {
        try {
            final DownloadHandle handle = findDownload(server, request);
            final long idleTimeoutMs = (long) field(LocalAudioServer.class, "DOWNLOAD_IDLE_TIMEOUT_MS").get(null);
            field(handle.download().getClass(), "touched").setLong(handle.download(),
                    System.nanoTime() / 1_000_000L - idleTimeoutMs - 1L);
            LocalAudioServer.tick(server);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("実server Downloadのidle境界を進められない", e);
        }
    }

    private static void assertDownloadRemoved(GameTestHelper helper, MinecraftServer server, UUID request) {
        final boolean active = downloads(server).values().stream().anyMatch(d -> {
            try { return request.equals(field(d.getClass(), "request").get(d)); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        });
        helper.assertFalse(active, "closeDownloadのIO/server cleanup境界を完了していない: " + request);
    }

    private static DownloadHandle findDownload(MinecraftServer server, UUID request) {
        for (final Object download : downloads(server).values()) {
            try {
                if (request.equals(field(download.getClass(), "request").get(download))) {
                    return new DownloadHandle(download,
                            (UUID) field(download.getClass(), "id").get(download),
                            field(download.getClass(), "offset").getLong(download));
                }
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("LocalAudioServer.Download境界を読めない", e);
            }
        }
        throw new IllegalStateException("active server downloadが無い: " + request);
    }

    private static Map<?, ?> downloads(MinecraftServer server) {
        try {
            final Map<?, ?> states = (Map<?, ?>) field(LocalAudioServer.class, "STATES").get(null);
            final Object state = states.get(server);
            if (state == null) throw new IllegalStateException("LocalAudioServer stateが無い");
            return (Map<?, ?>) field(state.getClass(), "downloads").get(state);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("LocalAudioServer state/download mapを読めない", e);
        }
    }

    private static void replaceDownloadInputWithFailure(Object download) {
        try {
            final InputStream original = (InputStream) field(download.getClass(), "input").get(download);
            if (original != null) original.close();
            field(download.getClass(), "input").set(download, new InputStream() {
                @Override public int read() throws IOException { throw new IOException("fixture read failure"); }
            });
        } catch (ReflectiveOperationException | IOException e) {
            throw new IllegalStateException("実server Download input境界を置き換えられない", e);
        }
    }

    private static void cleanupExpiredUploadTest(MinecraftServer server, ServerPlayer[] players,
            INetworkHelper[] previous, boolean[] fakePlayerRegistered, AtomicBoolean cleanupDone) {
        if (!cleanupDone.compareAndSet(false, true)) return;
        try {
            if (previous[0] != null) Services.swapNetwork(previous[0]);
        } finally {
            try {
                for (int i = 0; i < players.length; i++) {
                    if (fakePlayerRegistered[i]) {
                        unregisterConnectedFakePlayer(server.getPlayerList(), players[i]);
                        fakePlayerRegistered[i] = false;
                    }
                }
                // Disconnect cleanup closes a transfer left behind when an assertion fails or times out.
                LocalAudioServer.tick(server);
            } finally {
                for (ServerPlayer player : players) player.discard();
            }
        }
    }

    private static void cleanupDownloadTest(MinecraftServer server, ServerPlayer player,
            INetworkHelper[] previous, boolean[] fakePlayerRegistered, AtomicBoolean cleanupDone) {
        if (!cleanupDone.compareAndSet(false, true)) return;
        try {
            if (previous[0] != null) Services.swapNetwork(previous[0]);
        } finally {
            try {
                if (fakePlayerRegistered[0]) {
                    unregisterConnectedFakePlayer(server.getPlayerList(), player);
                    fakePlayerRegistered[0] = false;
                    // disconnect branch closes any download left by an assertion failure or test timeout.
                    LocalAudioServer.tick(server);
                }
            } finally {
                player.discard();
            }
        }
    }

    private static void registerConnectedFakePlayer(PlayerList players, ServerPlayer player) {
        try {
            final Field mapField = field(PlayerList.class, "playersByUUID");
            @SuppressWarnings("unchecked") final Map<UUID, ServerPlayer> byUuid =
                    (Map<UUID, ServerPlayer>) mapField.get(players);
            byUuid.put(player.getUUID(), player);
            if (players.getPlayer(player.getUUID()) != player)
                throw new IllegalStateException("PlayerList current-player identity did not retain FakePlayer");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("GameTest FakePlayerをconnected identityへ登録できない", e);
        }
    }

    private static void unregisterConnectedFakePlayer(PlayerList players, ServerPlayer player) {
        try {
            @SuppressWarnings("unchecked") final Map<UUID, ServerPlayer> byUuid =
                    (Map<UUID, ServerPlayer>) field(PlayerList.class, "playersByUUID").get(players);
            byUuid.remove(player.getUUID(), player);
            if (players.getPlayer(player.getUUID()) == player)
                throw new IllegalStateException("PlayerList still exposes removed FakePlayer");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("GameTest FakePlayerをconnected identityから戻せない", e);
        }
    }

    private static Field field(Class<?> owner, String name) throws ReflectiveOperationException {
        final Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private record DownloadHandle(Object download, UUID transferId, long offset) {
    }

    /** 指定 generation への ERROR が捕獲されていないことを確認する。 */
    private static void assertNoError(GameTestHelper helper, CapturingNetwork network,
            ServerPlayer player, long generation, String message) {
        final List<CapturingNetwork.Sent> sent = network.of(LocalAudioPayload.class);
        final boolean rejected = sent.stream().anyMatch(s -> s.payload() instanceof LocalAudioPayload p
                && p.operation() == LocalAudioPayload.ERROR && p.generation() == generation
                && s.player() == player);
        helper.assertFalse(rejected, message + " 捕獲送信: " + describe(sent));
    }

    /** store の仮受信ファイルが全て消えた = discard が io worker で走り切った。 */
    private static void assertIncomingEmpty(GameTestHelper helper, Path incoming) {
        long parts;
        try (var files = Files.list(incoming)) {
            parts = files.filter(f -> f.getFileName().toString().startsWith("upload-")).count();
        } catch (IOException e) {
            throw new IllegalStateException("incoming を読めない: " + incoming, e);
        }
        helper.assertTrue(parts == 0, "仮受信ファイルが残っている: " + parts + "件 (io: " + describeIoThread() + ")");
    }

    private static String describe(List<CapturingNetwork.Sent> sent) {
        final StringBuilder out = new StringBuilder();
        for (final CapturingNetwork.Sent s : sent) {
            if (s.payload() instanceof LocalAudioPayload p) {
                out.append("[op=").append(p.operation()).append(" gen=").append(p.generation())
                        .append(" name=").append(p.name()).append(']');
            }
        }
        return out.toString();
    }

    /** writable book の診断上限内に収めるため、worker状態と先頭フレームだけを記録する。 */
    private static String describeIoThread() {
        for (final Thread thread : Thread.getAllStackTraces().keySet()) {
            if ("mdm-local-audio-server".equals(thread.getName())) {
                final StackTraceElement[] stack = thread.getStackTrace();
                return thread.getState().name() + (stack.length == 0 ? "" : " @ " + stack[0]);
            }
        }
        return "no io worker";
    }

    private static LocalAudioPayload fetch(UUID request, String hash) {
        return LocalAudioPayload.control(LocalAudioPayload.FETCH, request, 0, 0, hash, 0, 0, "");
    }

    private static LocalAudioPayload fetch(UUID request, long generation, String hash) {
        return LocalAudioPayload.control(LocalAudioPayload.FETCH, request, 0, generation, hash, 0, 0, "");
    }

    private static LocalAudioPayload begin(int containerId, long generation, String hash) {
        return LocalAudioPayload.control(LocalAudioPayload.BEGIN, LocalAudioPayload.NONE,
                containerId, generation, hash, 0, 1024, "fixture");
    }

    /** 画面を閉じた扱いにしてから、送信が必ず失敗する network の下で掃除 tick を回す。 */
    private static void expireUploadWithFailedSend(GameTestHelper helper, ServerPlayer player,
            MinecraftServer server, CapturingNetwork network, ThrowingSendNetwork throwing, int expected) {
        player.containerMenu = player.inventoryMenu;
        Services.swapNetwork(throwing);
        try {
            LocalAudioServer.tick(server);
        } finally {
            Services.swapNetwork(network);
        }
        helper.assertTrue(throwing.attempted.size() == expected,
                "期限切れ通知の送信が試行されていない (送信失敗経路を踏む前提条件を作れていない): "
                        + throwing.attempted.size());
    }

    /** step が失敗しても差し替えた network を元に戻す (後続 batch の送信を奪わない)。 */
    private static void guardedStep(INetworkHelper[] restoreTo, Runnable body) {
        try {
            body.run();
        } catch (Throwable t) {
            if (restoreTo[0] != null) Services.swapNetwork(restoreTo[0]);
            throw t;
        }
    }

    /** thenWaitUntil 用: 断言失敗は待ち続け、それ以外は network を戻して即座に失敗させる。 */
    private static void waitingStep(INetworkHelper[] restoreTo, Runnable body) {
        try {
            body.run();
        } catch (net.minecraft.gametest.framework.GameTestAssertException stillWaiting) {
            throw stillWaiting;
        } catch (Throwable t) {
            if (restoreTo[0] != null) Services.swapNetwork(restoreTo[0]);
            throw t;
        }
    }

    private static MusicDiscMakerBlockEntity maker(GameTestHelper helper) {
        helper.setBlock(MAKER_POS, ModBlocks.MUSIC_DISC_MAKER.get().defaultBlockState());
        final MusicDiscMakerBlockEntity maker = (MusicDiscMakerBlockEntity) helper.getLevel()
                .getBlockEntity(helper.absolutePos(MAKER_POS));
        helper.assertTrue(maker != null, "Music Disc Maker BEを生成できていない");
        return maker;
    }

    /** 内容が hash と一致する原本だけを、ワールドの audio store 領域へ直接置く。 */
    private static List<String> audioFixtures(GameTestHelper helper, int count) {
        final Path audioDir = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT)
                .resolve("music_disc_maker").resolve("audio");
        final List<String> hashes = new ArrayList<>();
        try {
            Files.createDirectories(audioDir);
            for (int i = 0; i < count; i++) {
                final byte[] bytes = ("mdm-gametest-local-audio-" + i).getBytes(StandardCharsets.UTF_8);
                final String hash = hashOf(new String(bytes, StandardCharsets.UTF_8));
                Files.write(audioDir.resolve(hash + ".mp3"), bytes);
                hashes.add(hash);
            }
        } catch (IOException e) {
            throw new IllegalStateException("local audio fixture を書けない", e);
        }
        return hashes;
    }

    /** 1 DATAの後にもNEXT readが残るように、actual server stream用の2 chunk fixtureを置く。 */
    private static String largeAudioFixture(GameTestHelper helper) {
        final byte[] bytes = new byte[LocalAudioStore.CHUNK_BYTES * 2 + 17];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 31 + 7);
        final String hash = hashOf(bytes);
        final Path audioDir = helper.getLevel().getServer().getWorldPath(LevelResource.ROOT)
                .resolve("music_disc_maker").resolve("audio");
        try {
            Files.createDirectories(audioDir);
            Files.write(audioDir.resolve(hash + ".mp3"), bytes);
        } catch (IOException e) {
            throw new IllegalStateException("download fixtureを書けない", e);
        }
        return hash;
    }

    private static String hashOf(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hashOf(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** sendToPlayer で記録してから投げる。実際の送信経路で発生する失敗を再現する。 */
    private static final class ThrowingSendNetwork implements INetworkHelper {
        private final List<ModPayload> attempted = new ArrayList<>();

        @Override
        public void sendToServer(ModPayload payload) {
        }

        @Override
        public void sendToPlayer(ServerPlayer player, ModPayload payload) {
            attempted.add(payload);
            throw new IllegalStateException("simulated send failure");
        }

        @Override
        public void sendToPlayersTrackingChunk(ServerLevel level, ChunkPos chunk, ModPayload payload) {
        }
    }
}
