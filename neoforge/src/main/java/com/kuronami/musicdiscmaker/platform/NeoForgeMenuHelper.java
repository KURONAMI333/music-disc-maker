package com.kuronami.musicdiscmaker.platform;

import com.kuronami.musicdiscmaker.block.BoomboxBlockEntity;
import com.kuronami.musicdiscmaker.block.DiscDyeingTableBlockEntity;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.block.MusicDiscMakerBlockEntity;
import com.kuronami.musicdiscmaker.menu.BoomboxMenu;
import com.kuronami.musicdiscmaker.menu.AlbumMenu;
import com.kuronami.musicdiscmaker.menu.SpeakerMenu;
import com.kuronami.musicdiscmaker.block.SpeakerBlockEntity;
import com.kuronami.musicdiscmaker.menu.BoomboxSource;
import com.kuronami.musicdiscmaker.menu.DiscDyeingTableMenu;
import com.kuronami.musicdiscmaker.menu.GoldenJukeboxMenu;
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
 * client ctor は {@code (id, inv, RegistryFriendlyByteBuf)} が buf から BlockPos を読む (menu 側で処理)。
 */
public class NeoForgeMenuHelper implements IMenuHelper {

    @Override
    public MenuType<MusicDiscMakerMenu> createMusicDiscMakerMenuType() {
        //? if >=1.21.2 {
        return IMenuTypeExtension.create(MusicDiscMakerMenu::new);
        //?} else {
        /*return IMenuTypeExtension.create(
                (id, inv, buf) -> new MusicDiscMakerMenu(id, inv, buf.readBlockPos()));
        *///?}
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
        //? if >=1.21.2 {
        return IMenuTypeExtension.create(GoldenJukeboxMenu::new);
        //?} else {
        /*return IMenuTypeExtension.create(
                (id, inv, buf) -> new GoldenJukeboxMenu(id, inv, buf.readBlockPos()));
        *///?}
    }

    @Override
    public void openGoldenJukeboxMenu(ServerPlayer player, BlockPos pos) {
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> {
            final BlockEntity be = player.level().getBlockEntity(pos);
            return be instanceof GoldenJukeboxBlockEntity jukebox
                    ? new GoldenJukeboxMenu(id, inv, jukebox)
                    : null;
        //? if >=1.21.2 {
        }, Component.translatable("block.music_disc_maker.golden_jukebox")), buf -> buf.writeBlockPos(pos));
        //?} else {
        /*}, Component.translatable("gui.music_disc_maker.golden_jukebox.title")), buf -> buf.writeBlockPos(pos));
        *///?}
    }

    @Override
    public MenuType<SpeakerMenu> createSpeakerMenuType() {
        //? if >=1.21.2 {
        return IMenuTypeExtension.create(SpeakerMenu::new);
        //?} else {
        /*return IMenuTypeExtension.create(
                (id, inv, buf) -> new SpeakerMenu(id, inv, buf.readBlockPos()));
        *///?}
    }

    @Override
    public void openSpeakerMenu(ServerPlayer player, BlockPos pos) {
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> {
            final BlockEntity be = player.level().getBlockEntity(pos);
            return be instanceof SpeakerBlockEntity table
                    ? new SpeakerMenu(id, inv, table)
                    : null;
        }, Component.translatable("block.music_disc_maker.speaker")), buf -> buf.writeBlockPos(pos));
    }

    @Override
    public MenuType<DiscDyeingTableMenu> createDiscDyeingTableMenuType() {
        //? if >=1.21.2 {
        return IMenuTypeExtension.create(DiscDyeingTableMenu::new);
        //?} else {
        /*return IMenuTypeExtension.create(
                (id, inv, buf) -> new DiscDyeingTableMenu(id, inv, buf.readBlockPos()));
        *///?}
    }

    @Override
    public void openDiscDyeingTableMenu(ServerPlayer player, BlockPos pos) {
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> {
            final BlockEntity be = player.level().getBlockEntity(pos);
            return be instanceof DiscDyeingTableBlockEntity table
                    ? new DiscDyeingTableMenu(id, inv, table)
                    : null;
        }, Component.translatable("block.music_disc_maker.disc_dyeing_table")), buf -> buf.writeBlockPos(pos));
    }

    @Override
    public MenuType<BoomboxMenu> createBoomboxMenuType() {
        return IMenuTypeExtension.create(BoomboxMenu::new);
    }

    @Override
    public void openBoomboxMenu(ServerPlayer player, BoomboxSource source) {
        // 設置した機体は BE が実在する時だけ開く。手持ちは常に開く (スタックは menu 側が持たない)。
        if (source.isPlaced()
                && !(player.level().getBlockEntity(source.pos()) instanceof BoomboxBlockEntity)) {
            return;
        }
        player.openMenu(new SimpleMenuProvider(
                (id, inv, p) -> new BoomboxMenu(id, inv, source),
                Component.translatable("block.music_disc_maker.boombox")),
                buf -> BoomboxSource.STREAM_CODEC.encode(buf, source));
    }

    @Override
    public MenuType<AlbumMenu> createAlbumMenuType() { return IMenuTypeExtension.create(AlbumMenu::new); }

    @Override
    public void openAlbumMenu(ServerPlayer player, BoomboxSource source) {
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new AlbumMenu(id, inv, source),
                Component.translatable("item.music_disc_maker.album")),
                buf -> BoomboxSource.STREAM_CODEC.encode(buf, source));
    }
}
