package com.kuronami.musicdiscmaker.platform;

import com.kuronami.musicdiscmaker.block.MusicDiscMakerBlockEntity;
import com.kuronami.musicdiscmaker.menu.MusicDiscMakerMenu;
import com.kuronami.musicdiscmaker.platform.services.IMenuHelper;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.extensions.IForgeMenuType;
import net.minecraftforge.network.NetworkHooks;

/**
 * Forge 実装: extended menu (BlockPos を client ctor へ運ぶ)。
 * MenuType は {@link IForgeMenuType#create} で生成し、open 時は {@link NetworkHooks#openScreen} の
 * extraData writer で BlockPos を buf に載せる。
 */
public class ForgeMenuHelper implements IMenuHelper {

    @Override
    public MenuType<MusicDiscMakerMenu> createMusicDiscMakerMenuType() {
        return IForgeMenuType.create(
                (id, inv, buf) -> new MusicDiscMakerMenu(id, inv, buf.readBlockPos()));
    }

    @Override
    public void openMusicDiscMakerMenu(ServerPlayer player, BlockPos pos) {
        NetworkHooks.openScreen(player, new SimpleMenuProvider((id, inv, p) -> {
            final BlockEntity be = player.level().getBlockEntity(pos);
            return be instanceof MusicDiscMakerBlockEntity maker
                    ? new MusicDiscMakerMenu(id, inv, maker)
                    : null;
        }, Component.translatable("gui.music_disc_maker.title")), buf -> buf.writeBlockPos(pos));
    }
}
