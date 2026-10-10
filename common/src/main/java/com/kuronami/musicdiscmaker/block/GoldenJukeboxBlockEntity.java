package com.kuronami.musicdiscmaker.block;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
//? if >=1.21 {
import com.kuronami.musicdiscmaker.compat.additionaladditions.AdditionalAdditionsAlbumSupport;
//?} else {
//?}
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.MediaSequence;
import com.kuronami.musicdiscmaker.component.MediaSequenceResolver;
import com.kuronami.musicdiscmaker.component.PlaybackCursor;
import com.kuronami.musicdiscmaker.component.PlaybackClockAccess;
import com.kuronami.musicdiscmaker.component.PauseAwarePlaybackClock;
import com.kuronami.musicdiscmaker.component.PlaylistPlayback;
import com.kuronami.musicdiscmaker.component.VanillaTrackData;
import com.kuronami.musicdiscmaker.event.SpeakerPlayback;
import com.kuronami.musicdiscmaker.event.GoldenSourceRegistry;
import com.kuronami.musicdiscmaker.event.GoldenSourceRetirements;
import com.kuronami.musicdiscmaker.item.AlbumItem;
import com.kuronami.musicdiscmaker.register.ModItems;
//? if >=1.21.2 {
import com.kuronami.musicdiscmaker.network.ModPayload;
//?} elif >=1.21 {
//?} else {
/*import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;

import com.kuronami.musicdiscmaker.network.ModPayload;
*/
//?}
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.register.ModBlockEntities;
//? if >=1.21 {
import com.kuronami.musicdiscmaker.register.ModDataComponents;
//?} else {
//?}

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
//? if >=1.21.2 {
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
//?} elif >=1.21 {
/*import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
*/
//?} else {
//?}
import net.minecraft.core.NonNullList;
//? if >=1.21.2 {
import net.minecraft.core.component.DataComponents;
//?} else {
//?}
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
//? if >=1.21 {
//?} else {
/*import net.minecraft.world.item.Item;
*/
//?}
import net.minecraft.world.item.ItemStack;
//? if >=1.21 {
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.JukeboxSongPlayer;
//?} else {
/*import net.minecraft.world.item.RecordItem;
*/
//?}
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
//? if >=1.21.11 {
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
//?} elif >=1.21 {
//?} else {
/*import net.minecraft.world.level.gameevent.GameEvent;
*/
//?}

/**
 * 強化版ジュークボックスの BlockEntity。
 *
 * <p>ディスク 1 枚を持ち、再生設定 (可聴範囲・音量・リピート・再生/停止) を NBT に永続化する。
 * vanilla {@link JukeboxSongPlayer} を埋め込み、vanilla ディスクの音・ノートパーティクル・
 * コンパレータ出力・gameEvent・曲終了検知を得る。custom disc は既存 maker と同じく silent song
 * (JUKEBOX_PLAYABLE) 経由で vanilla 再生状態に乗り、実音声は LavaPlayer が per-block の
 * 可聴範囲・音量つきでストリームする ({@link PlayDiscPayload})。
 *
 * <p>再生の権威はこの BE (server)。ActiveDiscRegistry は使わず、late-join には
 * {@link #resendTo(ServerPlayer)} で現在位置を送る。
 *
 * <p>金のジュークボックス付きトロッコは、この BE を {@link GoldenJukeboxMount} つきで 1 つ載せる。
 * 曲の選び方・送り方・止め方・保存は設置した時と同じで、座標に結び付いた処理 (赤石入力・コンパレータ・
 * blockstate・Speaker・座標宛ての再生 payload) だけを使わず、鳴らす・知らせるを mount に任せる。
 */
public class GoldenJukeboxBlockEntity extends BlockEntity implements WorldlyContainer {

    public static final int SLOT_DISC = 0;
    private static final int SIZE = 1;
    private static final int[] AUTOMATION_SLOTS = {SLOT_DISC};

    /** 範囲の下限。机の周り・小部屋だけで鳴らせるよう 16 から下げた (2026-10-10 本人裁定)。 */
    public static final int RANGE_MIN = 4;
    public static final int RANGE_MAX = 256;
    public static final int RANGE_DEFAULT = 64;
    public static final int VOLUME_MIN = 0;
    public static final int VOLUME_MAX = 200;
    public static final int VOLUME_DEFAULT = 100;
    /**
     * 指向性の既定値。true = 従来どおりの positional audio。NBT に {@code directional} が無い
     * (= 新規設置 / この機能より前のセーブ) ときもこの値になるので、既存ワールドの聴こえ方は変わらない。
     */
    public static final boolean DIRECTIONAL_DEFAULT = true;

    //? if >=1.21 {
    //?} else {
/*    private static final int EVENT_PLAY_RECORD = 1010;
    private static final int EVENT_STOP_RECORD = 1011;

    private static final String SYNC_ELAPSED_MS_KEY = "syncElapsedMs";
    */
    //?}
    private final NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);

    // ── 永続設定 ──
    private int rangeBlocks = RANGE_DEFAULT;
    private int volumePercent = VOLUME_DEFAULT;
    private boolean repeat = false;
    /** 現在の媒体全体を曲単位で混ぜる。順序そのものは保存せず seed と anchor から再生する。 */
    private boolean shuffle = false;
    private long shuffleSeed = 0L;
    private int shuffleAnchorDiscIndex = PlaybackCursor.NO_INDEX;
    private int shuffleAnchorTrackIndex = PlaybackCursor.NO_INDEX;
    private PlaybackCursor cursor = PlaybackCursor.initial();
    /** 盤交換またはload時にだけ作る媒体の索引。Albumをserver tickごとに全走査しない。 */
    private MediaSequenceResolver.ResolvedSequence mediaSequence =
            MediaSequenceResolver.resolve(ItemStack.EMPTY);
    /** tick では作り直さない、現在媒体用の物理順または shuffle 順。 */
    private MediaSequence playbackSequence = MediaSequence.empty();
    /**
     * 音の指向性。true = 従来どおりの positional audio (左右定位・距離減衰つき) = 既定。
     * false = 可聴範囲の中にいる限り位置に関係なくフラットに聴こえる (BGM モード)。範囲外では
     * 黙る (範囲ゲートは維持する)。client へは BE 同期と再生 payload の両方で運ぶ。
     */
    private boolean directional = DIRECTIONAL_DEFAULT;
    /** Stable server identity for links. Null until loaded from NBT or first requested on a server. */
    @Nullable
    private UUID sourceId;
    /** pause 時に保存した再生位置 (ms)。resume で offset seek に使う。 */
    private long pausedOffsetMs = 0L;
    /**
     * 再生開始時の {@link net.minecraft.world.level.Level#getGameTime()}。-1 = 停止/一時停止中。
     * client の進捗バー計算用に同期する (wall-clock はマルチプレイで機体差があるため gameTime を使う)。
     */
    private long playbackStartGameTime = -1L;
    /** Sample the input on attachment; loading a powered chunk must not count as a new press. */
    private boolean redstonePowerInitialized;
    private boolean lastRedstonePower;
    /** Persisted proof that this medium reached its terminal end and may be exported. */
    private boolean automationFinished = false;
    //? if >=1.21 {
    private static final String SYNC_ELAPSED_MS_KEY = "syncElapsedMs";
    //?}

    // ── server 揮発 ──
    //? if >=1.21 {
    private final JukeboxSongPlayer songPlayer = new JukeboxSongPlayer(this::onSongChanged, this.getBlockPos());
    //?} else {
    //?}
    /** 現在の再生開始 wall-clock (ms)。0 = 未再生。live sync / repeat / late-join の offset 計算に使う。 */
    private long startMillis = 0L;
    /** 初回 server tick で chunk load 後の再生復帰を 1 度だけ行うためのフラグ。 */
    private boolean initialized = false;
    /**
     * 直近 tick の {@link #isRedstonePlaying()}。実尺での再生終了は vanilla の {@code songPlayer} には
     * 見えない (無音バケットはまだ鳴っている) ので {@link #onSongChanged()} 由来の neighbor 更新が来ない。
     * 値が変わった tick だけ更新を撃つためのラッチ。
     */
    private boolean redstonePlayingLatch = false;
    /** Last comparator state announced to neighbours; -1 forces the first update. */
    private int comparatorLatch = -1;

    // ── client 揮発 ──
    /** sync で受け取った再生経過 (ms)。-1 = 停止中 / 未受信。 */
    private long clientElapsedAnchorMs = -1L;
    /** そのアンカーを受け取った client の壁時計 (ms)。 */
    private long clientAnchorWallClockMs = 0L;

    /**
     * 載せた BE がバニラ盤の終わりを尺で決める時の猶予。設置した BE が使う {@code JukeboxSongPlayer} は
     * 尺 + 20 tick で終わるので、それに合わせる。
     */
    private static final long MOUNTED_VANILLA_END_GRACE_MS = 1_000L;

    /** 載せられている時の持ち主。設置した金のジュークボックスでは null。 */
    @Nullable
    private GoldenJukeboxMount mount;

    public GoldenJukeboxBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.GOLDEN_JUKEBOX.get(), pos, state);
    }

    /** トロッコに載せる BE。{@link #setLevel} より先に mount を持たせ、設置物の登録簿へ載せない。 */
    public GoldenJukeboxBlockEntity(BlockPos pos, BlockState state, GoldenJukeboxMount mount) {
        this(pos, state);
        this.mount = mount;
    }

    /** トロッコに載っているか。 */
    public boolean isMounted() {
        return mount != null;
    }

    /** 載せた側が chest vehicle の保存・hopper 経路で同じ盤のリストを見るための口。 */
    public NonNullList<ItemStack> mountedItems() {
        return items;
    }

    /** 載せた BE の全状態 (保存と client への反映に使う)。 */
    public CompoundTag mountedStateTag() {
        //? if >=1.21 {
        return getUpdateTag(level.registryAccess());
        //?} else {
        /*return getUpdateTag();
        *///?}
    }

    /**
     * GUI へ届ける状態。盤 (アルバムは大きい) は menu のスロット同期が運ぶので外す。
     * 受け側は {@link #applyMountedSync} で自分の盤を残したまま読み込む。
     */
    public CompoundTag mountedSyncTag() {
        final CompoundTag tag = mountedStateTag().copy();
        tag.remove("Items");
        tag.remove("inventory");
        return tag;
    }

    /** client: {@link #mountedSyncTag()} を、今持っている盤を残したまま読み込む。 */
    public void applyMountedSync(CompoundTag state) {
        final CompoundTag local = mountedStateTag();
        final CompoundTag merged = state.copy();
        for (final String key : new String[] {"Items", "inventory"}) {
            if (local.contains(key)) merged.put(key, local.get(key));
        }
        loadMountedState(merged);
    }

    /** {@link #mountedStateTag()} を読み込む。設置した BE の同期と同じ読み込み経路。 */
    public void loadMountedState(CompoundTag tag) {
        //? if >=1.21.11 {
        loadWithComponents(net.minecraft.world.level.storage.TagValueInput.create(
                net.minecraft.util.ProblemReporter.DISCARDING, level.registryAccess(), tag));
        //?} elif >=1.21 {
        /*loadWithComponents(tag, level.registryAccess());
        *///?} else {
        /*load(tag);
        *///?}
    }

    @Override
    public void setChanged() {
        // 載せた BE の座標は世界のブロックではない。そこへ chunk の変更やコンパレータ更新を撃たない。
        if (mount != null) {
            mount.markDirty();
            return;
        }
        super.setChanged();
    }

    // ── GUI / block 用アクセサ ──

    public com.kuronami.musicdiscmaker.network.PlaybackSourceStamp playbackIdentity() {
        return new com.kuronami.musicdiscmaker.network.PlaybackSourceStamp(sourceIdentity(), cursor.generation());
    }

    public int getRangeBlocks() {
        return rangeBlocks;
    }

    public int getVolumePercent() {
        return volumePercent;
    }

    public boolean isRepeat() {
        return repeat;
    }

    public boolean isShuffle() {
        return shuffle;
    }

    /** GUI 用の再生可能曲数。媒体の解決結果は盤交換・ロード時にのみ更新する。 */
    public int navigableTrackCount() {
        return getAlbumTrack() >= 0 ? albumTrackCount() : mediaSequence.tracks().size();
    }

    public boolean isPaused() {
        return cursor.state() == PlaybackCursor.State.PAUSED;
    }

    public boolean isStopped() {
        return cursor.state() == PlaybackCursor.State.STOPPED;
    }

    public PlaybackCursor playbackCursor() {
        return cursor;
    }

    /** 今まさに音を出しているか (再生中で、読み込み直し後の再開も済んでいる)。 */
    public boolean isSounding() {
        return startMillis > 0L && cursor.state() == PlaybackCursor.State.PLAYING;
    }

    /** true = positional audio (既定)。false = 範囲内フラット聴取 (BGM モード)。 */
    public boolean isDirectional() {
        return directional;
    }

    private long playbackTimeMs() {
        if (level instanceof ServerLevel serverLevel
                && serverLevel.getServer() instanceof PlaybackClockAccess clock) {
            return clock.mdm$playbackTimeMs();
        }
        if (level instanceof PlaybackClockAccess clock) return clock.mdm$playbackTimeMs();
        return PauseAwarePlaybackClock.realTimeMs();
    }

    /**
     * 現在の再生経過 (ms)。progress バー表示用。一時停止中は保存 offset、停止中は 0。
     *
     * <p>server は稼働中の実時間を使い、復元直後だけ保存済みゲーム時刻から位置を戻す。
     *
     * <p>client は sync で受け取った経過 ({@value #SYNC_ELAPSED_MS_KEY}) を受信時刻に固定し、そこから
     * 自分の壁時計で進める。<b>client でゲーム内 tick から数えてはいけない</b> — client の
     * {@code gameTime} はサーバが 20 tick ごとに送る値で上書きされるので、TPS が 20 を割った分だけ
     * 表示が遅れ、1 秒周期の補正で止まって飛ぶ (実測: 223 秒の曲で約 4 秒 = 1.8% のずれ)。
     * 曲送りも実音と同じ実時間基準を使い、シングルプレイのゲーム一時停止中は時計を止める。
     */
    public long currentElapsedMs() {
        if (isPaused()) {
            return pausedOffsetMs;
        }
        if (level == null) {
            return 0L;
        }
        //? if >=1.21.2 {
        if (level.isClientSide()) {
        //?} else {
/*        if (level.isClientSide) {
        */
        //?}
            final long anchored = clientElapsedMs();
            return anchored < 0L ? 0L : clampToTrackDuration(anchored);
        }
        if (startMillis > 0L) {
            return clampToTrackDuration(Math.max(0L, playbackTimeMs() - startMillis));
        }
        if (cursor.state() != PlaybackCursor.State.PLAYING) {
            return 0L;
        }
        return clampToTrackDuration(Math.max(0L, (level.getGameTime() - playbackStartGameTime) * 50L));
    }

    /**
     * 有限尺は総尺でクランプする (非リピートの自然終了後に経過が尺を超えて増え続けるのを防ぐ)。
     * リピート時はループごとに起点がリセットされるため境界クランプは無害。
     */
    private long clampToTrackDuration(long elapsed) {
        final long dur = trackDurationMs();
        return dur > 0L ? Math.min(elapsed, dur) : elapsed;
    }

    /** Save the actual audio offset even when server ticks lag behind real time. */
    private long savedPlaybackStartGameTime() {
        if (level instanceof ServerLevel && startMillis > 0L && !isPaused()) {
            return level.getGameTime() - Math.max(0L, playbackTimeMs() - startMillis) / 50L;
        }
        return playbackStartGameTime;
    }

    //? if >=1.21.2 {
    private long syncElapsedMs() {
    //?} elif >=1.21 {
/*    public long syncElapsedMs() {
    */
    //?} else {
/*    private long syncElapsedMs() {
    */
    //?}
        if (startMillis > 0L) {
            return Math.max(0L, playbackTimeMs() - startMillis);
        }
        if (cursor.state() == PlaybackCursor.State.PLAYING && level != null && playbackStartGameTime >= 0L) {
            return Math.max(0L, (level.getGameTime() - playbackStartGameTime) * 50L);
        }
        return -1L;
    }

    /**
     * client 側の再生経過 (ms)。-1 = 停止中 / アンカー未受信。
     * sync で受け取った経過を受信時刻に固定し、そこから client 自身の壁時計で進める。
     */
    private long clientElapsedMs() {
        if (cursor.state() != PlaybackCursor.State.PLAYING || clientElapsedAnchorMs < 0L) {
            return -1L;
        }
        return clientElapsedAnchorMs + Math.max(0L, playbackTimeMs() - clientAnchorWallClockMs);
    }

    /**
     * client 専用: 実音が鳴り始めた瞬間に表示アンカーを実音の開始へ打ち直す。
     * {@code ClientPlaybackManager} が最初の実 PCM ({@code LavaPlayerAudioStream#noteFirstAudio}) を
     * 検知した時に、1 再生につき 1 回だけ main thread から呼ぶ。
     *
     * <p>アンカーの値は「この再生が開始したオフセット」(頭出しなら頭出し先・通常再生なら 0) で、
     * 以後の {@link #currentElapsedMs()} は実音の開始からの経過になる。
     * URL 解決・sound engine への登録に掛かった時間を表示が先走りしなくなる。
     *
     * <p>server で呼ばれたら何もしない。1 回だけの保証は呼び出し側
     * ({@code PlaybackSessions#noteFirstAudio} の世代ガード) が持つ。
     * 打ち直し後も server の BE 同期 (getUpdateTag / loadAdditional) が来れば
     * {@code clientElapsedAnchorMs} は上書きされる余地が残る (表示が server の現在経過へ戻るだけで、
     * 悪化はしない)。
     */
    public void clientReanchorElapsedToAudioStart(long startOffsetMs) {
        if (level == null || level instanceof ServerLevel) {
            return; // server (専用サーバを含む) では何もしない
        }
        this.clientElapsedAnchorMs = Math.max(0L, startOffsetMs);
        this.clientAnchorWallClockMs = playbackTimeMs();
    }

    /**
     * 現在のディスクの総尺 (ms)。0 = 不明 / ラジオ。
     *
     * <p>custom disc は {@link CustomTrackData#durationMs()} が実尺を持つ。vanilla や他 MOD の
     * ディスクは {@code jukebox_song} の {@code length_in_seconds} を使う (例: [Let's Do] Furniture の
     * {@code furniture:letsdo_theme} = 124 秒)。<b>この順序を入れ替えてはいけない</b> —
     * custom disc も {@code SilentSongs} のバケット長を持つ {@code jukebox_playable} を抱えているので、
     * song を先に見ると実尺 95 秒のトラックがバケットの 120 秒として表示される。
     */
    public long trackDurationMs() {
        final CustomTrackData t = currentTrack();
        if (t != null) {
            return t.durationMs();
        }
        //? if >=1.21 {
        return songFor(effectiveDisc()).map(h -> h.value().lengthInTicks() * 50L).orElse(0L);
        //?} else {
/*        final ItemStack disc = effectiveDisc();
        return disc.getItem() instanceof RecordItem record ? record.getLengthInTicks() * 50L : 0L;
        */
        //?}
    }

    /**
     * progress バーで頭出し操作を許可できるか。有限尺の custom disc と、バニラ・他 MOD の盤。
     *
     * <p>バニラ・他 MOD の盤は途中からの再生を管理再生で鳴らす ({@code SpeakerPlayback#managesVanilla})。
     * 2026-10-10 本人要望でカスタム盤と同じく動かせるようにした。
     */
    public boolean isSeekable() {
        return hasDisc() && (currentTrack() != null || currentVanillaTrack() != null)
                && !isLiveStream() && trackDurationMs() > 0L;
    }

    //? if >=26.1 {
    //?} elif >=1.21.2 {
/*    public static boolean isPlayableInSlot(ItemStack stack) {
        return stack.has(DataComponents.JUKEBOX_PLAYABLE) || AdditionalAdditionsAlbumSupport.isAlbum(stack)
                || isPlayableMdmAlbum(stack);
    }
    */
    //?} else {
    //?}
    public boolean hasDisc() {
        return !items.get(SLOT_DISC).isEmpty();
    //? if >=26.1 {
    }
    public static boolean isPlayableInSlot(ItemStack stack) {
        return stack.has(DataComponents.JUKEBOX_PLAYABLE) || AdditionalAdditionsAlbumSupport.isAlbum(stack)
                || isPlayableMdmAlbum(stack);
    }
    //?} elif >=1.21.2 {
/*    }
    */
    //?} elif >=1.21 {
/*    }
    public static boolean isPlayableInSlot(ItemStack stack) {
        return stack.has(DataComponents.JUKEBOX_PLAYABLE) || AdditionalAdditionsAlbumSupport.isAlbum(stack)
                || isPlayableMdmAlbum(stack);
    }
    */
    //?} else {
/*    }
    */
    //?}
    public ItemStack getDisc() {
        return items.get(SLOT_DISC);
    }

    public ItemStack effectiveDisc() {
        if (isMdmAlbum()) {
            return AlbumItem.contents(getDisc()).discAt(Math.max(0, cursor.discIndex()));
        }
        //? if >=1.21 {
        return getAlbumTrack() >= 0
                ? AdditionalAdditionsAlbumSupport.trackAt(getDisc(), getAlbumTrack()) : getDisc();
        //?} else {
        /*return getDisc();
        *///?}
    }

    /** MDM Albumは、少なくとも1曲を解決できる時だけ音源スロットへ入る。 */
    private static boolean isPlayableMdmAlbum(ItemStack stack) {
        return stack.is(ModItems.ALBUM.get()) && !MediaSequenceResolver.resolve(stack).tracks().isEmpty();
    }

    private boolean isMdmAlbum() {
        return mediaSequence.kind() == MediaSequenceResolver.Kind.MDM_ALBUM;
    }

    private MediaSequence.Position mediaPosition() {
        return new MediaSequence.Position(Math.max(0, cursor.discIndex()), Math.max(0, cursor.trackIndex()));
    }

    private void rebuildMediaSequence() {
        mediaSequence = MediaSequenceResolver.resolve(getDisc());
        rebuildPlaybackSequence();
    }

    private void rebuildPlaybackSequence() {
        final MediaSequence physical = MediaSequence.of(
                mediaSequence.tracks().stream().map(MediaSequenceResolver.ResolvedTrack::position).toList());
        if (!shuffle || physical.isEmpty()) {
            playbackSequence = physical;
            return;
        }
        final MediaSequence.Position savedAnchor = shuffleAnchorPosition();
        final MediaSequence.Position anchor = savedAnchor != null && physical.positions().contains(savedAnchor)
                ? savedAnchor : physical.first().orElseThrow();
        setShuffleAnchor(anchor);
        playbackSequence = physical.shuffled(anchor, shuffleSeed);
    }

    @Nullable
    private MediaSequence.Position shuffleAnchorPosition() {
        return shuffleAnchorDiscIndex < 0 || shuffleAnchorTrackIndex < 0
                ? null : new MediaSequence.Position(shuffleAnchorDiscIndex, shuffleAnchorTrackIndex);
    }

    private void setShuffleAnchor(MediaSequence.Position position) {
        shuffleAnchorDiscIndex = position.discIndex();
        shuffleAnchorTrackIndex = position.trackIndex();
    }

    private Optional<MediaSequenceResolver.ResolvedTrack> nextResolved(boolean manual) {
        if (!manual) {
            final Optional<MediaSequenceResolver.ResolvedTrack> playing = mediaSequence.at(mediaPosition());
            if (playing.isEmpty() || !playing.get().isVanilla() && !MediaSequence.allowsAutomaticAdvance(
                    playing.get().track().durationMs(), playing.get().track().radio())) {
                return Optional.empty();
            }
        }
        return playbackSequence.next(mediaPosition(), manual || repeat).flatMap(mediaSequence::at);
    }

    /** Additional Additions の互換アルバムの盤位置。MDM の Album とは別の容器。 */
    public int getAlbumTrack() {
        //? if >=1.21 {
        return AdditionalAdditionsAlbumSupport.isAlbum(getDisc()) ? Math.max(0, cursor.discIndex()) : -1;
        //?} else {
        /*return -1;
        *///?}
    }

    public int albumTrackCount() {
        //? if >=1.21 {
        return getAlbumTrack() < 0 ? 0 : AdditionalAdditionsAlbumSupport.trackCount(getDisc());
        //?} else {
        /*return 0;
        *///?}
    }

    /** 現在のディスクが custom disc なら track を、そうでなければ null。 */
    public CustomTrackData currentTrack() {
        if (isMdmAlbum()) {
            return mediaSequence.at(mediaPosition()).map(MediaSequenceResolver.ResolvedTrack::track).orElse(null);
        }
        final ItemStack disc = effectiveDisc();
        //? if >=1.21 {
        if (disc.is(ModItems.CUSTOM_MUSIC_DISC.get()) && disc.has(ModDataComponents.CUSTOM_TRACK.get())) {
            final CustomTrackData t = disc.get(ModDataComponents.CUSTOM_TRACK.get());
            return t != null && !t.isEmpty() ? t : null;
        //?} else {
/*        if (disc.getItem() instanceof CustomMusicDiscItem) {
            final CustomTrackData t = CustomMusicDiscItem.getTrack(disc);
            return !t.isEmpty() ? t : null;
        */
        //?}
        }
        return null;
    }

    /**
     * 現在選択中のバニラまたは他 MOD のジュークボックス曲を返す。
     *
     * <p>{@link #effectiveDisc()} と {@link #songFor(ItemStack)} を既存再生経路と共有するため、Album 内の
     * 現在盤も同じ規則で選ばれる。MDM の custom disc は無音の jukebox song を持つが、それを実音として
     * 配送しないよう、曲メタの有無にかかわらず明示的に除外する。
     */
    @Nullable
    public VanillaTrackData currentVanillaTrack() {
        final ItemStack disc = effectiveDisc();
        if (disc.isEmpty()) {
            return null;
        }
        //? if >=1.21 {
        if (disc.is(ModItems.CUSTOM_MUSIC_DISC.get())) {
            return null;
        }
        return songFor(disc).map(holder -> {
            final JukeboxSong song = holder.value();
            //? if >=1.21.2 {
            final String soundEventId = song.soundEvent().value().location().toString();
            //?} else {
            /*final String soundEventId = song.soundEvent().value().getLocation().toString();
            *///?}
            return new VanillaTrackData(soundEventId, song.lengthInTicks() * 50L);
        }).orElse(null);
        //?} else {
/*        if (disc.getItem() instanceof CustomMusicDiscItem
                || !(disc.getItem() instanceof RecordItem record)) {
            return null;
        }
        return new VanillaTrackData(
                record.getSound().getLocation().toString(), record.getLengthInTicks() * 50L);
        *///?}
    }

    @Nullable
    public CustomTrackData nextAlbumTrack() {
        //? if >=1.21 {
        final int next = PlaylistPlayback.nextIndex(getAlbumTrack(), albumTrackCount(), repeat);
        if (getAlbumTrack() < 0 || next < 0) {
            return null;
        }
        final ItemStack disc = AdditionalAdditionsAlbumSupport.trackAt(getDisc(), next);
        return prefetchable(disc.get(ModDataComponents.CUSTOM_TRACK.get()));
        //?} else {
        /*return null;
        *///?}
    }

    @Nullable
    public CustomTrackData nextPlaybackTrack() {
        if (isMdmAlbum()) {
            return nextResolved(true)
                    .map(MediaSequenceResolver.ResolvedTrack::track)
                    .filter(track -> PlaylistPlayback.prefetchable(track.durationMs(), track.radio()))
                    .orElse(null);
        }
        if (getAlbumTrack() >= 0) {
            return nextAlbumTrack();
        }
        return repeat ? prefetchable(currentTrack()) : null;
    }

    @Nullable
    private static CustomTrackData prefetchable(CustomTrackData track) {
        return track != null && !track.isEmpty()
                && PlaylistPlayback.prefetchable(track.durationMs(), track.radio()) ? track : null;
    }

    @Nullable
    public Component discSongDescription() {
        //? if >=1.21 {
        return songFor(effectiveDisc()).map(Holder::value).map(JukeboxSong::description).orElse(null);
        //?} else {
        /*final ItemStack disc = effectiveDisc();
        return disc.getItem() instanceof RecordItem record ? record.getDisplayName() : null;
        *///?}
    }

    public boolean isLiveStream() {
        final CustomTrackData t = currentTrack();
        return t != null && (t.radio() || t.durationMs() <= 0L);
    }

    /** 回路状態のコンパレータ出力。空=0、停止・pause=1、再生=15。 */
    public int getComparatorOutput() {
        if (!hasDisc()) return 0;
        return cursor.state() == PlaybackCursor.State.PLAYING ? 15 : 1;
    }

    /** vanilla 再生状態 (無音バケット song が鳴っているか)。particle / gameEvent と同じ土俵。 */
    public boolean isVanillaPlaying() {
        //? if >=1.21 {
        return songPlayer.isPlaying();
        //?} else {
/*        return startMillis != 0L && !isPaused();
        */
        //?}
    }

    /**
     * 実再生状態の判定。比較器と隣接機構の更新に使う。
     *
     * <p><b>{@link #isVanillaPlaying()} で判定してはいけない</b> — あれは custom disc に貼った無音
     * バケット song の尺 (10 秒刻み・尺不明は 3600 秒・ラジオは 7200 秒) であって実曲の尺ではない。
     * バケットで判定すると実曲が終わっても比較器が再生中の値を出し続け、終了通知が遅れる
     * (実尺 95 秒の曲でも 120 秒バケットなら 25 秒遅れる)。
     *
     * <ul>
     * <li>一時停止中 → 0</li>
     * <li>custom disc でない (vanilla / 他 MOD) → 実音の権威は {@code songPlayer} なのでそのまま (バニラ準拠)</li>
     * <li>ライブ / ラジオ → 実尺が無く、実際に鳴り続けている → 再生中は 15 のまま</li>
     * <li>repeat ON の有限尺 (アルバムを除く) → 曲尺ごとに張り直して鳴り続ける → 15 のまま</li>
     * <li>それ以外の有限尺 custom disc → 実尺と経過で判定する</li>
     * </ul>
     */
    public boolean isRedstonePlaying() {
        if (isPaused()) {
            return false;
        }
        final CustomTrackData track = currentTrack();
        if (track == null) {
            //? if >=1.21 {
            return songPlayer.isPlaying();
            //?} else {
/*            final long dur = trackDurationMs();
            if (dur <= 0L) {
                return isVanillaPlaying();
            }
            return startMillis > 0L && playbackTimeMs() - startMillis < dur;
            */
            //?}
        }
        //? if >=1.21 {
        if (track.radio() || track.durationMs() <= 0L || (repeat && !isMdmAlbum() && getAlbumTrack() < 0)) {
        //?} else {
/*        if (track.radio() || track.durationMs() <= 0L || (repeat && !isMdmAlbum())) {
        */
        //?}
            return startMillis > 0L;
        }
        return startMillis > 0L && playbackTimeMs() - startMillis < track.durationMs();
    }

    /**
     * 再生状態・比較器値が変わった tick だけ neighbor を更新する。実尺での終了は
     * {@code songPlayer} には見えず {@link #onSongChanged()} が呼ばれないので、ここが唯一の通知経路。
     * 毎 tick は撃たない。
     */
    private void refreshRedstoneSignal() {
        if (mount != null) return; // 載せた BE には隣接ブロックが無い
        final boolean now = isRedstonePlaying();
        final boolean playingChanged = now != redstonePlayingLatch;
        redstonePlayingLatch = now;
        final int comparatorNow = getComparatorOutput();
        final boolean comparatorChanged = comparatorNow != comparatorLatch;
        comparatorLatch = comparatorNow;
        if (!playingChanged && !comparatorChanged) {
            return;
        }
        if (level != null) {
            level.updateNeighborsAt(getBlockPos(), getBlockState().getBlock());
            if (comparatorChanged) {
                level.updateNeighbourForOutputSignal(getBlockPos(), getBlockState().getBlock());
            }
        }
    }

    private boolean isServer() {
        //? if >=26.1 {
        return level != null && !level.isClientSide();
    }

    private Optional<Holder<JukeboxSong>> songFor(ItemStack disc) {
        return JukeboxSong.fromStack(disc);
        //?} elif >=1.21.2 {
/*        return level != null && !level.isClientSide();
    }

    private Optional<Holder<JukeboxSong>> songFor(ItemStack disc) {
        return JukeboxSong.fromStack(level.registryAccess(), disc);
        */
        //?} elif >=1.21 {
/*        return level != null && !level.isClientSide;
    }

    private Optional<Holder<JukeboxSong>> songFor(ItemStack disc) {
        return level == null ? Optional.empty() : JukeboxSong.fromStack(level.registryAccess(), disc);
        */
        //?} else {
/*        return level != null && !level.isClientSide;
        */
        //?}
    }

    // ── 設定変更 (GUI から C2S 経由) ──

    public void setRangeBlocks(int value) {
        this.rangeBlocks = Mth.clamp(value, RANGE_MIN, RANGE_MAX);
        // 可聴範囲は client 側 BE から tick で live 再読され、変化時にチャンネルの減衰半径 (OpenAL の
        // max distance) をライブ更新する (音量と同じ即反映)。再ストリーム不要 = sync のみ。
        sync();
    }

    public void setVolumePercent(int value) {
        this.volumePercent = Mth.clamp(value, VOLUME_MIN, VOLUME_MAX);
        // 音量は client 側 BE から tick で live 再読されるので再ブロードキャスト不要。
        sync();
    }

    public void setRepeat(boolean value) {
        this.repeat = value;
        sync();
    }

    /**
     * 曲単位の shuffle を切り替える。切替時は今鳴っている曲を順序の先頭にするが、
     * 音源を止めたり再送したりはしない。
     */
    public void setShuffle(boolean value) {
        if (shuffle == value) {
            return;
        }
        shuffle = value;
        if (shuffle) {
            final MediaSequence.Position anchor = mediaSequence.at(mediaPosition())
                    .map(MediaSequenceResolver.ResolvedTrack::position)
                    .or(() -> mediaSequence.first().map(MediaSequenceResolver.ResolvedTrack::position))
                    .orElse(null);
            if (anchor != null) {
                setShuffleAnchor(anchor);
                shuffleSeed = ThreadLocalRandom.current().nextLong();
            }
        }
        rebuildPlaybackSequence();
        cursor = cursor.invalidate();
        sync();
    }

    /**
     * 指向性の切り替え。client は再生を止めずにその場でモードを変える (毎 tick の座標の書き方を
     * 変えるだけで {@code relative} を触らないので再ストリーム不要) = 音量・範囲と同じ sync のみ。
     */
    public void setDirectional(boolean value) {
        this.directional = value;
        sync();
    }

    /** 一時停止は位置を保つ。停止後の再生は先頭から始める。 */
    public void setPaused(boolean value) {
        setPausedInternal(value);
    }

    private void setPausedInternal(boolean value) {
        if (!isServer() || !hasDisc()) {
            return;
        }
        if (value) {
            if (cursor.state() != PlaybackCursor.State.PLAYING) {
                return;
            }
            pausedOffsetMs = clampToTrackDuration(Math.max(0L, syncElapsedMs()));
            stopAudio();
            cursor = cursor.pause();
            refreshRedstoneSignal();
            sync();
        } else if (cursor.state() != PlaybackCursor.State.PLAYING) {
            if (isStopped()) {
                playbackSequence.first().ifPresent(first -> cursor = cursor.moveTo(
                        first.discIndex(), first.trackIndex()));
                pausedOffsetMs = 0L;
            }
            startPlayback(pausedOffsetMs);
            if (cursor.state() == PlaybackCursor.State.PLAYING) pausedOffsetMs = 0L;
        }
    }

    /** A rising edge toggles playback once; falling power only rearms the input. */
    private void updateRedstonePower() {
        if (!isServer() || mount != null) return;
        final boolean powered = level.hasNeighborSignal(getBlockPos());
        if (!redstonePowerInitialized) {
            redstonePowerInitialized = true;
            lastRedstonePower = powered;
            return;
        }
        if (powered == lastRedstonePower) return;
        lastRedstonePower = powered;
        if (powered && hasDisc() && (!isMdmAlbum() || !mediaSequence.tracks().isEmpty())) {
            // STOPPED and PAUSED both use the existing start/resume path; PLAYING pauses.
            setPausedInternal(cursor.state() == PlaybackCursor.State.PLAYING);
        }
    }

    /**
     * GUI シークバーからの頭出し。有限尺 (非ラジオ) の custom disc と、バニラ・他 MOD の盤で効く。
     * 再生中は即座にその位置へ、一時停止中は resume 位置だけを更新する。
     */
    public void seekTo(long offsetMs) {
        if (!isServer() || !hasDisc()) {
            return;
        }
        // 停止画面の再生ボタンも offset 0 を送る。vanilla 盤も先頭から再開できる。
        if (isStopped() && offsetMs == 0L) {
            setPaused(false);
            return;
        }
        if (isLiveStream()) {
            return;
        }
        if (!isSeekable()) {
            return;
        }
        final long duration = trackDurationMs();
        //? if >=1.21 {
        final long clamped = Mth.clamp(offsetMs, 0L, duration);
        //?} else {
/*        final long clamped = Math.max(0L, Math.min(offsetMs, duration));
        */
        //?}
        if (isPaused()) {
            pausedOffsetMs = clamped;
            sync();
        } else {
            startPlayback(clamped);
            sync();
        }
    }

    /** 現在の媒体を次の再生可能位置へ送る。LIVE・尺不明でも手動操作なら脱出できる。 */
    public void nextTrack() {
        if (!isServer() || selectCompatibilityAlbumTrack(1) || mediaSequence.tracks().isEmpty()) {
            return;
        }
        selectResolved(nextResolved(true), false);
    }

    /** 現在の媒体を直前の再生可能位置へ戻す。pause中は再生を始めず選択位置だけを変える。 */
    public void previousTrack() {
        if (!isServer() || selectCompatibilityAlbumTrack(-1) || mediaSequence.tracks().isEmpty()) {
            return;
        }
        final Optional<MediaSequenceResolver.ResolvedTrack> previous =
                playbackSequence.previous(mediaPosition(), true).flatMap(mediaSequence::at);
        if (previous.isPresent()) {
            selectResolved(previous, false);
        }
    }

    private boolean selectCompatibilityAlbumTrack(int direction) {
        final int current = getAlbumTrack();
        if (current < 0) return false;
        final int count = albumTrackCount();
        if (count > 0) {
            selectPosition(new MediaSequence.Position(Math.floorMod(current + direction, count), 0));
        }
        return true;
    }

    private void selectResolved(Optional<MediaSequenceResolver.ResolvedTrack> selected, boolean naturalEnd) {
        if (selected.isEmpty()) {
            playbackSequence.first().ifPresent(first -> cursor = cursor.moveTo(
                    first.discIndex(), first.trackIndex()));
            stopPlayback(naturalEnd);
            return;
        }
        selectPosition(selected.get().position());
    }

    private void selectPosition(MediaSequence.Position position) {
        final CustomTrackData previous = currentTrack();
        final int previousDiscIndex = cursor.discIndex();
        final int previousTrackIndex = cursor.trackIndex();
        cursor = cursor.moveTo(position.discIndex(), position.trackIndex());
        pausedOffsetMs = 0L;
        if (cursor.state() == PlaybackCursor.State.PLAYING) {
            stopAudioBeforeSameUrlPositionRestart(previous, previousDiscIndex, previousTrackIndex);
            startPlayback(0L);
        } else {
            sync();
        }
    }

    /** Captured actors may update saved playback without registering a second world source. */
    public long advanceCapturedPlayback(CompoundTag tag, long gameTime, long elapsedMs, @Nullable VanillaTrackData vanilla) {
        if (level != null) throw new IllegalStateException("Captured playback requires a detached block entity");
        if (cursor.state() != PlaybackCursor.State.PLAYING) return elapsedMs;
        final CustomTrackData track = currentTrack();
        if (track == null && vanilla == null) return elapsedMs;
        final long duration = track != null ? track.durationMs() : vanilla.durationMs();
        long offset = Math.max(0L, elapsedMs);
        if (MediaSequence.allowsAutomaticAdvance(duration, track != null && track.radio()) && offset >= duration) {
            //? if >=1.21 {
            if (getAlbumTrack() >= 0) {
                final int count = AdditionalAdditionsAlbumSupport.trackCount(getDisc());
                final int next = getAlbumTrack() + 1;
                if (next < count) cursor = cursor.startAt(next, 0);
                else if (repeat && count > 0) cursor = cursor.startAt(0, 0);
                else cursor = cursor.moveTo(0, 0).stop();
            } else
            //?}
            // アルバムの中のバニラ・他 MOD の盤は、単独の盤ではなくアルバムの次の曲へ送る。
            if (track == null && !isMdmAlbum()) {
                cursor = repeat ? cursor.startAt(cursor.discIndex(), cursor.trackIndex()) : cursor.stop();
            } else {
            final var next = nextResolved(false);
            if (next.isPresent()) {
                final var position = next.get().position();
                cursor = cursor.startAt(position.discIndex(), position.trackIndex());
            } else {
                playbackSequence.first().ifPresent(first -> cursor = cursor.moveTo(first.discIndex(), first.trackIndex()));
                cursor = cursor.stop();
            }
            }
            offset = 0L;
        }
        tag.putString("playbackState", cursor.state().name());
        tag.putLong("playbackGeneration", cursor.generation());
        tag.putInt("albumTrack", savedDiscIndex());
        tag.putInt("playlistTrack", savedTrackIndex());
        tag.putBoolean("paused", false);
        tag.putBoolean("automationFinished", cursor.state() == PlaybackCursor.State.STOPPED);
        tag.putLong("pausedOffset", 0L);
        tag.putLong("playbackStartGameTime", cursor.state() == PlaybackCursor.State.PLAYING
                ? gameTime - offset / 50L : -1L);
        return offset;
    }

    private void advanceMdmAlbum() {
        selectResolved(nextResolved(false), true);
    }

    /**
     * 同一 URL が別の論理曲位置へ続く場合、client 側の「同じ URL の再送」抑止を明示停止で越える。
     *
     * <p>{@link PlaybackCursor} は位置で世代を進めるが、既存の {@code PlayDiscPayload} は URL と
     * offset しか運ばない。送り直しが開始直後なら client は offset 差をシークと認識せず、同じ URL
     * のまま前の曲を鳴らし続ける。その場合だけ {@link StopDiscPayload} を先に broadcast し、次の
     * {@code PlayDiscPayload} を新しいロードにする。
     */
    private void stopAudioBeforeSameUrlPositionRestart(CustomTrackData previous,
            int previousDiscIndex, int previousTrackIndex) {
        if (cursor.state() != PlaybackCursor.State.PLAYING
                || (cursor.discIndex() == previousDiscIndex && cursor.trackIndex() == previousTrackIndex)
                || previous == null) {
            return;
        }
        final CustomTrackData selected = currentTrack();
        if (selected != null && previous.sourceKey().equals(selected.sourceKey())) {
            stopAudio();
        }
    }

    // ── 再生制御 ──

    /**
     * chunk ロード / 移動構造物の解体で BE が復元された後の再生復帰。transient な {@code startMillis} は
     * 0 に戻るが、永続化された {@code playbackStartGameTime} から現在位置を復元して途中から再生する
     * (頭出しにしない)。
     */
    private void resumePlaybackAfterLoad() {
        // Called only for a PLAYING cursor. An offset longer than the world's age has a valid
        // negative origin, so the origin's sign must not be treated as a stopped-state marker.
        // ライブ / ラジオ (無限長) は位置の概念が無い → ライブ先頭へ再接続する (offset 無意味)。
        if (isLiveStream()) {
            startPlayback(0L);
            return;
        }
        final long rawElapsed = Math.max(0L, (level.getGameTime() - playbackStartGameTime) * 50L);
        final long dur = trackDurationMs();
        if (dur > 0L && rawElapsed >= dur) {
            if (isMdmAlbum()) {
                advanceMdmAlbum();
                return;
            }
            //? if >=1.21 {
            if (getAlbumTrack() >= 0) {
                advanceAlbumTrack();
            } else
            //?}
            if (repeat) {
                startPlayback(rawElapsed % dur); // リピートはループ内の現在位置へ。
            } else {
                // 非リピートで復元前に自然終了済み → replay しない (頭出し再生を防ぐ)。
                stopPlayback(true);
            }
            return;
        }
        //? if >=1.21.2 {
        MusicDiscMaker.LOGGER.debug("Restored golden jukebox playback: resume={}ms (playbackStartGameTime={} gameTime={})",
        //?} elif >=1.21 {
        /*MusicDiscMaker.LOGGER.debug("Restored golden jukebox playback: resume={}ms (playbackStartGameTime={} gameTime={})",
        */
        //?} else {
        /*MusicDiscMaker.LOGGER.debug("Restored golden jukebox playback: resume={}ms (playbackStartGameTime={} gameTime={})",
        */
        //?}
                rawElapsed, playbackStartGameTime, level.getGameTime());
        startPlayback(rawElapsed);
    }

    private void startPlayback(long offsetMs) {
        if (!isServer() || !hasDisc()) {
            return;
        }
        automationFinished = false;
        //? if >=1.21 {
        final ItemStack disc = effectiveDisc();
        //?} else {
/*        // アルバムの中のバニラの盤も鳴らす (アルバムそのものは RecordItem ではない)。
        final ItemStack disc = effectiveDisc();
        */
        //?}
        cursor = cursor.startAt(Math.max(0, cursor.discIndex()), Math.max(0, cursor.trackIndex()));
        startMillis = playbackTimeMs() - offsetMs;
        playbackStartGameTime = level.getGameTime() - offsetMs / 50L;
        final CustomTrackData track = currentTrack();
        // 管理再生で鳴らす通常盤にはバニラの再生 event を送らない (SpeakerPlayback#managesVanilla)。
        final boolean managedVanilla = mount == null && track == null && currentVanillaTrack() != null
                && SpeakerPlayback.managesVanilla(this, offsetMs);
        //? if >=1.21 {
        if (mount == null) songFor(disc).ifPresent(song -> {
            if (managedVanilla) songPlayer.setSongWithoutPlaying(song, offsetMs / 50L);
            else songPlayer.play(level, song);
        });
        //?} else {
        //?}
        if (mount != null) {
            final VanillaTrackData vanillaTrack = track == null ? currentVanillaTrack() : null;
            if (track != null || vanillaTrack != null) {
                mount.play(track, vanillaTrack, offsetMs, rangeBlocks, volumePercent, directional, playbackIdentity());
            }
        } else if (track != null) {
            if (!SpeakerPlayback.play(this, track, offsetMs)) {
                broadcast(new PlayDiscPayload(getBlockPos(), track, offsetMs, rangeBlocks, volumePercent, directional, playbackIdentity()));
            }
            //? if >=1.21 {
            //?} else {
/*            level.gameEvent(GameEvent.JUKEBOX_PLAY, getBlockPos(), GameEvent.Context.of(getBlockState()));
            */
            //?}
        } else {
            //? if >=1.21 {
            //?} else {
/*            if (!managedVanilla) level.levelEvent(EVENT_PLAY_RECORD, getBlockPos(), Item.getId(disc.getItem()));
            */
            //?}
            final VanillaTrackData vanillaTrack = currentVanillaTrack();
            if (vanillaTrack != null) {
                SpeakerPlayback.play(this, vanillaTrack, offsetMs);
            }
        }
        //? if >=1.21 {
        redstonePlayingLatch = isRedstonePlaying();
        //?} else {
/*        refreshRedstoneSignal();
        */
        //?}
        // 再生起点と現在経過を client へ反映する (progress バーの起点)。
        sync();
    }

    private void stopPlayback() {
        stopPlayback(false);
    }

    private void stopPlayback(boolean naturalEnd) {
        if (!isServer()) {
            return;
        }
        stopAudio(naturalEnd);
        cursor = cursor.stop();
        automationFinished = naturalEnd && hasDisc();
        pausedOffsetMs = 0L;
        refreshRedstoneSignal();
        sync();
    }

    private void stopAudio() {
        stopAudio(false);
    }

    /** @param naturalEnd 曲を鳴らし切ったことによる停止か (client は自分の音が鳴り終わるまで待つ) */
    private void stopAudio(boolean naturalEnd) {
        if (!isServer()) {
            return;
        }
        if (mount != null) {
            startMillis = 0L;
            playbackStartGameTime = -1L;
            mount.stop(playbackIdentity(), naturalEnd);
            return;
        }
        //? if >=1.21 {
        songPlayer.stop(level, getBlockState());
        //?} else {
/*        final boolean wasPlaying = startMillis != 0L;
        */
        //?}
        startMillis = 0L;
        playbackStartGameTime = -1L;
        //? if >=1.21 {
        //?} else {
/*        level.levelEvent(EVENT_STOP_RECORD, getBlockPos(), 0);
        */
        //?}
        // 聴取中に音源chunkの追跡を外れても、clientは有限距離の外でvoiceを保持しうる。
        // Stopだけは同じ次元の全clientへ送る。半径判定では遠方の保持voiceを取り逃す。
        SpeakerPlayback.stopWithoutSend(this);
        final StopDiscPayload stop = new StopDiscPayload(getBlockPos(), playbackIdentity(), naturalEnd);
        if (level instanceof ServerLevel serverLevel) {
            Services.PLATFORM.sendMovingAudioStop(serverLevel, getBlockPos(), stop.identity());
        }
        // 従来のchunk通知は互換経路として維持。追跡者への重複Stopは同一世代で冪等。
        broadcast(stop);
        if (level instanceof ServerLevel serverLevel) {
            for (ServerPlayer player : serverLevel.players()) {
                Services.NETWORK.sendToPlayer(player, stop);
            }
        }
        //? if >=1.21 {
        //?} else {
/*        if (wasPlaying) {
            level.gameEvent(GameEvent.JUKEBOX_STOP_PLAY, getBlockPos(), GameEvent.Context.of(getBlockState()));
        }
        refreshRedstoneSignal();
        sync();
        */
        //?}
    }

    /** ディスクスロットが変わった時 (挿入/取り出し・ホッパー・コマンド・GUI)。再生を起動/停止する。 */
    private void onDiscChanged() {
        final boolean hasDisc = hasDisc();
        automationFinished = false;
        rebuildMediaSequence();
        if (isServer()) {
            // No ticker runs while the slot is empty. Sample power again on the next insert,
            // so changes during the empty interval cannot become a phantom rising edge.
            if (!hasDisc) {
                redstonePowerInitialized = false;
            } else if (!redstonePowerInitialized && mount == null) {
                redstonePowerInitialized = true;
                lastRedstonePower = level.hasNeighborSignal(getBlockPos());
            }
            final boolean keepPaused = isPaused();
            cursor = cursor.clear();
            if (hasDisc) {
                final MediaSequence.Position first = mediaSequence.first()
                        .map(MediaSequenceResolver.ResolvedTrack::position)
                        .orElse(new MediaSequence.Position(0, 0));
                cursor = new PlaybackCursor(first.discIndex(), first.trackIndex(),
                        keepPaused ? PlaybackCursor.State.PAUSED : PlaybackCursor.State.STOPPED,
                        cursor.generation());
                if (shuffle) {
                    setShuffleAnchor(first);
                }
            }
            rebuildPlaybackSequence();
        }
        updateHasRecordState(hasDisc);
        if (isServer()) {
            pausedOffsetMs = 0L;
        }
        setChanged();
        sync();
        if (!isServer()) {
            return;
        }
        if (hasDisc && !isPaused()
                && (!isMdmAlbum() || !mediaSequence.tracks().isEmpty())) {
            startPlayback(0L);
        } else if (!hasDisc || (isMdmAlbum() && mediaSequence.tracks().isEmpty())) {
            stopPlayback();
        }
        //? if >=1.21 {
        //?} else {
/*        if (level != null && mount == null) {
            level.updateNeighborsAt(getBlockPos(), getBlockState().getBlock());
        }
        */
        //?}
    }

    private void updateHasRecordState(boolean hasRecord) {
        if (mount == null && level != null && level.getBlockState(getBlockPos()) == getBlockState()
                && getBlockState().hasProperty(GoldenJukeboxBlock.HAS_RECORD)
                && getBlockState().getValue(GoldenJukeboxBlock.HAS_RECORD) != hasRecord) {
            level.setBlock(getBlockPos(), getBlockState().setValue(GoldenJukeboxBlock.HAS_RECORD, hasRecord), 2);
        }
    }

    /** ブロック破壊時 (setItem を通らない) に鳴りっぱなしを防ぐ。 */
    public void onBlockRemoved() {
        stopPlayback();
        if (sourceId != null && !isRemoved() && level instanceof ServerLevel serverLevel) {
            final GoldenSourceRegistry.Resolution owner = GoldenSourceRegistry.lookup(serverLevel, sourceId);
            if (owner.status() == GoldenSourceRegistry.Status.UNIQUE && owner.source() == this) {
                GoldenSourceRetirements.get(serverLevel).recordRemoval(sourceId, getBlockPos());
            }
        }
    }

    //? if >=1.21.11 {
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        onBlockRemoved();
    }
    //?}

    @Override
    public void setRemoved() {
        redstonePowerInitialized = false;
        if (mount == null) {
            GoldenSourceRegistry.unregister(this);
            SpeakerPlayback.remove(this);
        }
        super.setRemoved();
    }

    @Override
    public void setLevel(Level level) {
        final long oldClock = playbackTimeMs();
        if (mount == null && this.level != null && this.level != level) GoldenSourceRegistry.unregister(this);
        super.setLevel(level);
        // Deserialization can receive the client anchor before the level (and its paused clock).
        final long clockDelta = playbackTimeMs() - oldClock;
        if (clientElapsedAnchorMs >= 0L) clientAnchorWallClockMs += clockDelta;
        if (startMillis > 0L) startMillis += clockDelta;
        if (mount == null) GoldenSourceRegistry.register(this);
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        redstonePowerInitialized = false;
        if (mount == null) GoldenSourceRegistry.register(this);
    }

    /** late-join した player へ、再生中なら現在位置で音源種別に対応するpayloadを再送する。 */
    public void resendTo(ServerPlayer player) {
        if (!isServer() || isPaused()) {
            return;
        }
        final CustomTrackData customTrack = currentTrack();
        final VanillaTrackData vanillaTrack = customTrack == null ? currentVanillaTrack() : null;
        if (customTrack == null && vanillaTrack == null) {
            return;
        }
        // chunk 再ロード直後は startMillis がまだ transient の 0 のまま (最初の serverTick が
        // resumePlaybackAfterLoad を走らせる前)。この窓では永続化済みの playbackStartGameTime から
        // 経過を復元する (syncElapsedMs と同じ式)。-1 = 再生中でない。
        final long elapsed = syncElapsedMs();
        if (elapsed < 0L) {
            return;
        }
        final long dur = customTrack != null ? customTrack.durationMs() : vanillaTrack.durationMs();
        final boolean loopsWithinTrack = repeat && !isMdmAlbum() && getAlbumTrack() < 0;
        if (dur > 0L && elapsed >= dur && !loopsWithinTrack) {
            return;
        }
        final long offset = dur > 0L && loopsWithinTrack ? elapsed % dur : elapsed;
        if (customTrack != null && SpeakerPlayback.resendTo(this, player, customTrack, offset)) {
            return;
        }
        if (customTrack != null) {
            Services.NETWORK.sendToPlayer(player,
                    new PlayDiscPayload(getBlockPos(), customTrack, offset,
                            rangeBlocks, volumePercent, directional, playbackIdentity()));
        } else {
            // Speakerが無ければvanillaの通常同期を維持し、追加のclient音源は作らない。
            SpeakerPlayback.resendTo(this, player, vanillaTrack, offset);
        }
    }

    // ── tick (server) ──

    //? if >=1.21 {
    public static void serverTick(net.minecraft.world.level.Level level, BlockPos pos, BlockState state,
            GoldenJukeboxBlockEntity be) {
    //?} else {
/*    public static void serverTick(Level level, BlockPos pos, BlockState state, GoldenJukeboxBlockEntity be) {
    */
    //?}
        be.tickServer();
    }

    private void tickServer() {
        if (!isServer()) {
            return;
        }
        updateRedstonePower();
        if (!initialized) {
            initialized = true;
            if (startMillis == 0L && hasDisc() && cursor.state() == PlaybackCursor.State.PLAYING) {
                resumePlaybackAfterLoad();
            }
        }
        if (isMdmAlbum() && !isPaused() && startMillis > 0L && currentMdmTrackFinished()) {
            advanceMdmAlbum();
            refreshRedstoneSignal();
            return;
        }
        //? if >=1.21 {
        if (mount == null) songPlayer.tick(level, getBlockState());
        // 載せた BE は songPlayer を鳴らさない。バニラ盤の終わりは下の尺の判定で拾う。
        if (mount == null && !isMdmAlbum() && getAlbumTrack() < 0
                && currentTrack() == null && cursor.state() == PlaybackCursor.State.PLAYING
                && !songPlayer.isPlaying()) {
            // Standalone vanilla media has no custom wall-clock track. Its authoritative
            // JukeboxSongPlayer reaching EOF is the natural terminal event.
            if (repeat) startPlayback(0L);
            else stopPlayback(true);
            refreshRedstoneSignal();
            return;
        }
        if (getAlbumTrack() >= 0 && !isPaused() && startMillis > 0L && currentAlbumTrackFinished()) {
            advanceAlbumTrack();
            refreshRedstoneSignal();
            return;
        }
        //?}
        if (startMillis > 0L && !isPaused()) {
            final CustomTrackData track = currentTrack();
            if (getAlbumTrack() < 0) {
                final long baseDuration = trackDurationMs();
                final long duration = baseDuration > 0L && mount != null && track == null
                        ? baseDuration + MOUNTED_VANILLA_END_GRACE_MS : baseDuration;
                if (!isLiveStream() && duration > 0L && playbackTimeMs() - startMillis >= duration) {
                    if (repeat) {
                        startPlayback(0L);
                    } else {
                        stopPlayback(true);
                    }
                }
            }
            //? if >=1.21 {
            if (mount == null && startMillis > 0L && !songPlayer.isPlaying() && track != null
                    && (track.radio() || track.durationMs() <= 0L)) {
                songFor(effectiveDisc()).ifPresent(song -> songPlayer.play(level, song));
            }
            //?} else {
            /*if (mount == null && startMillis > 0L && track != null && level instanceof ServerLevel serverLevel
                    && level.getGameTime() % 20L == 0L) {
                level.gameEvent(GameEvent.JUKEBOX_PLAY, getBlockPos(), GameEvent.Context.of(getBlockState()));
                spawnNoteParticle(serverLevel);
            }
            *///?}
        }
        refreshRedstoneSignal();
        if (mount == null) SpeakerPlayback.tick(this);
    }

    private boolean currentMdmTrackFinished() {
        final CustomTrackData track = currentTrack();
        if (track != null) {
            return PlaylistPlayback.finished(playbackTimeMs() - startMillis, track.durationMs(), track.radio());
        }
        // アルバムの中のバニラ・他 MOD の盤は曲尺で次へ送る。下の単曲の終わりの判定 (同じ尺) より先に
        // ここで拾わないと、アルバムが次の盤へ進まずに止まる。
        final long duration = trackDurationMs();
        return duration > 0L && playbackTimeMs() - startMillis
                >= duration + (mount != null ? MOUNTED_VANILLA_END_GRACE_MS : 0L);
    }

    //? if >=1.21 {
    private boolean currentAlbumTrackFinished() {
        final CustomTrackData track = currentTrack();
        if (track != null && track.radio()) {
            return false;
        }
        final long dur = track != null ? track.durationMs() : 0L;
        if (dur > 0L) {
            return playbackTimeMs() - startMillis >= dur;
        }
        if (mount != null) {
            final long vanillaDuration = trackDurationMs();
            return vanillaDuration > 0L
                    && playbackTimeMs() - startMillis >= vanillaDuration + MOUNTED_VANILLA_END_GRACE_MS;
        }
        return !songPlayer.isPlaying();
    //?} else {
/*    private void spawnNoteParticle(ServerLevel serverLevel) {
        final BlockPos pos = getBlockPos();
        final double note = serverLevel.getRandom().nextInt(24) / 24.0;
        serverLevel.sendParticles(net.minecraft.core.particles.ParticleTypes.NOTE,
                pos.getX() + 0.5, pos.getY() + 1.2, pos.getZ() + 0.5, 0, note, 0.0, 0.0, 1.0);
    */
    //?}
    }

    //? if >=1.21.2 {
    private void advanceAlbumTrack() {
        final CustomTrackData previous = currentTrack();
        final int previousDiscIndex = cursor.discIndex();
        final int previousTrackIndex = cursor.trackIndex();
        final int count = AdditionalAdditionsAlbumSupport.trackCount(items.get(SLOT_DISC));
        final int next = getAlbumTrack() + 1;
        if (count <= 0 || next >= count) {
            if (repeat && count > 0) {
                cursor = cursor.moveTo(0, 0);
                stopAudioBeforeSameUrlPositionRestart(previous, previousDiscIndex, previousTrackIndex);
                startPlayback(0L);
            } else {
                cursor = cursor.moveTo(0, 0);
                stopPlayback(true);
                sync();
            }
            return;
        }
        cursor = cursor.moveTo(next, 0);
        stopAudioBeforeSameUrlPositionRestart(previous, previousDiscIndex, previousTrackIndex);
        startPlayback(0L);
    }

    private void onSongChanged() {
        if (level != null && mount == null) {
            level.updateNeighborsAt(getBlockPos(), getBlockState().getBlock());
        }
        setChanged();
    }

    private void broadcast(ModPayload payload) {
    //?} elif >=1.21 {
/*    private void advanceAlbumTrack() {
        final CustomTrackData previous = currentTrack();
        final int previousDiscIndex = cursor.discIndex();
        final int previousTrackIndex = cursor.trackIndex();
        final int count = AdditionalAdditionsAlbumSupport.trackCount(items.get(SLOT_DISC));
        final int next = getAlbumTrack() + 1;
        if (count <= 0 || next >= count) {
            if (repeat && count > 0) {
                cursor = cursor.moveTo(0, 0);
                stopAudioBeforeSameUrlPositionRestart(previous, previousDiscIndex, previousTrackIndex);
                startPlayback(0L);
            } else {
                cursor = cursor.moveTo(0, 0);
                stopPlayback(true);
                sync();
            }
            return;
        }
        cursor = cursor.moveTo(next, 0);
        stopAudioBeforeSameUrlPositionRestart(previous, previousDiscIndex, previousTrackIndex);
        startPlayback(0L);
    }

    private void onSongChanged() {
        if (level != null && mount == null) {
            level.updateNeighborsAt(getBlockPos(), getBlockState().getBlock());
        }
        setChanged();
    }

    private void broadcast(com.kuronami.musicdiscmaker.network.ModPayload payload) {
    */
    //?} else {
/*    private void broadcast(com.kuronami.musicdiscmaker.network.ModPayload payload) {
    */
    //?}
        if (level instanceof ServerLevel serverLevel) {
            //? if >=26.1 {
            Services.NETWORK.sendToPlayersTrackingChunk(serverLevel, ChunkPos.containing(getBlockPos()), payload);
            //?} else {
/*            Services.NETWORK.sendToPlayersTrackingChunk(serverLevel, new ChunkPos(getBlockPos()), payload);
            */
            //?}
        }
    }

    public void sync() {
        if (mount != null) {
            mount.markDirty();
            mount.sync();
            return;
        }
        setChanged();
        //? if >=1.21.2 {
        if (level != null && !level.isClientSide()) {
        //?} else {
/*        if (level != null && !level.isClientSide) {
        */
        //?}
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    private int savedDiscIndex() {
        return isMdmAlbum() ? cursor.discIndex() : getAlbumTrack();
    }

    private int savedTrackIndex() {
        return isMdmAlbum() ? cursor.trackIndex() : -1;
    }

    /**
     * Returns this placed source's persistent identity, assigning it exactly once when first needed.
     * Client and detached block entities must never mint authoritative identities.
     */
    public UUID sourceIdentity() {
        if (!(level instanceof ServerLevel)) {
            throw new IllegalStateException("Golden source identity can only be assigned in a ServerLevel");
        }
        if (sourceId == null) {
            sourceId = UUID.randomUUID();
            setChanged();
        }
        if (mount == null) GoldenSourceRegistry.register(this);
        return sourceId;
    }

    /** Returns the loaded identity without assigning one. */
    @Nullable
    public UUID peekSourceIdentity() {
        return sourceId;
    }

    @Nullable
    private static UUID parseSourceIdentity(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    // ── persistence (26.1.2: ValueInput/ValueOutput) ──

    @Override
    //? if >=1.21.11 {
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
    //?} elif >=1.21 {
/*    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
    */
    //?} else {
/*    public void load(CompoundTag tag) {
        super.load(tag);
    */
    //?}
        items.clear();
        //? if >=1.21.11 {
        ContainerHelper.loadAllItems(input, items);
        this.sourceId = parseSourceIdentity(input.getStringOr("source_id", ""));
        this.rangeBlocks = Mth.clamp(input.getIntOr("range", RANGE_DEFAULT), RANGE_MIN, RANGE_MAX);
        this.volumePercent = Mth.clamp(input.getIntOr("volume", VOLUME_DEFAULT), VOLUME_MIN, VOLUME_MAX);
        this.repeat = input.getBooleanOr("repeat", false);
        this.shuffle = input.getBooleanOr("shuffle", false);
        this.shuffleSeed = input.getLongOr("shuffleSeed", 0L);
        this.shuffleAnchorDiscIndex = input.getIntOr("shuffleAnchorDisc", PlaybackCursor.NO_INDEX);
        this.shuffleAnchorTrackIndex = input.getIntOr("shuffleAnchorTrack", PlaybackCursor.NO_INDEX);
        //?} elif >=1.21 {
/*        ContainerHelper.loadAllItems(tag.getCompound("inventory"), items, registries);
        this.sourceId = parseSourceIdentity(tag.getString("source_id"));
        this.rangeBlocks = tag.contains("range") ? Mth.clamp(tag.getInt("range"), RANGE_MIN, RANGE_MAX) : RANGE_DEFAULT;
        this.volumePercent = tag.contains("volume") ? Mth.clamp(tag.getInt("volume"), VOLUME_MIN, VOLUME_MAX) : VOLUME_DEFAULT;
        this.repeat = tag.getBoolean("repeat");
        this.shuffle = tag.getBoolean("shuffle");
        this.shuffleSeed = tag.getLong("shuffleSeed");
        this.shuffleAnchorDiscIndex = tag.contains("shuffleAnchorDisc")
                ? tag.getInt("shuffleAnchorDisc") : PlaybackCursor.NO_INDEX;
        this.shuffleAnchorTrackIndex = tag.contains("shuffleAnchorTrack")
                ? tag.getInt("shuffleAnchorTrack") : PlaybackCursor.NO_INDEX;
        */
        //?} else {
/*        ContainerHelper.loadAllItems(tag.getCompound("inventory"), items);
        this.sourceId = parseSourceIdentity(tag.getString("source_id"));
        this.rangeBlocks = tag.contains("range") ? Mth.clamp(tag.getInt("range"), RANGE_MIN, RANGE_MAX) : RANGE_DEFAULT;
        this.volumePercent = tag.contains("volume")
                ? Mth.clamp(tag.getInt("volume"), VOLUME_MIN, VOLUME_MAX) : VOLUME_DEFAULT;
        this.repeat = tag.getBoolean("repeat");
        this.shuffle = tag.getBoolean("shuffle");
        this.shuffleSeed = tag.getLong("shuffleSeed");
        this.shuffleAnchorDiscIndex = tag.contains("shuffleAnchorDisc")
                ? tag.getInt("shuffleAnchorDisc") : PlaybackCursor.NO_INDEX;
        this.shuffleAnchorTrackIndex = tag.contains("shuffleAnchorTrack")
                ? tag.getInt("shuffleAnchorTrack") : PlaybackCursor.NO_INDEX;
        */
        //?}
        // 旧セーブ (この機能より前) には無いので、欠けていたら既定 = 従来の positional に倒す。
        //? if >=1.21.11 {
        this.directional = input.getBooleanOr("directional", DIRECTIONAL_DEFAULT);
        final boolean restoredPaused = input.getBooleanOr("paused", false);
        final String restoredState = input.getStringOr("playbackState", "");
        final long generation = input.getLongOr("playbackGeneration", 0L);
        this.pausedOffsetMs = input.getLongOr("pausedOffset", 0L);
        this.playbackStartGameTime = input.getLongOr("playbackStartGameTime", -1L);
        this.automationFinished = input.getBooleanOr("automationFinished", false);
        final int restoredDiscIndex = input.getIntOr("albumTrack", -1);
        final int restoredTrackIndex = input.getIntOr("playlistTrack", -1);
        //?} elif >=1.21 {
/*        this.directional = tag.contains("directional") ? tag.getBoolean("directional") : DIRECTIONAL_DEFAULT;
        final boolean restoredPaused = tag.getBoolean("paused");
        final String restoredState = tag.getString("playbackState");
        final long generation = tag.getLong("playbackGeneration");
        this.pausedOffsetMs = tag.getLong("pausedOffset");
        this.playbackStartGameTime = tag.contains("playbackStartGameTime")
                ? tag.getLong("playbackStartGameTime") : -1L;
        this.automationFinished = tag.getBoolean("automationFinished");
        final int restoredDiscIndex = tag.contains("albumTrack") ? tag.getInt("albumTrack") : -1;
        final int restoredTrackIndex = tag.contains("playlistTrack") ? tag.getInt("playlistTrack") : -1;
        */
        //?} else {
/*        this.directional = tag.contains("directional") ? tag.getBoolean("directional") : DIRECTIONAL_DEFAULT;
        final boolean restoredPaused = tag.getBoolean("paused");
        final String restoredState = tag.getString("playbackState");
        final long generation = tag.getLong("playbackGeneration");
        this.pausedOffsetMs = tag.getLong("pausedOffset");
        this.playbackStartGameTime = tag.contains("playbackStartGameTime")
                ? tag.getLong("playbackStartGameTime") : -1L;
        this.automationFinished = tag.getBoolean("automationFinished");
        final int restoredDiscIndex = tag.contains("albumTrack") ? tag.getInt("albumTrack") : 0;
        final int restoredTrackIndex = tag.contains("playlistTrack") ? tag.getInt("playlistTrack") : 0;
        */
        //?}
        PlaybackCursor.State state;
        try {
            state = PlaybackCursor.State.valueOf(restoredState);
        } catch (IllegalArgumentException ignored) {
            // 旧セーブは pause と再生起点から復元。停止済みの盤をロードで再生し直さない。
            state = restoredPaused ? PlaybackCursor.State.PAUSED
                    : playbackStartGameTime >= 0L ? PlaybackCursor.State.PLAYING : PlaybackCursor.State.STOPPED;
        }
        rebuildMediaSequence();
        cursor = hasDisc() ? new PlaybackCursor(Math.max(0, restoredDiscIndex), Math.max(0, restoredTrackIndex),
                state, Math.max(0L, generation)) : PlaybackCursor.initial();
        if (hasDisc() && isMdmAlbum() && mediaSequence.tracks().isEmpty()) {
            cursor = new PlaybackCursor(PlaybackCursor.NO_INDEX, PlaybackCursor.NO_INDEX,
                    PlaybackCursor.State.STOPPED, Math.max(0L, generation));
            playbackStartGameTime = -1L;
            pausedOffsetMs = 0L;
        } else if (hasDisc() && isMdmAlbum() && mediaSequence.at(mediaPosition()).isEmpty()) {
            final Optional<MediaSequenceResolver.ResolvedTrack> first = mediaSequence.first();
            if (first.isPresent()) {
                cursor = new PlaybackCursor(first.get().position().discIndex(),
                        first.get().position().trackIndex(), state, Math.max(0L, generation));
            }
        }
        rebuildPlaybackSequence();
        if (!hasDisc()) {
            automationFinished = false;
        }
        comparatorLatch = -1;
        // A restored block samples its current input instead of treating it as a new press.
        redstonePowerInitialized = false;
        // アンカーに固定し、以降は client 自身の壁時計で進める。
        //? if >=26.1 {
        input.getLong(SYNC_ELAPSED_MS_KEY).ifPresent(syncElapsedMs -> {
            this.clientElapsedAnchorMs = syncElapsedMs;
            this.clientAnchorWallClockMs = playbackTimeMs();
        });
        //?} elif >=1.21.11 {
/*        input.getLong(SYNC_ELAPSED_MS_KEY).ifPresent(elapsed -> {
            this.clientElapsedAnchorMs = elapsed;
            this.clientAnchorWallClockMs = playbackTimeMs();
        });
        */
        //?} else {
/*        if (tag.contains(SYNC_ELAPSED_MS_KEY)) {
            this.clientElapsedAnchorMs = tag.getLong(SYNC_ELAPSED_MS_KEY);
            this.clientAnchorWallClockMs = playbackTimeMs();
        }
        */
        //?}
        if (mount == null) GoldenSourceRegistry.register(this);
    }

    @Override
    //? if >=1.21.11 {
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
        if (sourceId != null) output.putString("source_id", sourceId.toString());
        output.putInt("range", rangeBlocks);
        output.putInt("volume", volumePercent);
        output.putBoolean("repeat", repeat);
        output.putBoolean("shuffle", shuffle);
        output.putLong("shuffleSeed", shuffleSeed);
        output.putInt("shuffleAnchorDisc", shuffleAnchorDiscIndex);
        output.putInt("shuffleAnchorTrack", shuffleAnchorTrackIndex);
        output.putBoolean("directional", directional);
        output.putString("playbackState", cursor.state().name());
        output.putLong("playbackGeneration", cursor.generation());
        output.putBoolean("paused", isPaused());
        output.putBoolean("automationFinished", automationFinished);
        output.putLong("pausedOffset", pausedOffsetMs);
        output.putLong("playbackStartGameTime", savedPlaybackStartGameTime());
        output.putInt("albumTrack", savedDiscIndex());
        output.putInt("playlistTrack", savedTrackIndex());
    //?} elif >=1.21 {
/*    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        final CompoundTag invTag = new CompoundTag();
        SafeItemSaver.saveAllItems(invTag, items, registries);
        tag.put("inventory", invTag);
        if (sourceId != null) tag.putString("source_id", sourceId.toString());
        tag.putInt("range", rangeBlocks);
        tag.putInt("volume", volumePercent);
        tag.putBoolean("repeat", repeat);
        tag.putBoolean("shuffle", shuffle);
        tag.putLong("shuffleSeed", shuffleSeed);
        tag.putInt("shuffleAnchorDisc", shuffleAnchorDiscIndex);
        tag.putInt("shuffleAnchorTrack", shuffleAnchorTrackIndex);
        tag.putBoolean("directional", directional);
        tag.putString("playbackState", cursor.state().name());
        tag.putLong("playbackGeneration", cursor.generation());
        tag.putBoolean("paused", isPaused());
        tag.putBoolean("automationFinished", automationFinished);
        tag.putLong("pausedOffset", pausedOffsetMs);
        tag.putLong("playbackStartGameTime", savedPlaybackStartGameTime());
        tag.putInt("albumTrack", savedDiscIndex());
        tag.putInt("playlistTrack", savedTrackIndex());
    */
    //?} else {
/*    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        final CompoundTag invTag = new CompoundTag();
        ContainerHelper.saveAllItems(invTag, items);
        tag.put("inventory", invTag);
        if (sourceId != null) tag.putString("source_id", sourceId.toString());
        tag.putInt("range", rangeBlocks);
        tag.putInt("volume", volumePercent);
        tag.putBoolean("repeat", repeat);
        tag.putBoolean("shuffle", shuffle);
        tag.putLong("shuffleSeed", shuffleSeed);
        tag.putInt("shuffleAnchorDisc", shuffleAnchorDiscIndex);
        tag.putInt("shuffleAnchorTrack", shuffleAnchorTrackIndex);
        tag.putBoolean("directional", directional);
        tag.putString("playbackState", cursor.state().name());
        tag.putLong("playbackGeneration", cursor.generation());
        tag.putBoolean("paused", isPaused());
        tag.putBoolean("automationFinished", automationFinished);
        tag.putLong("pausedOffset", pausedOffsetMs);
        tag.putLong("playbackStartGameTime", savedPlaybackStartGameTime());
        tag.putInt("albumTrack", savedDiscIndex());
        tag.putInt("playlistTrack", savedTrackIndex());
    */
    //?}
    }

    @Override
    //? if >=1.21.2 {
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        final CompoundTag tag = saveCustomOnly(registries);
    //?} elif >=1.21 {
/*    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        final CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
    */
    //?} else {
/*    public CompoundTag getUpdateTag() {
        final CompoundTag tag = new CompoundTag();
        saveAdditional(tag);
    */
    //?}
        tag.putLong(SYNC_ELAPSED_MS_KEY, syncElapsedMs());
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ── Container (1 スロット・hopper / comparator の土台) ──

    @Override
    public int getContainerSize() {
        return SIZE;
    }

    @Override
    public boolean isEmpty() {
        return items.get(SLOT_DISC).isEmpty();
    }

    @Override
    public @NotNull ItemStack getItem(int slot) {
        return items.get(slot);
    }

    @Override
    public @NotNull ItemStack removeItem(int slot, int amount) {
        final ItemStack removed = ContainerHelper.removeItem(items, slot, amount);
        if (!removed.isEmpty()) {
            onDiscChanged();
        }
        return removed;
    }

    @Override
    public @NotNull ItemStack removeItemNoUpdate(int slot) {
        final ItemStack removed = ContainerHelper.takeItem(items, slot);
        if (!removed.isEmpty()) {
            onDiscChanged();
        }
        return removed;
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        if (!stack.isEmpty() && stack.getCount() > getMaxStackSize()) {
            stack.setCount(getMaxStackSize());
        }
        onDiscChanged();
    }

    @Override
    public int getMaxStackSize() {
        return 1;
    }

    @Override
    public boolean stillValid(Player player) {
        if (mount != null) return mount.stillValid(player);
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        //? if >=1.21 {
        return slot == SLOT_DISC && isPlayableInSlot(stack) && items.get(SLOT_DISC).isEmpty();
        //?} else {
/*        return slot == SLOT_DISC
                && (stack.getItem() instanceof RecordItem || isPlayableMdmAlbum(stack))
                && items.get(SLOT_DISC).isEmpty();
        */
        //?}
    }

    @Override
    public int[] getSlotsForFace(Direction side) {
        return AUTOMATION_SLOTS;
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, @Nullable Direction side) {
        return canPlaceItem(slot, stack) && (side == null || side != Direction.DOWN);
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction side) {
        return slot == SLOT_DISC && side == Direction.DOWN && isAutomationExtractionReady();
    }

    /** Only the terminal end of the whole medium may be exported. */
    public boolean isAutomationExtractionReady() {
        return hasDisc() && automationFinished && cursor.state() == PlaybackCursor.State.STOPPED;
    }

    @Override
    public void clearContent() {
        items.clear();
        onDiscChanged();
    }
}
