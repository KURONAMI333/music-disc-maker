package com.kuronami.musicdiscmaker.gametest;

import com.kuronami.musicdiscmaker.block.SpeakerBlock;
import com.kuronami.musicdiscmaker.block.SpeakerBlockEntity;
import com.kuronami.musicdiscmaker.menu.SpeakerMenu;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;

@net.minecraftforge.gametest.GameTestHolder("music_disc_maker")
@net.minecraftforge.gametest.PrefixGameTestTemplate(false)
public final class SpeakerMenuLegacyGameTests {
    private SpeakerMenuLegacyGameTests() { }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void buttonsPacketAndDataSlotsKeepFullVolumeRange(GameTestHelper helper) {
        final Fixture fixture = fixture(helper, "speaker-menu-legacy-buttons");
        try {
            final SpeakerMenu menu = new SpeakerMenu(61, fixture.player().getInventory(), fixture.speaker());
            fixture.player().containerMenu = menu;
            assertButton(helper, menu, fixture, 0, 0);
            assertButton(helper, menu, fixture, 100, 200);
            assertButton(helper, menu, fixture, 37, 74);
            helper.assertFalse(menu.clickMenuButton(fixture.player(), -1), "legacy負buttonを受理した");
            helper.assertFalse(menu.clickMenuButton(fixture.player(), 101), "legacy button 101を受理した");

            final FriendlyByteBuf wire = new FriendlyByteBuf(Unpooled.buffer());
            try {
                new ServerboundContainerButtonClickPacket(61, 100).write(wire);
                final ServerboundContainerButtonClickPacket decoded =
                        new ServerboundContainerButtonClickPacket(wire);
                helper.assertTrue(decoded.getContainerId() == 61 && decoded.getButtonId() == 100,
                        "legacy vanilla button packetが上限200%用ID 100をround-tripしない");
            } finally {
                wire.release();
            }

            final SpeakerMenu clientSlots = new SpeakerMenu(62, fixture.player().getInventory(), fixture.pos());
            clientSlots.setData(0, 37);
            clientSlots.setData(1, 1);
            helper.assertTrue(clientSlots.getVolumePercent() == 37 && clientSlots.isMuted(),
                    "legacy DataSlotのvolume/muteを同期していない");
            clientSlots.setData(0, 200);
            clientSlots.setData(1, 0);
            helper.assertTrue(clientSlots.getVolumePercent() == 200 && !clientSlots.isMuted(),
                    "legacy DataSlotの上限volume/mute解除を同期していない");
        } finally {
            fixture.close(helper);
        }
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    public static void rejectsWrongMenuDistanceReplacementAndDimension(GameTestHelper helper) {
        final Fixture fixture = fixture(helper, "speaker-menu-legacy-guards");
        SpeakerBlockEntity crossDimension = null;
        try {
            final SpeakerMenu menu = new SpeakerMenu(71, fixture.player().getInventory(), fixture.speaker());
            fixture.player().containerMenu = new SpeakerMenu(72, fixture.player().getInventory(), fixture.speaker());
            helper.assertFalse(menu.clickMenuButton(fixture.player(), 25), "legacy別menuから変更できた");
            fixture.player().containerMenu = menu;
            fixture.player().setPos(fixture.pos().getX() + 9.0D, fixture.pos().getY() + 0.5D,
                    fixture.pos().getZ() + 0.5D);
            helper.assertFalse(menu.clickMenuButton(fixture.player(), 25), "legacy 8 block超から変更できた");
            fixture.player().setPos(fixture.pos().getX() + 0.5D, fixture.pos().getY() + 0.5D,
                    fixture.pos().getZ() + 0.5D);

            helper.setBlock(fixture.relativePos(), Blocks.AIR.defaultBlockState());
            helper.setBlock(fixture.relativePos(), speakerState());
            helper.assertFalse(menu.clickMenuButton(fixture.player(), 25), "legacy stale menuが交換後BEを変更した");

            final var otherLevel = helper.getLevel().getServer().getLevel(Level.NETHER);
            helper.assertTrue(otherLevel != null, "legacy別次元guard用Netherがない");
            crossDimension = new SpeakerBlockEntity(fixture.pos(), speakerState());
            crossDimension.setLevel(otherLevel);
            final SpeakerMenu crossMenu = new SpeakerMenu(73, fixture.player().getInventory(), crossDimension);
            fixture.player().containerMenu = crossMenu;
            helper.assertFalse(crossMenu.clickMenuButton(fixture.player(), 25), "legacy別次元speakerを変更できた");
        } finally {
            if (crossDimension != null) crossDimension.setRemoved();
            fixture.close(helper);
        }
        helper.succeed();
    }

    private static void assertButton(GameTestHelper helper, SpeakerMenu menu, Fixture fixture,
            int buttonId, int expectedVolume) {
        helper.assertTrue(menu.clickMenuButton(fixture.player(), buttonId), "legacy有効button拒否: " + buttonId);
        helper.assertTrue(fixture.speaker().getVolumePercent() == expectedVolume
                        && menu.getVolumePercent() == expectedVolume,
                "legacy button " + buttonId + " がvolume " + expectedVolume + " を設定しない");
    }

    private static Fixture fixture(GameTestHelper helper, String name) {
        final BlockPos supportRel = new BlockPos(2, 0, 2);
        final BlockPos relativePos = supportRel.above();
        helper.setBlock(supportRel, Blocks.STONE.defaultBlockState());
        helper.setBlock(relativePos, speakerState());
        final BlockPos pos = helper.absolutePos(relativePos);
        final SpeakerBlockEntity speaker = (SpeakerBlockEntity) helper.getLevel().getBlockEntity(pos);
        helper.assertTrue(speaker != null, "legacy Speaker BEを生成できていない");
        final FakePlayer player = FakePlayerFactory.get(helper.getLevel(),
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), name));
        player.setPos(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        return new Fixture(relativePos, supportRel, pos, speaker, player);
    }

    private static net.minecraft.world.level.block.state.BlockState speakerState() {
        return ModBlocks.SPEAKER.get().defaultBlockState().setValue(SpeakerBlock.FACE, AttachFace.FLOOR);
    }

    private record Fixture(BlockPos relativePos, BlockPos supportRel, BlockPos pos,
            SpeakerBlockEntity speaker, FakePlayer player) {
        void close(GameTestHelper helper) {
            player.discard();
            helper.setBlock(relativePos, Blocks.AIR.defaultBlockState());
            helper.setBlock(supportRel, Blocks.AIR.defaultBlockState());
        }
    }
}
