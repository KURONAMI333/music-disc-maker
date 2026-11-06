package com.kuronami.musicdiscmaker.gametest;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.MusicDiscMakerBlockEntity;
import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.menu.MusicDiscMakerMenu;
import com.kuronami.musicdiscmaker.network.ClientResolvedTrackPayload;
import com.kuronami.musicdiscmaker.network.ModNetwork;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
//? if >=1.21.11 {
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
//?} else {
//?}
//? if <1.21.11 {
/*import net.minecraft.gametest.framework.GameTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MusicDiscMaker.MODID)
*///?}

/**
 * server の回線で解決できなかった時に client が送ってくる曲情報を、server がどこまで受け取るかの契約。
 *
 * <p>値は client の言い値なので、焼く前提 (空ディスク・空の出力) と、元の URL と同じ動画であることが
 * 揃った時だけ焼く。焼けなかった値は残さない (後から別の経路で焼かれると、client の値が盤になる)。
 */
public final class ClientResolvedTrackGameTests {

    private static final BlockPos MAKER = new BlockPos(2, 1, 2);
    private static final String WATCH = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";
    private static final String OTHER_VIDEO = "https://www.youtube.com/watch?v=aaaaaaaaaaa";

    private ClientResolvedTrackGameTests() {
    }

    /** 元の URL と違う動画の報告では焼かない (別の曲の尺を元の URL に貼らせない)。 */
    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void staleUrlIsIgnored(GameTestHelper helper) {
        final MusicDiscMakerBlockEntity maker = failedMaker(helper, true, false);
        final ServerPlayer player = openMakerScreen(helper, maker);
        try {
            ModNetwork.handleClientResolvedTrack(payload(maker, OTHER_VIDEO), player);
            helper.assertTrue(maker.getItem(MusicDiscMakerBlockEntity.SLOT_OUTPUT).isEmpty()
                            && !maker.hasResolvedTrack()
                            && maker.getItem(MusicDiscMakerBlockEntity.SLOT_INPUT).getCount() == 1,
                    "元の URL と違う動画の報告で盤を作った");
            helper.assertTrue(maker.isResolveFailed(), "古い報告で失敗表示が消えた");
        } finally {
            player.containerMenu = player.inventoryMenu;
            player.discard();
        }
        helper.succeed();
    }

    /** 解決中の製作機には受け取らない (server 自身の解決結果と競合させない)。 */
    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void reportWhileResolvingIsIgnored(GameTestHelper helper) {
        final MusicDiscMakerBlockEntity maker = failedMaker(helper, true, true);
        final ServerPlayer player = openMakerScreen(helper, maker);
        try {
            ModNetwork.handleClientResolvedTrack(payload(maker, WATCH), player);
            helper.assertTrue(maker.getItem(MusicDiscMakerBlockEntity.SLOT_OUTPUT).isEmpty()
                            && !maker.hasResolvedTrack()
                            && maker.getItem(MusicDiscMakerBlockEntity.SLOT_INPUT).getCount() == 1,
                    "解決中に届いた報告で盤を作った");
        } finally {
            player.containerMenu = player.inventoryMenu;
            player.discard();
        }
        helper.succeed();
    }

    /** 1 枚焼いた後の同じ報告では焼かない (焼いた時点で URL も失敗表示も消えている)。 */
    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void secondReportAfterBurnDoesNothing(GameTestHelper helper) {
        final MusicDiscMakerBlockEntity maker = failedMaker(helper, true, false);
        final ServerPlayer player = openMakerScreen(helper, maker);
        try {
            ModNetwork.handleClientResolvedTrack(payload(maker, WATCH), player);
            helper.assertTrue(maker.getItem(MusicDiscMakerBlockEntity.SLOT_OUTPUT).is(ModItems.CUSTOM_MUSIC_DISC.get()),
                    "client の報告から盤を作れない");
            // もう 1 枚焼ける状態に戻す (URL は焼いた時に消えているので非同期解決は始まらない)。
            maker.setItem(MusicDiscMakerBlockEntity.SLOT_OUTPUT, ItemStack.EMPTY);
            maker.setItem(MusicDiscMakerBlockEntity.SLOT_INPUT, new ItemStack(ModItems.BLANK_DISC.get()));
            ModNetwork.handleClientResolvedTrack(payload(maker, WATCH), player);
            helper.assertTrue(maker.getItem(MusicDiscMakerBlockEntity.SLOT_OUTPUT).isEmpty()
                            && !maker.hasResolvedTrack()
                            && maker.getItem(MusicDiscMakerBlockEntity.SLOT_INPUT).getCount() == 1,
                    "焼いた後の同じ報告でもう 1 枚焼いた");
        } finally {
            player.containerMenu = player.inventoryMenu;
            player.discard();
        }
        helper.succeed();
    }

    /** 空ディスクが無い時は焼かず、解決済みの曲も残さない (後から別の経路で焼かれない)。 */
    //? if <1.21.11 {
    /*@PrefixGameTestTemplate(false)
    @GameTest(template = "empty8x3x8", timeoutTicks = 100)
    *///?}
    public static void noBlankDiscLeavesNoCachedTrack(GameTestHelper helper) {
        final MusicDiscMakerBlockEntity maker = failedMaker(helper, false, false);
        final ServerPlayer player = openMakerScreen(helper, maker);
        try {
            ModNetwork.handleClientResolvedTrack(payload(maker, WATCH), player);
            helper.assertTrue(maker.getItem(MusicDiscMakerBlockEntity.SLOT_OUTPUT).isEmpty()
                            && !maker.hasResolvedTrack()
                            && maker.getResolvedForUrl().isEmpty(),
                    "空ディスクが無いのに解決済みの曲を残した");
            helper.assertTrue(maker.isResolveFailed(), "焼けなかった報告で失敗表示が消えた");
        } finally {
            player.containerMenu = player.inventoryMenu;
            player.discard();
        }
        helper.succeed();
    }

    private static ClientResolvedTrackPayload payload(MusicDiscMakerBlockEntity maker, String url) {
        return new ClientResolvedTrackPayload(maker.getBlockPos(), url, "曲名", "作者", 210_000L);
    }

    /**
     * 解決に失敗した状態の製作機を、非同期の再解決を起こさずに作る。
     *
     * <p>{@code setCurrentUrl} は {@code DiscFabrication.process} を呼んで解決を始めてしまうので、
     * 保存タグを組み立てて {@code loadWithComponents} で状態だけ入れる (こちらはスロット変更の
     * 通知を出さない)。
     */
    private static MusicDiscMakerBlockEntity failedMaker(GameTestHelper helper, boolean blankDisc, boolean resolving) {
        helper.setBlock(MAKER, ModBlocks.MUSIC_DISC_MAKER.get().defaultBlockState());
        final MusicDiscMakerBlockEntity maker = (MusicDiscMakerBlockEntity) helper.getLevel()
                .getBlockEntity(helper.absolutePos(MAKER));
        helper.assertTrue(maker != null, "Music Disc Maker BEを生成できていない");
        if (blankDisc) {
            // URL が空のうちに入れる (process は URL 空で何もしない)。
            maker.setItem(MusicDiscMakerBlockEntity.SLOT_INPUT, new ItemStack(ModItems.BLANK_DISC.get()));
        }
        final ServerLevel level = helper.getLevel();
        final CompoundTag tag = maker.saveCustomOnly(level.registryAccess());
        tag.putString("url", WATCH);
        tag.putBoolean("resolveFailed", true);
        tag.putString("failureReason", FailureReason.BOT_CHECK.name());
        tag.putBoolean("resolving", resolving);
        //? if >=1.21.11 {
        maker.loadWithComponents(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
        //?} else {
        /*maker.loadWithComponents(tag, level.registryAccess());
        *///?}
        helper.assertTrue(maker.isResolveFailed() && WATCH.equals(maker.getCurrentUrl()),
                "失敗状態を組み立てられていない");
        return maker;
    }

    /** その製作機の画面を開いている player を作る (server は画面を開いている player の報告だけ受ける)。 */
    private static ServerPlayer openMakerScreen(GameTestHelper helper, MusicDiscMakerBlockEntity maker) {
        final ServerPlayer player = helper.makeMockServerPlayerInLevel();
        final BlockPos pos = maker.getBlockPos();
        player.teleportTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
        player.containerMenu = new MusicDiscMakerMenu(1, player.getInventory(), maker);
        return player;
    }
}
