package com.kuronami.musicdiscmaker.compat.sophisticatedcore;

import com.kuronami.musicdiscmaker.MusicDiscMaker;

import net.minecraftforge.fml.ModList;

/**
 * Sophisticated Core 互換のソフト配線 (Forge 1.20.1)。{@code sophisticatedcore} がロードされている時だけ
 * {@link MdmDiscHandler} を {@link net.p3pp3rf1y.sophisticatedcore.upgrades.jukebox.DiscHandlerRegistry} へ登録する。
 *
 * <p>{@link #isLoaded()} は SC クラスを参照しない (ModList のみ) ので常に安全。{@link #registerHandler()} は
 * SC の新しい disc handler API を持つ場合だけ登録する。MOD の存在だけでは API の存在を保証しない。
 * DeceasedCraft 5.10.17 に固定された SC 1.2.106.1235 にはこの API が無く、従来の無条件登録は
 * 起動時に NoClassDefFoundError を起こす。API を直接参照する処理は別クラスへ遅延し、
 * 判定前のクラス検証でも新 API を要求しない。
 */
public final class SophisticatedCoreCompat {

    public static final String SC_MODID = "sophisticatedcore";

    private SophisticatedCoreCompat() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded(SC_MODID);
    }

    /** 呼び出し元の MOD 存在判定に加え、実際の API が利用できる時だけ登録する。 */
    public static void registerHandler() {
        if (!hasDiscHandlerApi()) {
            MusicDiscMaker.LOGGER.info("Sophisticated Core disc handler API is unavailable; skipping its streaming adapter");
            return;
        }
        DiscHandlerRegistration.register();
    }

    public static boolean hasDiscHandlerApi() {
        final ClassLoader loader = SophisticatedCoreCompat.class.getClassLoader();
        try {
            Class.forName("net.p3pp3rf1y.sophisticatedcore.api.IDiscHandler", false, loader);
            Class.forName("net.p3pp3rf1y.sophisticatedcore.upgrades.jukebox.DiscHandlerRegistry", false, loader);
            return true;
        } catch (ClassNotFoundException | LinkageError unavailable) {
            return false;
        }
    }

    /** API 判定を通過するまでロードしない。外側のクラスには SC 型を要求させない。 */
    private static final class DiscHandlerRegistration {

        private static void register() {
            // v1.1.0 から disc は RecordItem なので SC の VanillaDiscHandler (instanceof RecordItem)
            // も我々の disc を supports する。findHandler は登録順の先勝ちなので、先頭 (index 0) に
            // 割り込んで必ず我々が先勝ちする (= backpack で無音でなく実ストリームが鳴る)。getHandlers は可変 List。
            net.p3pp3rf1y.sophisticatedcore.upgrades.jukebox.DiscHandlerRegistry.getHandlers().add(0, new MdmDiscHandler());
        }
    }
}
