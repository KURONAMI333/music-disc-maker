package com.kuronami.musicdiscmaker.platform.registry;

import java.util.function.Supplier;

import net.minecraft.resources.Identifier;

/**
 * loader 非依存の登録ハンドル。NeoForge では {@code DeferredHolder}、Fabric では
 * 即時登録済みインスタンスをラップする。common コードは {@link #get()} で値を取る。
 */
public interface RegistryHolder<T> extends Supplier<T> {

    Identifier getId();

    @Override
    T get();
}
