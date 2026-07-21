package com.kuronami.musicdiscmaker.register;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.platform.registry.RegistrationProvider;
import com.kuronami.musicdiscmaker.platform.registry.RegistryHolder;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

public final class ModCreativeTab {

    public static final RegistrationProvider<CreativeModeTab> TABS =
            RegistrationProvider.get(Registries.CREATIVE_MODE_TAB, MusicDiscMaker.MODID);

    public static final RegistryHolder<CreativeModeTab> MAIN =
            TABS.register("main", () -> CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                    .title(Component.translatable("itemGroup.music_disc_maker"))
                    .icon(() -> new ItemStack(ModItems.MUSIC_DISC_MAKER.get()))
                    .displayItems((params, output) -> {
                        output.accept(ModItems.MUSIC_DISC_MAKER.get());
                        output.accept(ModItems.ENHANCED_JUKEBOX.get());
                        output.accept(ModItems.BLANK_DISC.get());
                    })
                    .build());

    private ModCreativeTab() {
    }

    public static void init() {
    }
}
