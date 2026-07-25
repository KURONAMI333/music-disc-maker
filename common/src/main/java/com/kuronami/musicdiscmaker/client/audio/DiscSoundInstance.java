package com.kuronami.musicdiscmaker.client.audio;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import com.kuronami.musicdiscmaker.Config;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.register.ModSounds;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Camera;
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
    /**
     * 音の指向性。true = 従来どおり音源の座標を書き込む positional audio。
     * false = 可聴範囲の中にいる限り listener の座標そのものを書き込む = 距離 0 = 左右差なし・減衰なしの
     * フラット聴取 (BGM モード)。範囲外では音量 0 にする ({@link #flatAudible})。
     *
     * <p>{@code relative} フラグを使わないのが要点。あれは {@code SoundEngine#play} 時に 1 回だけ
     * 適用され tick では再適用されないので、ライブ切替に使うと「停止 → 現在 offset で再 play」= 再バッファの
     * 音切れを伴う。位置は {@code SoundEngine#tickNonPaused} が毎 tick {@code setSelfPosition} で
     * 押し込むので、座標を listener に置くだけで同じ聴こえ方が瞬時に得られる。
     */
    private boolean directional = true;
    /**
     * フラットモードの範囲ゲートの現在状態。境界上を歩くと毎 tick 0 ↔ 全開で震えるので、
     * 現在の状態に {@link #GATE_HYSTERESIS} の余裕を与える (最近傍選択のヒステリシスと同趣旨)。
     */
    private boolean flatAudible = true;

    /** フラットモードの範囲ゲートの履歴幅 (ブロック)。 */
    private static final double GATE_HYSTERESIS = 1.0;

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
     * 指向性の設定。生成直後 (play 前) と、{@link LiveAudioConfigAnchor} 経由のライブ切替から呼ぶ。
     * モードが変わった時はゲート状態を「聴こえる」に戻す (次の tick で正しく再判定される)。
     */
    public void setDirectional(boolean value) {
        if (this.directional != value) {
            this.directional = value;
            this.flatAudible = true;
        }
    }

    /**
     * 実効音量 = (volumePercent/100) × client の Records 相対倍率。
     * フラットモードで範囲外にいるときは 0 (= 範囲ゲート)。
     */
    private float computeVolume() {
        if (!directional && !flatAudible) {
            return 0.0F;
        }
        return (float) (volumePercent / 100.0 * Config.volumeMultiplier());
    }

    /**
     * OpenAL の listener 位置 (= {@code SoundEngine} が {@code Listener#setListenerPosition} に渡す
     * カメラ位置)。カメラ未初期化ならプレイヤーの目線で代替する。level 未生成では {@code null}。
     *
     * <p>フラットモードの音源配置・範囲ゲートと、{@link MultiSpeakerAnchor} の最近傍判定が
     * 同じ 1 点を使うための共有アクセサ。
     */
    @Nullable
    public static Vec3 listenerPos() {
        final Minecraft mc = Minecraft.getInstance();
        final Camera camera = mc.gameRenderer != null ? mc.gameRenderer.getMainCamera() : null;
        if (camera != null && camera.isInitialized()) {
            return camera.getPosition();
        }
        return mc.player != null ? mc.player.getEyePosition() : null;
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
     *
     * <p>{@code SoundEngine#play} はチャンネルのハンドルを {@code join()} で同期に受け取ってから
     * {@code instanceToChannel} へ入れるので、「再生中なのにチャンネル未割当」の状態はハンドル取得に
     * 失敗した時 (streaming プール 2〜8 本の枯渇) だけで、その場合その音はそもそも鳴っていない。
     * mixin 側は該当なしを黙って無視する。
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
        // 音源 (または最近傍スピーカー) の実座標。フラットモードでもゲート距離の基準として使う。
        final Vec3 p = anchor.worldPos(1.0F);
        // 強化版ジュークボックス固有: client 側 BE から音量・可聴範囲を毎 tick 再読し、スライダー操作を
        // 再ロードなしで即反映する。音量はローカルフィールド書き換えのみ、範囲は減衰半径 (OpenAL の
        // max distance) なので変化時だけチャンネルへ反映する (毎 tick の execute は無駄)。
        // 対象は LiveConfigAnchor を実装するアンカー = 原ブロックが client world に実在するもの
        // (固定ジューク StaticAnchor)。Create 捕獲式は AIR 化で、Aeronautics 変換式は off-map sub-level に
        // 実在するため再読元が client world に無く、いずれも実装せず自然に弾かれる。vanilla jukebox の
        // BE も GoldenJukeboxBlockEntity ではないので弾かれる。
        if (anchor instanceof LiveAudioConfigAnchor la) {
            // 再読元が 1 箇所に定まらないアンカー (最近傍のスピーカー or 音源) は、値の解決を
            // アンカー側に任せる。負値 = 「今は分からない」なので現在値を保持する。
            final int liveVolume = la.liveVolumePercent();
            if (liveVolume >= 0) {
                this.volumePercent = liveVolume;
            }
            final int liveRange = la.liveRangeBlocks();
            if (liveRange >= 0 && liveRange != this.rangeBlocks) {
                this.rangeBlocks = liveRange;
                applyLinearAttenuation();
            }
            final int liveDirectional = la.liveDirectional();
            if (liveDirectional >= 0) {
                setDirectional(liveDirectional != 0);
            }
        } else if (anchor instanceof LiveConfigAnchor lc) {
            final BlockPos configPos = lc.configPos();
            final Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.level.isLoaded(configPos)
                    && mc.level.getBlockEntity(configPos) instanceof GoldenJukeboxBlockEntity be) {
                this.volumePercent = be.getVolumePercent();
                final int beRange = be.getRangeBlocks();
                if (beRange != this.rangeBlocks) {
                    this.rangeBlocks = beRange;
                    applyLinearAttenuation();
                }
            }
        }
        applyListeningPosition(p);
        this.volume = computeVolume();
    }

    /**
     * その tick の音源座標を決める。
     *
     * <p>指向性 ON = 実座標をそのまま書く (従来どおり OpenAL が定位と距離減衰をかける)。
     * OFF = 実効可聴範囲の内側なら listener の座標を書く。距離 0 になるので線形減衰のゲインは 1.0、
     * 方向ベクトルも 0 = 左右差なし。範囲外では実座標へ戻し、{@link #computeVolume()} が 0 を返す。
     *
     * <p>ゲートは「停止」でなく「無音」にしてある。ストリームを閉じてしまうと範囲へ戻った時に再生を
     * 復帰させる手段が client 側に無く、server の再送を待つことになるため。指向性 ON でも範囲の外端では
     * 減衰で 0 になる (= ストリームは開いたまま) ので、外から見た挙動は連続している。
     */
    private void applyListeningPosition(Vec3 sourcePos) {
        Vec3 write = sourcePos;
        if (!directional) {
            final Vec3 ear = listenerPos();
            if (ear != null) {
                final double limit = flatAudible ? effectiveRange() + GATE_HYSTERESIS : effectiveRange();
                flatAudible = ear.distanceTo(sourcePos) <= limit;
                if (flatAudible) {
                    write = ear;
                }
            }
        }
        this.x = write.x;
        this.y = write.y;
        this.z = write.z;
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
        return CompletableFuture.completedFuture(new LavaPlayerAudioStream(source, onStreamEnded));
    }

    /**
     * 計算音量が 0 でも再生を開始させる。{@code SoundEngine#play} は
     * {@code volume==0 && !canStartSilent()} で早期 return し、以後 tick されない
     * ({@code queuedTickableSounds} に入らない) ため、音量 0% のスピーカー・自前減衰モードで
     * 「二度と鳴らない」状態に落ちるのを防ぐ。
     */
    @Override
    public boolean canStartSilent() {
        return true;
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
