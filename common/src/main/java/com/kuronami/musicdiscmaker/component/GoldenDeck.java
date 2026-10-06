package com.kuronami.musicdiscmaker.component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 金のジュークボックス付きトロッコが保持する「デッキ」状態 (座標・level に依存しない部分)。
 *
 * <p>金トロッコ ({@code GoldenJukeboxMinecartEntity}) の再生権威としての状態のうち、
 * メディア実体以外をここに集める。設計書 {@code MDM_310_GOLDEN_JUKEBOX_MINECART_DESIGN_RESULT_20260929.md}
 * の「金ジュークの状態 NBT キーを共有する」裁定に従い、保存キーは {@code GoldenJukeboxBlockEntity}
 * と同じ名前を使う (互換ツール・将来のデッキ共有化のため)。
 *
 * <p>メディア (ItemStack) は {@code ContainerEntity} 側の "Items" スロットが持つので、ここには
 * 置かない。保存書式の本体は Minecraft の tag で、読み書きの型変換は entity 側の帯別 adapter が担う。
 * このクラスは Minecraft の型を参照しないので、共通 test source set (MC classpath 無し) で
 * 鍵集合・往復・採番境界を検証できる。
 *
 * <h2>書き込んではいけないキー</h2>
 * このデッキの保存データは破壊時に drop item の ENTITY_DATA / EntityTag へ同梱され、
 * 再設置時に {@code entity.load} 系へ直接流れる。entity 側が解釈する予約キー
 * ({@link #RESERVED_ENTITY_KEYS}) をデッキが書くと、配置位置や機体 UUID を奪うので禁止する。
 */
public final class GoldenDeck {

    // ── 保存キー (GoldenJukeboxBlockEntity の saveAdditional と同じ名前) ──
    public static final String KEY_SOURCE_ID = "source_id";
    public static final String KEY_RANGE = "range";
    public static final String KEY_VOLUME = "volume";
    public static final String KEY_REPEAT = "repeat";
    public static final String KEY_SHUFFLE = "shuffle";
    public static final String KEY_SHUFFLE_SEED = "shuffleSeed";
    public static final String KEY_SHUFFLE_ANCHOR_DISC = "shuffleAnchorDisc";
    public static final String KEY_SHUFFLE_ANCHOR_TRACK = "shuffleAnchorTrack";
    public static final String KEY_DIRECTIONAL = "directional";
    public static final String KEY_PLAYBACK_STATE = "playbackState";
    public static final String KEY_PLAYBACK_GENERATION = "playbackGeneration";
    public static final String KEY_PAUSED = "paused";
    public static final String KEY_REDSTONE_PAUSED = "redstonePaused";
    public static final String KEY_AUTOMATION_FINISHED = "automationFinished";
    public static final String KEY_PAUSED_OFFSET = "pausedOffset";
    public static final String KEY_PLAYBACK_START_GAME_TIME = "playbackStartGameTime";
    public static final String KEY_ALBUM_TRACK = "albumTrack";
    public static final String KEY_PLAYLIST_TRACK = "playlistTrack";

    /**
     * デッキが書き込みうるキーの全集合 (書き順)。{@code source_id} は未採番の時だけ欠ける。
     * entity NBT / item ENTITY_DATA 双方の検証でこの集合を使う。
     */
    public static final Set<String> KEYS = Set.of(
            KEY_SOURCE_ID, KEY_RANGE, KEY_VOLUME, KEY_REPEAT, KEY_SHUFFLE, KEY_SHUFFLE_SEED,
            KEY_SHUFFLE_ANCHOR_DISC, KEY_SHUFFLE_ANCHOR_TRACK, KEY_DIRECTIONAL, KEY_PLAYBACK_STATE,
            KEY_PLAYBACK_GENERATION, KEY_PAUSED, KEY_REDSTONE_PAUSED, KEY_AUTOMATION_FINISHED,
            KEY_PAUSED_OFFSET, KEY_PLAYBACK_START_GAME_TIME, KEY_ALBUM_TRACK, KEY_PLAYLIST_TRACK);

    /**
     * デッキ保存データが絶対に書いてはいけない entity/container 側の予約キー。
     * ENTITY_DATA 経由の再設置で entity 初期状態を奪わないための境界。
     * ({@code Items}/{@code LootTable} 系は {@code ContainerEntity} の chest vehicle 経路が持つ。)
     */
    public static final Set<String> RESERVED_ENTITY_KEYS = Set.of(
            "Pos", "Motion", "Rotation", "UUID", "id", "Dimension", "FallDistance", "Fire", "Air",
            "OnGround", "Invulnerable", "PortalCooldown", "CustomName", "CustomNameVisible", "Silent",
            "NoGravity", "Glowing", "Tags", "Passengers", "Items", "LootTable", "LootTableSeed",
            "CommandBlock", "DisplayState", "DisplayOffset", "CustomDisplayTile",
            "Item", "ItemDropChance", "PickupDelay", "Age", "Health", "HurtTime", "DeathTime",
            "MinDisplayOffset", "MaxDisplayOffset", "PushX", "PushZ", "Fuel",
            "TransferCooldown", "Enabled", "LastOutput", "LoveCause", "AbsorptionAmount");

    // ── 既定値・範囲 (GoldenJukeboxBlockEntity と同じ値。BE 側は Minecraft 型を持つため
    //    この純クラスからは参照できず、値だけ並記する) ──
    public static final int RANGE_MIN = 16;
    public static final int RANGE_MAX = 256;
    public static final int RANGE_DEFAULT = 64;
    public static final int VOLUME_MIN = 0;
    public static final int VOLUME_MAX = 200;
    public static final int VOLUME_DEFAULT = 100;
    public static final boolean DIRECTIONAL_DEFAULT = true;

    private UUID sourceId;
    private PlaybackCursor cursor = PlaybackCursor.initial();
    private long pausedOffsetMs = 0L;
    private long playbackStartGameTime = -1L;
    private int rangeBlocks = RANGE_DEFAULT;
    private int volumePercent = VOLUME_DEFAULT;
    private boolean repeat = false;
    private boolean shuffle = false;
    private long shuffleSeed = 0L;
    private int shuffleAnchorDiscIndex = PlaybackCursor.NO_INDEX;
    private int shuffleAnchorTrackIndex = PlaybackCursor.NO_INDEX;
    private boolean directional = DIRECTIONAL_DEFAULT;
    /** redstone 挙動は未決定 (設計ゲート未通過)。保存書式だけ BE と揃え、挙動は実装しない。 */
    private boolean redstonePaused = false;
    private boolean automationFinished = false;

    /**
     * 未採番なら新しい機体識別子を発行する。呼び出し側が server 権威 (ServerLevel 上) を保証する。
     * client/ detached 経路からは呼ばない。
     */
    public UUID ensureSourceId() {
        if (sourceId == null) {
            sourceId = UUID.randomUUID();
        }
        return sourceId;
    }

    /**
     * 保存値から復元した識別子を代入する。entity の読み込み経路専用で、
     * 新規採番 ({@link #ensureSourceId}) とは別の入口にする。
     */
    public void assignSourceId(UUID restored) {
        this.sourceId = restored;
    }

    /** 発行済みの機体識別子 (未採番なら null)。 */
    public UUID peekSourceId() {
        return sourceId;
    }

    public boolean hasSourceId() {
        return sourceId != null;
    }

    public PlaybackCursor cursor() {
        return cursor;
    }

    public void setCursor(PlaybackCursor value) {
        cursor = value == null ? PlaybackCursor.initial() : value;
    }

    public long pausedOffsetMs() {
        return pausedOffsetMs;
    }

    public void setPausedOffsetMs(long value) {
        pausedOffsetMs = Math.max(0L, value);
    }

    public long playbackStartGameTime() {
        return playbackStartGameTime;
    }

    public void setPlaybackStartGameTime(long value) {
        playbackStartGameTime = value;
    }

    public int rangeBlocks() {
        return rangeBlocks;
    }

    public void setRangeBlocks(int value) {
        rangeBlocks = clamp(value, RANGE_MIN, RANGE_MAX);
    }

    public int volumePercent() {
        return volumePercent;
    }

    public void setVolumePercent(int value) {
        volumePercent = clamp(value, VOLUME_MIN, VOLUME_MAX);
    }

    public boolean isRepeat() {
        return repeat;
    }

    public void setRepeat(boolean value) {
        repeat = value;
    }

    public boolean isShuffle() {
        return shuffle;
    }

    /**
     * 順序指定をまとめて代入する。anchor は盤/盤内曲の復元位置で、無指定は {@code NO_INDEX}。
     */
    public void setShuffle(boolean value, long seed, int anchorDiscIndex, int anchorTrackIndex) {
        shuffle = value;
        shuffleSeed = seed;
        shuffleAnchorDiscIndex = anchorDiscIndex;
        shuffleAnchorTrackIndex = anchorTrackIndex;
    }

    public long shuffleSeed() {
        return shuffleSeed;
    }

    public int shuffleAnchorDiscIndex() {
        return shuffleAnchorDiscIndex;
    }

    public int shuffleAnchorTrackIndex() {
        return shuffleAnchorTrackIndex;
    }

    public boolean isDirectional() {
        return directional;
    }

    public void setDirectional(boolean value) {
        directional = value;
    }

    public boolean isRedstonePaused() {
        return redstonePaused;
    }

    public void setRedstonePaused(boolean value) {
        redstonePaused = value;
    }

    public boolean isAutomationFinished() {
        return automationFinished;
    }

    public void setAutomationFinished(boolean value) {
        automationFinished = value;
    }

    /**
     * 収納されたメディアが変わった時の遷移。位置と再生起点を消し、機体の設定
     * (範囲/音量/順序) は機器に残す。識別子は機体のものなので消さない。
     */
    public void onMediaChanged() {
        cursor = cursor.clear();
        pausedOffsetMs = 0L;
        playbackStartGameTime = -1L;
        automationFinished = false;
    }

    /**
     * 破壊→item 化の直前に再生状態を凍結する。再生中なら経過位置を pause 状態として
     * 確定させ、拾い直し後の play が新しい世代で始まる土台にする (設計書 §2.8)。
     *
     * @param currentOffsetMs 再生中の経過位置。PLAYING 以外では使わない
     */
    public void freezeForCarry(long currentOffsetMs) {
        if (cursor.state() == PlaybackCursor.State.PLAYING) {
            pausedOffsetMs = Math.max(0L, currentOffsetMs);
            playbackStartGameTime = -1L;
            cursor = cursor.pause();
        }
    }

    /**
     * hopper からの取出し可否 (GoldenJukeboxBlockEntity#isAutomationExtractionReady と同じ規則。
     * block の近傍信号項だけは固定ブロックを持たない cart には無いので含まない)。
     */
    public boolean isAutomationExtractionReady(boolean hasMedia) {
        return hasMedia && automationFinished
                && cursor.state() == PlaybackCursor.State.STOPPED
                && !redstonePaused && cursor.state() != PlaybackCursor.State.PAUSED;
    }

    /**
     * 保存データをキー列マップへ書き出す。値の型は String / Integer / Long / Boolean のみ
     * (NBT の put 系へそのまま写せる)。{@code source_id} は未掲番なら書かない (BE と同じ)。
     */
    public Map<String, Object> encode() {
        final Map<String, Object> tag = new LinkedHashMap<>();
        if (sourceId != null) {
            tag.put(KEY_SOURCE_ID, sourceId.toString());
        }
        tag.put(KEY_RANGE, rangeBlocks);
        tag.put(KEY_VOLUME, volumePercent);
        tag.put(KEY_REPEAT, repeat);
        tag.put(KEY_SHUFFLE, shuffle);
        tag.put(KEY_SHUFFLE_SEED, shuffleSeed);
        tag.put(KEY_SHUFFLE_ANCHOR_DISC, shuffleAnchorDiscIndex);
        tag.put(KEY_SHUFFLE_ANCHOR_TRACK, shuffleAnchorTrackIndex);
        tag.put(KEY_DIRECTIONAL, directional);
        tag.put(KEY_PLAYBACK_STATE, cursor.state().name());
        tag.put(KEY_PLAYBACK_GENERATION, cursor.generation());
        tag.put(KEY_PAUSED, cursor.state() == PlaybackCursor.State.PAUSED);
        tag.put(KEY_REDSTONE_PAUSED, redstonePaused);
        tag.put(KEY_AUTOMATION_FINISHED, automationFinished);
        tag.put(KEY_PAUSED_OFFSET, pausedOffsetMs);
        tag.put(KEY_PLAYBACK_START_GAME_TIME, playbackStartGameTime);
        tag.put(KEY_ALBUM_TRACK, cursor.discIndex());
        tag.put(KEY_PLAYLIST_TRACK, cursor.trackIndex());
        return tag;
    }

    /**
     * キー列マップから復元する。欠損キーは BE の loadAdditional と同じ既定へ倒す。
     * 媒体の有無は container 側が先に読んだ後の実測値を渡す (BE の {@code hasDisc()} 相当)。
     * 識別子の採番はここでは行わない (読み込みでは既存値だけを復元し、無ければ未掲番のまま)。
     *
     * @param values   保存値マップ
     * @param hasMedia メディアスロットに実メディアが入っているか
     * @return 復元したデッキ
     */
    public static GoldenDeck decode(Map<String, ?> values, boolean hasMedia) {
        final GoldenDeck deck = new GoldenDeck();
        deck.sourceId = parseSourceId(stringValue(values, KEY_SOURCE_ID, ""));
        deck.rangeBlocks = clamp(intValue(values, KEY_RANGE, RANGE_DEFAULT), RANGE_MIN, RANGE_MAX);
        deck.volumePercent = clamp(intValue(values, KEY_VOLUME, VOLUME_DEFAULT), VOLUME_MIN, VOLUME_MAX);
        deck.repeat = boolValue(values, KEY_REPEAT, false);
        deck.shuffle = boolValue(values, KEY_SHUFFLE, false);
        deck.shuffleSeed = longValue(values, KEY_SHUFFLE_SEED, 0L);
        deck.shuffleAnchorDiscIndex = intValue(values, KEY_SHUFFLE_ANCHOR_DISC, PlaybackCursor.NO_INDEX);
        deck.shuffleAnchorTrackIndex = intValue(values, KEY_SHUFFLE_ANCHOR_TRACK, PlaybackCursor.NO_INDEX);
        deck.directional = boolValue(values, KEY_DIRECTIONAL, DIRECTIONAL_DEFAULT);
        final boolean restoredPaused = boolValue(values, KEY_PAUSED, false);
        final String restoredState = stringValue(values, KEY_PLAYBACK_STATE, "");
        final long generation = Math.max(0L, longValue(values, KEY_PLAYBACK_GENERATION, 0L));
        deck.pausedOffsetMs = Math.max(0L, longValue(values, KEY_PAUSED_OFFSET, 0L));
        deck.playbackStartGameTime = longValue(values, KEY_PLAYBACK_START_GAME_TIME, -1L);
        deck.redstonePaused = boolValue(values, KEY_REDSTONE_PAUSED, false);
        deck.automationFinished = boolValue(values, KEY_AUTOMATION_FINISHED, false);
        // Match GoldenJukeboxBlockEntity.loadAdditional: a legacy save without
        // positional keys falls back to the first position when media exists.
        // Explicitly stored NO_INDEX remains distinguishable from absent keys.
        final int discIndex = intValue(values, KEY_ALBUM_TRACK, 0);
        final int trackIndex = intValue(values, KEY_PLAYLIST_TRACK, 0);
        PlaybackCursor.State state;
        try {
            state = PlaybackCursor.State.valueOf(restoredState);
        } catch (IllegalArgumentException ignored) {
            // 旧形式は pause フラグと再生起点から復元 (GoldenJukeboxBlockEntity.loadAdditional と同じ)
            state = restoredPaused ? PlaybackCursor.State.PAUSED
                    : deck.playbackStartGameTime >= 0L ? PlaybackCursor.State.PLAYING
                    : PlaybackCursor.State.STOPPED;
        }
        final boolean stoppedWithoutPosition = state == PlaybackCursor.State.STOPPED
                && discIndex == PlaybackCursor.NO_INDEX && trackIndex == PlaybackCursor.NO_INDEX;
        deck.cursor = hasMedia
                ? new PlaybackCursor(stoppedWithoutPosition ? PlaybackCursor.NO_INDEX : Math.max(0, discIndex),
                        stoppedWithoutPosition ? PlaybackCursor.NO_INDEX : Math.max(0, trackIndex),
                        state, generation)
                : PlaybackCursor.initial();
        if (!hasMedia) {
            deck.redstonePaused = false;
            deck.automationFinished = false;
        }
        return deck;
    }

    private static UUID parseSourceId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String stringValue(Map<String, ?> values, String key, String fallback) {
        final Object value = values.get(key);
        return value instanceof String s ? s : fallback;
    }

    private static int intValue(Map<String, ?> values, String key, int fallback) {
        final Object value = values.get(key);
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private static long longValue(Map<String, ?> values, String key, long fallback) {
        final Object value = values.get(key);
        return value instanceof Number n ? n.longValue() : fallback;
    }

    private static boolean boolValue(Map<String, ?> values, String key, boolean fallback) {
        final Object value = values.get(key);
        return value instanceof Boolean b ? b : fallback;
    }
}
