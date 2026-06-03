package com.kuronami.musicdiscmaker.register;

import java.util.function.Supplier;

import com.kuronami.musicdiscmaker.MusicDiscMaker;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTab {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MusicDiscMaker.MODID);

    public static final Supplier<CreativeModeTab> MAIN = TABS.register("main", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.music_disc_maker"))
            .icon(() -> new ItemStack(ModItems.MUSIC_DISC_MAKER.get()))
            .displayItems((params, output) -> {
                output.accept(ModItems.MUSIC_DISC_MAKER.get());
                output.accept(ModItems.BLANK_DISC.get());
            })
            .build());

    private ModCreativeTab() {
    }

    public static void register(IEventBus modEventBus) {
        TABS.register(modEventBus);
    }
}
