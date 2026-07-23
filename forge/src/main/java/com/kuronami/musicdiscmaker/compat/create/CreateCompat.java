package com.kuronami.musicdiscmaker.compat.create;

import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;

/**
 * Create 互換のソフト配線。{@code create} がロードされている時だけ、強化版ジュークボックスへ
 * {@link CreateAudioMovementBehaviour} を登録し、Create 捕獲式 contraption に載った時の再生継続を有効にする。
 *
 * <p>{@link #isLoaded()} は Create クラスを参照しない ({@code Services.PLATFORM} のみ) ので常に安全。
 * {@link #registerMovementBehaviour()} は Create クラス ({@link MovementBehaviour} registry /
 * {@link CreateAudioMovementBehaviour}) を参照するので、呼び出し側で必ず {@link #isLoaded()} ガードして
 * から呼ぶ (こうすれば Create 非導入環境でメソッド検証時に Create が class-load されない)。
 * {@code SophisticatedCoreCompat} と同型のパターン。API は forge/fabric 同一 (Create 6.0.8 / 6.0.8.1)
 * なので共有 common に置く。
 */
public final class CreateCompat {

    public static final String MODID = "create";

    /** isModLoaded は不変なのでキャッシュする。 */
    private static Boolean loaded;

    private CreateCompat() {
    }

    public static boolean isLoaded() {
        if (loaded == null) {
            loaded = Services.PLATFORM.isModLoaded(MODID);
        }
        return loaded;
    }

    /**
     * 強化版ジュークボックスへ MovementBehaviour を登録する。必ず {@link #isLoaded()} が true の時だけ
     * 呼ぶこと (Create クラスを参照するため)。捕獲式 contraption に載った時の音源追従を有効にする。
     * server/client 両側で呼んでよい (client でも tick は要求しないが登録自体は無害)。
     */
    public static void registerMovementBehaviour() {
        MovementBehaviour.REGISTRY.register(ModBlocks.GOLDEN_JUKEBOX.get(), new CreateAudioMovementBehaviour());
    }
}
