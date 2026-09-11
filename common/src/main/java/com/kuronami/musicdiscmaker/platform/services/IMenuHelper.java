package com.kuronami.musicdiscmaker.platform.services;

import com.kuronami.musicdiscmaker.menu.BoomboxMenu;
import com.kuronami.musicdiscmaker.menu.AlbumMenu;
import com.kuronami.musicdiscmaker.menu.SpeakerMenu;
import com.kuronami.musicdiscmaker.menu.BoomboxSource;
import com.kuronami.musicdiscmaker.menu.DiscDyeingTableMenu;
import com.kuronami.musicdiscmaker.menu.GoldenJukeboxMenu;
import com.kuronami.musicdiscmaker.menu.MusicDiscMakerMenu;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;

/**
 * extended/networked menu の loader 抽象 (開く時に BlockPos を client へ運ぶ)。
 * NeoForge は {@code IMenuTypeExtension} + {@code openMenu(provider, buf-writer)}、
 * Fabric は {@code ExtendedScreenHandlerType} + {@code ExtendedScreenHandlerFactory}。
 */
public interface IMenuHelper {

    /** BlockPos を client ctor へ運ぶ extended MenuType を生成する (登録は generic provider 側)。 */
    MenuType<MusicDiscMakerMenu> createMusicDiscMakerMenuType();

    /** server 側: pos を付けて menu を開く。 */
    void openMusicDiscMakerMenu(ServerPlayer player, BlockPos pos);

    /** BlockPos を client ctor へ運ぶ強化版ジュークボックスの extended MenuType を生成する。 */
    MenuType<GoldenJukeboxMenu> createGoldenJukeboxMenuType();

    /** server 側: pos を付けて強化版ジュークボックスの設定 menu を開く。 */
    void openGoldenJukeboxMenu(ServerPlayer player, BlockPos pos);

    /** BlockPos を client ctor へ運ぶ Disc Dyeing Table の extended MenuType を生成する。 */
    MenuType<DiscDyeingTableMenu> createDiscDyeingTableMenuType();

    /** server 側: pos を付けて Disc Dyeing Table の menu を開く。 */
    void openDiscDyeingTableMenu(ServerPlayer player, BlockPos pos);

    /**
     * ブームボックスの extended MenuType を生成する。運ぶのは {@link BoomboxSource}
     * (置いてある機体か、持っている手か)。BlockPos 1 本で足りないのはブームボックスだけ。
     */
    MenuType<BoomboxMenu> createBoomboxMenuType();

    /** server 側: どの機体かを付けてブームボックスの GUI を開く。 */
    void openBoomboxMenu(ServerPlayer player, BoomboxSource source);

    /** 手持ち Album の extended menu を生成・開く。 */
    MenuType<SpeakerMenu> createSpeakerMenuType();

    void openSpeakerMenu(ServerPlayer player, BlockPos pos);

    MenuType<AlbumMenu> createAlbumMenuType();

    void openAlbumMenu(ServerPlayer player, BoomboxSource source);
}
