package com.kuronami.musicdiscmaker.gametest;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.component.PlaybackCursor;
import com.kuronami.musicdiscmaker.event.BoomboxPlayback;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** 1.20.1 NBT 側でも媒体差し替えの generation 規則を固定する。 */
@GameTestHolder(MusicDiscMaker.MODID)
@PrefixGameTestTemplate(false)
public final class BoomboxPlaybackLegacyGameTests {
    private BoomboxPlaybackLegacyGameTests() {
    }

    @GameTest(template = "empty8x3x8")
    public static void legacyReplacingMediaClearsPositionWithoutResettingGeneration(GameTestHelper helper) {
        final PlaybackCursor playing = PlaybackCursor.initial().startAt(0, 0);
        final BoomboxContents before = new BoomboxContents(new ItemStack(Items.MUSIC_DISC_13), 74L,
                playing, 12_345L, true, true, 55L, 0, 0, 63);
        final BoomboxContents after = before.withDisc(new ItemStack(Items.MUSIC_DISC_CAT));
        helper.assertTrue(after.cursor().state() == PlaybackCursor.State.STOPPED
                        && !after.cursor().hasPosition()
                        && after.cursor().generation() > before.cursor().generation(),
                "legacy媒体差し替えが位置を消すか generation を進めていない");
        helper.assertTrue(after.pausedOffsetMs() == 0L && !after.shuffle()
                        && after.shuffleAnchorDisc() == PlaybackCursor.NO_INDEX
                        && after.volumePercent() == 63,
                "legacy媒体差し替えが pause/shuffle を初期化せず、機体設定まで失った");
        helper.succeed();
    }

    @GameTest(template = "empty8x3x8")
    public static void legacySessionlessPlayingStateDoesNotOfferImplicitResume(GameTestHelper helper) {
        final PlaybackCursor playing = PlaybackCursor.initial().startAt(0, 0);
        final ItemStack boombox = new ItemStack(ModItems.BOOMBOX.get());
        BoomboxContents.store(boombox, new BoomboxContents(new ItemStack(Items.MUSIC_DISC_13), 987L,
                playing, 0L, false, false, 0L, PlaybackCursor.NO_INDEX, PlaybackCursor.NO_INDEX, 100));
        helper.assertTrue(BoomboxPlayback.stateOf(boombox).cursor().state() == PlaybackCursor.State.STOPPED
                        && BoomboxPlayback.stateOf(boombox).cursor().generation() == playing.generation(),
                "legacy Session無しのPLAYING componentが表示上も勝手に再開されている");
        final var player = net.minecraftforge.common.util.FakePlayerFactory.getMinecraft(helper.getLevel());
        final int sessionsBefore = BoomboxPlayback.activeSessionCount();
        BoomboxPlayback.nextCarried(player, boombox, playing.generation());
        helper.assertTrue(BoomboxPlayback.activeSessionCount() == sessionsBefore
                        && !BoomboxPlayback.isPlaying(987L)
                        && BoomboxPlayback.stateOf(boombox).cursor().state() == PlaybackCursor.State.STOPPED,
                "legacy Session無しのNEXTが意図せず再生を開始した");
        helper.succeed();
    }
}
