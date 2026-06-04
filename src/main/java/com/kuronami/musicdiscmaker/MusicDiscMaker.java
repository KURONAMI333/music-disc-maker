package com.kuronami.musicdiscmaker;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import com.kuronami.musicdiscmaker.register.ModBlockEntities;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModCreativeTab;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;
import com.kuronami.musicdiscmaker.register.ModMenus;
import com.kuronami.musicdiscmaker.register.ModSounds;
import com.kuronami.musicdiscmaker.network.ModNetwork;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

@Mod(MusicDiscMaker.MODID)
public class MusicDiscMaker {

    public static final String MODID = "music_disc_maker";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MusicDiscMaker(IEventBus modEventBus, ModContainer modContainer) {
        ModDataComponents.register(modEventBus);
        ModBlocks.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModItems.register(modEventBus);
        ModMenus.register(modEventBus);
        ModSounds.register(modEventBus);
        ModCreativeTab.register(modEventBus);

        modEventBus.addListener(this::registerCapabilities);
        modEventBus.addListener(ModNetwork::register);

        modContainer.registerConfig(ModConfig.Type.CLIENT, Config.SPEC);
    }

    private void registerCapabilities(RegisterCapabilitiesEvent event) {
        // hopper 等から空ディスク投入 / 出力取り出しを可能にする
        event.registerBlockEntity(Capabilities.ItemHandler.BLOCK, ModBlockEntities.MUSIC_DISC_MAKER.get(),
                (be, side) -> be.getInventory());
    }
}
