package com.kuronami.musicdiscmaker.platform.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

/**
 * Fabric 実装: {@link RegistrationProvider} を {@code Registry.register} の即時登録でラップする。
 * 値の生成は登録時に行われるため、common ホルダの static 初期化 (entry の {@code ModRegistries.init()})
 * で依存順に touch される必要がある。レジストリは {@code onInitialize} 中は開いているので即時登録できる。
 */
public final class FabricRegistrationProvider<T> implements RegistrationProvider<T> {

    private final Registry<T> registry;
    private final String modid;
    private final List<RegistryHolder<T>> entries = new ArrayList<>();
    private final Collection<RegistryHolder<T>> entriesView = Collections.unmodifiableCollection(entries);

    private FabricRegistrationProvider(Registry<T> registry, String modid) {
        this.registry = registry;
        this.modid = modid;
    }

    @Override
    public <R extends T> RegistryHolder<R> register(String name, Supplier<R> factory) {
        final Identifier id = Identifier.fromNamespaceAndPath(this.modid, name);
        final R obj = Registry.register(this.registry, id, factory.get());
        final RegistryHolder<R> holder = new Holder<>(id, obj);
        @SuppressWarnings("unchecked")
        final RegistryHolder<T> upcast = (RegistryHolder<T>) holder;
        this.entries.add(upcast);
        return holder;
    }

    @Override
    public Collection<RegistryHolder<T>> getEntries() {
        return this.entriesView;
    }

    private record Holder<T>(Identifier id, T value) implements RegistryHolder<T> {

        @Override
        public T get() {
            return this.value;
        }

        @Override
        public Identifier getId() {
            return this.id;
        }
    }

    /** ServiceLoader 経由で解決される factory。 */
    public static final class Factory implements RegistrationProvider.Factory {

        @Override
        public <T> RegistrationProvider<T> create(ResourceKey<? extends Registry<T>> registryKey, String modid) {
            @SuppressWarnings("unchecked")
            // 26.2: 根レジストリの値取得は Registry.getValue(Identifier) (旧 get(...) は Optional を返すよう変更)。
            // ResourceKey.location() も identifier() に rename 済み。
            final Registry<T> registry = (Registry<T>) BuiltInRegistries.REGISTRY.getValue(registryKey.identifier());
            if (registry == null) {
                throw new IllegalStateException("root registry に " + registryKey.identifier() + " が見つからない");
            }
            return new FabricRegistrationProvider<>(registry, modid);
        }
    }
}
