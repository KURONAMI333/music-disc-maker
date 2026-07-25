package com.kuronami.musicdiscmaker.platform;

import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.block.MusicDiscMakerBlockEntity;
import com.kuronami.musicdiscmaker.menu.GoldenJukeboxMenu;
import com.kuronami.musicdiscmaker.menu.MusicDiscMakerMenu;
import com.kuronami.musicdiscmaker.platform.services.IMenuHelper;

import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Fabric 実装: extended menu (BlockPos を client ctor へ運ぶ)。
 * 26.2 の fabric-api は旧 {@code fabric-screen-handler-api-v1} を廃し、
 * {@code fabric-menu-api-v1} の {@link ExtendedScreenHandlerType} / {@link ExtendedScreenHandlerFactory} に統合した。
 * MenuType は {@link ExtendedScreenHandlerType} (data 型 + StreamCodec)、open 時は {@link ExtendedScreenHandlerFactory}
 * が pos を載せる。client 側 factory は pos から BE を解決して server 側と同じ menu を組む。
 */
public class FabricMenuHelper implements IMenuHelper {

    @Override
    public MenuType<MusicDiscMakerMenu> createMusicDiscMakerMenuType() {
        return new ExtendedScreenHandlerType<MusicDiscMakerMenu, BlockPos>(
                (id, inv, pos) -> {
                    final BlockEntity be = inv.player.level().getBlockEntity(pos);
                    return be instanceof MusicDiscMakerBlockEntity maker
                            ? new MusicDiscMakerMenu(id, inv, maker)
                            : null;
                },
                BlockPos.STREAM_CODEC);
    }

    @Override
    public void openMusicDiscMakerMenu(ServerPlayer player, BlockPos pos) {
        player.openMenu(new ExtendedScreenHandlerFactory<BlockPos>() {

            @Override
            public BlockPos getScreenOpeningData(ServerPlayer p) {
                return pos;
            }

            @Override
            public Component getDisplayName() {
                return Component.translatable("gui.music_disc_maker.title");
            }

            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                final BlockEntity be = p.level().getBlockEntity(pos);
                return be instanceof MusicDiscMakerBlockEntity maker
                        ? new MusicDiscMakerMenu(id, inv, maker)
                        : null;
            }
        });
    }

    @Override
    public MenuType<GoldenJukeboxMenu> createGoldenJukeboxMenuType() {
        return new ExtendedScreenHandlerType<GoldenJukeboxMenu, BlockPos>(
                (id, inv, pos) -> {
                    final BlockEntity be = inv.player.level().getBlockEntity(pos);
                    return be instanceof GoldenJukeboxBlockEntity jukebox
                            ? new GoldenJukeboxMenu(id, inv, jukebox)
                            : null;
                },
                BlockPos.STREAM_CODEC);
    }

    @Override
    public void openGoldenJukeboxMenu(ServerPlayer player, BlockPos pos) {
        player.openMenu(new ExtendedScreenHandlerFactory<BlockPos>() {

            @Override
            public BlockPos getScreenOpeningData(ServerPlayer p) {
                return pos;
            }

            @Override
            public Component getDisplayName() {
                return Component.translatable("block.music_disc_maker.golden_jukebox");
            }

            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                final BlockEntity be = p.level().getBlockEntity(pos);
                return be instanceof GoldenJukeboxBlockEntity jukebox
                        ? new GoldenJukeboxMenu(id, inv, jukebox)
                        : null;
            }
        });
    }
}
