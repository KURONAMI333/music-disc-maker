package com.kuronami.musicdiscmaker.client.audio;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import com.kuronami.musicdiscmaker.Config;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
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
import net.minecraft.world.phys.Vec3;

public class DiscSoundInstance extends AbstractTickableSoundInstance {

    private final IAudioSource source;
    /** source を高々一度だけ close するためのガード (requestStop と stream 経由の close の二重解放を防ぐ)。 */
    private final AtomicBoolean sourceClosed = new AtomicBoolean(false);
    /** 音源の位置と存在を供給するアンカー (固定 jukebox / entity 追従 / 移動構造物)。 */
    private final DiscAnchor anchor;
    /**
     * 強化版ジュークボックス由来の可聴範囲 (ブロック)。0 = per-block 設定なし (client config を使う)。
     * 実効範囲は {@link #resolve} で min(rangeBlocks, config) にする。
     * スライダー操作で変わるため mutable。{@link #tick} が client 側 BE から毎 tick 再読し、変化時に
     * チャンネルの減衰半径をライブ更新する (音量と同じ即反映・再ストリーム不要)。
     */
    private int rangeBlocks;
    /** 強化版ジュークボックス由来の音量 (%)。100 = 通常 (config volumeMultiplier のみ)。 */
    private int volumePercent;
    /** ストリーム終端 (read=-1) 時に一度だけ呼ばれるコールバック (ラジオ再接続用)。null=無効。 */
    @Nullable
    private final Runnable onStreamEnded;

    public DiscSoundInstance(BlockPos pos, IAudioSource source) {
        this(pos, source, 0, 100, null);
    }

    public DiscSoundInstance(BlockPos pos, IAudioSource source, int rangeBlocks, int volumePercent) {
        this(pos, source, rangeBlocks, volumePercent, null);
    }

    public DiscSoundInstance(BlockPos pos, IAudioSource source, int rangeBlocks, int volumePercent,
            @Nullable Runnable onStreamEnded) {
        super(ModSounds.CUSTOM_DISC_PLAYBACK.get(), SoundSource.RECORDS, RandomSource.create());
        this.source = source;
        this.anchor = new StaticAnchor(pos);
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
        this.anchor = new EntityAnchor(entity);
        this.rangeBlocks = 0;
        this.volumePercent = 100;
        this.onStreamEnded = null;
        this.x = entity.getX();
        this.y = entity.getY();
        this.z = entity.getZ();
        initCommon();
    }

    /**
     * 任意の {@link DiscAnchor} に張り付く汎用コンストラクタ。移動構造物 (Create contraption 等) の
     * loader 別アダプタが独自 anchor を渡すのに使う。初期 x/y/z は anchor の現在位置で埋める。
     */
    public DiscSoundInstance(DiscAnchor anchor, IAudioSource source, int rangeBlocks, int volumePercent,
            @Nullable Runnable onStreamEnded) {
        super(ModSounds.CUSTOM_DISC_PLAYBACK.get(), SoundSource.RECORDS, RandomSource.create());
        this.source = source;
        this.anchor = anchor;
        this.rangeBlocks = rangeBlocks;
        this.volumePercent = volumePercent;
        this.onStreamEnded = onStreamEnded;
        final Vec3 p = anchor.worldPos(1.0F);
        this.x = p.x;
        this.y = p.y;
        this.z = p.z;
        initCommon();
    }

    private void initCommon() {
        this.volume = computeVolume();
        this.pitch = 1.0F;
        this.looping = false;
        this.relative = false;
        this.attenuation = Attenuation.LINEAR;
    }

    /**
     * 素材ゲイン。カスタムディスクの PCM はバニラのレコード ogg より小さく鳴る。内訳は
     * (a) client config {@code volumeMultiplier} の既定 0.5 = -6.0 dB、
     * (b) {@code LavaAudioSource.downmixStereoToMono} の (L+R)/2 が非相関ステレオで最大 -3.0 dB。
     * 2.0 × √2 ≒ 2.83 が両方を戻した値。
     *
     * <p>候補は 2 つ。聴き比べで切り替えるときはこの 1 行だけを変える。
     * <ul>
     *   <li>{@code 2.0} — (a) だけを打ち消す。既定スライダー (100%) で OpenAL gain がバニラと同じ
     *       1.0 になり、PCM 段は素通し = 歪みの余地がゼロ。素材の小ささは残る。</li>
     *   <li>{@code 2.83} — (a)+(b)。既定スライダーでバニラより +3.0 dB。素材の小ささを埋める側。</li>
     * </ul>
     */
    private static final double MATERIAL_GAIN = 2.83;

    /** 押し込み先の PCM ストリーム。{@link #getCustomStream()} が streaming 側から生成する。 */
    @Nullable
    private volatile LavaPlayerAudioStream stream;

    /**
     * 素材から耳までの総ゲイン = (volumePercent/100) × client の Records 相対倍率 × {@link #MATERIAL_GAIN}。
     */
    private double totalGain() {
        return volumePercent / 100.0 * Config.volumeMultiplier() * MATERIAL_GAIN;
    }

    /**
     * OpenAL へ渡すゲイン。{@code SoundEngine#calculateVolume} が [0,1] にクランプするので 1.0 で
     * 頭打ちにし、超過分は {@link #computePcmGain()} が PCM サンプル側で担う。
     * min/max に割り振るので両者の積は常に {@link #totalGain()} に一致する。
     *
     * <p>頭打ちにしたことで、{@code volumeMultiplier} を既定の 0.5 から上げていた場合に
     * 高音量側で付いていた減衰距離のおまけ ({@code Math.max(getVolume(),1.0F) * range}) は無くなる。
     * 既定 config では総ゲインが 1.0 を超えるのは PCM 段だけなので聴取範囲は変わらない。
     */
    private float computeVolume() {
        return (float) Math.min(totalGain(), 1.0);
    }

    /** PCM 段のゲイン。OpenAL が表現できない 1.0 超の領域だけを担当する (1.0 = 素通し)。 */
    private float computePcmGain() {
        return (float) Math.max(totalGain(), 1.0);
    }

    /** 現在の PCM ゲインを再生中のストリームへ反映する。未開栓なら何もしない。 */
    private void pushPcmGain() {
        final LavaPlayerAudioStream s = this.stream;
        if (s != null) {
            s.setPcmGain(computePcmGain());
        }
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
     * このインスタンスの現在の可聴範囲 (ブロック)。0 = per-block 設定なし。
     * {@link #tick} が client 側 BE から追従させ、変化時に {@link #applyLinearAttenuation} で
     * チャンネルの減衰半径をライブ更新する。
     */
    public int getRangeBlocks() {
        return rangeBlocks;
    }

    /**
     * 現在の実効範囲をチャンネルの線形減衰半径 (OpenAL max distance) へ即反映する。
     * {@code SoundEngine#play} と同じ式 ({@code max(getVolume(),1) * attenuationDistance}) で算出し、
     * loader mixin ({@link SoundEngineChannelAccess}) 経由で再生中チャンネルへ書き込む。
     * チャンネル未割当 (play 直後の 1 tick 窓) の場合は resolve() 焼き込み値が既に反映済みなので何もしない。
     */
    private void applyLinearAttenuation() {
        final SoundManager soundManager = Minecraft.getInstance().getSoundManager();
        if (soundManager instanceof SoundEngineHolder holder
                && holder.mdm$soundEngine() instanceof SoundEngineChannelAccess channels) {
            final float dist = Math.max(this.getVolume(), 1.0F) * effectiveRange();
            channels.mdm$updateLinearAttenuation(this, dist);
        }
    }

    @Override
    public void tick() {
        // 音源が消えたら (jukebox 撤去・entity 除去・移動構造物の解体等いずれの経路でも) 鳴りっぱなしを止める。
        // 判定は anchor に委譲する (StaticAnchor は chunk 未ロード時は停止しない = 遠距離の誤消音を防ぐ)。
        if (!anchor.isValid()) {
            stop();
            return;
        }
        final Vec3 p = anchor.worldPos(1.0F);
        this.x = p.x;
        this.y = p.y;
        this.z = p.z;
        // 強化版ジュークボックス固有 (StaticAnchor のみ): client 側 BE から音量・可聴範囲を毎 tick 再読し、
        // スライダー操作を再ロードなしで即反映する。音量はローカルフィールド書き換えのみ、範囲は減衰半径
        // (OpenAL の max distance) なので変化時だけチャンネルへ反映する (毎 tick の execute は無駄)。
        // vanilla jukebox の BE は GoldenJukeboxBlockEntity ではないので instanceof で自然に弾かれる。
        if (anchor instanceof StaticAnchor sa) {
            final Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.level.isLoaded(sa.pos())
                    && mc.level.getBlockEntity(sa.pos()) instanceof GoldenJukeboxBlockEntity be) {
                this.volumePercent = be.getVolumePercent();
                this.volume = computeVolume();
                final int beRange = be.getRangeBlocks();
                if (beRange != this.rangeBlocks) {
                    this.rangeBlocks = beRange;
                    applyLinearAttenuation();
                }
            }
        }
        pushPcmGain();
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager handler) {
        WeighedSoundEvents result = super.resolve(handler);
        if (this.sound != null && this.sound != SoundManager.EMPTY_SOUND) {
            int range = effectiveRange();
            this.sound = new Sound(
                    this.sound.getLocation(),
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
        final LavaPlayerAudioStream s = new LavaPlayerAudioStream(source, onStreamEnded);
        s.setPcmGain(computePcmGain());
        this.stream = s;
        return CompletableFuture.completedFuture(s);
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
