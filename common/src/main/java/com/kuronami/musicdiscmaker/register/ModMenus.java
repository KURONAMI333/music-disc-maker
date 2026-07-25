package com.kuronami.musicdiscmaker.register;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.menu.BoomboxMenu;
import com.kuronami.musicdiscmaker.menu.GoldenJukeboxMenu;
import com.kuronami.musicdiscmaker.menu.MusicDiscMakerMenu;
import com.kuronami.musicdiscmaker.menu.SpeakerMenu;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.platform.registry.RegistrationProvider;
import com.kuronami.musicdiscmaker.platform.registry.RegistryHolder;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;

public final class ModMenus {

    public static final RegistrationProvider<MenuType<?>> MENUS =
            RegistrationProvider.get(Registries.MENU, MusicDiscMaker.MODID);

    // extended MenuType の生成は loader 固有 (NeoForge=IMenuTypeExtension / Fabric=ExtendedScreenHandlerType)。
    // 登録自体は generic provider、値だけ Service が供給する。
    public static final RegistryHolder<MenuType<MusicDiscMakerMenu>> MUSIC_DISC_MAKER =
            MENUS.register("music_disc_maker", () -> Services.MENU.createMusicDiscMakerMenuType());

    public static final RegistryHolder<MenuType<GoldenJukeboxMenu>> GOLDEN_JUKEBOX =
            MENUS.register("golden_jukebox", () -> Services.MENU.createGoldenJukeboxMenuType());

    public static final RegistryHolder<MenuType<SpeakerMenu>> SPEAKER =
            MENUS.register("speaker", () -> Services.MENU.createSpeakerMenuType());

    public static final RegistryHolder<MenuType<BoomboxMenu>> BOOMBOX =
            MENUS.register("boombox", () -> Services.MENU.createBoomboxMenuType());

    private ModMenus() {
    }

    public static void init() {
    }
}
