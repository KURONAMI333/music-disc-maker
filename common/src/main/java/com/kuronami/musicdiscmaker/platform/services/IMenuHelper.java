package com.kuronami.musicdiscmaker.platform.services;

import com.kuronami.musicdiscmaker.menu.EnhancedJukeboxMenu;
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

    /** 強化版ジュークボックスの設定 menu を生成する (BlockPos を client ctor へ運ぶ)。 */
    MenuType<EnhancedJukeboxMenu> createEnhancedJukeboxMenuType();

    /** server 側: pos を付けて強化版ジュークボックスの設定 menu を開く。 */
    void openEnhancedJukeboxMenu(ServerPlayer player, BlockPos pos);
}
