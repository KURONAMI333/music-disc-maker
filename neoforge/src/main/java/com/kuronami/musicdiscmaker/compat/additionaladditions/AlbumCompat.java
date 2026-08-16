package com.kuronami.musicdiscmaker.compat.additionaladditions;

import com.kuronami.musicdiscmaker.compat.album.AlbumSupport;

import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

/**
 * アルバム互換のソフト配線 (NeoForge)。{@code additionaladditions} がロードされている時だけ
 * {@link AlbumProvider} を {@link AlbumSupport} へ差し込み、強化版ジュークボックスがアルバムを
 * 受け入れてトラックを送れるようにする。
 *
 * <p>AA のクラス参照 ({@link AlbumProvider}) は {@code isLoaded()} ガードを通過した後の
 * enqueueWork ラムダ内でのみ class-load される ({@code CreateCompat} と同型のパターン)。
 * common setup は client / dedicated server の両方で走るので、GUI 側の表示にも同じ実装が効く。
 */
public final class AlbumCompat {

    public static final String AA_MODID = "additionaladditions";

    private AlbumCompat() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded(AA_MODID);
    }

    /** mod event bus に無条件登録してよい (内部で isLoaded ガード)。 */
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        if (!isLoaded()) {
            return;
        }
        event.enqueueWork(AlbumProvider::install);
    }
}
