package com.kuronami.musicdiscmaker.compat.sophisticatedcore;

import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.p3pp3rf1y.sophisticatedcore.upgrades.jukebox.DiscHandlerRegistry;

/**
 * Sophisticated Core 互換のソフト配線。{@code sophisticatedcore} がロードされている時だけ
 * {@link MdmDiscHandler} を {@link DiscHandlerRegistry} へ登録する。
 *
 * <p>SC のクラス参照は全て {@code isLoaded()} ガードの内側でのみ class-load されるので、
 * SC 非導入環境でも安全 (NoClassDefFoundError にならない)。
 */
public final class SophisticatedCoreCompat {

    public static final String SC_MODID = "sophisticatedcore";

    private SophisticatedCoreCompat() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded(SC_MODID);
    }

    /** mod event bus に無条件登録してよい (内部で isLoaded ガード)。 */
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        if (!isLoaded()) {
            return;
        }
        // ここから先で初めて SC クラス (DiscHandlerRegistry/MdmDiscHandler) が class-load される。
        // v1.1.0 から custom disc は JUKEBOX_PLAYABLE component を持つため、SC の VanillaDiscHandler
        // (JukeboxSong.fromStack ベース) も我々の disc を supports してしまう。findHandler は登録順
        // 線形走査で最初の supports 勝ちなので、先頭 (index 0) に割り込んで必ず我々が先勝ちする
        // (= backpack で無音の silent song でなく実ストリームが鳴る)。getHandlers() は生の可変 List。
        event.enqueueWork(() -> DiscHandlerRegistry.getHandlers().add(0, new MdmDiscHandler()));
    }

    /**
     * backpack 再生ペイロードの client 受信を登録する。ペイロード自体は SC 非依存なので無条件でよい
     * (SC 未導入なら送信されないだけ)。受信ハンドラ内の SC 参照は invoke 時のみ class-load される。
     */
    public static void registerPayload(PayloadRegistrar registrar) {
        registrar.playToClient(BackpackPlayDiscPayload.TYPE, BackpackPlayDiscPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> SophisticatedCoreCompatClient.play(payload)));
    }
}
