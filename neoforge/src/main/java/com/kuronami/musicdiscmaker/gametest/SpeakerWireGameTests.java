package com.kuronami.musicdiscmaker.gametest;

import java.util.ArrayList;
import java.util.List;
import com.kuronami.musicdiscmaker.network.SpeakerEntry;
import com.kuronami.musicdiscmaker.network.SpeakerSetPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
//? if <1.21.2 {
/*import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
*///?}

//? if <1.21.2 {
/*@GameTestHolder("music_disc_maker")
*///?}
public final class SpeakerWireGameTests {
    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void snapshotRoundTripAndMalformedCount(GameTestHelper helper) {
        final var mutable = new ArrayList<SpeakerEntry>();
        for (int facing = 0; facing < 36; facing++) {
            mutable.add(new SpeakerEntry(new BlockPos(-8192 + facing, 120, 9000), 200, facing % 2 == 0, facing));
        }
        final var original = new SpeakerSetPayload(new BlockPos(1200, -32, -4800), 256, 173, false, mutable);
        mutable.clear();
        final FriendlyByteBuf wire = new FriendlyByteBuf(Unpooled.buffer());
        try {
            final var vanilla = new com.kuronami.musicdiscmaker.network.PlayVanillaDiscPayload(
                    new BlockPos(-5000, -48, 9000),
                    new com.kuronami.musicdiscmaker.component.VanillaTrackData("minecraft:music_disc.cat", 185000L),
                    73000L, 96, 173, false, 17L);
            vanilla.write(wire);
            helper.assertTrue(vanilla.equals(com.kuronami.musicdiscmaker.network.PlayVanillaDiscPayload.read(wire)),
                    "vanilla speaker sound, offset or generation changed on wire");
            helper.assertTrue(!wire.isReadable(), "vanilla speaker packet left trailing bytes");
            wire.clear();
            original.write(wire);
            helper.assertTrue(original.speakers().size() == 36, "snapshot retained mutable input list");
            helper.assertTrue(original.equals(SpeakerSetPayload.read(wire)), "speaker settings changed on wire");
            helper.assertTrue(!wire.isReadable(), "speaker packet left trailing bytes");
            wire.clear();
            new SpeakerSetPayload(BlockPos.ZERO, 32, 100, true, List.of()).write(wire);
            helper.assertTrue(SpeakerSetPayload.read(wire).speakers().isEmpty(), "empty set did not clear speakers");
            for (int invalidCount : new int[] {-1, Integer.MAX_VALUE}) {
                wire.clear();
                wire.writeLong(0L);
                wire.writeVarInt(32);
                wire.writeVarInt(100);
                wire.writeBoolean(true);
                wire.writeVarInt(invalidCount);
                boolean rejected = false;
                try { SpeakerSetPayload.read(wire); }
                catch (IllegalArgumentException expected) { rejected = true; }
                helper.assertTrue(rejected, "invalid count accepted: " + invalidCount);
            }
        } finally {
            wire.release();
        }
        helper.succeed();
    }
}
