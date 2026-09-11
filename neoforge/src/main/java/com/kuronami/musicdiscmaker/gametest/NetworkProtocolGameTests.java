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
 * <p>だから「codec を触ったら版を上げる」を人の注意力に任せない。ここでは全 payload の
 * wire バイト列の指紋と版を 1 つの表にして、<b>どちらか片方だけが動いたら赤くする</b>。
 *
 * <ul>
 *   <li>codec にフィールドを足した / 型を変えた / 順番を変えた → 指紋が動く → 版を上げろと赤くなる</li>
 *   <li>版を下げた / 戻した → 版の突き合わせで赤くなる</li>
 * </ul>
 *
 * <p>更新の手順: wire を意図して変えたら、{@link #PROTOCOL_VERSION} を上げ、赤くなった実測値を
 * {@link #WIRE_FINGERPRINT} に書き写す。<b>指紋だけ書き換えて版を据え置くのは禁止</b> —
 * それがこのテストが捕まえたい唯一の操作。
 ^/
@GameTestHolder(MusicDiscMaker.MODID)
*///?}
public class NetworkProtocolGameTests {

    private static final String TEMPLATE = "empty8x3x8";

    /** Album色componentの登録を含む版。既存payloadの見本は不変。{@link ModNetwork#PROTOCOL_VERSION} と一致すること。 */
    private static final String PROTOCOL_VERSION = "13";

    /** 上の版に対応する wire 指紋 (下の見本を全 payload の codec で符号化した結果の SHA-256)。 */
    private static final String WIRE_FINGERPRINT =
            "ec6ae480322987e107e4c337fdbe4c9350ee52ce3624904abb446e5ec03469c1";

    /** 指紋の材料になる見本。<b>値を変えると指紋も変わる</b>ので、増減させないこと。 */
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
    //? if <1.21.2 {
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
    //? if <1.21.2 {
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

    /** 全 payload を見本で符号化し、その連結の SHA-256 を返す。 */
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

