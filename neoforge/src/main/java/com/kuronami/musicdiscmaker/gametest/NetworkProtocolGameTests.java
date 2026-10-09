package com.kuronami.musicdiscmaker.gametest;

//? if >=1.21.2 {
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.network.ConfigureJukeboxPayload;
import com.kuronami.musicdiscmaker.network.ModNetwork;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.ResolveUrlPayload;
import com.kuronami.musicdiscmaker.network.SeekJukeboxPayload;
import com.kuronami.musicdiscmaker.network.NavigateJukeboxPayload;
import com.kuronami.musicdiscmaker.network.ShuffleJukeboxPayload;
import com.kuronami.musicdiscmaker.network.ControlBoomboxPayload;
import com.kuronami.musicdiscmaker.network.BoomboxStatePayload;
import com.kuronami.musicdiscmaker.network.BoomboxPlayPayload;
import com.kuronami.musicdiscmaker.network.BoomboxStopPayload;
import com.kuronami.musicdiscmaker.component.BoomboxPlaybackState;
import com.kuronami.musicdiscmaker.component.PlaybackCursor;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.network.LocalAudioPayload;
import com.kuronami.musicdiscmaker.network.ModPayloadTypes;
import com.kuronami.musicdiscmaker.network.VersionPayload;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.codec.StreamCodec;
//?} else {
/*import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.compat.aeronautics.SubLevelPlayDiscPayload;
import com.kuronami.musicdiscmaker.compat.aeronautics.SubLevelStopDiscPayload;
import com.kuronami.musicdiscmaker.compat.create.ContraptionPlayDiscPayload;
import com.kuronami.musicdiscmaker.compat.sophisticatedcore.BackpackPlayDiscPayload;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.network.ConfigureJukeboxPayload;
import com.kuronami.musicdiscmaker.network.ModNetwork;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.ResolveUrlPayload;
import com.kuronami.musicdiscmaker.network.SeekJukeboxPayload;
import com.kuronami.musicdiscmaker.network.NavigateJukeboxPayload;
import com.kuronami.musicdiscmaker.network.ShuffleJukeboxPayload;
import com.kuronami.musicdiscmaker.network.ControlBoomboxPayload;
import com.kuronami.musicdiscmaker.network.BoomboxStatePayload;
import com.kuronami.musicdiscmaker.network.BoomboxPlayPayload;
import com.kuronami.musicdiscmaker.network.BoomboxStopPayload;
import com.kuronami.musicdiscmaker.component.BoomboxPlaybackState;
import com.kuronami.musicdiscmaker.component.PlaybackCursor;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.network.LocalAudioPayload;
import com.kuronami.musicdiscmaker.network.ModPayloadTypes;
import com.kuronami.musicdiscmaker.network.VersionPayload;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/^*
 * wire 形式と {@link ModNetwork#PROTOCOL_VERSION} を<b>組にして</b>固定する。
 *
 * <h2>ここが要る理由 — 版を上げ忘れると、接続エラーではなく無言の破損になる</h2>
 * 2.2.0 では {@code PlayDiscPayload} と {@code ConfigureJukeboxPayload} に指向性 (BOOL) が
 * 1 バイト増えたのに、NeoForge の登録は {@code registrar("1")} のままだった。NeoForge は
 * この文字列だけで互換を交渉するので、2.1.0 の client と 2.2.0 の server は
 * <b>「同じ版だ」と合意してから、食い違うバイト列を読み合う</b>。出るのは接続エラーではなく、
 * 座標や範囲の取り違え・切断という原因の見えない症状になる。
 *
 * <p>だから「codec を触ったら版を上げる」を人の注意力に任せない。ここではこの cell で
 * 登録される全 wire payload (compat 経由で登るものを含む) のバイト列の指紋と版を
 * 1 つの表にして、<b>どちらか片方だけが動いたら赤くする</b>。
 *
 * <ul>
 *   <li>codec にフィールドを足した / 型を変えた / 順番を変えた → 指紋が動く → 版を上げろと赤くなる</li>
 *   <li>版を下げた / 戻した → 版の突き合わせで赤くなる</li>
 * </ul>
 *
 * <p>更新の手順: wire を意図して変えたら、{@link #PROTOCOL_VERSION} を上げ、赤くなった実測値を
 * {@link #WIRE_FINGERPRINT} に書き写す。<b>指紋だけ書き換えて版を据え置くのは禁止</b> —
 * それがこのテストが捕まえたい唯一の操作。見本集合だけを変えた場合 (wire 形式は不変) は
 * 版を据え置いたまま指紋を採り直してよい。
 ^/
@GameTestHolder(MusicDiscMaker.MODID)
*///?}
//? if >=1.21.2 && <1.21.11 {
/*import com.kuronami.musicdiscmaker.MusicDiscMaker;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
*///?}

public class NetworkProtocolGameTests {

    private static final String TEMPLATE = "empty8x3x8";

    /** 3.1.0 公開版。16 は候補段階の wire で、曲の終わりによる停止の印 ({@code StopDiscPayload#naturalEnd})
        を足して 17、金ジューク付きトロッコを金ジュークの menu・操作へ統合して 18、ブームボックスの再生に
        バニラ・他 MOD の盤の曲を足して 19 にした。{@link ModNetwork#PROTOCOL_VERSION} と一致すること。 */
    private static final String PROTOCOL_VERSION = "19";

    /** 上の版に対応する wire 指紋 (下の見本をこの cell で登録される全 payload の codec で
        符号化した結果の SHA-256)。見本集合は cell 依存 — <1.21.2 では compat 経由の4型
        (sc_backpack_play / create_contraption_play / sable_sublevel_play / sable_sublevel_stop) が
        増えるので値が違う。codec 定義は全 cell 共通ソースなので、<1.21.2 側で載せれば
        他 cell の形式変更もここで検出できる。 */
    //? if <1.21.2 {
    /*private static final String WIRE_FINGERPRINT =
            "dcca4f49289324c8a788295a40e933dfadd55582027c8285a8b73cb521cab184";
    *///?} else {
    private static final String WIRE_FINGERPRINT =
            "b4c71adb5d107940665bd0b6a62445c6c107ae03522186c4fc2f2ba02fc86713";
    //?}

    /** 指紋の材料になる見本。<b>値を変えると指紋も変わる</b>ので、無闇に増減させないこと。
        見本の追加は wire 形式を変えないので版は据え置くが、指紋は採り直す。 */
    private static final BlockPos SAMPLE_POS = new BlockPos(12, -37, 480);

    private static final CustomTrackData SAMPLE_TRACK = new CustomTrackData(
            "https://www.youtube.com/watch?v=YOYeJn4mz8M", "Around the World", "Daft Punk",
            428_000L, "https://i.ytimg.com/vi/YOYeJn4mz8M/hqdefault.jpg", false);

    /**
     * <b>これが回帰テスト。</b> wire 形式と版が組のまま動いていないこと。
     *
     * <p>失敗したときのメッセージは「何を直すか」まで書く — 指紋が動いた = 版を上げる、
     * 版が動いた = 指紋を採り直す。
     */
    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    *///?}
    public static void wireFormatAndProtocolVersionMoveTogether(GameTestHelper helper) {
        helper.assertTrue(PROTOCOL_VERSION.equals(ModNetwork.PROTOCOL_VERSION),
                "protocol 版が " + ModNetwork.PROTOCOL_VERSION + " に動いている (このテストの想定は "
                        + PROTOCOL_VERSION + ")。wire を変えたなら指紋も採り直すこと。"
                        + " 下げた・戻したなら、古い client が新しい server に入れてしまう");

        final String actual = fingerprint();
        helper.assertTrue(WIRE_FINGERPRINT.equals(actual),
                "payload の wire 形式が変わっている (指紋 " + actual + ")。"
                        + " ModNetwork.PROTOCOL_VERSION を上げてから、この値を WIRE_FINGERPRINT に書き写すこと。"
                        + " 版を据え置くと、古い client と新しい server が同じ版だと合意して壊れたバイト列を読む");
        helper.succeed();
    }

    /**
     * 指向性が実際に wire に載っていること (指紋が「たまたま一致した」で通らないようにする対照)。
     * ここが落ちるのは指向性が codec から抜けた時なので、症状は「トグルが同期されない」。
     */
    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    *///?}
    public static void directionalSurvivesTheRoundTrip(GameTestHelper helper) {
        final var identity = new com.kuronami.musicdiscmaker.network.PlaybackSourceStamp(
                java.util.UUID.fromString("d89e535b-1266-4a97-a098-fcd66390a0b0"), 917L);
        final var stop = new StopDiscPayload(SAMPLE_POS, identity);
        helper.assertTrue(roundTrip(mdmCodec(StopDiscPayload::read), stop).equals(stop),
                "Stopのsource IDまたはgenerationが往復で失われた");
        final var speakers = new com.kuronami.musicdiscmaker.network.SpeakerSetPayload(
                SAMPLE_POS, 96, 70, false, java.util.List.of(), identity);
        helper.assertTrue(roundTrip(mdmCodec(com.kuronami.musicdiscmaker.network.SpeakerSetPayload::read), speakers).equals(speakers),
                "SpeakerSetのsource IDまたはgenerationが往復で失われた");
        final var vanilla = new com.kuronami.musicdiscmaker.network.PlayVanillaDiscPayload(SAMPLE_POS,
                new com.kuronami.musicdiscmaker.component.VanillaTrackData("minecraft:music_disc.cat", 180000L),
                5000L, 96, 70, false, identity);
        helper.assertTrue(roundTrip(mdmCodec(com.kuronami.musicdiscmaker.network.PlayVanillaDiscPayload::read), vanilla).equals(vanilla),
                "vanilla Playのsource IDまたはgenerationが往復で失われた");
        for (final boolean directional : new boolean[] {true, false}) {
            final PlayDiscPayload play =
                    new PlayDiscPayload(SAMPLE_POS, SAMPLE_TRACK, 1_000L, 96, 70, directional, identity);
            final PlayDiscPayload back = roundTrip(mdmCodec(PlayDiscPayload::read), play);
            helper.assertTrue(back.identity().equals(identity), "custom Playのsource IDまたはgenerationが往復で失われた");
            helper.assertTrue(back.directional() == directional,
                    "PlayDiscPayload の指向性が往復で " + back.directional() + " に化けている");
            helper.assertTrue(back.rangeBlocks() == 96 && back.volumePercent() == 70,
                    "指向性の隣のフィールドがずれている (codec の並びが食い違っている)");

            final ConfigureJukeboxPayload configure =
                    new ConfigureJukeboxPayload(SAMPLE_POS, 96, 70, true, false, directional);
            final ConfigureJukeboxPayload configureBack =
                    roundTrip(mdmCodec(ConfigureJukeboxPayload::read), configure);
            helper.assertTrue(configureBack.directional() == directional,
                    "ConfigureJukeboxPayload の指向性が往復で化けている");
            helper.assertTrue(configureBack.repeat() && !configureBack.paused(),
                    "repeat/paused が指向性とずれている (codec の並びが食い違っている)");
        }
        helper.succeed();
    }

    /**
     * <b>これが回帰テスト。</b> local_audio は双方向 payload であり、clientbound と
     * serverbound の両方へ handler が登録されていなければならない。
     *
     * <p>1.21.11 以降の {@code playBidirectional} 単一 handler 版は client 側 handler を
     * null 登録するだけで、client の ClientNetworkRegistry 検査が「clientbound handler
     * 未登録」で起動を止める (26.2 で実発生)。server 側の登録結果を NeoForge の
     * registry から直接確かめる。旧 API (1.21.1) は登録 record が handler を持つ。
     */
    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    *///?}
    public static void localAudioRegistersHandlersOnBothFlows(GameTestHelper helper) {
        try {
            //? if >=1.21.11 {
            for (final String field : new String[] {"SERVERBOUND_HANDLERS", "CLIENTBOUND_HANDLERS"}) {
                final var f = net.neoforged.neoforge.network.registration.NetworkRegistry.class
                        .getDeclaredField(field);
                f.setAccessible(true);
                final var byProtocol = (Map<?, ?>) f.get(null);
                final var handlers =
                        (Map<?, ?>) byProtocol.get(net.minecraft.network.ConnectionProtocol.PLAY);
                helper.assertTrue(handlers != null && handlers.containsKey(
                        com.kuronami.musicdiscmaker.network.ModPayloadTypes.LocalAudio.TYPE.id()),
                        "local_audio の " + field + " への登録がない"
                                + " (26.2 client 起動を止めた clientbound 未登録の欠陥)");
            }
            //?} else {
            /*final var f = net.neoforged.neoforge.network.registration.NetworkRegistry.class
                    .getDeclaredField("PAYLOAD_REGISTRATIONS");
            f.setAccessible(true);
            final var byProtocol = (Map<?, ?>) f.get(null);
            final var regs =
                    (Map<?, ?>) byProtocol.get(net.minecraft.network.ConnectionProtocol.PLAY);
            final var reg = regs == null
                    ? null
                    : regs.get(com.kuronami.musicdiscmaker.network.ModPayloadTypes.LocalAudio.TYPE.id());
            helper.assertTrue(reg != null
                            && reg.getClass().getMethod("handler").invoke(reg) != null,
                    "local_audio の登録または handler がない");
            *///?}
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException("NetworkRegistry の登録内容を読めなかった", e);
        }
        helper.succeed();
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    *///?}
    /** CHECK/AVAILABLE のopcodeと全フィールドのwire順を、登録codecの実往復で固定する。 */
    public static void localAudioCheckAndAvailableSurviveRegisteredRoundTrip(GameTestHelper helper) {
        final var requestId = java.util.UUID.fromString("01234567-89ab-cdef-fedc-ba9876543210");
        final String hash = "a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0fedcba9876543210";
        final byte[] data = new byte[] {0, 127, (byte) 128, (byte) 255, 42};

        // production定数は別にliteralで固定し、wire goldens側は常に独立したliteral期待値にする。
        helper.assertTrue(LocalAudioPayload.CHECK == 10, "CHECK opcode定数が10から変わった");
        helper.assertTrue(LocalAudioPayload.AVAILABLE == 11, "AVAILABLE opcode定数が11から変わった");
        final LocalAudioPayload check = new LocalAudioPayload(LocalAudioPayload.CHECK, requestId, 173,
                0x0102030405060708L, hash, 0x1122334455667788L, 0x1020304050607080L,
                "cache-check-contract.mp3", data);
        final LocalAudioPayload available = new LocalAudioPayload(LocalAudioPayload.AVAILABLE, requestId, 173,
                0x0102030405060708L, hash, 0x1122334455667788L, 0x1020304050607080L,
                "cache-check-contract.mp3", data);

        final String checkWire = "0a0123456789abcdeffedcba9876543210ad01010203040506070840"
                + "61316232633364346535663630373138323933613462356336643765386639303132333435363738396162636465663066656463626139383736353433323130"
                + "1122334455667788102030405060708018"
                + "63616368652d636865636b2d636f6e74726163742e6d703305007f80ff2a";
        final String availableWire = "0b" + checkWire.substring(2);

        assertLocalAudioContract(helper, check, 10, checkWire);
        assertLocalAudioContract(helper, available, 11, availableWire);
        helper.succeed();
    }

    private static void assertLocalAudioContract(GameTestHelper helper, LocalAudioPayload expected,
            int expectedOpcode, String expectedWireHex) {
        final byte[] encoded = encodeRegisteredLocalAudio(expected);
        helper.assertTrue(java.util.HexFormat.of().formatHex(encoded).equals(expectedWireHex),
                "local_audio opcode " + expectedOpcode + " のregistry codec wire bytesまたはfield順が変わった: "
                        + java.util.HexFormat.of().formatHex(encoded));

        final LocalAudioPayload decoded = decodeRegisteredLocalAudio(encoded);
        helper.assertTrue(decoded.operation() == expectedOpcode,
                "local_audio opcodeが " + expectedOpcode + " から " + decoded.operation() + " に変わった");
        helper.assertTrue(decoded.id().equals(java.util.UUID.fromString("01234567-89ab-cdef-fedc-ba9876543210")),
                "local_audio request UUIDがwire往復で変わった");
        helper.assertTrue(decoded.containerId() == 173, "local_audio container idがwire往復で変わった");
        helper.assertTrue(decoded.generation() == 0x0102030405060708L,
                "local_audio generationがwire往復で変わった");
        helper.assertTrue(decoded.hash().equals(
                        "a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0fedcba9876543210"),
                "local_audio hashがwire往復で変わった");
        helper.assertTrue(decoded.offset() == 0x1122334455667788L, "local_audio offsetがwire往復で変わった");
        helper.assertTrue(decoded.size() == 0x1020304050607080L, "local_audio sizeがwire往復で変わった");
        helper.assertTrue(decoded.name().equals("cache-check-contract.mp3"),
                "local_audio nameがwire往復で変わった");
        helper.assertTrue(java.util.Arrays.equals(decoded.bytes(), new byte[] {0, 127, (byte) 128, (byte) 255, 42}),
                "local_audio bytesがwire往復で変わった");
    }

    private static byte[] encodeRegisteredLocalAudio(LocalAudioPayload payload) {
        final ByteBuf raw = Unpooled.buffer();
        try {
            ModPayloadTypes.LocalAudio.STREAM_CODEC.encode(new net.minecraft.network.FriendlyByteBuf(raw),
                    new ModPayloadTypes.LocalAudio(payload));
            final byte[] encoded = new byte[raw.readableBytes()];
            raw.getBytes(raw.readerIndex(), encoded);
            return encoded;
        } finally {
            raw.release();
        }
    }

    private static LocalAudioPayload decodeRegisteredLocalAudio(byte[] encoded) {
        final ByteBuf raw = Unpooled.wrappedBuffer(encoded);
        try {
            return ModPayloadTypes.LocalAudio.STREAM_CODEC
                    .decode(new net.minecraft.network.FriendlyByteBuf(raw)).inner();
        } finally {
            raw.release();
        }
    }

    //? if <1.21.2 {
    /*/^*
     * compat 経由で登録される4つの payload が、実 codec で往復しても値を保つこと。
     * 指紋へ載せた見本が「実際に使われる codec から来ている」ことの対照でもある
     * (偽物の codec や手書きバイト列を載せると、実装を変えても指紋も往復も動かない)。
     * <1.21.2 専用: この3型 (Contraption/SubLevel×2) は >=1.21.2 には存在しない。
     ^/
    @PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE)
    public static void compatPayloadsSurviveTheRoundTrip(GameTestHelper helper) {
        final var identity = new com.kuronami.musicdiscmaker.network.PlaybackSourceStamp(
                java.util.UUID.fromString("d89e535b-1266-4a97-a098-fcd66390a0b0"), 917L);
        final var backpack = new BackpackPlayDiscPayload(
                java.util.UUID.fromString("3f6a2c2e-3f3a-4c01-9b6f-9f2a0b9c8d7e"),
                SAMPLE_TRACK, SAMPLE_POS, 7);
        helper.assertTrue(roundTrip(BackpackPlayDiscPayload.STREAM_CODEC, backpack).equals(backpack),
                "BackpackPlayDiscPayload が実 codec の往復で失われた");
        final var receivers = new com.kuronami.musicdiscmaker.network.MovingSpeakerState(
                net.minecraft.world.phys.Vec3.atCenterOf(SAMPLE_POS),
                java.util.List.of(new com.kuronami.musicdiscmaker.network.SpeakerEntry(
                        new BlockPos(-8000, 64, 3000), 137, true, 35)));
        for (final boolean directional : new boolean[] {true, false}) {
            final var contraption = new ContraptionPlayDiscPayload(7, SAMPLE_POS, SAMPLE_TRACK,
                    1_000L, 96, 70, directional, identity,
                    new com.kuronami.musicdiscmaker.component.VanillaTrackData(
                            "minecraft:music_disc.cat", 185000L),
                    receivers);
            helper.assertTrue(roundTrip(ContraptionPlayDiscPayload.STREAM_CODEC, contraption)
                            .equals(contraption),
                    "ContraptionPlayDiscPayload が実 codec の往復で失われた (directional="
                            + directional + ")");
            final var subPlay = new SubLevelPlayDiscPayload(SAMPLE_POS, SAMPLE_TRACK, 1_000L,
                    96, 70, directional, identity);
            helper.assertTrue(roundTrip(SubLevelPlayDiscPayload.STREAM_CODEC, subPlay).equals(subPlay),
                    "SubLevelPlayDiscPayload が実 codec の往復で失われた (directional="
                            + directional + ")");
        }
        final var subStop = new SubLevelStopDiscPayload(SAMPLE_POS, identity);
        helper.assertTrue(roundTrip(SubLevelStopDiscPayload.STREAM_CODEC, subStop).equals(subStop),
                "SubLevelStopDiscPayload が実 codec の往復で失われた");
        helper.succeed();
    }
    *///?}

    /** この cell で登録される全 wire payload を見本で符号化し、その連結の SHA-256 を返す。 */
    private static String fingerprint() {
        final Map<String, byte[]> encoded = new LinkedHashMap<>();
        encoded.put("play_disc", encode(mdmCodec(PlayDiscPayload::read),
                new PlayDiscPayload(SAMPLE_POS, SAMPLE_TRACK, 1_000L, 96, 70, false)));
        encoded.put("stop_disc", encode(mdmCodec(StopDiscPayload::read), new StopDiscPayload(SAMPLE_POS)));
        encoded.put("configure_jukebox", encode(mdmCodec(ConfigureJukeboxPayload::read),
                new ConfigureJukeboxPayload(SAMPLE_POS, 96, 70, true, false, false)));
        encoded.put("seek_jukebox", encode(mdmCodec(SeekJukeboxPayload::read),
                new SeekJukeboxPayload(SAMPLE_POS, 1_000L)));
        encoded.put("navigate_jukebox", encode(mdmCodec(NavigateJukeboxPayload::read),
                new NavigateJukeboxPayload(SAMPLE_POS, 7, 123L, true)));
        encoded.put("shuffle_jukebox", encode(mdmCodec(ShuffleJukeboxPayload::read),
                new ShuffleJukeboxPayload(SAMPLE_POS, 7, 123L, true)));
        encoded.put("control_boombox", encode(mdmCodec(ControlBoomboxPayload::read),
                new ControlBoomboxPayload(7, 123L, ControlBoomboxPayload.SEEK, 5_000L)));
        encoded.put("boombox_state", encode(mdmCodec(BoomboxStatePayload::read),
                new BoomboxStatePayload(7, new BoomboxPlaybackState(
                        new PlaybackCursor(2, 3, PlaybackCursor.State.PAUSED, 123L), 5_000L, true, false, 70))));
        encoded.put("boombox_play", encode(mdmCodec(BoomboxPlayPayload::read),
                BoomboxPlayPayload.placed(42L, SAMPLE_POS, SAMPLE_TRACK, 1_000L, 123L, 70)));
        encoded.put("boombox_stop", encode(mdmCodec(BoomboxStopPayload::read), new BoomboxStopPayload(42L)));
        encoded.put("boombox_play_vanilla", encode(mdmCodec(BoomboxPlayPayload::read),
                BoomboxPlayPayload.carried(42L, 7, null,
                        new com.kuronami.musicdiscmaker.component.VanillaTrackData("minecraft:music_disc.cat", 185_000L),
                        1_000L, 123L, 70)));
        encoded.put("resolve_url", encode(mdmCodec(ResolveUrlPayload::read),
                new ResolveUrlPayload(SAMPLE_POS, SAMPLE_TRACK.url())));
        encoded.put("speaker_set", encode(mdmCodec(com.kuronami.musicdiscmaker.network.SpeakerSetPayload::read),
                new com.kuronami.musicdiscmaker.network.SpeakerSetPayload(SAMPLE_POS, 96, 173, false,
                        java.util.List.of(new com.kuronami.musicdiscmaker.network.SpeakerEntry(
                                new BlockPos(-8000, 64, 3000), 137, true, 35)))));

        encoded.put("play_vanilla_disc", encode(mdmCodec(com.kuronami.musicdiscmaker.network.PlayVanillaDiscPayload::read),
                new com.kuronami.musicdiscmaker.network.PlayVanillaDiscPayload(SAMPLE_POS,
                        new com.kuronami.musicdiscmaker.component.VanillaTrackData("minecraft:music_disc.cat", 185000L),
                        73000L, 96, 173, false, 7L)));
        // 14-mp3-curios-dev で増えた装備機体の open 要求。見本に載せないと codec を変えても指紋が動かない。
        encoded.put("open_equipped_boombox",
                encode(mdmCodec(com.kuronami.musicdiscmaker.network.OpenEquippedBoomboxPayload::read),
                        new com.kuronami.musicdiscmaker.network.OpenEquippedBoomboxPayload(
                                "boombox", 0, 42L)));
        // BoomboxSource は menu open の extended buf に載る wire 型。装備参照 (slot/index/機体id)
        // の追加がこの見本で検出できる。
        encoded.put("boombox_source", encode(
                com.kuronami.musicdiscmaker.menu.BoomboxSource.STREAM_CODEC,
                com.kuronami.musicdiscmaker.menu.BoomboxSource.equipped("boombox", 0, 42L)));
        // 14-mp3-dev で増えた双方向転送。見本に載せないと codec を変えても指紋が動かない。
        encoded.put("local_audio", encode(mdmCodec(LocalAudioPayload::read),
                new LocalAudioPayload(LocalAudioPayload.DATA,
                        java.util.UUID.fromString("3f6a2c2e-3f3a-4c01-9b6f-9f2a0b9c8d7e"),
                        7, 123L,
                        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                        512L, 4096L, "track.mp3", new byte[] {1, 2, 3, 4})));
        //? if <1.21.2 {
        /*// compat 経由で登録される wire payload。3型は <1.21.2 にしか存在せず、
        // BackpackPlayDiscPayload の codec も全 cell 共通ソースなので、ここへ載せれば
        // どの cell の形式変更もこの指紋で検出できる。
        final var compatIdentity = new com.kuronami.musicdiscmaker.network.PlaybackSourceStamp(
                java.util.UUID.fromString("d89e535b-1266-4a97-a098-fcd66390a0b0"), 917L);
        encoded.put("sc_backpack_play", encode(BackpackPlayDiscPayload.STREAM_CODEC,
                new BackpackPlayDiscPayload(
                        java.util.UUID.fromString("3f6a2c2e-3f3a-4c01-9b6f-9f2a0b9c8d7e"),
                        SAMPLE_TRACK, SAMPLE_POS, 7)));
        encoded.put("create_contraption_play", encode(ContraptionPlayDiscPayload.STREAM_CODEC,
                new ContraptionPlayDiscPayload(7, SAMPLE_POS, SAMPLE_TRACK, 1_000L, 96, 70, false,
                        compatIdentity,
                        new com.kuronami.musicdiscmaker.component.VanillaTrackData(
                                "minecraft:music_disc.cat", 185000L),
                        new com.kuronami.musicdiscmaker.network.MovingSpeakerState(
                                net.minecraft.world.phys.Vec3.atCenterOf(SAMPLE_POS),
                                java.util.List.of(new com.kuronami.musicdiscmaker.network.SpeakerEntry(
                                        new BlockPos(-8000, 64, 3000), 137, true, 35))))));
        encoded.put("sable_sublevel_play", encode(SubLevelPlayDiscPayload.STREAM_CODEC,
                new SubLevelPlayDiscPayload(SAMPLE_POS, SAMPLE_TRACK, 1_000L, 96, 70, false,
                        compatIdentity)));
        encoded.put("sable_sublevel_stop", encode(SubLevelStopDiscPayload.STREAM_CODEC,
                new SubLevelStopDiscPayload(SAMPLE_POS, compatIdentity)));
        *///?}
        final com.kuronami.musicdiscmaker.network.PlaybackSourceStamp cartIdentity =
                new com.kuronami.musicdiscmaker.network.PlaybackSourceStamp(
                        java.util.UUID.fromString("00000000-0000-4000-8000-00000000c0de"), 123L);
        encoded.put("golden_cart_play", encode(mdmCodec(com.kuronami.musicdiscmaker.network.GoldenCartPlayPayload::read),
                new com.kuronami.musicdiscmaker.network.GoldenCartPlayPayload(42, SAMPLE_TRACK, null, 1_000L,
                        96, 70, false, cartIdentity)));
        encoded.put("golden_cart_stop", encode(mdmCodec(com.kuronami.musicdiscmaker.network.GoldenCartStopPayload::read),
                new com.kuronami.musicdiscmaker.network.GoldenCartStopPayload(42, cartIdentity, true)));
        final net.minecraft.nbt.CompoundTag cartState = new net.minecraft.nbt.CompoundTag();
        cartState.putInt("range", 96);
        encoded.put("golden_cart_sync", encode(mdmCodec(com.kuronami.musicdiscmaker.network.GoldenCartSyncPayload::read),
                new com.kuronami.musicdiscmaker.network.GoldenCartSyncPayload(42, cartState)));
        // handshake 自体の形式も見る。運ぶ文字列は版の値ではなく固定の見本にする
        // (current() を入れると版を上げるたびに指紋が動き、「形式が変わった」検出と混ざる)。
        encoded.put("version", encode(mdmCodec(VersionPayload::read),
                new VersionPayload("fingerprint-sample")));

        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (final Map.Entry<String, byte[]> entry : encoded.entrySet()) {
                // 名前も混ぜる: payload の登録名が変われば ID の互換も切れるため。
                digest.update(entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update(entry.getValue());
            }
            final StringBuilder hex = new StringBuilder();
            for (final byte b : digest.digest()) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (final Exception ex) {
            throw new IllegalStateException("wire 指紋を計算できない", ex);
        }
    }


    private static <T extends com.kuronami.musicdiscmaker.network.ModPayload>
            StreamCodec<ByteBuf, T> mdmCodec(
                    java.util.function.Function<net.minecraft.network.FriendlyByteBuf, T> reader) {
        return StreamCodec.of(
                (ByteBuf buf, T msg) -> msg.write(new net.minecraft.network.FriendlyByteBuf(buf)),
                (ByteBuf buf) -> reader.apply(new net.minecraft.network.FriendlyByteBuf(buf)));
    }

    private static <T> byte[] encode(StreamCodec<ByteBuf, T> codec, T value) {
        final ByteBuf buf = Unpooled.buffer();
        try {
            codec.encode(buf, value);
            final byte[] bytes = new byte[buf.readableBytes()];
            buf.getBytes(buf.readerIndex(), bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    private static <T> T roundTrip(StreamCodec<ByteBuf, T> codec, T value) {
        final ByteBuf buf = Unpooled.buffer();
        try {
            codec.encode(buf, value);
            return codec.decode(buf);
        } finally {
            buf.release();
        }
    }
}

