package com.kuronami.musicdiscmaker.platform.registry;

import java.util.function.Supplier;

//? if >=1.21.2 {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;
*///?}

/**
 * loader 非依存の登録ハンドル。NeoForge では {@code DeferredHolder}、Fabric では
 * 即時登録済みインスタンスをラップする。common コードは {@link #get()} で値を取る。
 */
public interface RegistryHolder<T> extends Supplier<T> {

    //? if >=1.21.2 {
    Identifier getId();
    //?} else {
    /*ResourceLocation getId();
    *///?}

    @Override
    T get();
}

