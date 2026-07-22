package com.kuronami.musicdiscmaker.client.audio;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import com.kuronami.musicdiscmaker.Config;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.register.ModBlocks;
import com.kuronami.musicdiscmaker.register.ModSounds;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public class DiscSoundInstance extends AbstractTickableSoundInstance {

    private final IAudioSource source;
    /** source を高々一度だけ close するためのガード (requestStop と stream 経由の close の二重解放を防ぐ)。 */
    private final AtomicBoolean sourceClosed = new AtomicBoolean(false);
    @Nullable
    private final Entity followEntity;
    /** block 再生時の jukebox 位置 (entity 再生時は null)。撤去検知に使う。 */
    @Nullable
    private final BlockPos blockPos;
    /** 強化版ジュークボックス由来の per-block 可聴範囲 (ブロック)。0 = client config の playbackRange。 */
    private final int rangeBlocks;
    /** 強化版ジュークボックス由来の音量 (%)。100 = 通常 (config volumeMultiplier のみ)。強化版は毎 tick 再読する。 */
    private int volumePercent;
    /** ストリーム終端 (read=-1) 時に一度だけ呼ばれるコールバック (ラジオ再接続用)。null=無効。 */
    @Nullable
    private final Runnable onStreamEnded;

    public DiscSoundInstance(BlockPos pos, IAudioSource source) {
        this(pos, source, 0, 100, null);
    }

    public DiscSoundInstance(BlockPos pos, IAudioSource source, int rangeBlocks, int volumePercent,
            @Nullable Runnable onStreamEnded) {
        super(ModSounds.CUSTOM_DISC_PLAYBACK.get(), SoundSource.RECORDS, RandomSource.create());
        this.source = source;
        this.followEntity = null;
        this.blockPos = pos.immutable();
        this.rangeBlocks = rangeBlocks;
        this.volumePercent = volumePercent;
        this.onStreamEnded = onStreamEnded;
        this.x = pos.getX() + 0.5;
        this.y = pos.getY() + 0.5;
        this.z = pos.getZ() + 0.5;
        initCommon();
    }

    public DiscSoundInstance(Entity entity, IAudioSource source) {
        super(ModSounds.CUSTOM_DISC_PLAYBACK.get(), SoundSource.RECORDS, RandomSource.create());
        this.source = source;
        this.followEntity = entity;
        this.blockPos = null;
        this.rangeBlocks = 0;
        this.volumePercent = 100;
        this.onStreamEnded = null;
        this.x = entity.getX();
        this.y = entity.getY();
        this.z = entity.getZ();
        initCommon();
    }

    private void initCommon() {
        this.volume = computeVolume();
        this.pitch = 1.0F;
        this.looping = false;
        this.relative = false;
        this.attenuation = Attenuation.LINEAR;
    }

    /** 実効音量 = (volumePercent/100) × client の Records 相対倍率。 */
    private float computeVolume() {
        return (float) (volumePercent / 100.0 * Config.volumeMultiplier());
    }

    /**
     * 実効可聴範囲。強化版 (rangeBlocks>0) は per-block 設定を maxPlaybackRange (既定 256) で頭打ちにし、
     * 通常 jukebox の playbackRange (Fabric は 64 固定) には縛られない。無設定は従来通り playbackRange。
     */
    private int effectiveRange() {
        if (rangeBlocks > 0) {
            return Math.min(rangeBlocks, Config.maxPlaybackRange());
        }
        return Config.playbackRange();
    }

    /**
     * このインスタンスに焼き込まれた可聴範囲 (ブロック)。0 = per-block 設定なし。
     * 減衰半径は {@link #resolve} で固定されるため、範囲変更の反映には再ストリームが要る。
     * その要否判定 ({@link ClientPlaybackManager} の dedup) に使う。
     */
    public int getRangeBlocks() {
        return rangeBlocks;
    }

    @Override
    public void tick() {
        // jukebox が撤去されたら (破壊・爆発・ピストン・コマンド等いずれの経路でも) 鳴りっぱなしを止める。
        // chunk 未ロード時は air が返るため isLoaded でゲートする (遠距離 = playbackRange 内の減衰を誤って切らない)。
        // 対象は vanilla jukebox と強化版ジュークボックスの両方 (強化版は独自ブロックなので明示的に許可する)。
        if (blockPos != null) {
            final Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.level.isLoaded(blockPos)) {
                final BlockState state = mc.level.getBlockState(blockPos);
                final boolean isEnhanced = state.is(ModBlocks.GOLDEN_JUKEBOX.get());
                if (!state.is(Blocks.JUKEBOX) && !isEnhanced) {
                    stop();
                    return;
                }
                // 強化版: client 側 BE から音量を毎 tick 再読し、スライダー操作を再ロードなしで即反映する。
                if (isEnhanced && mc.level.getBlockEntity(blockPos) instanceof GoldenJukeboxBlockEntity be) {
                    this.volumePercent = be.getVolumePercent();
                    this.volume = computeVolume();
                }
            }
        }
        if (followEntity != null) {
            if (followEntity.isRemoved()) {
                stop();
                return;
            }
            if (followEntity instanceof Player player) {
                final Vec3 look = player.getLookAngle();
                this.x = player.getX() + look.x;
                this.y = player.getEyeY() + look.y;
                this.z = player.getZ() + look.z;
            } else {
                this.x = followEntity.getX();
                this.y = followEntity.getY();
                this.z = followEntity.getZ();
            }
        }
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager handler) {
        WeighedSoundEvents result = super.resolve(handler);
        if (this.sound != null && this.sound != SoundManager.EMPTY_SOUND) {
            int range = effectiveRange();
            this.sound = new Sound(
                    this.sound.getLocation().toString(),
                    this.sound.getVolume(),
                    this.sound.getPitch(),
                    this.sound.getWeight(),
                    this.sound.getType(),
                    this.sound.shouldStream(),
                    this.sound.shouldPreload(),
                    range);
        }
        return result;
    }

    public CompletableFuture<AudioStream> getCustomStream() {
        return CompletableFuture.completedFuture(new LavaPlayerAudioStream(source, onStreamEnded));
    }

    public void requestStop() {
        this.stop();
        // stream 開栓前 (getCustomStream 未呼び出し) に停止すると、MC が AudioStream.close を
        // 発火しない = source が閉じられず LavaPlayer の player thread/buffer がリークする。
        // ここで冪等に閉じる。stream 経由で後から close されても LavaAudioSource.close は無害。
        if (sourceClosed.compareAndSet(false, true)) {
            source.close();
        }
    }
}
