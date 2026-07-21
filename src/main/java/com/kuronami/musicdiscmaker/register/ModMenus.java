package com.kuronami.musicdiscmaker.register;

import java.util.function.Supplier;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.menu.EnhancedJukeboxMenu;
import com.kuronami.musicdiscmaker.menu.MusicDiscMakerMenu;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, MusicDiscMaker.MODID);

    public static final Supplier<MenuType<MusicDiscMakerMenu>> MUSIC_DISC_MAKER =
            MENUS.register("music_disc_maker", () -> IMenuTypeExtension.create(MusicDiscMakerMenu::new));

    public static final Supplier<MenuType<EnhancedJukeboxMenu>> ENHANCED_JUKEBOX =
            MENUS.register("enhanced_jukebox", () -> IMenuTypeExtension.create(EnhancedJukeboxMenu::new));

    private ModMenus() {
    }

    public static void register(IEventBus modEventBus) {
        MENUS.register(modEventBus);
    }
}
