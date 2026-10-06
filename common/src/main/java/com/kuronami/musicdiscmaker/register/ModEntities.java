package com.kuronami.musicdiscmaker.register;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.entity.GoldenJukeboxMinecartEntity;
import com.kuronami.musicdiscmaker.platform.registry.RegistrationProvider;
import com.kuronami.musicdiscmaker.platform.registry.RegistryHolder;

import net.minecraft.core.registries.Registries;
//? if >=1.21.2 {
//? if >=1.21.11 {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}
import net.minecraft.resources.ResourceKey;
//?}
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

/**
 * entity 登録。現状は金のジュークボックス付きトロッコのみ。
 * Fabric は即時登録なので {@code ModItems} より先に init すること
 * ({@code GoldenJukeboxMinecartItem} 生成時に entity type を参照する帯がある)。
 */
public final class ModEntities {

    public static final RegistrationProvider<EntityType<?>> ENTITIES =
            RegistrationProvider.get(Registries.ENTITY_TYPE, MusicDiscMaker.MODID);

    /** 金のジュークボックス付きトロッコ。サイズ・追尾距離はバニラのコンテナ付きトロッコと同じ。 */
    public static final RegistryHolder<EntityType<GoldenJukeboxMinecartEntity>> GOLDEN_JUKEBOX_MINECART =
            ENTITIES.register("golden_jukebox_minecart",
                    () -> EntityType.Builder
                            .<GoldenJukeboxMinecartEntity>of(GoldenJukeboxMinecartEntity::new, MobCategory.MISC)
                            .sized(0.98F, 0.7F)
                            .clientTrackingRange(8)
                            //? if >=1.21.2 {
                            //? if >=1.21.11 {
                            .build(ResourceKey.create(Registries.ENTITY_TYPE,
                                    Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID,
                                            "golden_jukebox_minecart")))
                            //?} else {
                            /*.build(ResourceKey.create(Registries.ENTITY_TYPE,
                                    ResourceLocation.fromNamespaceAndPath(MusicDiscMaker.MODID,
                                            "golden_jukebox_minecart")))
                            *///?}
                            //?} else {
                            /*.build("golden_jukebox_minecart")
                            *///?}
            );

    private ModEntities() {
    }

    public static void init() {
    }
}
