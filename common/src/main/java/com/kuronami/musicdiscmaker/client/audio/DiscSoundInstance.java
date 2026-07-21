package com.kuronami.musicdiscmaker.client.audio;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import com.kuronami.musicdiscmaker.Config;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
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

    public DiscSoundInstance(BlockPos pos, IAudioSource source) {
        super(ModSounds.CUSTOM_DISC_PLAYBACK.get(), SoundSource.RECORDS, RandomSource.create());
        this.source = source;
        this.followEntity = null;
        this.blockPos = pos.immutable();
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
        this.x = entity.getX();
        this.y = entity.getY();
        this.z = entity.getZ();
        initCommon();
    }

    private void initCommon() {
        this.volume = (float) Config.volumeMultiplier();
        this.pitch = 1.0F;
        this.looping = false;
        this.relative = false;
        this.attenuation = Attenuation.LINEAR;
    }

    @Override
    public void tick() {
        // jukebox が撤去されたら (破壊・爆発・ピストン・コマンド等いずれの経路でも) 鳴りっぱなしを止める。
        // chunk 未ロード時は air が返るため isLoaded でゲートする (遠距離 = playbackRange 内の減衰を誤って切らない)。
        if (blockPos != null) {
            final Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.level.isLoaded(blockPos)
                    && !mc.level.getBlockState(blockPos).is(Blocks.JUKEBOX)) {
                stop();
                return;
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
            int range = Config.playbackRange();
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
        return CompletableFuture.completedFuture(new LavaPlayerAudioStream(source));
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
