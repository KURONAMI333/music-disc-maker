package com.kuronami.musicdiscmaker.platform.services;

import java.util.UUID;

import com.kuronami.musicdiscmaker.menu.BoomboxMenu;
import com.kuronami.musicdiscmaker.menu.GoldenJukeboxMenu;
import com.kuronami.musicdiscmaker.menu.MusicDiscMakerMenu;
import com.kuronami.musicdiscmaker.menu.SpeakerMenu;

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

    /** BlockPos を client ctor へ運ぶスピーカーの extended MenuType を生成する。 */
    MenuType<SpeakerMenu> createSpeakerMenuType();

    /** server 側: pos を付けてスピーカーの設定 menu を開く。 */
    void openSpeakerMenu(ServerPlayer player, BlockPos pos);

    /**
     * アイテム個体の UUID を client ctor へ運ぶブームボックスの extended MenuType を生成する。
     * ブロックに紐づかない唯一の menu なので、運ぶのは BlockPos でなく UUID。
     */
    MenuType<BoomboxMenu> createBoomboxMenuType();

    /** server 側: アイテム個体の UUID を付けてブームボックスの設定 menu を開く。 */
    void openBoomboxMenu(ServerPlayer player, UUID boomboxId);
}
