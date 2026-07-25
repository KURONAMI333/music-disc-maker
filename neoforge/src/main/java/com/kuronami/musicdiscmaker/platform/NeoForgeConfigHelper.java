package com.kuronami.musicdiscmaker.platform;

import com.kuronami.musicdiscmaker.beat.BeatBand;
import com.kuronami.musicdiscmaker.beat.BeatMode;
import com.kuronami.musicdiscmaker.platform.services.IConfigHelper;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * NeoForge 実装: client 設定 ({@code ModConfigSpec})。entry が {@link #SPEC} を CLIENT config として登録する。
 * 共通の {@code com.kuronami.musicdiscmaker.Config} と FQN が衝突しないよう、spec はここに保持する。
 */
public class NeoForgeConfigHelper implements IConfigHelper {

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.DoubleValue VOLUME_MULTIPLIER = BUILDER
            .comment("Playback volume multiplier for custom discs (relative to the Records sound slider).")
            .defineInRange("volumeMultiplier", 0.5, 0.0, 1.0);

    public static final ModConfigSpec.IntValue MAX_CONCURRENT = BUILDER
            .comment("Maximum number of custom discs playing simultaneously (memory protection).")
            .defineInRange("maxConcurrent", 16, 1, 64);

    public static final ModConfigSpec.IntValue PLAYBACK_RANGE = BUILDER
            .comment("Distance in blocks at which custom disc audio fades to silence (vanilla discs use 16).")
            .defineInRange("playbackRange", 64, 16, 256);

    public static final ModConfigSpec.IntValue MAX_PLAYBACK_RANGE = BUILDER
            .comment("Upper cap for the Enhanced Jukebox per-block range setting (blocks). "
                    + "Effective range = min(block setting, this). Separate from playbackRange.")
            .defineInRange("maxPlaybackRange", 256, 16, 256);

    // ── 音源ローカルキャッシュ (CLIENT config) ───────────────────────────
    // 再生は client ごとに個別のストリームなので、キャッシュも client 側。

    public static final ModConfigSpec.BooleanValue AUDIO_CACHE_ENABLED = BUILDER
            .comment("Keep a local copy of tracks you have listened to all the way through, "
                    + "and play from that copy next time.",
                    "Why turn this on: online sources break. When YouTube changed its signatures in 2026, "
                    + "every disc stopped playing for weeks. With this on, any track you have already "
                    + "played to the end keeps working on this PC even when the source is unreachable.",
                    "Cost: disk space (about 1.5 MB per 4-minute track) and a local copy of the audio "
                    + "on your machine. Off by default so that this is your choice.",
                    "SoundCloud is never cached (their terms forbid it). Radio and live streams are "
                    + "never cached (they have no end). Files live in music_disc_maker/cache/*.audio "
                    + "and can be deleted by hand at any time.")
            .define("audioCacheEnabled", false);

    public static final ModConfigSpec.IntValue AUDIO_CACHE_MAX_MB = BUILDER
            .comment("Disk budget for cached audio, in MB. 0 = unlimited. "
                    + "The oldest tracks are removed first once the budget is exceeded.",
                    "This budget is separate from beatCacheMaxMB even though both live in the same folder.")
            .defineInRange("audioCacheMaxMB", 1024, 0, 65536);

    public static final ModConfigSpec SPEC = BUILDER.build();

    // ── SERVER config ────────────────────────────────────────────────────
    // スピーカーのリンク制約は server 側 (設置時の検証・payload の歯止め) で読むので、CLIENT spec には
    // 置かない。CLIENT spec は dedicated server にロードされず、値を引いた時点で落ちる。

    private static final ModConfigSpec.Builder SERVER_BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue SPEAKER_LINK_RANGE = SERVER_BUILDER
            .comment("Maximum distance (blocks) at which a Speaker can be linked to a Golden Jukebox.")
            .defineInRange("speakerLinkRange", 128, 16, 512);

    public static final ModConfigSpec.IntValue MAX_SPEAKERS_PER_SOURCE = SERVER_BUILDER
            .comment("Maximum number of Speakers that can be linked to a single Golden Jukebox.")
            .defineInRange("maxSpeakersPerSource", 16, 1, 64);

    // ── ビート連動レッドストーン (SERVER config) ──────────────────────────
    // 解析もコンパレータ出力も server で起きる。プレイ中に触る想定なので config GUI から
    // 変えられる ModConfigSpec に全部出す (kura の実機チューニング帯)。

    public static final ModConfigSpec.BooleanValue BEAT_ENABLED = SERVER_BUILDER
            .comment("Enable beat-reactive comparator output on the Enhanced Jukebox. "
                    + "When off, its comparator always reads 0.")
            .define("beatEnabled", true);

    public static final ModConfigSpec.EnumValue<BeatMode> BEAT_MODE = SERVER_BUILDER
            .comment("ENVELOPE = comparator follows the loudness of the selected band. "
                    + "ONSET = fires a short full-strength pulse on each detected hit.")
            .defineEnum("beatMode", BeatMode.ENVELOPE);

    public static final ModConfigSpec.EnumValue<BeatBand> BEAT_BAND = SERVER_BUILDER
            .comment("Frequency band the strength is taken from. "
                    + "LOW = kick/bass, MID = snare/vocals, HIGH = hats, FULL = whole mix.")
            .defineEnum("beatBand", BeatBand.LOW);

    public static final ModConfigSpec.DoubleValue BEAT_SENSITIVITY = SERVER_BUILDER
            .comment("Gain applied to the beat envelope. Higher reacts to quieter parts.")
            .defineInRange("beatSensitivity", 1.0, 0.25, 4.0);

    public static final ModConfigSpec.IntValue BEAT_FLOOR_DB = SERVER_BUILDER
            .comment("How many dB below the track's reference level counts as zero.")
            .defineInRange("beatFloorDb", -36, -72, -6);

    public static final ModConfigSpec.IntValue BEAT_ATTACK_MS = SERVER_BUILDER
            .comment("Rise time constant in milliseconds. 0 = instant.")
            .defineInRange("beatAttackMs", 0, 0, 2000);

    public static final ModConfigSpec.IntValue BEAT_RELEASE_MS = SERVER_BUILDER
            .comment("Fall time constant in milliseconds. Match this to the inertia of your contraption.")
            .defineInRange("beatReleaseMs", 180, 0, 5000);

    public static final ModConfigSpec.IntValue BEAT_OFFSET_MS = SERVER_BUILDER
            .comment("Output offset in milliseconds. Positive delays the signal, "
                    + "NEGATIVE fires it early to cancel out mechanical delay (repeaters, pistons, inertia).")
            .defineInRange("beatOffsetMs", 0, -2000, 2000);

    public static final ModConfigSpec.DoubleValue BEAT_ONSET_THRESHOLD = SERVER_BUILDER
            .comment("ONSET mode: how strong a hit has to be to fire (0..1).")
            .defineInRange("beatOnsetThreshold", 0.5, 0.05, 1.0);

    public static final ModConfigSpec.IntValue BEAT_ONSET_PULSE_TICKS = SERVER_BUILDER
            .comment("ONSET mode: how long each pulse is held, in ticks.")
            .defineInRange("beatOnsetPulseTicks", 2, 1, 20);

    public static final ModConfigSpec.IntValue BEAT_HYSTERESIS = SERVER_BUILDER
            .comment("Changes within this many levels are ignored, to cut down redstone updates.")
            .defineInRange("beatHysteresis", 1, 0, 3);

    public static final ModConfigSpec.IntValue BEAT_MIN_UPDATE_TICKS = SERVER_BUILDER
            .comment("Minimum ticks between comparator updates. Raise this if large circuits lag.")
            .defineInRange("beatMinUpdateTicks", 1, 1, 10);

    public static final ModConfigSpec.IntValue BEAT_UNCALIBRATED_OFFSET_MS = SERVER_BUILDER
            .comment("Fallback offset used when no client reports when the audio actually started "
                    + "(empty server, or a client without the mod).")
            .defineInRange("beatUncalibratedOffsetMs", 0, -5000, 5000);

    public static final ModConfigSpec.IntValue BEAT_FFT_SIZE = SERVER_BUILDER
            .comment("Analysis FFT size. Larger separates bass better at a small CPU cost.")
            .defineInRange("beatFftSize", 2048, 1024, 4096);

    public static final ModConfigSpec.IntValue BEAT_CACHE_MAX_MB = SERVER_BUILDER
            .comment("Disk budget for cached beat maps, in MB. 0 = unlimited.")
            .defineInRange("beatCacheMaxMB", 64, 0, 1024);

    public static final ModConfigSpec.IntValue BEAT_MAX_CONCURRENT_ANALYSES = SERVER_BUILDER
            .comment("How many tracks may be analysed at the same time.")
            .defineInRange("beatMaxConcurrentAnalyses", 2, 1, 8);

    public static final ModConfigSpec SERVER_SPEC = SERVER_BUILDER.build();

    @Override
    public double volumeMultiplier() {
        return VOLUME_MULTIPLIER.get();
    }

    @Override
    public int maxConcurrent() {
        return MAX_CONCURRENT.get();
    }

    @Override
    public int playbackRange() {
        return PLAYBACK_RANGE.get();
    }

    @Override
    public int maxPlaybackRange() {
        return MAX_PLAYBACK_RANGE.get();
    }

    @Override
    public int speakerLinkRange() {
        return SPEAKER_LINK_RANGE.get();
    }

    @Override
    public int maxSpeakersPerSource() {
        return MAX_SPEAKERS_PER_SOURCE.get();
    }

    @Override
    public boolean beatEnabled() {
        return BEAT_ENABLED.get();
    }

    @Override
    public BeatMode beatMode() {
        return BEAT_MODE.get();
    }

    @Override
    public BeatBand beatBand() {
        return BEAT_BAND.get();
    }

    @Override
    public double beatSensitivity() {
        return BEAT_SENSITIVITY.get();
    }

    @Override
    public int beatFloorDb() {
        return BEAT_FLOOR_DB.get();
    }

    @Override
    public int beatAttackMs() {
        return BEAT_ATTACK_MS.get();
    }

    @Override
    public int beatReleaseMs() {
        return BEAT_RELEASE_MS.get();
    }

    @Override
    public int beatOffsetMs() {
        return BEAT_OFFSET_MS.get();
    }

    @Override
    public double beatOnsetThreshold() {
        return BEAT_ONSET_THRESHOLD.get();
    }

    @Override
    public int beatOnsetPulseTicks() {
        return BEAT_ONSET_PULSE_TICKS.get();
    }

    @Override
    public int beatHysteresis() {
        return BEAT_HYSTERESIS.get();
    }

    @Override
    public int beatMinUpdateTicks() {
        return BEAT_MIN_UPDATE_TICKS.get();
    }

    @Override
    public int beatUncalibratedOffsetMs() {
        return BEAT_UNCALIBRATED_OFFSET_MS.get();
    }

    @Override
    public int beatFftSize() {
        // 2 のべき乗でないと FFT が作れない。範囲指定では中間値も入るので、近い 2 冪へ丸める。
        final int raw = BEAT_FFT_SIZE.get();
        if (raw >= 3072) {
            return 4096;
        }
        return raw >= 1536 ? 2048 : 1024;
    }

    @Override
    public int beatCacheMaxMB() {
        return BEAT_CACHE_MAX_MB.get();
    }

    @Override
    public int beatMaxConcurrentAnalyses() {
        return BEAT_MAX_CONCURRENT_ANALYSES.get();
    }

    @Override
    public boolean audioCacheEnabled() {
        return AUDIO_CACHE_ENABLED.get();
    }

    @Override
    public int audioCacheMaxMB() {
        return AUDIO_CACHE_MAX_MB.get();
    }
}
