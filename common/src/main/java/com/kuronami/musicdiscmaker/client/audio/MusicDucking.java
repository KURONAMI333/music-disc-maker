package com.kuronami.musicdiscmaker.client.audio;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import com.kuronami.musicdiscmaker.Config;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/**
 * 盤の曲が聞こえている間は BGM を止め、曲が止んだら同じ BGM を続きから鳴らす (client 設定
 * {@link Config#muteMusicDuringDiscs()}・既定は有効)。MDM の再生機器 (金ジューク・ブームボックス・スピーカー) だけでなく、
 * バニラのジュークボックスや他 MOD の盤も含める。
 *
 * <p>BGM は止める (stop) のではなく、「音楽」分類の音のチャンネルを一時停止する。止めると曲が終わった後に
 * 別の BGM が数分待ってから始まるが、一時停止なら同じ曲がその場から戻る。保留中は {@code MusicManager#tick} を
 * 進めないので、保留中に次の BGM が始まることもない。判定と一時停止は {@code MixinMusicManagerDucking}
 * と {@code MixinSoundEngineMusicDucking} から呼ぶ (client main thread)。
 */
public final class MusicDucking {

    /** いま一時停止している「音楽」分類の音。戻す時はここに入れたものだけ戻す。 */
    private static final Set<SoundInstance> HELD = Collections.newSetFromMap(new IdentityHashMap<>());

    private MusicDucking() {
    }

    /**
     * {@code MusicManager#tick} の頭で毎 tick 呼ぶ。盤の曲が聞こえていれば BGM を一時停止して true
     * (= その tick の MusicManager を進めない)、聞こえていなければ止めていた BGM を戻して false。
     */
    public static boolean holdMusic() {
        final Access engine = engine();
        if (Config.muteMusicDuringDiscs() && engine.mdm$isDiscAudible()) {
            // 毎 tick 掛け直す: 鳴り始めた直後の BGM はまだチャンネルが無く、1 tick 後に止まる。
            engine.mdm$pauseMusic(HELD);
            return true;
        }
        if (!HELD.isEmpty()) {
            engine.mdm$unpause(HELD);
            HELD.clear();
        }
        return false;
    }

    /** ゲームのポーズ解除 ({@code SoundEngine#resume}) は全チャンネルを戻すので、保留中の BGM だけ止め直す。 */
    public static void afterEngineResume(Access engine) {
        if (!HELD.isEmpty()) {
            engine.mdm$pauseMusic(HELD);
        }
    }

    /**
     * 盤の曲として聞こえている音か。MDM の再生は {@link DiscSoundInstance} の範囲で、それ以外は「レコード」分類の
     * ストリーム音 (バニラや他 MOD の盤。音符ブロックはストリームでないので数えない) をバニラの減衰距離で判定する。
     */
    public static boolean isAudibleDisc(SoundInstance instance, Vec3 ear) {
        if (instance instanceof DiscSoundInstance disc) {
            return disc.isInRangeOf(ear);
        }
        if (instance.getSource() != SoundSource.RECORDS) {
            return false;
        }
        final Sound sound = instance.getSound();
        if (sound == null || !sound.shouldStream() || instance.getVolume() <= 0.0F) {
            return false;
        }
        if (instance.isRelative() || instance.getAttenuation() == SoundInstance.Attenuation.NONE) {
            return true;
        }
        // SoundEngine#play と同じ式の可聴半径。
        final double range = Math.max(instance.getVolume(), 1.0F) * sound.getAttenuationDistance();
        return ear.distanceToSqr(instance.getX(), instance.getY(), instance.getZ()) <= range * range;
    }

    private static Access engine() {
        return (Access) ((SoundEngineHolder) Minecraft.getInstance().getSoundManager()).mdm$soundEngine();
    }

    /** {@code MixinSoundEngineMusicDucking} が {@code SoundEngine} に実装する duck interface。 */
    public interface Access {

        /** 再生中の音に、聞こえている盤の曲が 1 つでもあるか。 */
        boolean mdm$isDiscAudible();

        /** 再生中の「音楽」分類の音をすべて一時停止し、{@code into} に加える。 */
        void mdm$pauseMusic(Set<SoundInstance> into);

        /** 指定の音のチャンネルを再開する。もう鳴っていない音は何もしない。 */
        void mdm$unpause(Set<SoundInstance> instances);
    }
}
