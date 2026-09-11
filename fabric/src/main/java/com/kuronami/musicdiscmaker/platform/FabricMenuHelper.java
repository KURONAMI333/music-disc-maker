package com.kuronami.musicdiscmaker.platform;
//? if >=1.21 {
//?} else {

/*import org.jetbrains.annotations.Nullable;
*///?}

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

//? if >=26.2 {
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuType;
//?} else {
/*import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
*///?}
import net.minecraft.core.BlockPos;
//? if >=1.21 {
//?} else {
/*import net.minecraft.network.FriendlyByteBuf;
*///?}
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
 * {@code fabric-menu-api-v1} の {@link ExtendedMenuType} / {@link ExtendedMenuProvider} に統合した。
 * MenuType は {@link ExtendedMenuType} (data 型 + StreamCodec)、open 時は {@link ExtendedMenuProvider}
 * が pos を載せる。client 側 factory は pos から BE を解決して server 側と同じ menu を組む。
 */
public class FabricMenuHelper implements IMenuHelper {

    @Override
    public MenuType<MusicDiscMakerMenu> createMusicDiscMakerMenuType() {
        //? if >=26.2 {
        return new ExtendedMenuType<MusicDiscMakerMenu, BlockPos>(
                (id, inv, pos) -> {
                    final BlockEntity be = inv.player.level().getBlockEntity(pos);
                    return be instanceof MusicDiscMakerBlockEntity maker
                            ? new MusicDiscMakerMenu(id, inv, maker)
                            : null;
                },
                BlockPos.STREAM_CODEC);
        //?} elif >=1.21.2 {
        /*return new ExtendedScreenHandlerType<MusicDiscMakerMenu, BlockPos>(
                (id, inv, pos) -> {
                    final BlockEntity be = inv.player.level().getBlockEntity(pos);
                    return be instanceof MusicDiscMakerBlockEntity maker
                            ? new MusicDiscMakerMenu(id, inv, maker)
                            : null;
                },
                BlockPos.STREAM_CODEC);
        *///?} elif >=1.21 {
        /*return new ExtendedScreenHandlerType<>(
                (id, inv, pos) -> new MusicDiscMakerMenu(id, inv, pos), BlockPos.STREAM_CODEC);
        *///?} else {
        /*return new ExtendedScreenHandlerType<>(
                (id, inv, buf) -> new MusicDiscMakerMenu(id, inv, buf.readBlockPos()));
        *///?}
    }

    @Override
    public void openMusicDiscMakerMenu(ServerPlayer player, BlockPos pos) {
        //? if >=26.2 {
        player.openMenu(new ExtendedMenuProvider<BlockPos>() {
        //?} elif >=1.21 {
        /*player.openMenu(new ExtendedScreenHandlerFactory<BlockPos>() {
        *///?} else {
        /*player.openMenu(new ExtendedScreenHandlerFactory() {
        *///?}

            @Override
            //? if >=1.21 {
            public BlockPos getScreenOpeningData(ServerPlayer p) {
                return pos;
            //?} else {
            /*public void writeScreenOpeningData(ServerPlayer p, FriendlyByteBuf buf) {
                buf.writeBlockPos(pos);
            *///?}
            }

            @Override
            public Component getDisplayName() {
                return Component.translatable("gui.music_disc_maker.title");
            }

            //? if >=1.21 {
            //?} else {
            /*@Nullable
            *///?}
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
        //? if >=26.2 {
        return new ExtendedMenuType<GoldenJukeboxMenu, BlockPos>(
                (id, inv, pos) -> {
                    final BlockEntity be = inv.player.level().getBlockEntity(pos);
                    return be instanceof GoldenJukeboxBlockEntity jukebox
                            ? new GoldenJukeboxMenu(id, inv, jukebox)
                            : null;
                },
                BlockPos.STREAM_CODEC);
        //?} elif >=1.21.2 {
        /*return new ExtendedScreenHandlerType<GoldenJukeboxMenu, BlockPos>(
                (id, inv, pos) -> {
                    final BlockEntity be = inv.player.level().getBlockEntity(pos);
                    return be instanceof GoldenJukeboxBlockEntity jukebox
                            ? new GoldenJukeboxMenu(id, inv, jukebox)
                            : null;
                },
                BlockPos.STREAM_CODEC);
        *///?} elif >=1.21 {
        /*return new ExtendedScreenHandlerType<>(
                (id, inv, pos) -> new GoldenJukeboxMenu(id, inv, pos), BlockPos.STREAM_CODEC);
        *///?} else {
        /*return new ExtendedScreenHandlerType<>(
                (id, inv, buf) -> new GoldenJukeboxMenu(id, inv, buf.readBlockPos()));
        *///?}
    }

    @Override
    public void openGoldenJukeboxMenu(ServerPlayer player, BlockPos pos) {
        //? if >=26.2 {
        player.openMenu(new ExtendedMenuProvider<BlockPos>() {
        //?} elif >=1.21 {
        /*player.openMenu(new ExtendedScreenHandlerFactory<BlockPos>() {
        *///?} else {
        /*player.openMenu(new ExtendedScreenHandlerFactory() {
        *///?}

            @Override
            //? if >=1.21 {
            public BlockPos getScreenOpeningData(ServerPlayer p) {
                return pos;
            //?} else {
            /*public void writeScreenOpeningData(ServerPlayer p, FriendlyByteBuf buf) {
                buf.writeBlockPos(pos);
            *///?}
            }

            @Override
            public Component getDisplayName() {
                //? if >=1.21.2 {
                return Component.translatable("block.music_disc_maker.golden_jukebox");
                //?} else {
                /*return Component.translatable("gui.music_disc_maker.golden_jukebox.title");
                *///?}
            }

            //? if >=1.21 {
            //?} else {
            /*@Nullable
            *///?}
            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                final BlockEntity be = p.level().getBlockEntity(pos);
                return be instanceof GoldenJukeboxBlockEntity jukebox
                        ? new GoldenJukeboxMenu(id, inv, jukebox)
                        : null;
            }
        });
    }

    @Override
    public MenuType<SpeakerMenu> createSpeakerMenuType() {
        //? if >=26.2 {
        return new ExtendedMenuType<>(SpeakerMenu::new, BlockPos.STREAM_CODEC);
        //?} elif >=1.21 {
        /*return new ExtendedScreenHandlerType<>(SpeakerMenu::new, BlockPos.STREAM_CODEC);
        *///?} else {
        /*return new ExtendedScreenHandlerType<>(
                (id, inv, buf) -> new SpeakerMenu(id, inv, buf.readBlockPos()));
        *///?}
    }

    @Override
    public void openSpeakerMenu(ServerPlayer player, BlockPos pos) {
        //? if >=26.2 {
        player.openMenu(new ExtendedMenuProvider<BlockPos>() {
        //?} elif >=1.21 {
        /*player.openMenu(new ExtendedScreenHandlerFactory<BlockPos>() {
        *///?} else {
        /*player.openMenu(new ExtendedScreenHandlerFactory() {
        *///?}

            @Override
            //? if >=1.21 {
            public BlockPos getScreenOpeningData(ServerPlayer p) {
                return pos;
            //?} else {
            /*public void writeScreenOpeningData(ServerPlayer p, FriendlyByteBuf buf) {
                buf.writeBlockPos(pos);
            *///?}
            }

            @Override
            public Component getDisplayName() {
                return Component.translatable("block.music_disc_maker.speaker");
            }

            //? if >=1.21 {
            //?} else {
            /*@Nullable
            *///?}
            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                final BlockEntity be = p.level().getBlockEntity(pos);
                return be instanceof SpeakerBlockEntity table
                        ? new SpeakerMenu(id, inv, table)
                        : null;
            }
        });
    }
    @Override
    public MenuType<DiscDyeingTableMenu> createDiscDyeingTableMenuType() {
        //? if >=26.2 {
        return new ExtendedMenuType<DiscDyeingTableMenu, BlockPos>(
                (id, inv, pos) -> {
                    final BlockEntity be = inv.player.level().getBlockEntity(pos);
                    return be instanceof DiscDyeingTableBlockEntity table
                            ? new DiscDyeingTableMenu(id, inv, table)
                            : null;
                },
                BlockPos.STREAM_CODEC);
        //?} elif >=1.21.2 {
        /*return new ExtendedScreenHandlerType<DiscDyeingTableMenu, BlockPos>(
                (id, inv, pos) -> {
                    final BlockEntity be = inv.player.level().getBlockEntity(pos);
                    return be instanceof DiscDyeingTableBlockEntity table
                            ? new DiscDyeingTableMenu(id, inv, table)
                            : null;
                },
                BlockPos.STREAM_CODEC);
        *///?} elif >=1.21 {
        /*return new ExtendedScreenHandlerType<>(
                (id, inv, pos) -> new DiscDyeingTableMenu(id, inv, pos), BlockPos.STREAM_CODEC);
        *///?} else {
        /*return new ExtendedScreenHandlerType<>(
                (id, inv, buf) -> new DiscDyeingTableMenu(id, inv, buf.readBlockPos()));
        *///?}
    }

    @Override
    public void openDiscDyeingTableMenu(ServerPlayer player, BlockPos pos) {
        //? if >=26.2 {
        player.openMenu(new ExtendedMenuProvider<BlockPos>() {
        //?} elif >=1.21 {
        /*player.openMenu(new ExtendedScreenHandlerFactory<BlockPos>() {
        *///?} else {
        /*player.openMenu(new ExtendedScreenHandlerFactory() {
        *///?}

            @Override
            //? if >=1.21 {
            public BlockPos getScreenOpeningData(ServerPlayer p) {
                return pos;
            //?} else {
            /*public void writeScreenOpeningData(ServerPlayer p, FriendlyByteBuf buf) {
                buf.writeBlockPos(pos);
            *///?}
            }

            @Override
            public Component getDisplayName() {
                return Component.translatable("block.music_disc_maker.disc_dyeing_table");
            }

            //? if >=1.21 {
            //?} else {
            /*@Nullable
            *///?}
            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                final BlockEntity be = p.level().getBlockEntity(pos);
                return be instanceof DiscDyeingTableBlockEntity table
                        ? new DiscDyeingTableMenu(id, inv, table)
                        : null;
            }
        });
    }
    //? if >=1.21 {

    @Override
    public MenuType<BoomboxMenu> createBoomboxMenuType() {
        //? if >=26.2 {
        return new ExtendedMenuType<BoomboxMenu, BoomboxSource>(
                (id, inv, source) -> new BoomboxMenu(id, inv, source), BoomboxSource.STREAM_CODEC);
        //?} else {
        /*return new ExtendedScreenHandlerType<BoomboxMenu, BoomboxSource>(
                (id, inv, source) -> new BoomboxMenu(id, inv, source), BoomboxSource.STREAM_CODEC);
        *///?}
    }

    @Override
    public void openBoomboxMenu(ServerPlayer player, BoomboxSource source) {
        // 設置した機体は BE が実在する時だけ開く。手持ちは常に開く。
        if (source.isPlaced()
                && !(player.level().getBlockEntity(source.pos()) instanceof BoomboxBlockEntity)) {
            return;
        }
        //? if >=26.2 {
        player.openMenu(new ExtendedMenuProvider<BoomboxSource>() {
        //?} else {
        /*player.openMenu(new ExtendedScreenHandlerFactory<BoomboxSource>() {
        *///?}

            @Override
            public BoomboxSource getScreenOpeningData(ServerPlayer p) {
                return source;
            }

            @Override
            public Component getDisplayName() {
                return Component.translatable("block.music_disc_maker.boombox");
            }

            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                return new BoomboxMenu(id, inv, source);
            }
        });
    }

    @Override
    public MenuType<AlbumMenu> createAlbumMenuType() {
        //? if >=26.2 {
        return new ExtendedMenuType<>((id, inv, source) -> new AlbumMenu(id, inv, source), BoomboxSource.STREAM_CODEC);
        //?} else {
        /*return new ExtendedScreenHandlerType<>((id, inv, source) -> new AlbumMenu(id, inv, source), BoomboxSource.STREAM_CODEC);
        *///?}
    }

    @Override
    public void openAlbumMenu(ServerPlayer player, BoomboxSource source) {
        //? if >=26.2 {
        player.openMenu(new ExtendedMenuProvider<BoomboxSource>() {
        //?} else {
        /*player.openMenu(new ExtendedScreenHandlerFactory<BoomboxSource>() {
        *///?}
            @Override public BoomboxSource getScreenOpeningData(ServerPlayer p) { return source; }
            @Override public Component getDisplayName() { return Component.translatable("item.music_disc_maker.album"); }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) { return new AlbumMenu(id, inv, source); }
        });
    }
    //?} else {

    /*@Override
    public MenuType<BoomboxMenu> createBoomboxMenuType() {
        // BoomboxMenu の client ctor が buf を直接受ける (運ぶのは BlockPos ではなく BoomboxSource)。
        return new ExtendedScreenHandlerType<>((id, inv, buf) -> new BoomboxMenu(id, inv, buf));
    }

    @Override
    public void openBoomboxMenu(ServerPlayer player, BoomboxSource source) {
        // 設置した機体は BE が実在する時だけ開く。手持ちは常に開く。
        if (source.isPlaced()
                && !(player.level().getBlockEntity(source.pos()) instanceof BoomboxBlockEntity)) {
            return;
        }
        player.openMenu(new ExtendedScreenHandlerFactory() {

            @Override
            public void writeScreenOpeningData(ServerPlayer p, FriendlyByteBuf buf) {
                source.write(buf);
            }

            @Override
            public Component getDisplayName() {
                return Component.translatable("block.music_disc_maker.boombox");
            }

            @Override
            public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) {
                return new BoomboxMenu(id, inv, source);
            }
        });
    }

    @Override
    public MenuType<AlbumMenu> createAlbumMenuType() {
        return new ExtendedScreenHandlerType<>((id, inv, buf) -> new AlbumMenu(id, inv, buf));
    }

    @Override
    public void openAlbumMenu(ServerPlayer player, BoomboxSource source) {
        player.openMenu(new ExtendedScreenHandlerFactory() {
            @Override public void writeScreenOpeningData(ServerPlayer p, FriendlyByteBuf buf) { source.write(buf); }
            @Override public Component getDisplayName() { return Component.translatable("item.music_disc_maker.album"); }
            @Override public AbstractContainerMenu createMenu(int id, Inventory inv, Player p) { return new AlbumMenu(id, inv, source); }
        });
    }
    *///?}
}
