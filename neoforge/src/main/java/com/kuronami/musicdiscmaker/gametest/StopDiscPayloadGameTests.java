package com.kuronami.musicdiscmaker.gametest;

//? if >=1.21 {
import java.util.UUID;

import com.kuronami.musicdiscmaker.network.PlaybackSourceStamp;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
//? if <1.21.11 {
/*import com.kuronami.musicdiscmaker.MusicDiscMaker;
import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
*///?}

/** 曲の終わりによる停止の印が通信で落ちないこと (client はこの印がある停止だけ鳴り終わりまで待つ)。 */
//? if <1.21.11 {
/*@GameTestHolder(MusicDiscMaker.MODID)
*///?}
public final class StopDiscPayloadGameTests {
    private static final String TEMPLATE = "empty8x3x8";

    private StopDiscPayloadGameTests() {
    }

    private static StopDiscPayload roundTrip(GameTestHelper helper, StopDiscPayload payload) {
        final FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        payload.write(buf);
        final StopDiscPayload read = StopDiscPayload.read(buf);
        helper.assertTrue(buf.readableBytes() == 0, "読み残しがある (書いた形と読む形がずれている)");
        return read;
    }

    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = TEMPLATE, timeoutTicks = 20)
    *///?}
    public static void naturalEndFlagSurvivesTheWire(GameTestHelper helper) {
        final PlaybackSourceStamp stamp = new PlaybackSourceStamp(UUID.randomUUID(), 7L);
        final StopDiscPayload natural = roundTrip(helper, new StopDiscPayload(new BlockPos(1, 64, -3), stamp, true));
        helper.assertTrue(natural.naturalEnd(), "曲の終わりの印が通信で落ちている");
        helper.assertTrue(natural.jukeboxPos().equals(new BlockPos(1, 64, -3)) && natural.identity().equals(stamp),
                "位置か音源の識別子が通信でずれている");
        helper.assertTrue(!roundTrip(helper, new StopDiscPayload(new BlockPos(0, 0, 0))).naturalEnd()
                        && !roundTrip(helper, new StopDiscPayload(new BlockPos(0, 0, 0), stamp)).naturalEnd(),
                "取り出し・一時停止の停止に曲の終わりの印が付いている");
        helper.succeed();
    }
}
//?}
