package com.kuronami.musicdiscmaker.client.audio;

import java.util.concurrent.CompletableFuture;
//? if >=1.21.2 {
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
//?} elif >=1.21 {
/*import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
*/
//?} else {
//?}
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.kuronami.musicdiscmaker.Config;
//? if >=1.21.2 {
import com.kuronami.musicdiscmaker.MusicDiscMaker;
//?} elif >=1.21 {
/*import com.kuronami.musicdiscmaker.MusicDiscMaker;
*/
//?} else {
//?}
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
//? if >=1.21.2 {
//?} elif >=1.21 {
/*import com.kuronami.musicdiscmaker.component.CustomTrackData;
*/
//?} else {
//?}
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.register.ModSounds;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
//? if >=26.2 {
import net.minecraft.client.Options;
//?} elif >=1.21.2 {
/*import net.minecraft.client.Options;
*/
//?} elif >=1.21 {
/*import net.minecraft.client.Options;
*/
//?} else {
//?}
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

//? if >=26.2 {
public class DiscSoundInstance extends AbstractTickableSoundInstance
        implements PlaybackVoice, SoundEngineAcceptance.Engine, CustomAudioStreamProvider {

    private static final ExecutorService PREBUFFER = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "music_disc_maker-prebuffer");
        thread.setDaemon(true);
        return thread;
    });

    private static final long PREBUFFER_BUDGET_MS = 10_000L;
//?} elif >=1.21.2 {
/*public class DiscSoundInstance extends AbstractTickableSoundInstance
        implements PlaybackVoice, SoundEngineAcceptance.Engine, CustomAudioStreamProvider {

    private static final ExecutorService PREBUFFER = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "music_disc_maker-prebuffer");
        thread.setDaemon(true);
        return thread;
    });

    private static final long PREBUFFER_BUDGET_MS = 10_000L;
*/
//?} elif >=1.21 {
/*public class DiscSoundInstance extends AbstractTickableSoundInstance
        implements PlaybackVoice, SoundEngineAcceptance.Engine, CustomAudioStreamProvider {

    private static final ExecutorService PREBUFFER = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "music_disc_maker-prebuffer");
        thread.setDaemon(true);
        return thread;
    });

    private static final long PREBUFFER_BUDGET_MS = 10_000L;
*/
//?} else {
/*public class DiscSoundInstance extends AbstractTickableSoundInstance implements PlaybackVoice, SoundEngineAcceptance.Engine, CustomAudioStreamProvider {
*/
//?}

    private final IAudioSource source;
    /** source を高々一度だけ close するためのガード (requestStop と stream 経由の close の二重解放を防ぐ)。 */
    private final AtomicBoolean sourceClosed = new AtomicBoolean(false);
    /** 音源の位置と存在を供給するアンカー (固定 jukebox / entity 追従 / 移動構造物)。 */
    private volatile DiscAnchor anchor;
    /**
     * 強化版ジュークボックス由来の可聴範囲 (ブロック)。0 = per-block 設定なし (client config を使う)。
     * 実効範囲は {@link #resolve} で min(rangeBlocks, maxPlaybackRange) にする。
     * スライダー操作で変わるため mutable。{@link #tick} が client 側 BE から毎 tick 再読し、変化時に
     * チャンネルの減衰半径をライブ更新する (音量と同じ即反映・再ストリーム不要)。
     */
    private int rangeBlocks;
    /** 強化版ジュークボックス由来の音量 (%)。100 = 通常 (config volumeMultiplier のみ)。 */
    private int volumePercent;
    /** anchor切替中だけ使う追加gain。音量設定そのものは書き換えない。 */
    private double liveGainMultiplier = 1.0D;
    /** ストリーム終端 (read=-1) 時に一度だけ呼ばれるコールバック (ラジオ再接続用)。null=無効。 */
    @Nullable
    private final Runnable onStreamEnded;
    /**
     * 音の指向性。true = 音源の座標を書き込む positional audio。
     * false = 可聴範囲の中にいる限り左右差なし・減衰なしで鳴らすフラット聴取 (BGM モード)。
     * 範囲外では音量 0 にする ({@link #flatAudible})。
     *
     * <p>フラット側はバニラのグローバル音 ({@code SimpleSoundInstance#forMusic}) と同じ作法で作る:
     * {@code relative} を立てて座標 {@code (0,0,0)} を書く。{@code relative} は座標をリスナー相対で
     * 解釈させるので、距離は恒久的に厳密 0 になる。<b>音源座標に listener の座標をコピーする形では
     * 足りない</b> — こちらの書き込みは {@link #tick} = 20Hz なのに対し、OpenAL の listener 座標は
     * {@code SoundEngine#updateSource} が毎フレーム更新するので、相対ベクトルが 20Hz の鋸歯状に
     * 振れる。{@code AL_SOURCE_RADIUS} は設定されず定位は正規化方向ベクトルだけで決まるため、
     * 距離が 0.28 ブロックでも L/R ゲインが毎秒 20 回段差状に切り替わって歪む。
     *
     * <p>{@code relative} は {@code SoundEngine#play} 時にしか適用されないので、ライブ切替は
     * 再生中チャンネルへ直接書き込む ({@link SoundEngineChannelAccess#mdm$setRelative})。
     * 鳴らし直しは要らない。
     */
    private boolean directional = true;
    /**
     * フラットモードの範囲ゲートの現在状態。境界上を歩くと毎 tick 0 ↔ 全開で震えるので、
     * 現在の状態に {@link #GATE_HYSTERESIS} の余裕を与える。
     */
    private boolean flatAudible = true;
    /**
     * 範囲ゲートの現在のゲイン (0=無音 / 1=全開)。{@link #flatAudible} の反転をそのまま音量へ流すと
     * フルスケールの段差 = ポップノイズになるので、tick ごとに目標へ寄せる。
     */
    private float flatGate = 1.0F;

    /** フラットモードの範囲ゲートの履歴幅 (ブロック)。 */
    private static final double GATE_HYSTERESIS = 1.0;
    /** 範囲ゲートの立ち上がり/立ち下がりに掛ける tick 数 (20 tick = 1 秒)。 */
    private static final int GATE_FADE_TICKS = 15;

    // 構築子は package-private。compat 経路 (compat.*) は CompatPlayback を通るしか作る手段が無く、
    // そこが失敗の届け先を必ず繋ぐ。作るだけで繋がない経路を書くとコンパイルが通らない
    // (理由は CompatPlayback の javadoc)。同じ package の ClientPlaybackManager は直接使える。

    DiscSoundInstance(BlockPos pos, IAudioSource source) {
        this(pos, source, 0, 100, null);
    }

    DiscSoundInstance(BlockPos pos, IAudioSource source, int rangeBlocks, int volumePercent,
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

    DiscSoundInstance(Entity entity, IAudioSource source) {
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
     * 任意の {@link DiscAnchor} に張り付く汎用コンストラクタ。移動構造物の互換アダプタが独自 anchor を
     * 渡すのに使う。初期 x/y/z は anchor の現在位置で埋める。
     */
    DiscSoundInstance(DiscAnchor anchor, IAudioSource source, int rangeBlocks, int volumePercent,
            @Nullable Runnable onStreamEnded) {
        super(ModSounds.CUSTOM_DISC_PLAYBACK.get(), SoundSource.RECORDS, RandomSource.create());
        this.source = source;
        this.anchor = anchor;
        this.rangeBlocks = rangeBlocks;
        this.volumePercent = volumePercent;
        if (anchor instanceof LiveAudioConfig audioConfig) {
            this.liveGainMultiplier = audioConfig.gainMultiplier();
        }
        this.onStreamEnded = onStreamEnded;
        final Vec3 p = anchor.worldPos(1.0F);
        this.x = p.x;
        this.y = p.y;
        this.z = p.z;
        initCommon();
    }

    /**
     * 固定speaker集合へ既存decoderを張り替える。
     *
     * <p>先に再生を始めた船音源も、元の動的anchorを集合の音源候補として保持する。
     */
    void useSpeakerAnchor(MultiSpeakerAnchor speakerAnchor) {
        speakerAnchor.useSourceAnchor(anchor);
        anchor = speakerAnchor;
        setVolumePercent(speakerAnchor.volumePercent());
        setLiveGainMultiplier(speakerAnchor.gainMultiplier());
        //? if >=1.21 {
        setRangeBlocks(speakerAnchor.rangeBlocks());
        //?} else {
/*        final int nextRange = speakerAnchor.rangeBlocks();
        if (nextRange != this.rangeBlocks) {
            this.rangeBlocks = nextRange;
            applyLinearAttenuation();
        }
*/
        //?}
        setDirectional(speakerAnchor.directional());
    }

    private void initCommon() {
        this.volume = computeVolume();
        this.pitch = 1.0F;
        this.looping = false;
        // フラットモードは (0,0,0) をリスナー相対で書く。play 時点で OFF ならそこから乗る。
        this.relative = !this.directional;
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
     * 意図した停止 (jukebox 撤去・{@link #requestStop()}) の印。
     *
     * <p>{@link LavaPlayerAudioStream} がストリームの終端でこれを読み、立っていれば
     * 「途中で切れた」の失敗報告を出さない。立てるのはこちら側だけなので、
     * <b>印を立てる経路と印を読む経路は必ず同じ帯に揃える</b> (片方だけだと、
     * 誰も立てない印を読み続けて意図した停止が全部失敗として報告される)。
     */
    private final AtomicBoolean expectedEnd = new AtomicBoolean(false);

    @Nullable
    private volatile Consumer<PlaybackFailure> failureSink;

    //? if >=1.21.2 {
    @Nullable
    private volatile PlaybackTiming timing;
    //?}

    //? if >=26.2 {
    @Override
    //?} elif >=1.21.2 {
    //?} elif >=1.21 {
/*    @Nullable
    private volatile PlaybackTiming timing;

    @Nullable
    private volatile Runnable firstAudioSink;
    */
    //?} else {
    //?}
    public void setDirectional(boolean value) {
        if (this.directional == value) {
            return;
        }
        this.directional = value;
        this.relative = !value;
        this.flatAudible = true;
        if (value) {
            final Vec3 p = anchor.worldPos(1.0F);
            this.x = p.x;
            this.y = p.y;
            this.z = p.z;
        } else {
            this.x = 0.0;
            this.y = 0.0;
            this.z = 0.0;
        }
        applyRelative();
    }

    //? if >=1.21.2 {
    @Override
    public void setVolumePercent(int value) {
        this.volumePercent = Math.max(0, value);
    }

    @Override
    public void setRangeBlocks(int value) {
        if (this.rangeBlocks != value) {
            this.rangeBlocks = value;
            applyLinearAttenuation();
        }
    }
    //?} elif >=1.21 {
/*    public void setVolumePercent(int value) {
        this.volumePercent = Math.max(0, value);
    }

    public void setRangeBlocks(int value) {
        if (this.rangeBlocks != value) {
            this.rangeBlocks = value;
            applyLinearAttenuation();
        }
    
    }
    */
    //?} else {
    /*public void setVolumePercent(int value) {
        this.volumePercent = Math.max(0, value);
    }
    public void setRangeBlocks(int value) {
        if (this.rangeBlocks != value) {
            this.rangeBlocks = value;
            applyLinearAttenuation();
        }
    }
    *///?}
    private double totalGain() {
        return volumePercent / 100.0 * Config.volumeMultiplier() * MATERIAL_GAIN * flatGate
                * liveGainMultiplier;
    }

    private void setLiveGainMultiplier(double value) {
        this.liveGainMultiplier = Double.isFinite(value) ? Math.max(0.0D, value) : 0.0D;
    }

    /** 範囲ゲートのゲインを目標へ 1 tick 分寄せる。目標は「フラットモードで範囲外」なら 0、他は 1。 */
    private void advanceFlatGate() {
        final float target = !directional && !flatAudible ? 0.0F : 1.0F;
        final float step = 1.0F / GATE_FADE_TICKS;
        if (flatGate < target) {
            flatGate = Math.min(target, flatGate + step);
        } else if (flatGate > target) {
            flatGate = Math.max(target, flatGate - step);
        }
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

    //? if >=26.2 {
    //?} elif >=1.21.2 {
/*    @Nullable
    public static Vec3 listenerPos() {
        final Minecraft mc = Minecraft.getInstance();
        final Camera camera = mc.gameRenderer != null ? mc.gameRenderer.getMainCamera() : null;
        if (camera != null && camera.isInitialized()) {
            return camera.position();
        }
        return mc.player != null ? mc.player.getEyePosition() : null;
    }
    */
    //?} else {
/*    @Nullable
    public static Vec3 listenerPos() {
        final Minecraft mc = Minecraft.getInstance();
        final Camera camera = mc.gameRenderer != null ? mc.gameRenderer.getMainCamera() : null;
        if (camera != null && camera.isInitialized()) {
            return camera.getPosition();
        }
        return mc.player != null ? mc.player.getEyePosition() : null;
    }
    */
    //?}
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

    //? if >=26.2 {
    @Nullable
    public static Vec3 listenerPos() {
        final Minecraft mc = Minecraft.getInstance();
        final Camera camera = mc.gameRenderer != null ? mc.gameRenderer.mainCamera() : null;
        if (camera != null && camera.isInitialized()) {
            return camera.position();
        }
        return mc.player != null ? mc.player.getEyePosition() : null;
    }
    //?} else {
    //?}
    @Override
    public void tick() {
        // 音源が消えたら (jukebox 撤去・entity 除去・移動構造物の解体等いずれの経路でも) 鳴りっぱなしを止める。
        // 判定は anchor に委譲する (StaticAnchor は chunk 未ロード時は停止しない = 遠距離の誤消音を防ぐ)。
        if (!anchor.isValid()) {
            //? if >=1.21.2 {
            //?} elif >=1.21 {
/*            if (anchor instanceof StaticAnchor sa) {
                ClientPlaybackManager.get().cancelPrefetch(sa.pos(), "tick: anchor-invalid");
            }
            */
            //?} else {
            //?}
            markExpectedEnd(); // 撤去による停止。この後の終端を「途中で切れた」と報告しない
            stop();
            return;
        }
        // 音源の実座標。フラットモードでもゲート距離の基準として使う。
        final Vec3 p = anchor.worldPos(1.0F);
        //? if >=1.21.2 {
        if (anchor instanceof LiveAudioConfig audioConfig) {
            setVolumePercent(audioConfig.volumePercent());
            setLiveGainMultiplier(audioConfig.gainMultiplier());
            setDirectional(audioConfig.directional());
            setRangeBlocks(audioConfig.rangeBlocks());
        } else if (anchor instanceof LiveConfigAnchor lc) {
            final BlockPos configPos = lc.configPos();
        //?} elif >=1.21 {
/*        if (anchor instanceof LiveAudioConfig audioConfig) {
            setVolumePercent(audioConfig.volumePercent());
            setLiveGainMultiplier(audioConfig.gainMultiplier());
            setDirectional(audioConfig.directional());
            setRangeBlocks(audioConfig.rangeBlocks());
        } else if (anchor instanceof StaticAnchor sa) {
        */
        //?} else {
/*        if (anchor instanceof LiveAudioConfig audioConfig) {
            this.volumePercent = audioConfig.volumePercent();
            setLiveGainMultiplier(audioConfig.gainMultiplier());
            setDirectional(audioConfig.directional());
            final int nextRange = audioConfig.rangeBlocks();
            if (nextRange != this.rangeBlocks) {
                this.rangeBlocks = nextRange;
                applyLinearAttenuation();
            }
        } else if (anchor instanceof LiveConfigAnchor lc) {
            final BlockPos configPos = lc.configPos();
        */
        //?}
            final Minecraft mc = Minecraft.getInstance();
            //? if >=1.21.2 {
            if (mc.level != null && mc.level.isLoaded(configPos)
                    && mc.level.getBlockEntity(configPos) instanceof GoldenJukeboxBlockEntity be) {
                setVolumePercent(be.getVolumePercent());
            //?} elif >=1.21 {
/*            if (mc.level != null && mc.level.isLoaded(sa.pos())
                    && mc.level.getBlockEntity(sa.pos()) instanceof GoldenJukeboxBlockEntity be) {
                setVolumePercent(be.getVolumePercent());
            */
            //?} else {
/*            if (mc.level != null && mc.level.isLoaded(configPos)
                    && mc.level.getBlockEntity(configPos) instanceof GoldenJukeboxBlockEntity be) {
                this.volumePercent = be.getVolumePercent();
            */
            //?}
                setDirectional(be.isDirectional());
                //? if >=1.21.2 {
                setRangeBlocks(be.getRangeBlocks());
                //?} elif >=1.21 {
/*                setRangeBlocks(be.getRangeBlocks());
                ClientPlaybackManager.drivePrefetch(sa.pos(), be);
                */
                //?} else {
/*                final int beRange = be.getRangeBlocks();
                if (beRange != this.rangeBlocks) {
                    this.rangeBlocks = beRange;
                    applyLinearAttenuation();
                }
                */
                //?}
            }
        }
        applyListeningPosition(p);
        advanceFlatGate();
        this.volume = computeVolume();
        pushPcmGain();
    }


    /**
     * その tick の音源座標を決め、フラットモードの範囲ゲートを判定する。
     *
     * <p>指向性 ON = 実座標をそのまま書く (OpenAL が定位と距離減衰をかける)。
     * OFF = {@code relative} が立っているので {@code (0,0,0)} = 耳の位置。距離は厳密に 0 なので
     * 線形減衰のゲインは 1.0、方向ベクトルも 0 = 左右差なし。範囲の判定は書き込む座標と無関係に
     * <b>音源の実座標</b>で行い、範囲外なら {@link #flatGate} が音量を絞る。
     *
     * <p>ゲートは「停止」でなく「無音」にしてある。ストリームを閉じてしまうと範囲へ戻った時に再生を
     * 復帰させる手段が client 側に無く、server の再送を待つことになるため。指向性 ON でも範囲の外端では
     * 減衰で 0 になる (= ストリームは開いたまま) ので、外から見た挙動は連続している。
     */
    private void applyListeningPosition(Vec3 sourcePos) {
        if (directional) {
            this.x = sourcePos.x;
            this.y = sourcePos.y;
            this.z = sourcePos.z;
            return;
        }
        final Vec3 ear = listenerPos();
        if (ear != null) {
            final double limit = flatAudible ? effectiveRange() + GATE_HYSTERESIS : effectiveRange();
            flatAudible = ear.distanceTo(sourcePos) <= limit;
        }
        this.x = 0.0;
        this.y = 0.0;
        this.z = 0.0;
    }

    /**
     * 現在のモードの {@code relative} を再生中チャンネルへ即反映する。
     * チャンネル未割当 (play 直後の 1 tick 窓) の場合は {@code SoundEngine#play} が
     * {@link #isRelative()} を読んで焼き込んだ値が既に載っているので何もしない。
     */
    private void applyRelative() {
        final SoundManager soundManager = Minecraft.getInstance().getSoundManager();
        if (soundManager instanceof SoundEngineHolder holder
                && holder.mdm$soundEngine() instanceof SoundEngineChannelAccess channels) {
            channels.mdm$setRelative(this, !directional);
        }
    }

    @Override
    public WeighedSoundEvents resolve(SoundManager handler) {
        WeighedSoundEvents result = super.resolve(handler);
        if (this.sound != null && this.sound != SoundManager.EMPTY_SOUND) {
            int range = effectiveRange();
            this.sound = new Sound(
                    //? if >=1.21 {
                    this.sound.getLocation(),
                    //?} else {
/*                    this.sound.getLocation().toString(),
                    */
                    //?}
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

    /**
     * {@code sound} が未解決 ({@code resolve()} 前) の窓では、親に委ねず素の {@code volume} を返す。
     *
     * <p>親の実装は解決済みの {@code sound} を前提に音量を組み立てる。<b>play より前に
     * 音量を読む経路</b>があるとその前提が崩れるので、ここで解決前だけ切り分ける。
     */
    @Override
    public float getVolume() {
        return getSound() == null ? this.volume : super.getVolume();
    }

    public void setFailureSink(@Nullable Consumer<PlaybackFailure> sink) {
        this.failureSink = sink;
    }

    //? if >=1.21.2 {
    /**
     * 最初の実 PCM が渡った時の届け先 (1.21 帯は下の帯別腕が持つ)。
     * 書き込み = main thread / 呼び出し = streaming thread (1 ストリームにつき 1 回)。
     */
    @Nullable
    private volatile Runnable firstAudioSink;

    /** 実音が鳴り始めた時の届け先を差す (play より前・main thread から)。 */
    public void setFirstAudioSink(@Nullable Runnable sink) {
        this.firstAudioSink = sink;
    }

    public void setTiming(@Nullable PlaybackTiming value) {
        this.timing = value;
    }

    @Override
    public boolean playAndConfirm() {
        final SoundManager sounds = Minecraft.getInstance().getSoundManager();
        // 26.2 の STARTED_SILENTLY も isActive なら受理済み。NOT_STARTED だけを拒否する。
        sounds.play(this);
        return sounds.isActive(this);
    }

    @Override
    public boolean mutedOut() {
        final Options options = Minecraft.getInstance().options;
        return getVolume() <= 0.0F
                || options.getSoundSourceVolume(SoundSource.MASTER) <= 0.0F
                || options.getSoundSourceVolume(SoundSource.RECORDS) <= 0.0F;
    }
    //?} elif >=1.21 {
/*    public void setTiming(@Nullable PlaybackTiming value) {
        this.timing = value;
    }

    public void setFirstAudioSink(@Nullable Runnable sink) {
        this.firstAudioSink = sink;
    }

    @Override
    public boolean playAndConfirm() {
        final SoundManager sounds = Minecraft.getInstance().getSoundManager();
        sounds.play(this);
        return sounds.isActive(this);
    }

    @Override
    public boolean mutedOut() {
        final Options options = Minecraft.getInstance().options;
        return getVolume() <= 0.0F
                || options.getSoundSourceVolume(SoundSource.MASTER) <= 0.0F
                || options.getSoundSourceVolume(SoundSource.RECORDS) <= 0.0F;
    }
    */
    //?} else {
/*
    @Override
    public boolean playAndConfirm() {
        final SoundManager sounds = Minecraft.getInstance().getSoundManager();
        sounds.play(this);
        return sounds.isActive(this);
    }

    @Override
    public boolean mutedOut() {
        final var options = Minecraft.getInstance().options;
        return getVolume() <= 0.0F
                || options.getSoundSourceVolume(SoundSource.MASTER) <= 0.0F
                || options.getSoundSourceVolume(SoundSource.RECORDS) <= 0.0F;
    }

    // 最初の実 PCM が渡った時の届け先 (1.21 帯は上の帯別腕が持つ)。
    @Nullable
    private volatile Runnable firstAudioSink;

    // 実音が鳴り始めた時の届け先を差す (play より前・main thread から)。
    public void setFirstAudioSink(@Nullable Runnable sink) {
        this.firstAudioSink = sink;
    }
*/
    //?}
    public CompletableFuture<AudioStream> getCustomStream() {
        final LavaPlayerAudioStream s =
                new LavaPlayerAudioStream(source, onStreamEnded, failureSink, expectedEnd);
        s.setPcmGain(computePcmGain());
        //? if >=1.21.2 {
        s.setTiming(timing);
        s.setFirstAudioSink(firstAudioSink);
        //?} elif >=1.21 {
/*        s.setTiming(timing);
        s.setFirstAudioSink(firstAudioSink);
        */
        //?} else {
/*        s.setFirstAudioSink(firstAudioSink);
        */
        //?}
        this.stream = s;
        //? if >=1.21 {
        final CompletableFuture<AudioStream> ready = new CompletableFuture<>();
        try {
            PREBUFFER.execute(() -> {
                try {
                    s.prefill(PREBUFFER_BUDGET_MS);
                } catch (final Throwable t) {
                    MusicDiscMaker.LOGGER.warn(
                            "Pre-buffering failed; the sound engine thread will fill the buffers", t);
                } finally {
                    ready.complete(s);
                }
            });
        } catch (final Throwable t) {
            MusicDiscMaker.LOGGER.warn(
                    "Could not start pre-buffering; the sound engine thread will fill the buffers", t);
            ready.complete(s);
        }
        return ready;
        //?} else {
/*        return CompletableFuture.completedFuture(s);
        */
        //?}
    }

    @Override
    public CompletableFuture<AudioStream> createAudioStream(SoundBufferLibrary buffers, Sound sound, boolean looping) {
        return getCustomStream();
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@link #requestStop()} はこのインスタンスに停止フラグを立てて音源を閉じるだけで、
     * {@code SoundManager} が抱えているチャンネルはその tick では手放されない。管理側が
     * 「今すぐ黙らせて席を空ける」意味で呼ぶのはこちら。
     */
    @Override
    public void stopAndRelease() {
        requestStop();
        Minecraft.getInstance().getSoundManager().stop(this);
    }

    /**
     * バニラの {@code isStopped()} を、remap されない MOD 側の名前で外へ出す。
     *
     * <p>この 1 段を挟まないと、Fabric の出荷 jar で {@link PlaybackVoice#isVoiceStopped()} を
     * 満たすメソッドが消える (継承元が intermediary 名になるため)。<b>ここを
     * {@code @Override public boolean isStopped()} に書き換えないこと</b> — 宣言側も remap されるので
     * 同じことになる。
     */
    @Override
    public boolean isVoiceStopped() {
        return isStopped();
    }

    /** これ以降の終端を「意図した停止」として扱う。{@link #expectedEnd} の唯一の立て手。 */
    private void markExpectedEnd() {
        this.expectedEnd.set(true);
    }

    public void requestStop() {
        this.stop();
        markExpectedEnd();
        // 発火しない = source が閉じられず LavaPlayer の player thread/buffer がリークする。
        // ここで冪等に閉じる。stream 経由で後から close されても LavaAudioSource.close は無害。
        if (sourceClosed.compareAndSet(false, true)) {
            source.close();
        }
    }
}






