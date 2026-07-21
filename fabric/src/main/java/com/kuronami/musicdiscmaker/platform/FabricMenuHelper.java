package com.kuronami.musicdiscmaker.platform;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.block.EnhancedJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.block.MusicDiscMakerBlockEntity;
import com.kuronami.musicdiscmaker.menu.EnhancedJukeboxMenu;
import com.kuronami.musicdiscmaker.menu.MusicDiscMakerMenu;
import com.kuronami.musicdiscmaker.platform.services.IMenuHelper;

import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Fabric 実装: extended menu (BlockPos を client ctor へ運ぶ)。
 * 1.20.1 の {@link ExtendedScreenHandlerType} は {@code (id, inv, buf)} ファクトリ、open 時は
 * {@link ExtendedScreenHandlerFactory#writeScreenOpeningData} で pos を buf に載せる。
 */
public class FabricMenuHelper implements IMenuHelper {

    @Override
    public MenuType<MusicDiscMakerMenu> createMusicDiscMakerMenuType() {
        return new ExtendedScreenHandlerType<>(
                (id, inv, buf) -> new MusicDiscMakerMenu(id, inv, buf.readBlockPos()));
    }

    @Override
    public void openMusicDiscMakerMenu(ServerPlayer player, BlockPos pos) {
        player.openMenu(new ExtendedScreenHandlerFactory() {

            @Override
            public void writeScreenOpeningData(ServerPlayer p, FriendlyByteBuf buf) {
                buf.writeBlockPos(pos);
            }

            @Override
            public Component getDisplayName() {
                return Component.translatable("gui.music_disc_maker.title");
            }

            @Nullable
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
    public MenuType<EnhancedJukeboxMenu> createEnhancedJukeboxMenuType() {
        return new ExtendedScreenHandlerType<>(
                (id, inv, buf) -> new EnhancedJukeboxMenu(id, inv, buf.readBlockPos()));
    }

    @Override
    public void openEnhancedJukeboxMenu(ServerPlayer player, BlockPos pos) {
        player.openMenu(new ExtendedScreenHandlerFactory() {

            @Override
            public void writeScreenOpeningData(ServerPlayer p, FriendlyByteBuf buf) {
                buf.writeBlockPos(pos);
            }

            @Override
            public Component getDisplayName() {
                return Component.translatable("gui.music_disc_maker.enhanced_jukebox.title");
            }

            @Nullable
            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                final BlockEntity be = p.level().getBlockEntity(pos);
                return be instanceof EnhancedJukeboxBlockEntity jukebox
                        ? new EnhancedJukeboxMenu(id, inv, jukebox)
                        : null;
            }
        });
    }
}
