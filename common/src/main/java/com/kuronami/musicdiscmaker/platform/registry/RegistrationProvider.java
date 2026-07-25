package com.kuronami.musicdiscmaker.platform.registry;

import java.util.Collection;
import java.util.ServiceLoader;
import java.util.function.Supplier;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;

/**
 * cross-loader な deferred register 抽象。common 側の {@code Mod*} ホルダがこれで登録を宣言し、
 * 各ローダー実装が実際の登録を行う:
 * <ul>
 *   <li>NeoForge — {@code DeferredRegister} をラップし、mod event bus へ bind (遅延)。</li>
 *   <li>Fabric — {@code Registry.register} で即時登録 (entry の onInitialize 内で依存順に touch)。</li>
 * </ul>
 */
public interface RegistrationProvider<T> {

    <R extends T> RegistryHolder<R> register(String name, Supplier<R> factory);

    Collection<RegistryHolder<T>> getEntries();

    static <T> RegistrationProvider<T> get(ResourceKey<? extends Registry<T>> registryKey, String modid) {
        return Factory.INSTANCE.create(registryKey, modid);
    }

    /** ServiceLoader 経由で loader 実装を解決する factory。 */
    interface Factory {

        Factory INSTANCE = ServiceLoader.load(Factory.class).findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "RegistrationProvider.Factory の実装が見つからない (loader 実装の META-INF/services 未登録)"));

        <T> RegistrationProvider<T> create(ResourceKey<? extends Registry<T>> registryKey, String modid);
    }
}
