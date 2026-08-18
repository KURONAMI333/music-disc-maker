package com.kuronami.musicdiscmaker.client.audio;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.kuronami.musicdiscmaker.Config;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.register.ModSounds;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
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

public class DiscSoundInstance extends AbstractTickableSoundInstance
        implements PlaybackVoice, SoundEngineAcceptance.Engine {

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
     * 任意の {@link DiscAnchor} に張り付く汎用コンストラクタ。移動構造物 (Create contraption 等) の
     * loader 別アダプタが独自 anchor を渡すのに使う。初期 x/y/z は anchor の現在位置で埋める。
     */
    DiscSoundInstance(DiscAnchor anchor, IAudioSource source, int rangeBlocks, int volumePercent,
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
     * 再生スレッドの中で落ちた失敗の届け先。書き込み = main thread /
     * 読み出し = streaming スレッド ({@link #getCustomStream()})。
     */
    @Nullable
    private volatile Consumer<PlaybackFailure> failureSink;

    /**
     * 指向性の設定。生成直後 (play 前) と、client 側 BE 追従によるライブ切替から呼ぶ。
     * モードが変わった時はゲート状態を「聴こえる」に戻し (次の tick で正しく再判定される)、
     * 座標と {@code relative} を新しいモードのものへ即座に入れ替える。ゲインの現在値
     * ({@link #flatGate}) は据え置くので、無音側から戻った時もフェードで立ち上がる。
     */
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

    /**
     * 音量 (%) のライブ更新。次の {@link #tick} で {@link #computeVolume()} と PCM ゲインに乗る。
     *
     * <p>{@link #tick} が client 側 BE から再読するのは {@link StaticAnchor} の時だけなので、
     * 移動構造物のアンカー (Create contraption・Sable sub-level) はそちらの経路に入らない。
     * そこは server が周期送信する現在値をこの口で押し込む。
     *
     * @param value 音量 (%)。100 = 通常
     */
    public void setVolumePercent(int value) {
        this.volumePercent = value;
    }

    /**
     * 可聴範囲 (ブロック) のライブ更新。範囲は OpenAL の減衰半径なので、値が動いた時だけ
     * 再生中チャンネルへ書き込む ({@link #applyLinearAttenuation})。<b>ここを書き換えるだけでは
     * 音は変わらない</b> — 減衰半径は play 時に焼き込まれているため。
     *
     * @param value 可聴範囲 (ブロック)。0 = client config の既定を使う
     */
    public void setRangeBlocks(int value) {
        if (this.rangeBlocks != value) {
            this.rangeBlocks = value;
            applyLinearAttenuation();
        }
    }

    /**
     * 素材から耳までの総ゲイン = (volumePercent/100) × client の Records 相対倍率 ×
     * {@link #MATERIAL_GAIN} × 範囲ゲート ({@link #flatGate})。
     *
     * <p>ゲートはここで<b>1 回だけ</b>掛かる。{@link #computeVolume()} と {@link #computePcmGain()}
     * はこの値を min/max で分け合うだけなので、{@code LavaPlayerAudioStream} の 1 極フィルタと
     * 二重には掛からない (PCM 段が受け取るのは常に {@code max(totalGain,1.0)})。
     */
    private double totalGain() {
        return volumePercent / 100.0 * Config.volumeMultiplier() * MATERIAL_GAIN * flatGate;
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

    /**
     * OpenAL の listener 位置 (= {@code SoundEngine} が {@code Listener#setListenerPosition} に渡す
     * カメラ位置)。カメラ未初期化ならプレイヤーの目線で代替する。level 未生成では {@code null}。
     * フラットモードの音源配置と範囲ゲートが同じ 1 点を使うための共有アクセサ。
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
            // ここは server の停止 packet を待たずに自分で止まる経路 (ブロック撤去等) なので、
            // 先読みの取り消しも自分でやる。放置すると 60 秒リーパーに殺されるまで居座る。
            if (anchor instanceof StaticAnchor sa) {
                ClientPlaybackManager.get().cancelPrefetch(sa.pos());
            }
            stop();
            return;
        }
        // 音源の実座標。フラットモードでもゲート距離の基準として使う。
        final Vec3 p = anchor.worldPos(1.0F);
        // 強化版ジュークボックス固有 (StaticAnchor のみ): client 側 BE から音量・可聴範囲・指向性を毎 tick
        // 再読し、GUI 操作を再ロードなしで即反映する。音量と指向性はローカルフィールド書き換えのみ、範囲は
        // 減衰半径 (OpenAL の max distance) なので変化時だけチャンネルへ反映する (毎 tick の execute は無駄)。
        // vanilla jukebox の BE は GoldenJukeboxBlockEntity ではないので instanceof で自然に弾かれる。
        if (anchor instanceof StaticAnchor sa) {
            final Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && mc.level.isLoaded(sa.pos())
                    && mc.level.getBlockEntity(sa.pos()) instanceof GoldenJukeboxBlockEntity be) {
                setVolumePercent(be.getVolumePercent());
                setDirectional(be.isDirectional());
                setRangeBlocks(be.getRangeBlocks());
                driveAlbumPrefetch(sa.pos(), be);
            }
        }
        applyListeningPosition(p);
        advanceFlatGate();
        this.volume = computeVolume();
        pushPcmGain();
    }

    /**
     * アルバムの次トラックの先読みを始める残り時間 (ms)。
     *
     * <p>lavaplayer は {@code provide} されない player を 60 秒で殺す ({@code CLEANUP}) ので、
     * 掴むまでの時間はそれより十分短く固定する。殺された先読みを掴むと {@code read()} が即
     * {@code -1} を返し、MC は「尺ゼロの曲」として扱う = 完全無音で、元のギャップより悪い。
     */
    private static final long PREFETCH_LEAD_MS = 15_000L;

    /**
     * アルバム再生の終わり際に次トラックを先読みさせる (強化版ジュークボックス限定)。
     *
     * <p><b>残り時間が 0 以下でも取り消さない。</b> client の {@code currentElapsedMs()} は
     * 尺でクランプされるので、曲の終わり際は残り 0 に張り付く。そこで取り消すと
     * 次トラックの packet が来る直前に必ず閉じてしまい、先読みが一度も当たらなくなる。
     * 取り消すのは「一時停止」「アルバムでない」「次が無い」「早すぎ (= 後方シークを含む)」だけ。
     *
     * <p>次の曲が変わった場合の閉じ直しは {@link PlaybackPrefetch#begin} が URL で判断するので、
     * ここは毎 tick 素直に呼んでよい。
     */
    private static void driveAlbumPrefetch(BlockPos pos, GoldenJukeboxBlockEntity be) {
        final ClientPlaybackManager manager = ClientPlaybackManager.get();
        if (be.getAlbumTrack() < 0 || be.isPaused()) {
            manager.cancelPrefetch(pos);
            return;
        }
        final long duration = be.trackDurationMs();
        if (duration <= 0L || duration - be.currentElapsedMs() > PREFETCH_LEAD_MS) {
            manager.cancelPrefetch(pos);
            return;
        }
        final CustomTrackData next = be.nextAlbumTrack();
        if (next == null) {
            manager.cancelPrefetch(pos);
            return;
        }
        manager.prefetchNext(pos, next);
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

    /**
     * 再生スレッドの中で落ちた失敗の届け先を差す。{@link #getCustomStream()} が開栓時に
     * ストリームへ渡すので、{@code SoundManager#play} より前に差しておくこと
     * (未設定なら曲名なしの既定報告になる)。
     *
     * @param sink 失敗の届け先 ({@code null} 可)
     */
    public void setFailureSink(@Nullable Consumer<PlaybackFailure> sink) {
        this.failureSink = sink;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code SoundManager#play} は受理しなかったことを戻り値で返さないので、engine 側の
     * 唯一の観測点で見る。{@code SoundEngine#play} は成功経路の中で {@code instanceToChannel} へ
     * 入れてから返るため、直後の {@code isActive} が偽なら捨てられている (捨てる 8 経路と
     * その静けさは {@link SoundEngineAcceptance} の javadoc)。
     */
    @Override
    public boolean playAndConfirm() {
        final SoundManager sounds = Minecraft.getInstance().getSoundManager();
        sounds.play(this);
        return sounds.isActive(this);
    }

    /**
     * {@inheritDoc}
     *
     * <p>engine の {@code calculateVolume} は {@code volume × Records} を、{@code listener} の
     * gain は master をそのまま見る。どれかが 0 なら {@code play} は必ず捨てるので、
     * 「engine が受理しなかった」ではなく「音量が 0 だ」と答えられる。
     * この音源自身の音量 (ジュークボックスのスライダー・{@code volumeMultiplier}) も同じ扱いにする。
     */
    @Override
    public boolean mutedOut() {
        final Options options = Minecraft.getInstance().options;
        return getVolume() <= 0.0F
                || options.getSoundSourceVolume(SoundSource.MASTER) <= 0.0F
                || options.getSoundSourceVolume(SoundSource.RECORDS) <= 0.0F;
    }

    public CompletableFuture<AudioStream> getCustomStream() {
        final LavaPlayerAudioStream s = new LavaPlayerAudioStream(source, onStreamEnded, failureSink);
        s.setPcmGain(computePcmGain());
        this.stream = s;
        return CompletableFuture.completedFuture(s);
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
