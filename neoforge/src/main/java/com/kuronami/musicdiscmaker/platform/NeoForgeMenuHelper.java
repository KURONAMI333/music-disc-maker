package com.kuronami.musicdiscmaker.platform;

import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.block.MusicDiscMakerBlockEntity;
import com.kuronami.musicdiscmaker.block.SpeakerBlockEntity;
import java.util.UUID;

import com.kuronami.musicdiscmaker.menu.BoomboxMenu;
import com.kuronami.musicdiscmaker.menu.GoldenJukeboxMenu;
import com.kuronami.musicdiscmaker.menu.MusicDiscMakerMenu;
import com.kuronami.musicdiscmaker.menu.SpeakerMenu;
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
    public MenuType<GoldenJukeboxMenu> createGoldenJukeboxMenuType() {
        return IMenuTypeExtension.create(
                (id, inv, buf) -> new GoldenJukeboxMenu(id, inv, buf.readBlockPos()));
    }

    @Override
    public void openGoldenJukeboxMenu(ServerPlayer player, BlockPos pos) {
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> {
            final BlockEntity be = player.level().getBlockEntity(pos);
            return be instanceof GoldenJukeboxBlockEntity jukebox
                    ? new GoldenJukeboxMenu(id, inv, jukebox)
                    : null;
        }, Component.translatable("gui.music_disc_maker.golden_jukebox.title")), buf -> buf.writeBlockPos(pos));
    }

    @Override
    public MenuType<SpeakerMenu> createSpeakerMenuType() {
        return IMenuTypeExtension.create(
                (id, inv, buf) -> new SpeakerMenu(id, inv, buf.readBlockPos()));
    }

    @Override
    public void openSpeakerMenu(ServerPlayer player, BlockPos pos) {
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> {
            final BlockEntity be = player.level().getBlockEntity(pos);
            return be instanceof SpeakerBlockEntity speaker ? new SpeakerMenu(id, speaker) : null;
        }, Component.translatable("gui.music_disc_maker.speaker.title")), buf -> buf.writeBlockPos(pos));
    }

    @Override
    public MenuType<BoomboxMenu> createBoomboxMenuType() {
        return IMenuTypeExtension.create(
                (id, inv, buf) -> new BoomboxMenu(id, inv, buf.readUUID()));
    }

    @Override
    public void openBoomboxMenu(ServerPlayer player, UUID boomboxId) {
        // ブロックに紐づかないので BlockEntity は引かない。menu 側が UUID からスタックを解決する。
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new BoomboxMenu(id, inv, boomboxId),
                Component.translatable("gui.music_disc_maker.boombox.title")),
                buf -> buf.writeUUID(boomboxId));
    }
}
