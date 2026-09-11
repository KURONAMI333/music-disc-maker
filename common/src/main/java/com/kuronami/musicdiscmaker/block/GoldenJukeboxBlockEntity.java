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
//? if >=1.21.2 {
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
 */
public class GoldenJukeboxBlockEntity extends BlockEntity implements WorldlyContainer {

    public static final int SLOT_DISC = 0;
    private static final int SIZE = 1;
    private static final int[] AUTOMATION_SLOTS = {SLOT_DISC};

    public static final int RANGE_MIN = 16;
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
    /** True only while an active redstone signal owns the current pause. */
    private boolean redstonePaused = false;
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

    public GoldenJukeboxBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.GOLDEN_JUKEBOX.get(), pos, state);
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
        if (cursor.state() == PlaybackCursor.State.PLAYING && level != null) {
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
     * progress バーで頭出し操作を許可できるか (有限尺の custom disc のみ)。
     *
     * <p><b>{@code trackDurationMs() > 0} だけで判定してはいけない</b> — vanilla / 他 MOD の
     * ディスクも尺を返すようになったが、実音は {@code JukeboxSongPlayer} が鳴らしていて頭出しの手段が
     * 無い ({@link #seekTo} が {@code currentTrack() == null} で即 return する)。つまみだけ出て
     * 掴んでも戻る、という形になるので custom disc であることを明示条件に残す。
     */
    public boolean isSeekable() {
        return hasDisc() && currentTrack() != null && !isLiveStream() && trackDurationMs() > 0L;
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
            if (playing.isEmpty() || !MediaSequence.allowsAutomaticAdvance(
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
            //? if >=1.21.11 {
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
     * redstone 信号源 (再生中 = 15) の判定。
     *
     * <p><b>{@link #isVanillaPlaying()} で判定してはいけない</b> — あれは custom disc に貼った無音
     * バケット song の尺 (10 秒刻み・尺不明は 3600 秒・ラジオは 7200 秒) であって実曲の尺ではない。
     * バケットで判定すると実曲が終わっても真下のホッパーが {@code ENABLED=false} のまま残り、
     * ディスクが取り出せない (実尺 95 秒の曲でも 120 秒バケットなら 25 秒遅れる)。
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
     * redstone 信号が立ち上がった / 落ちた tick だけ neighbor を更新する。実尺での終了は
     * {@code songPlayer} には見えず {@link #onSongChanged()} が呼ばれないので、ここが唯一の通知経路。
     * 毎 tick は撃たない。
     */
    private void refreshRedstoneSignal() {
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
        // A player action takes ownership from redstone, including an already-paused cursor.
        final boolean ownershipChanged = redstonePaused;
        redstonePaused = false;
        setPausedInternal(value);
        if (ownershipChanged && value && isPaused()) {
            setChanged();
            refreshRedstoneSignal();
            sync();
        }
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
            // A resume request while still powered is converted back into a redstone-owned hold.
            // Preserve its seek offset until power is actually released.
            if (cursor.state() == PlaybackCursor.State.PLAYING) pausedOffsetMs = 0L;
        }
    }

    /** Applies level-sensitive input and resumes only a pause created by that input. */
    private void updateRedstonePower() {
        if (!isServer()) return;
        final boolean powered = level.hasNeighborSignal(getBlockPos());
        if (powered) {
            if (!hasDisc() || redstonePaused || isPaused()) return;
            if (cursor.state() == PlaybackCursor.State.PLAYING) {
                redstonePaused = true;
                setPausedInternal(true);
            }
        } else if (redstonePaused) {
            redstonePaused = false;
            if (hasDisc()) setPausedInternal(false);
            else {
                setChanged();
                sync();
            }
        }
    }

    /**
     * GUI シークバーからの頭出し。有限尺 (非ラジオ) の custom disc の時だけ効く。
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
        final CustomTrackData track = currentTrack();
        if (track == null || track.durationMs() <= 0L) {
            return;
        }
        //? if >=1.21 {
        final long clamped = Mth.clamp(offsetMs, 0L, track.durationMs());
        //?} else {
/*        final long clamped = Math.max(0L, Math.min(offsetMs, track.durationMs()));
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
            if (track == null) {
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
        if (selected != null && previous.url().equals(selected.url())) {
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
        if (level.hasNeighborSignal(getBlockPos())) {
            redstonePaused = true;
            pausedOffsetMs = Math.max(0L, offsetMs);
            if (cursor.state() == PlaybackCursor.State.PLAYING) {
                stopAudio();
                cursor = cursor.pause();
            } else if (cursor.state() == PlaybackCursor.State.STOPPED) {
                cursor = cursor.startAt(Math.max(0, cursor.discIndex()), Math.max(0, cursor.trackIndex())).pause();
            }
            refreshRedstoneSignal();
            setChanged();
            sync();
            return;
        }
        //? if >=1.21 {
        final ItemStack disc = effectiveDisc();
        //?} else {
/*        final ItemStack disc = items.get(SLOT_DISC);
        */
        //?}
        cursor = cursor.startAt(Math.max(0, cursor.discIndex()), Math.max(0, cursor.trackIndex()));
        startMillis = playbackTimeMs() - offsetMs;
        playbackStartGameTime = level.getGameTime() - offsetMs / 50L;
        //? if >=1.21 {
        songFor(disc).ifPresent(song -> songPlayer.play(level, song));
        //?} else {
        //?}
        final CustomTrackData track = currentTrack();
        if (track != null) {
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
/*            // 先にvanilla eventを送り、続くSpeaker payloadが同じ座標のnative音を置き換える。
            level.levelEvent(EVENT_PLAY_RECORD, getBlockPos(), Item.getId(disc.getItem()));
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
        stopAudio();
        cursor = cursor.stop();
        automationFinished = naturalEnd && hasDisc();
        pausedOffsetMs = 0L;
        refreshRedstoneSignal();
        sync();
    }

    private void stopAudio() {
        if (!isServer()) {
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
        // 旧chunk経路は常に維持する。Speaker設置直後でplayer別stateがまだ無くても既存clientを止める。
        SpeakerPlayback.stop(this);
        broadcast(new StopDiscPayload(getBlockPos(), playbackIdentity()));
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
            final boolean keepPaused = isPaused();
            final boolean poweredHold = hasDisc && level.hasNeighborSignal(getBlockPos());
            if (!hasDisc) redstonePaused = false;
            else if (poweredHold && !keepPaused) redstonePaused = true;
            cursor = cursor.clear();
            if (hasDisc) {
                final MediaSequence.Position first = mediaSequence.first()
                        .map(MediaSequenceResolver.ResolvedTrack::position)
                        .orElse(new MediaSequence.Position(0, 0));
                cursor = new PlaybackCursor(first.discIndex(), first.trackIndex(),
                        keepPaused || poweredHold ? PlaybackCursor.State.PAUSED : PlaybackCursor.State.STOPPED,
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
        if (hasDisc && !isPaused() && !redstonePaused
                && (!isMdmAlbum() || !mediaSequence.tracks().isEmpty())) {
            startPlayback(0L);
        } else if (!hasDisc || (isMdmAlbum() && mediaSequence.tracks().isEmpty())) {
            stopPlayback();
        }
        //? if >=1.21 {
        //?} else {
/*        if (level != null) {
            level.updateNeighborsAt(getBlockPos(), getBlockState().getBlock());
        }
        */
        //?}
    }

    private void updateHasRecordState(boolean hasRecord) {
        if (level != null && level.getBlockState(getBlockPos()) == getBlockState()
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

    //? if >=1.21.2 {
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        onBlockRemoved();
    }
    //?}

    @Override
    public void setRemoved() {
        GoldenSourceRegistry.unregister(this);
        SpeakerPlayback.remove(this);
        super.setRemoved();
    }

    @Override
    public void setLevel(Level level) {
        final long oldClock = playbackTimeMs();
        if (this.level != null && this.level != level) GoldenSourceRegistry.unregister(this);
        super.setLevel(level);
        // Deserialization can receive the client anchor before the level (and its paused clock).
        final long clockDelta = playbackTimeMs() - oldClock;
        if (clientElapsedAnchorMs >= 0L) clientAnchorWallClockMs += clockDelta;
        if (startMillis > 0L) startMillis += clockDelta;
        GoldenSourceRegistry.register(this);
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        GoldenSourceRegistry.register(this);
    }

    /** late-join した player へ、再生中なら現在位置で音源種別に対応するpayloadを再送する。 */
    public void resendTo(ServerPlayer player) {
        if (!isServer() || isPaused()) {
            return;
        }
        final CustomTrackData customTrack = currentTrack();
        final VanillaTrackData vanillaTrack = customTrack == null ? currentVanillaTrack() : null;
        if ((customTrack == null && vanillaTrack == null) || startMillis == 0L) {
            return;
        }
        final long elapsed = Math.max(0L, playbackTimeMs() - startMillis);
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
        songPlayer.tick(level, getBlockState());
        if (!isMdmAlbum() && getAlbumTrack() < 0
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
                final long duration = trackDurationMs();
                if (!isLiveStream() && duration > 0L && playbackTimeMs() - startMillis >= duration) {
                    if (repeat) {
                        startPlayback(0L);
                    } else {
                        stopPlayback(true);
                    }
                }
            }
            //? if >=1.21 {
            if (startMillis > 0L && !songPlayer.isPlaying() && track != null
                    && (track.radio() || track.durationMs() <= 0L)) {
                songFor(effectiveDisc()).ifPresent(song -> songPlayer.play(level, song));
            }
            //?} else {
            /*if (startMillis > 0L && track != null && level instanceof ServerLevel serverLevel
                    && level.getGameTime() % 20L == 0L) {
                level.gameEvent(GameEvent.JUKEBOX_PLAY, getBlockPos(), GameEvent.Context.of(getBlockState()));
                spawnNoteParticle(serverLevel);
            }
            *///?}
        }
        refreshRedstoneSignal();
        SpeakerPlayback.tick(this);
    }

    private boolean currentMdmTrackFinished() {
        final CustomTrackData track = currentTrack();
        return track != null && PlaylistPlayback.finished(
                playbackTimeMs() - startMillis, track.durationMs(), track.radio());
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
        if (level != null) {
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
        if (level != null) {
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
        GoldenSourceRegistry.register(this);
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
    //? if >=1.21.2 {
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
        //? if >=1.21.2 {
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
        //? if >=1.21.2 {
        this.directional = input.getBooleanOr("directional", DIRECTIONAL_DEFAULT);
        final boolean restoredPaused = input.getBooleanOr("paused", false);
        final String restoredState = input.getStringOr("playbackState", "");
        final long generation = input.getLongOr("playbackGeneration", 0L);
        this.pausedOffsetMs = input.getLongOr("pausedOffset", 0L);
        this.playbackStartGameTime = input.getLongOr("playbackStartGameTime", -1L);
        this.redstonePaused = input.getBooleanOr("redstonePaused", false);
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
        this.redstonePaused = tag.getBoolean("redstonePaused");
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
        this.redstonePaused = tag.getBoolean("redstonePaused");
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
            redstonePaused = false;
            automationFinished = false;
        }
        comparatorLatch = -1;
        // アンカーに固定し、以降は client 自身の壁時計で進める。
        //? if >=26.1 {
        input.getLong(SYNC_ELAPSED_MS_KEY).ifPresent(syncElapsedMs -> {
            this.clientElapsedAnchorMs = syncElapsedMs;
            this.clientAnchorWallClockMs = playbackTimeMs();
        });
        //?} elif >=1.21.2 {
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
        GoldenSourceRegistry.register(this);
    }

    @Override
    //? if >=1.21.2 {
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
        output.putBoolean("redstonePaused", redstonePaused);
        output.putBoolean("automationFinished", automationFinished);
        output.putLong("pausedOffset", pausedOffsetMs);
        output.putLong("playbackStartGameTime", savedPlaybackStartGameTime());
        output.putInt("albumTrack", savedDiscIndex());
        output.putInt("playlistTrack", savedTrackIndex());
    //?} elif >=1.21 {
/*    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        final CompoundTag invTag = new CompoundTag();
        ContainerHelper.saveAllItems(invTag, items, registries);
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
        tag.putBoolean("redstonePaused", redstonePaused);
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
        tag.putBoolean("redstonePaused", redstonePaused);
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
        return hasDisc() && automationFinished && cursor.state() == PlaybackCursor.State.STOPPED
                && !redstonePaused && !isPaused()
                && (level == null || !level.hasNeighborSignal(getBlockPos()));
    }

    @Override
    public void clearContent() {
        items.clear();
        onDiscChanged();
    }
}
