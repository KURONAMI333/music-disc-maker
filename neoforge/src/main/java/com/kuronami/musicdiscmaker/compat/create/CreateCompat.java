package com.kuronami.musicdiscmaker.compat.create;

import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;

import net.neoforged.fml.ModList;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Create 互換のソフト配線。{@code create} がロードされている時だけ、強化版ジュークボックスへ
 * {@link CreateAudioMovementBehaviour} を登録し、Create 捕獲式 contraption に載った時の再生継続を
 * 有効にする。
 *
 * <p>Create のクラス参照 ({@link MovementBehaviour}/{@link CreateAudioMovementBehaviour}) は全て
 * {@code isLoaded()} ガードを通過した後の enqueueWork ラムダ内でのみ class-load される。よって Create
 * 非導入環境でこの class をロードしても NoClassDefFoundError にならない (import はシンボル解決のみで
 * class-load を起こさず、ラムダ本体は Create 導入時にしか実行されない)。{@code SophisticatedCoreCompat}
 * と同型のパターン。
 *
 * <p>設計方針 (spike C 節 / server 主導): 組立時に server の {@link CreateAudioMovementBehaviour#startMoving}
 * が凍結 BE から track を読んで client へ payload を送り、client が {@link ContraptionAnchor} で追従再生する。
 * client 側で {@link MovementBehaviour#tick} を要求しないため「tick が client で走るか」という未検証前提に
 * 依存しない (contraption entity の id 解決 + toGlobalVector = public API のみ)。
 */
public final class CreateCompat {

    public static final String CREATE_MODID = "create";

    private CreateCompat() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded(CREATE_MODID);
    }

    /** mod event bus に無条件登録してよい (内部で isLoaded ガード)。 */
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        if (!isLoaded()) {
            return;
        }
        // ここから先で初めて Create クラス (MovementBehaviour registry / CreateAudioMovementBehaviour) が
        // class-load される。捕獲式 contraption に載った強化版ジュークボックスの音源追従を有効にする。
        event.enqueueWork(() -> MovementBehaviour.REGISTRY.register(
                ModBlocks.GOLDEN_JUKEBOX.get(), new CreateAudioMovementBehaviour()));
    }

    /**
     * contraption 再生ペイロードの client 受信を登録する。ペイロード自体は Create 非依存なので無条件で
     * よい (Create 未導入なら送信されないだけ)。受信ハンドラ内の Create 参照は invoke 時のみ class-load。
     */
    public static void registerPayload(PayloadRegistrar registrar) {
        registrar.playToClient(ContraptionPlayDiscPayload.TYPE, ContraptionPlayDiscPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> CreateAudioClient.play(payload)));
    }
}
