package com.kuronami.musicdiscmaker.platform;

import com.kuronami.musicdiscmaker.block.EnhancedJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.block.MusicDiscMakerBlockEntity;
import com.kuronami.musicdiscmaker.menu.EnhancedJukeboxMenu;
import com.kuronami.musicdiscmaker.menu.MusicDiscMakerMenu;
import com.kuronami.musicdiscmaker.platform.services.IMenuHelper;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;

/**
 * NeoForge 実装: extended menu (BlockPos を client ctor へ運ぶ)。
 * MenuType は {@code IMenuTypeExtension.create} で生成し、open 時は buf に BlockPos を載せる。
 */
public class NeoForgeMenuHelper implements IMenuHelper {

    @Override
    public MenuType<MusicDiscMakerMenu> createMusicDiscMakerMenuType() {
        return IMenuTypeExtension.create(
                (id, inv, buf) -> new MusicDiscMakerMenu(id, inv, buf.readBlockPos()));
    }

    @Override
    public void openMusicDiscMakerMenu(ServerPlayer player, BlockPos pos) {
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> {
            final BlockEntity be = player.level().getBlockEntity(pos);
            return be instanceof MusicDiscMakerBlockEntity maker
                    ? new MusicDiscMakerMenu(id, inv, maker)
                    : null;
        }, Component.translatable("gui.music_disc_maker.title")), buf -> buf.writeBlockPos(pos));
    }

    @Override
    public MenuType<EnhancedJukeboxMenu> createEnhancedJukeboxMenuType() {
        return IMenuTypeExtension.create(
                (id, inv, buf) -> new EnhancedJukeboxMenu(id, inv, buf.readBlockPos()));
    }

    @Override
    public void openEnhancedJukeboxMenu(ServerPlayer player, BlockPos pos) {
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> {
            final BlockEntity be = player.level().getBlockEntity(pos);
            return be instanceof EnhancedJukeboxBlockEntity jukebox
                    ? new EnhancedJukeboxMenu(id, inv, jukebox)
                    : null;
        }, Component.translatable("gui.music_disc_maker.enhanced_jukebox.title")), buf -> buf.writeBlockPos(pos));
    }
}
