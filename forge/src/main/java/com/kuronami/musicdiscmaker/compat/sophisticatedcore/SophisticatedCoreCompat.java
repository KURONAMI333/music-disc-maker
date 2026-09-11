package com.kuronami.musicdiscmaker.compat.sophisticatedcore;

import net.minecraftforge.fml.ModList;
import net.p3pp3rf1y.sophisticatedcore.upgrades.jukebox.DiscHandlerRegistry;

/**
 * Sophisticated Core 互換のソフト配線 (Forge 1.20.1)。{@code sophisticatedcore} がロードされている時だけ
 * {@link MdmDiscHandler} を {@link DiscHandlerRegistry} へ登録する。
 *
 * <p>{@link #isLoaded()} は SC クラスを参照しない (ModList のみ) ので常に安全。{@link #registerHandler()} は
 * SC クラスを参照するので、呼び出し側で必ず {@link #isLoaded()} ガードしてから呼ぶ
 * (こうすれば SC 非導入環境でメソッド検証時に SC が class-load されない)。
 */
public final class SophisticatedCoreCompat {

    public static final String SC_MODID = "sophisticatedcore";

    private SophisticatedCoreCompat() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded(SC_MODID);
    }

    /** 必ず {@link #isLoaded()} が true の時だけ呼ぶこと (SC クラスを参照するため)。 */
    public static void registerHandler() {
        // v1.1.0 から disc は RecordItem なので SC の VanillaDiscHandler (instanceof RecordItem)
        // も我々の disc を supports する。findHandler は登録順の先勝ちなので、先頭 (index 0) に
        // 割り込んで必ず我々が先勝ちする (= backpack で無音でなく実ストリームが鳴る)。getHandlers は可変 List。
        DiscHandlerRegistry.getHandlers().add(0, new MdmDiscHandler());
    }
}
