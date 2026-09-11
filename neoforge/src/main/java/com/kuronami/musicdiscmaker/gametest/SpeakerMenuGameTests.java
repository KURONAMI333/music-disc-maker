package com.kuronami.musicdiscmaker.gametest;

import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.SpeakerBlock;
import com.kuronami.musicdiscmaker.block.SpeakerBlockEntity;
import com.kuronami.musicdiscmaker.menu.SpeakerMenu;
import com.kuronami.musicdiscmaker.register.ModBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
//? if <1.21.2 {
/*import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
*///?}
public final class SpeakerMenuGameTests {
    private SpeakerMenuGameTests() { }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100, batch = "speakerMenu")
    *///?}
    public static void buttonsAndDataSlotsKeepFullVolumeRange(GameTestHelper helper) {
        final Fixture fixture = fixture(helper, "speaker-menu-buttons");
        try {
            final SpeakerMenu menu = new SpeakerMenu(41, fixture.player().getInventory(), fixture.speaker());
            fixture.player().containerMenu = menu;
            assertButton(helper, menu, fixture, 0, 0);
            assertButton(helper, menu, fixture, 100, 200);
            assertButton(helper, menu, fixture, 37, 74);
            helper.assertFalse(menu.clickMenuButton(fixture.player(), -1), "負のbutton IDを受理した");
            helper.assertFalse(menu.clickMenuButton(fixture.player(), 101), "button ID 101を受理した");
            helper.assertTrue(fixture.speaker().getVolumePercent() == 74,
                    "拒否したbutton IDがspeaker音量を書き換えた");

            final SpeakerMenu clientSlots = new SpeakerMenu(42, fixture.player().getInventory(), fixture.pos());
            clientSlots.setData(0, 37);
            clientSlots.setData(1, 1);
            helper.assertTrue(clientSlots.getVolumePercent() == 37 && clientSlots.isMuted(),
                    "DataSlotのvolume/muteを公開getterへ同期していない");
            clientSlots.setData(0, 200);
            clientSlots.setData(1, 0);
            helper.assertTrue(clientSlots.getVolumePercent() == 200 && !clientSlots.isMuted(),
                    "DataSlotの上限volumeまたはmute解除を同期していない");
        } finally {
            fixture.close(helper);
        }
        helper.succeed();
    }

    //? if <1.21.2 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100, batch = "speakerMenuGuard")
    *///?}
    public static void rejectsWrongMenuDistanceReplacementAndDimension(GameTestHelper helper) {
        final Fixture fixture = fixture(helper, "speaker-menu-guards");
        SpeakerBlockEntity crossDimension = null;
        try {
            final SpeakerMenu menu = new SpeakerMenu(51, fixture.player().getInventory(), fixture.speaker());
            final SpeakerMenu other = new SpeakerMenu(52, fixture.player().getInventory(), fixture.speaker());
            fixture.player().containerMenu = other;
            helper.assertFalse(menu.clickMenuButton(fixture.player(), 25), "別menuからspeakerを変更できた");

            fixture.player().containerMenu = menu;
            fixture.player().setPos(fixture.pos().getX() + 9.0D, fixture.pos().getY() + 0.5D,
                    fixture.pos().getZ() + 0.5D);
            helper.assertFalse(menu.clickMenuButton(fixture.player(), 25), "8 block超からspeakerを変更できた");
            fixture.player().setPos(fixture.pos().getX() + 0.5D, fixture.pos().getY() + 0.5D,
                    fixture.pos().getZ() + 0.5D);

            helper.setBlock(fixture.relativePos(), Blocks.AIR.defaultBlockState());
            helper.setBlock(fixture.relativePos(), speakerState());
            helper.assertTrue(helper.getLevel().getBlockEntity(fixture.pos()) != fixture.speaker(),
                    "同位置のSpeaker BEを交換できていない");
            helper.assertFalse(menu.clickMenuButton(fixture.player(), 25), "交換前BEを参照するstale menuが変更を受理した");

            final ServerLevel otherLevel = helper.getLevel().getServer().getLevel(Level.NETHER);
            helper.assertTrue(otherLevel != null, "別次元guard用のNether levelがない");
            crossDimension = new SpeakerBlockEntity(fixture.pos(), speakerState());
            crossDimension.setLevel(otherLevel);
            final SpeakerMenu crossMenu = new SpeakerMenu(53, fixture.player().getInventory(), crossDimension);
            fixture.player().containerMenu = crossMenu;
            helper.assertFalse(crossMenu.clickMenuButton(fixture.player(), 25), "別次元のspeakerを変更できた");
        } finally {
            if (crossDimension != null) crossDimension.setRemoved();
            fixture.close(helper);
        }
        helper.succeed();
    }

    private static void assertButton(GameTestHelper helper, SpeakerMenu menu, Fixture fixture,
            int buttonId, int expectedVolume) {
        helper.assertTrue(menu.clickMenuButton(fixture.player(), buttonId),
                "有効なbutton IDを拒否した: " + buttonId);
        helper.assertTrue(fixture.speaker().getVolumePercent() == expectedVolume
                        && menu.getVolumePercent() == expectedVolume,
                "button ID " + buttonId + " が期待volume " + expectedVolume + " を設定しない");
    }

    private static Fixture fixture(GameTestHelper helper, String playerName) {
        final BlockPos supportRel = new BlockPos(2, 0, 2);
        final BlockPos relativePos = supportRel.above();
        helper.setBlock(supportRel, Blocks.STONE.defaultBlockState());
        helper.setBlock(relativePos, speakerState());
        final SpeakerBlockEntity speaker = (SpeakerBlockEntity) helper.getLevel()
                .getBlockEntity(helper.absolutePos(relativePos));
        helper.assertTrue(speaker != null, "Speaker BEを生成できていない");
        final FakePlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.randomUUID(), playerName));
        final BlockPos pos = helper.absolutePos(relativePos);
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
