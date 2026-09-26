package com.kuronami.musicdiscmaker.gametest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.audio.local.LocalAudioServer;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
//? if <1.21.2 {
/*import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
*///?}

/**
 * local MP3 の server 側転送境界 ({@link LocalAudioServer}) の同時実行と失敗時掃除を固定する。
 *
 * <p>この面は通常 build では一切見えない: payload は {@link CapturingNetwork} でしか観測できず、
 * 失敗は「次に再生が必要になった時にだけ音が出ない」として後で表面化する。2 本立てで、
 * 受信枠の使い分けと、期限切れ upload の解放が送信成否に依存しないことを実装の実振る舞いで固定する。
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
    //? if <1.21.2 {
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
     * 期限切れ upload の解放が、ERROR 通知の送信成功に依存しないこと。送信が投げても
     * upload slot と store 側の仮転送が解放され、次の BEGIN が受理されること。
     */
    //? if <1.21.2 {
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
        helper.startSequence()
                .thenExecute(() -> guardedStep(original, () -> {
                    original[0] = Services.swapNetwork(network);
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
                        Services.swapNetwork(original[0]);
                        uploaderA.discard();
                        uploaderB.discard();
                        uploaderC.discard();
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
        helper.assertTrue(ready, "gen=" + generation + " の READY が届いていない。捕獲送信: "
                + describe(sent) + " io: " + describeIoThread());
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

    /** local audio の io worker の状態を failure メッセージへ入れる (失敗時の診断)。 */
    private static String describeIoThread() {
        for (final Thread thread : Thread.getAllStackTraces().keySet()) {
            if ("mdm-local-audio-server".equals(thread.getName())) {
                final StringBuilder out = new StringBuilder(thread.getState().name());
                for (final StackTraceElement frame : thread.getStackTrace()) out.append("\n  ").append(frame);
                return out.toString();
            }
        }
        return "no mdm-local-audio-server thread";
    }

    private static LocalAudioPayload fetch(UUID request, String hash) {
        return LocalAudioPayload.control(LocalAudioPayload.FETCH, request, 0, 0, hash, 0, 0, "");
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

    private static String hashOf(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
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
