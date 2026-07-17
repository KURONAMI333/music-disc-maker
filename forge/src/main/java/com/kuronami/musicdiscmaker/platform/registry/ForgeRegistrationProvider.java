package com.kuronami.musicdiscmaker.platform.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * Forge 実装: common の {@link RegistrationProvider} を {@link DeferredRegister} でラップする。
 * common 側ホルダの static 初期化で生成された provider を集約し、entry が {@link #registerAll(IEventBus)}
 * で全 {@link DeferredRegister} を mod event bus へ bind する。
 */
public final class ForgeRegistrationProvider<T> implements RegistrationProvider<T> {

    // common ホルダの static 初期化で生成される全 provider。entry で一括 bind する。
    private static final List<ForgeRegistrationProvider<?>> ALL = new ArrayList<>();

    private final DeferredRegister<T> deferredRegister;
    private final List<RegistryHolder<T>> entries = new ArrayList<>();
    private final Collection<RegistryHolder<T>> entriesView = Collections.unmodifiableCollection(entries);

    private ForgeRegistrationProvider(ResourceKey<? extends Registry<T>> registryKey, String modid) {
        this.deferredRegister = DeferredRegister.create(registryKey, modid);
        synchronized (ALL) {
            ALL.add(this);
        }
    }

    @Override
    public <R extends T> RegistryHolder<R> register(String name, Supplier<R> factory) {
        final RegistryObject<R> object = this.deferredRegister.register(name, factory);
        final RegistryHolder<R> holder = new Holder<>(object);
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
            for (final ForgeRegistrationProvider<?> provider : ALL) {
                provider.deferredRegister.register(bus);
            }
        }
    }

    private record Holder<T>(RegistryObject<T> object) implements RegistryHolder<T> {

        @Override
        public T get() {
            return this.object.get();
        }

        @Override
        public ResourceLocation getId() {
            return this.object.getId();
        }
    }

    /** ServiceLoader 経由で解決される factory。 */
    public static final class Factory implements RegistrationProvider.Factory {

        @Override
        public <T> RegistrationProvider<T> create(ResourceKey<? extends Registry<T>> registryKey, String modid) {
            return new ForgeRegistrationProvider<>(registryKey, modid);
        }
    }
}
