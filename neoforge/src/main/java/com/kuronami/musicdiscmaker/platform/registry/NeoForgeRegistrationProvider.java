package com.kuronami.musicdiscmaker.platform.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import com.kuronami.musicdiscmaker.platform.registry.RegistrationProvider;
import com.kuronami.musicdiscmaker.platform.registry.RegistryHolder;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * NeoForge 実装: common の {@link RegistrationProvider} を {@link DeferredRegister} でラップする。
 * common 側ホルダの static 初期化で生成された provider を集約し、entry が {@link #registerAll(IEventBus)}
 * で全 {@link DeferredRegister} を mod event bus へ bind する。
 */
public final class NeoForgeRegistrationProvider<T> implements RegistrationProvider<T> {

    // common ホルダの static 初期化で生成される全 provider。entry で一括 bind する。
    private static final List<NeoForgeRegistrationProvider<?>> ALL = new ArrayList<>();

    private final DeferredRegister<T> deferredRegister;
    private final List<RegistryHolder<T>> entries = new ArrayList<>();
    private final Collection<RegistryHolder<T>> entriesView = Collections.unmodifiableCollection(entries);

    private NeoForgeRegistrationProvider(ResourceKey<? extends Registry<T>> registryKey, String modid) {
        this.deferredRegister = DeferredRegister.create(registryKey, modid);
        synchronized (ALL) {
            ALL.add(this);
        }
    }

    @Override
    public <R extends T> RegistryHolder<R> register(String name, Supplier<R> factory) {
        final DeferredHolder<T, R> deferred = this.deferredRegister.register(name, factory);
        final RegistryHolder<R> holder = new Holder<>(deferred);
        @SuppressWarnings("unchecked")
        final RegistryHolder<T> upcast = (RegistryHolder<T>) holder;
        this.entries.add(upcast);
        return holder;
    }

    @Override
    public Collection<RegistryHolder<T>> getEntries() {
        return this.entriesView;
    }

    /** 集約した全 {@link DeferredRegister} を mod event bus へ bind する (entry から呼ぶ)。 */
    public static void registerAll(IEventBus bus) {
        synchronized (ALL) {
            for (final NeoForgeRegistrationProvider<?> provider : ALL) {
                provider.deferredRegister.register(bus);
            }
        }
    }

    private record Holder<T>(DeferredHolder<? super T, T> deferred) implements RegistryHolder<T> {

        @Override
        public T get() {
            return this.deferred.get();
        }

        @Override
        public ResourceLocation getId() {
            return this.deferred.getId();
        }
    }

    /** ServiceLoader 経由で解決される factory。 */
    public static final class Factory implements RegistrationProvider.Factory {

        @Override
        public <T> RegistrationProvider<T> create(ResourceKey<? extends Registry<T>> registryKey, String modid) {
            return new NeoForgeRegistrationProvider<>(registryKey, modid);
        }
    }
}
