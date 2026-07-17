package com.kuronami.musicdiscmaker.register;

/**
 * 各ローダー entry から呼ぶ登録 bootstrap。
 * Fabric は {@code Registry.register} 即時登録なので依存順に touch する必要がある
 * (BlockItem→Block / BlockEntity→Block / CreativeTab→Item)。NeoForge は遅延なので順不同で可。
 */
public final class ModRegistries {

    private ModRegistries() {
    }

    public static void init() {
        ModBlocks.init();
        ModBlockEntities.init();
        ModItems.init();
        ModDataComponents.init();
        ModSounds.init();
        ModMenus.init();
        ModCreativeTab.init();
    }
}
