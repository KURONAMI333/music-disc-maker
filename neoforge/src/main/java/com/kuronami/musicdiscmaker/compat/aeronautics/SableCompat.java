package com.kuronami.musicdiscmaker.compat.aeronautics;

import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Create Aeronautics (Sable 物理エンジン) 互換のソフト配線。物理飛空艇 = Sable 変換式 sub-level に載った
 * 強化版ジュークボックスの再生追従を、{@code sable} がロードされている時だけ有効にする。
 *
 * <p>gate は物理コアの {@code sable} を見る。Aeronautics (modid {@code aeronautics}) はこの Sable を必須依存と
 * して同梱し、MDM が使う座標変換・sub-level tracking API は全て Sable が提供するため。Sable 型を触る配線
 * ({@link SableServerAudio} の登録) は全て {@link #isLoaded()} gate 通過後の enqueueWork ラムダ内でのみ
 * class-load される (Sable 非導入で NoClassDefFoundError にならない。{@code CreateCompat} と同型)。
 *
 * <p>設計方針: Sable の sub-level は独自同期で vanilla chunk-tracking を通らないため、MDM の通常再生 trigger は
 * 届かない。{@link SableServerAudio} が server tick で sub-level を走査し、tracking player へ
 * {@link SubLevelPlayDiscPayload} を送る。client は {@link SableAudioClient} が {@link SableSubLevelAnchor} で
 * 変換式追従再生する (詳細は各クラスの javadoc)。
 */
public final class SableCompat {

    /** 物理エンジン Sable の modid (Aeronautics の必須依存。API 提供層)。 */
    public static final String SABLE_MODID = "sable";

    private SableCompat() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded(SABLE_MODID);
    }

    /** mod event bus に無条件登録してよい (内部で isLoaded ガード)。 */
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        if (!isLoaded()) {
            return;
        }
        // ここから先で初めて Sable 参照クラス (SableServerAudio) が class-load される。物理 sub-level に載った
        // 強化版ジュークボックスの再生を tracking player へ橋渡しする server tick 走査を登録する。
        event.enqueueWork(() -> NeoForge.EVENT_BUS.addListener(SableServerAudio::onServerTick));
    }

    /**
     * sub-level 再生ペイロードの client 受信を登録する。ペイロード自体は Sable 非依存の vanilla 型なので
     * 無条件でよい (Sable 未導入なら送信されないだけ)。受信ハンドラ内の Sable 参照 (SableSubLevelAnchor) は
     * invoke 時のみ class-load。
     */
    public static void registerPayload(PayloadRegistrar registrar) {
        registrar.playToClient(SubLevelPlayDiscPayload.TYPE, SubLevelPlayDiscPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> SableAudioClient.play(payload)));
    }
}
