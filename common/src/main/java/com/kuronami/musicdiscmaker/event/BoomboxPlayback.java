package com.kuronami.musicdiscmaker.event;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.block.BoomboxBlockEntity;
import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.component.BoomboxPlaybackState;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.MediaSequence;
import com.kuronami.musicdiscmaker.component.MediaSequenceResolver;
import com.kuronami.musicdiscmaker.component.PlaybackCursor;
import com.kuronami.musicdiscmaker.component.PlaybackClockAccess;
import com.kuronami.musicdiscmaker.component.PauseAwarePlaybackClock;
//? if >=1.21 {
//?} else {
/*import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
*///?}
import com.kuronami.musicdiscmaker.network.BoomboxPlayPayload;
import com.kuronami.musicdiscmaker.network.BoomboxStopPayload;
import com.kuronami.musicdiscmaker.network.ModPayload;
import com.kuronami.musicdiscmaker.platform.Services;
//? if >=1.21 {
import com.kuronami.musicdiscmaker.register.ModDataComponents;
//?}

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * ブームボックスの再生を統括する server 側の揮発 state。
 *
 * <h2>鍵は機体そのもの</h2>
 * セッションは {@code BoomboxContents#id} で引く。持ち主でも座標でもスロットでもない。これにより
 * 「持ち替えても鳴り続ける」「同じ曲を積んだ 2 台が独立に鳴り独立に止まる」が両立する
 * (設計上の決定 2026-09-07「複数台は、同時に鳴らすかな　金のジュークボックスと同じ仕様」)。
 *
 * <h2>tick 源は 2 つあり、どちらも「生きている」を打刻するだけ</h2>
 * <ul>
 *   <li><b>持ち歩き</b>: {@link #tick(MinecraftServer)} が {@link #SCAN_INTERVAL_TICKS} ごとに
 *       オンラインの player を走査する。バニラの {@code inventoryTick} には乗せない — あれは
 *       「バニラがそのスロットを tick するか」に継続条件を委ねることになり、カーソルに掴んだ
 *       スタックのような境界例を原理的に扱えない ({@link BoomboxCarry})</li>
 *   <li><b>設置</b>: BlockEntity の server ticker が {@link #servePlaced} を毎 tick 呼ぶ</li>
 * </ul>
 *
 * <p>どちらの経路も {@code lastSeenMillis} を打つだけで、<b>停止は「打刻が途絶えたこと」で決まる</b>
 * ({@link #STALE_MS})。2 つの tick 源の順序に依存しないので、落とした / チェストへ入れた /
 * 死亡ドロップ / ログアウト / ブロック破壊がどれも同じ 1 本の経路で止まる。
 *
 * <h2>GUI の媒体投入</h2>
 * GUI の slot 0 に媒体を入れると、その機体は直ちに再生を始める。停止・一時停止・音量・曲送りは
 * 引き続き GUI の操作で行う。差し替え前の session は先に止め、取り出しだけでは開始しない。
 */
public final class BoomboxPlayback {

    /** keep-alive を撃つ間隔 (ms)。client 側のタイムアウトはこれより十分長い。 */
    public static final long HEARTBEAT_MS = 1_000L;

    /**
     * 終端判定を待たせる猶予 (ms)。
     *
     * <p>server が持つ経過は<b>payload を撃った時刻</b>基準だが、client の音は「S2C レイテンシ +
     * URL 解決 + プリバッファ」ぶん遅れて立つ。猶予無しだと停止が可聴位置 {@code 尺 - 遅れ} で
     * 走り、<b>毎曲の末尾が切れる</b>。
     *
     * <p>🔸 これは校正ではなく近似。金ジュークは client からの報告で実測した値を使うが、
     * ブームボックスにはその報告経路が無い。v2 系譜の {@code AudioLag.UNCALIBRATED_MS} と
     * 同じ 8 秒を置いた (実測帯 5〜10 秒の中央)。報告経路を通せた時点でここは卒業する。
     */
    public static final long TAIL_GRACE_MS = 8_000L;

    /**
     * 持ち歩きの走査間隔 (tick)。keep-alive 間隔より短くしておかないと、送信判定の粒度が走査に
     * 律速されて実効間隔が倍近くまで伸びる。
     */
    public static final int SCAN_INTERVAL_TICKS = 10;

    /**
     * 打刻が途絶えてから停止するまでの猶予 (ms)。持ち歩きの走査間隔 (0.5 秒) より十分長く、
     * client 側の keep-alive タイムアウトより十分短く取る。
     */
    public static final long STALE_MS = 1_500L;

    /** 設置セッションの所有元。dimension と座標の両方が一致した BE だけが同じ機体を名乗れる。 */
    private record PlacedOwner(ResourceKey<Level> dimension, BlockPos pos) {
        private boolean matches(ServerLevel level, BlockPos candidate) {
            return dimension.equals(level.dimension()) && pos.equals(candidate);
        }
    }

    /** 再生中だけ持つ解決済み媒体と時計。component は永続状態、ここは tick 用 cache。 */
    private static final class Session {
        private final ItemStack medium;
        private final MediaSequenceResolver.ResolvedSequence resolved;
        private final MediaSequence playbackSequence;
        private final long startMillis;
        private final long audioGeneration;
        private long lastSentMillis;
        private long lastSeenMillis;
        private final LongSupplier clock;
        private ResourceKey<Level> levelKey;
        private ChunkPos chunk;
        @Nullable private PlacedOwner placedOwner;

        Session(ItemStack medium, MediaSequenceResolver.ResolvedSequence resolved,
                MediaSequence playbackSequence, long startMillis, long audioGeneration,
                ResourceKey<Level> levelKey, ChunkPos chunk, long clockNow, LongSupplier clock,
                @Nullable PlacedOwner placedOwner) {
            this.medium = medium.copy();
            this.resolved = resolved;
            this.playbackSequence = playbackSequence;
            this.startMillis = startMillis;
            this.audioGeneration = audioGeneration;
            this.lastSentMillis = clockNow;
            this.lastSeenMillis = clockNow;
            this.clock = clock;
            this.levelKey = levelKey;
            this.chunk = chunk;
            this.placedOwner = placedOwner;
        }
    }

    /** 機体の識別子 → セッション。 */
    private static final Map<Long, Session> SESSIONS = new HashMap<>();

    private static long playbackTimeMs(ServerLevel level) {
        return level.getServer() instanceof PlaybackClockAccess clock
                ? clock.mdm$playbackTimeMs() : PauseAwarePlaybackClock.realTimeMs();
    }

    /** 同じ機体を stop→start しても前の heartbeat と衝突させない server 内の音声 epoch。 */
    private static long nextAudioGeneration = 1L;

    private static int tickCounter;

    @FunctionalInterface
    private interface PayloadFactory {
        ModPayload create(CustomTrackData track, long offset, long audioGeneration, int volumePercent);
    }

    private BoomboxPlayback() {
    }

    // ── 問い合わせ ──────────────────────────────────────────────────────

    /** component の読み書きは帯差をここだけに閉じる。 */
    private static BoomboxContents contentsOf(ItemStack stack) {
        //? if >=1.21 {
        return stack.getOrDefault(ModDataComponents.BOOMBOX_CONTENTS.get(), BoomboxContents.EMPTY);
        //?} else {
        /*return BoomboxContents.of(stack);
        *///?}
    }

    private static void storeContents(ItemStack stack, BoomboxContents contents) {
        //? if >=1.21 {
        stack.set(ModDataComponents.BOOMBOX_CONTENTS.get(), contents);
        //?} else {
        /*BoomboxContents.store(stack, contents);
        *///?}
    }

    /** Menu/S2C 用の永続状態。Session が無い PLAYING は勝手に復帰させず表示だけ停止へ正規化する。 */
    public static BoomboxPlaybackState stateOf(ItemStack stack) {
        final BoomboxContents contents = contentsOf(stack);
        PlaybackCursor cursor = contents.cursor();
        final Session session = SESSIONS.get(contents.id());
        long elapsed = 0L;
        if (cursor.state() == PlaybackCursor.State.PAUSED) {
            elapsed = contents.pausedOffsetMs();
        } else if (cursor.state() == PlaybackCursor.State.PLAYING && session != null) {
            elapsed = Math.max(0L, session.clock.getAsLong() - session.startMillis);
        } else if (cursor.state() == PlaybackCursor.State.PLAYING) {
            cursor = new PlaybackCursor(cursor.discIndex(), cursor.trackIndex(), PlaybackCursor.State.STOPPED,
                    cursor.generation());
        }
        return new BoomboxPlaybackState(cursor, elapsed, contents.repeat(), contents.shuffle(),
                contents.volumePercent());
    }

    private static MediaSequence playbackSequence(BoomboxContents contents,
            MediaSequenceResolver.ResolvedSequence resolved) {
        final MediaSequence physical = resolved.positions();
        if (!contents.shuffle() || physical.isEmpty()) {
            return physical;
        }
        final MediaSequence.Position anchor = contents.shuffleAnchorDisc() >= 0
                ? new MediaSequence.Position(contents.shuffleAnchorDisc(), contents.shuffleAnchorTrack())
                : contents.cursor().hasPosition()
                        ? new MediaSequence.Position(contents.cursor().discIndex(), contents.cursor().trackIndex())
                        : null;
        return physical.shuffled(anchor, contents.shuffleSeed());
    }

    private static long mintAudioGeneration() {
        final long current = nextAudioGeneration;
        nextAudioGeneration = current == Long.MAX_VALUE ? 1L : current + 1L;
        return current;
    }

    /**
     * その機体が実際に鳴っているか。<b>判定の正本はセッションであって component ではない。</b>
     *
     * @param boomboxId 機体の識別子
     * @return 鳴っていれば {@code true}
     */
    public static boolean isPlaying(long boomboxId) {
        return boomboxId != BoomboxContents.UNASSIGNED && SESSIONS.containsKey(boomboxId);
    }

    /** テスト用: 生きているセッション数。 */
    public static int activeSessionCount() {
        return SESSIONS.size();
    }

    /**
     * そのスタックの機体識別子を返す (未採番なら採番する)。
     *
     * <p>クラフト直後のスタックに採番する口がバニラに無いので、<b>鳴らそうとした時に無ければ振る</b>。
     * 同一インベントリ内の重複 (クリエイティブの複製・{@code /give}・NBT 直書き) は
     * {@link #scan} が見つけて振り直す。
     *
     * @param stack ブームボックスのスタック
     * @return 識別子
     */
    public static long identify(ItemStack stack) {
        final BoomboxContents contents = contentsOf(stack);
        if (contents.hasId()) {
            return contents.id();
        }
        final long minted = BoomboxContents.mintId();
        storeContents(stack, contents.withId(minted));
        return minted;
    }

    /**
     * コピーされた機体を、鳴っている元機体と別の個体へ分離する。
     * 現在位置は一時停止状態として保存し、コピーしただけで二重再生は始めない。
     */
    public static void separateDuplicate(ItemStack stack) {
        final BoomboxContents contents = contentsOf(stack);
        final BoomboxPlaybackState state = stateOf(stack);
        PlaybackCursor cursor = state.cursor();
        if (cursor.hasPosition()) {
            cursor = cursor.state() == PlaybackCursor.State.PLAYING ? cursor.pause() : cursor.invalidate();
        } else {
            cursor = cursor.stop();
        }
        storeContents(stack, contents.withId(BoomboxContents.mintId()).withPlayback(cursor, state.elapsedMs()));
    }

    /** 設置機が別座標の active session を名乗っていたら、操作前に別個体へ分離する。 */
    private static void preparePlacedSource(ServerLevel level, BlockPos pos, BoomboxBlockEntity be) {
        final ItemStack stack = be.getStored();
        final BoomboxContents contents = contentsOf(stack);
        final Session session = SESSIONS.get(contents.id());
        if (session == null || session.placedOwner != null && session.placedOwner.matches(level, pos)) {
            return;
        }
        if (session.placedOwner == null
                && contents.cursor().state() == PlaybackCursor.State.PLAYING
                && ItemStack.matches(session.medium, contents.disc())) {
            // survival/creative の通常設置は、持ち歩き中の同じ機体と再生をそのまま移す。
            session.placedOwner = new PlacedOwner(level.dimension(), pos.immutable());
            return;
        }
        separateDuplicate(stack);
        be.setChanged();
    }

    /** 手元の旧コピーが設置機の active session を名乗っていたら、操作前に別個体へ分離する。 */
    private static void prepareCarriedSource(ItemStack stack) {
        final Session session = SESSIONS.get(contentsOf(stack).id());
        if (session != null && session.placedOwner != null) {
            separateDuplicate(stack);
        }
    }

    @Nullable
    private static CustomTrackData trackOf(ItemStack stack) {
        //? if >=1.21 {
        final BoomboxContents contents = contentsOf(stack);
        if (contents == null || !contents.hasDisc()) {
            return null;
        }
        final CustomTrackData track = contents.disc().get(ModDataComponents.CUSTOM_TRACK.get());
        //?} else {
        /*// 1.20.1 は曲メタも NBT。読み出しの糊は CustomMusicDiscItem が持つ (版側の override)。
        final BoomboxContents contents = BoomboxContents.of(stack);
        if (!contents.hasDisc()) {
            return null;
        }
        final CustomTrackData track = CustomMusicDiscItem.getTrack(contents.disc());
        *///?}
        return track != null && !track.isEmpty() ? track : null;
    }

    private record Started(long id, BoomboxContents contents, Session session, CustomTrackData track, long offset) {
    }

    @Nullable
    private static Started begin(ItemStack stack, ServerLevel level, ChunkPos chunk,
            @Nullable BlockPos placedPos) {
        BoomboxContents contents = contentsOf(stack);
        if (!contents.hasDisc()) {
            return null;
        }
        final MediaSequenceResolver.ResolvedSequence resolved = MediaSequenceResolver.resolve(contents.disc());
        final MediaSequence sequence = playbackSequence(contents, resolved);
        final MediaSequence.Position current = new MediaSequence.Position(
                Math.max(0, contents.cursor().discIndex()), Math.max(0, contents.cursor().trackIndex()));
        final MediaSequence.Position selected = sequence.positions().contains(current)
                ? current : sequence.first().orElse(null);
        if (selected == null) {
            return null;
        }
        final CustomTrackData track = resolved.at(selected).map(MediaSequenceResolver.ResolvedTrack::track).orElse(null);
        if (track == null) {
            return null;
        }
        final long id = identify(stack);
        contents = contentsOf(stack);
        final long offset = contents.cursor().state() == PlaybackCursor.State.PAUSED
                ? contents.pausedOffsetMs() : 0L;
        final PlaybackCursor cursor = contents.cursor().startAt(selected.discIndex(), selected.trackIndex());
        contents = contents.withPlayback(cursor, 0L);
        storeContents(stack, contents);
        final long now = playbackTimeMs(level);
        final Session session = new Session(contents.disc(), resolved, sequence, now - offset, mintAudioGeneration(),
                level.dimension(), chunk, now, () -> playbackTimeMs(level),
                placedPos == null ? null : new PlacedOwner(level.dimension(), placedPos.immutable()));
        SESSIONS.put(id, session);
        return new Started(id, contents, session, track, offset);
    }

    // ── GUI のための口 ──────────────────────────────────────────────────

    /**
     * 持ち歩いている機体の再生を起こす。
     *
     * @param player 持ち主
     * @param stack  ブームボックスのスタック
     * @return 鳴らせたか ({@code false} = ディスクが無い / custom disc でない)
     */
    public static boolean startCarried(ServerPlayer player, ItemStack stack) {
        // 新しい帯 (1.21.11 / 26.x で実測) では ServerPlayer#serverLevel() が無く、level() が
        // ServerLevel を返す共変オーバーライドになっている。キャストなら 4 帯とも同じ 1 行で書ける。
        final ServerLevel level = (ServerLevel) player.level();
        prepareCarriedSource(stack);
        final Started started = begin(stack, level, player.chunkPosition(), null);
        if (started == null) {
            return false;
        }
        send(level, player.chunkPosition(),
                BoomboxPlayPayload.carried(started.id(), player.getId(), started.track(), started.offset(),
                        started.session().audioGeneration, started.contents().volumePercent()));
        return true;
    }

    /**
     * 設置してある機体の再生を起こす。
     *
     * @param level 世界
     * @param pos   設置位置
     * @param be    その BlockEntity
     * @return 鳴らせたか
     */
    public static boolean startPlaced(ServerLevel level, BlockPos pos, BoomboxBlockEntity be) {
        final ChunkPos chunk = chunkOf(pos);
        preparePlacedSource(level, pos, be);
        final Started started = begin(be.getStored(), level, chunk, pos);
        if (started == null) {
            return false;
        }
        be.setChanged();
        send(level, chunk, BoomboxPlayPayload.placed(started.id(), pos, started.track(), started.offset(),
                started.session().audioGeneration, started.contents().volumePercent()));
        return true;
    }

    private record ControlContext(ServerLevel level, ChunkPos chunk, ItemStack stack, PayloadFactory payload,
            Runnable changed, @Nullable BlockPos placedPos) {
    }

    private static ControlContext carriedContext(ServerPlayer player, ItemStack stack) {
        final ServerLevel level = (ServerLevel) player.level();
        prepareCarriedSource(stack);
        final long id = contentsOf(stack).id();
        return new ControlContext(level, player.chunkPosition(), stack,
                (track, offset, epoch, volume) -> BoomboxPlayPayload.carried(id, player.getId(), track,
                        offset, epoch, volume), () -> { }, null);
    }

    private static ControlContext placedContext(ServerLevel level, BlockPos pos, BoomboxBlockEntity be) {
        preparePlacedSource(level, pos, be);
        final long id = contentsOf(be.getStored()).id();
        return new ControlContext(level, chunkOf(pos), be.getStored(),
                (track, offset, epoch, volume) -> BoomboxPlayPayload.placed(id, pos, track, offset, epoch, volume),
                be::setChanged, pos);
    }

    public static boolean pauseCarried(ServerPlayer player, ItemStack stack, long expectedGeneration) {
        return pause(carriedContext(player, stack), expectedGeneration);
    }

    public static boolean pausePlaced(ServerLevel level, BlockPos pos, BoomboxBlockEntity be,
            long expectedGeneration) {
        return pause(placedContext(level, pos, be), expectedGeneration);
    }

    public static boolean nextCarried(ServerPlayer player, ItemStack stack, long expectedGeneration) {
        return move(carriedContext(player, stack), expectedGeneration, true);
    }

    public static boolean nextPlaced(ServerLevel level, BlockPos pos, BoomboxBlockEntity be,
            long expectedGeneration) {
        return move(placedContext(level, pos, be), expectedGeneration, true);
    }

    public static boolean previousCarried(ServerPlayer player, ItemStack stack, long expectedGeneration) {
        return move(carriedContext(player, stack), expectedGeneration, false);
    }

    public static boolean previousPlaced(ServerLevel level, BlockPos pos, BoomboxBlockEntity be,
            long expectedGeneration) {
        return move(placedContext(level, pos, be), expectedGeneration, false);
    }

    public static boolean seekCarried(ServerPlayer player, ItemStack stack, long offsetMs,
            long expectedGeneration) {
        return seek(carriedContext(player, stack), offsetMs, expectedGeneration);
    }

    public static boolean seekPlaced(ServerLevel level, BlockPos pos, BoomboxBlockEntity be, long offsetMs,
            long expectedGeneration) {
        return seek(placedContext(level, pos, be), offsetMs, expectedGeneration);
    }

    public static boolean setRepeatCarried(ServerPlayer player, ItemStack stack, boolean enabled,
            long expectedGeneration) {
        return setRepeat(carriedContext(player, stack), enabled, expectedGeneration);
    }

    public static boolean setRepeatPlaced(ServerLevel level, BlockPos pos, BoomboxBlockEntity be,
            boolean enabled, long expectedGeneration) {
        return setRepeat(placedContext(level, pos, be), enabled, expectedGeneration);
    }

    public static boolean setShuffleCarried(ServerPlayer player, ItemStack stack, boolean enabled,
            long expectedGeneration) {
        return setShuffle(carriedContext(player, stack), enabled, expectedGeneration);
    }

    public static boolean setShufflePlaced(ServerLevel level, BlockPos pos, BoomboxBlockEntity be,
            boolean enabled, long expectedGeneration) {
        return setShuffle(placedContext(level, pos, be), enabled, expectedGeneration);
    }

    public static boolean setVolumeCarried(ServerPlayer player, ItemStack stack, int volumePercent,
            long expectedGeneration) {
        return setVolume(carriedContext(player, stack), volumePercent, expectedGeneration);
    }

    public static boolean setVolumePlaced(ServerLevel level, BlockPos pos, BoomboxBlockEntity be,
            int volumePercent, long expectedGeneration) {
        return setVolume(placedContext(level, pos, be), volumePercent, expectedGeneration);
    }

    /** GUI の slot 0 差し替え。鳴っていれば先に止め、旧媒体を指す操作 generation を失効させる。 */
    public static boolean replaceMediaCarried(ServerPlayer player, ItemStack stack, ItemStack disc,
            long expectedGeneration) {
        final boolean replaced = replaceMedia(carriedContext(player, stack), disc, expectedGeneration);
        if (replaced && !disc.isEmpty()) {
            startCarried(player, stack);
        }
        return replaced;
    }

    /** 設置機体の GUI からの媒体差し替え。 */
    public static boolean replaceMediaPlaced(ServerLevel level, BlockPos pos, BoomboxBlockEntity be, ItemStack disc,
            long expectedGeneration) {
        final boolean replaced = replaceMedia(placedContext(level, pos, be), disc, expectedGeneration);
        if (replaced && !disc.isEmpty()) {
            startPlaced(level, pos, be);
        }
        return replaced;
    }

    private static boolean matchesGeneration(BoomboxContents contents, long expectedGeneration) {
        return contents.cursor().generation() == expectedGeneration;
    }

    private static boolean replaceMedia(ControlContext context, ItemStack disc, long expectedGeneration) {
        final BoomboxContents contents = contentsOf(context.stack());
        if (!matchesGeneration(contents, expectedGeneration)) {
            return false;
        }
        stop(context.level(), context.chunk(), contents.id());
        storeContents(context.stack(), contents.withDisc(disc));
        context.changed().run();
        return true;
    }

    /** pause と resume を交互に行う。resume は保存 offset から別 audio epoch で開始する。 */
    private static boolean pause(ControlContext context, long expectedGeneration) {
        final BoomboxContents contents = contentsOf(context.stack());
        if (!matchesGeneration(contents, expectedGeneration) || !contents.cursor().hasPosition()) {
            return false;
        }
        final long id = contents.id();
        if (contents.cursor().state() == PlaybackCursor.State.PLAYING) {
            final Session session = SESSIONS.remove(id);
            final long offset = session == null ? 0L : Math.max(0L, playbackTimeMs(context.level()) - session.startMillis);
            storeContents(context.stack(), contents.withPlayback(contents.cursor().pause(), offset));
            context.changed().run();
            send(context.level(), context.chunk(), new BoomboxStopPayload(id));
            return true;
        }
        if (contents.cursor().state() == PlaybackCursor.State.PAUSED) {
            return startAt(context, contents, new MediaSequence.Position(contents.cursor().discIndex(),
                    contents.cursor().trackIndex()), contents.pausedOffsetMs());
        }
        return false;
    }

    private static boolean move(ControlContext context, long expectedGeneration, boolean forward) {
        final BoomboxContents contents = contentsOf(context.stack());
        if (!matchesGeneration(contents, expectedGeneration) || !contents.hasDisc()) {
            return false;
        }
        final PlaybackCursor effectiveCursor = stateOf(context.stack()).cursor();
        final MediaSequenceResolver.ResolvedSequence resolved = MediaSequenceResolver.resolve(contents.disc());
        final MediaSequence sequence = playbackSequence(contents, resolved);
        if (sequence.isEmpty()) {
            return false;
        }
        final MediaSequence.Position current = effectiveCursor.hasPosition()
                ? new MediaSequence.Position(effectiveCursor.discIndex(), effectiveCursor.trackIndex())
                : sequence.first().orElseThrow();
        final MediaSequence.Position target = (forward ? sequence.next(current, true)
                : sequence.previous(current, true)).orElseThrow();
        if (effectiveCursor.state() == PlaybackCursor.State.PLAYING) {
            return startAt(context, contents.withPlayback(effectiveCursor, contents.pausedOffsetMs()), target, 0L);
        }
        storeContents(context.stack(), contents.withPlayback(effectiveCursor.moveTo(target.discIndex(),
                target.trackIndex()), 0L));
        context.changed().run();
        return true;
    }

    private static boolean seek(ControlContext context, long requestedOffset, long expectedGeneration) {
        final BoomboxContents contents = contentsOf(context.stack());
        if (!matchesGeneration(contents, expectedGeneration) || !contents.cursor().hasPosition()) {
            return false;
        }
        final MediaSequenceResolver.ResolvedSequence resolved = MediaSequenceResolver.resolve(contents.disc());
        final MediaSequence.Position position = new MediaSequence.Position(contents.cursor().discIndex(),
                contents.cursor().trackIndex());
        final CustomTrackData track = resolved.at(position).map(MediaSequenceResolver.ResolvedTrack::track).orElse(null);
        if (track == null) {
            return false;
        }
        if (track.radio() || track.durationMs() <= 0L) {
            return false;
        }
        long offset = Math.max(0L, requestedOffset);
        if (track.durationMs() > 0L) {
            offset = Math.min(offset, track.durationMs());
        } else {
            offset = 0L;
        }
        if (contents.cursor().state() == PlaybackCursor.State.PLAYING) {
            return startAt(context, contents, position, offset);
        }
        storeContents(context.stack(), contents.withPlayback(contents.cursor().invalidate(), offset));
        context.changed().run();
        return true;
    }

    private static boolean setRepeat(ControlContext context, boolean enabled, long expectedGeneration) {
        final BoomboxContents contents = contentsOf(context.stack());
        if (!matchesGeneration(contents, expectedGeneration)) {
            return false;
        }
        storeContents(context.stack(), contents.withRepeat(enabled).withPlayback(contents.cursor().invalidate(),
                contents.pausedOffsetMs()));
        context.changed().run();
        return true;
    }

    private static boolean setShuffle(ControlContext context, boolean enabled, long expectedGeneration) {
        final BoomboxContents contents = contentsOf(context.stack());
        if (!matchesGeneration(contents, expectedGeneration)) {
            return false;
        }
        final MediaSequenceResolver.ResolvedSequence resolved = MediaSequenceResolver.resolve(contents.disc());
        final MediaSequence.Position anchor = contents.cursor().hasPosition()
                ? new MediaSequence.Position(contents.cursor().discIndex(), contents.cursor().trackIndex())
                : resolved.first().map(MediaSequenceResolver.ResolvedTrack::position).orElse(null);
        final long seed = enabled ? ThreadLocalRandom.current().nextLong() : 0L;
        final int anchorDisc = enabled && anchor != null ? anchor.discIndex() : PlaybackCursor.NO_INDEX;
        final int anchorTrack = enabled && anchor != null ? anchor.trackIndex() : PlaybackCursor.NO_INDEX;
        final BoomboxContents updated = contents.withShuffle(enabled, seed, anchorDisc, anchorTrack)
                .withPlayback(contents.cursor().invalidate(), contents.pausedOffsetMs());
        storeContents(context.stack(), updated);
        context.changed().run();
        final Session session = SESSIONS.get(updated.id());
        if (session != null) {
            SESSIONS.put(updated.id(), new Session(updated.disc(), resolved, playbackSequence(updated, resolved),
                    session.startMillis, session.audioGeneration, session.levelKey, session.chunk,
                    session.clock.getAsLong(), session.clock, session.placedOwner));
        }
        return true;
    }

    private static boolean setVolume(ControlContext context, int volumePercent, long expectedGeneration) {
        final BoomboxContents contents = contentsOf(context.stack());
        if (!matchesGeneration(contents, expectedGeneration)) {
            return false;
        }
        final BoomboxContents updated = contents.withVolumePercent(volumePercent)
                .withPlayback(contents.cursor().invalidate(), contents.pausedOffsetMs());
        storeContents(context.stack(), updated);
        context.changed().run();
        final Session session = SESSIONS.get(updated.id());
        if (session != null && updated.cursor().state() == PlaybackCursor.State.PLAYING) {
            final MediaSequence.Position position = new MediaSequence.Position(updated.cursor().discIndex(),
                    updated.cursor().trackIndex());
            session.resolved.at(position).ifPresent(track -> send(context.level(), context.chunk(),
                    context.payload().create(track.track(), offsetFor(track.track(), session, playbackTimeMs(context.level())),
                            session.audioGeneration, updated.volumePercent())));
        }
        return true;
    }

    private static boolean startAt(ControlContext context, BoomboxContents contents, MediaSequence.Position position,
            long offset) {
        final MediaSequenceResolver.ResolvedSequence resolved = MediaSequenceResolver.resolve(contents.disc());
        final MediaSequence sequence = playbackSequence(contents, resolved);
        final CustomTrackData track = resolved.at(position).map(MediaSequenceResolver.ResolvedTrack::track).orElse(null);
        if (track == null) {
            return false;
        }
        final PlaybackCursor cursor = contents.cursor().startAt(position.discIndex(), position.trackIndex());
        final BoomboxContents updated = contents.withPlayback(cursor, 0L);
        storeContents(context.stack(), updated);
        context.changed().run();
        final long now = playbackTimeMs(context.level());
        final Session session = new Session(updated.disc(), resolved, sequence,
                now - Math.max(0L, offset), mintAudioGeneration(), context.level().dimension(),
                context.chunk(), now, () -> playbackTimeMs(context.level()), context.placedPos() == null
                        ? null : new PlacedOwner(context.level().dimension(), context.placedPos().immutable()));
        SESSIONS.put(updated.id(), session);
        send(context.level(), context.chunk(), context.payload().create(track, offset, session.audioGeneration,
                updated.volumePercent()));
        return true;
    }

    /**
     * 再生を止めて即時停止 packet を撃つ。
     *
     * @param level     世界 (宛先の解決に使う)
     * @param chunk     音が出ていた chunk
     * @param boomboxId 機体の識別子
     */
    public static void stop(ServerLevel level, ChunkPos chunk, long boomboxId) {
        if (SESSIONS.remove(boomboxId) != null) {
            send(level, chunk, new BoomboxStopPayload(boomboxId));
        }
    }

    /**
     * 設置機を撤去する直前に、その座標が所有するセッションだけを止める。
     * 旧保存の同一 ID 複製なら先に別機体へ分離し、別座標で鳴る元機体を止めない。
     */
    public static void stopPlaced(ServerLevel level, BlockPos pos, BoomboxBlockEntity be) {
        preparePlacedSource(level, pos, be);
        stop(level, chunkOf(pos), BoomboxCarry.idOf(be.getStored()));
    }

    // ── 定期走査 ────────────────────────────────────────────────────────

    /**
     * server tick から毎 tick 呼ばれる。持ち歩きの走査は {@link #SCAN_INTERVAL_TICKS} tick に 1 回、
     * 打刻切れの掃除は毎回。
     *
     * <p><b>server tick の後半 (post) で呼ぶこと。</b> 設置してある機体の打刻は BlockEntity の
     * ticker が行うので、先に呼ぶとその tick の打刻を見ずに掃除してしまう。
     *
     * @param server 対象サーバー
     */
    public static void tick(MinecraftServer server) {
        final long now = playbackTimeMs(server.overworld());
        if (++tickCounter >= SCAN_INTERVAL_TICKS) {
            tickCounter = 0;
            scan(server.getPlayerList().getPlayers(), now);
        }
        sweep(server, now);
    }

    /**
     * 持ち歩きの走査の本体。player 集合を引数に取るのは、テストの mock プレイヤーが
     * {@code PlayerList} に載らないため (載らない集合を走査すると、テストが何も検証しない)。
     *
     * @param players 走査対象
     * @param now     現在時刻 (ms)
     */
    public static void scan(Collection<ServerPlayer> players, long now) {
        final Set<Long> seen = new HashSet<>();
        // 走査中に stop() が SESSIONS を触るので、player ごとに対象を取り切ってから処理する。
        for (final ServerPlayer player : new ArrayList<>(players)) {
            for (final ItemStack stack : BoomboxCarry.carried(player)) {
                serveCarried(player, stack, seen, now);
            }
        }
    }

    /** 走査で見つけた 1 スタックを処理する。 */
    private static void serveCarried(ServerPlayer player, ItemStack stack, Set<Long> seen, long now) {
        final long id = BoomboxCarry.idOf(stack);
        final Session session = SESSIONS.get(id);
        if (id == BoomboxContents.UNASSIGNED || session == null) {
            return; // 鳴っていない機体。採番も再生も GUI 側から起こす
        }
        if (session.placedOwner != null) {
            // 同じ ID の設置機がセッションを所有している。手元側は creative 設置・pick block・
            // 旧保存で残った複製なので、設置側を止めずに別機体へ直す。
            separateDuplicate(stack);
            return;
        }
        if (!seen.add(id)) {
            // 同じ走査で 2 個目が同じ識別子を名乗った = クリエ複製 / /give / NBT 直書き。
            // 放置すると 2 台が 1 セッションの url を交互に書き換えて再起動し続ける。
            // 振り直せば次の走査から別個体として扱われる (鳴っているのは先に見つけた側)。
            storeContents(stack, contentsOf(stack).withId(BoomboxContents.mintId()));
            return;
        }
        // 新しい帯 (1.21.11 / 26.x で実測) では ServerPlayer#serverLevel() が無く、level() が
        // ServerLevel を返す共変オーバーライドになっている。キャストなら 4 帯とも同じ 1 行で書ける。
        final ServerLevel level = (ServerLevel) player.level();
        final ChunkPos chunk = player.chunkPosition();
        if (!contentsOf(stack).hasDisc()) {
            stop(level, chunk, id); // ディスクを抜かれた
            return;
        }
        serve(level, chunk, id, session, stack, now,
                (track, offset, epoch, volume) -> BoomboxPlayPayload.carried(id, player.getId(), track,
                        offset, epoch, volume));
    }

    /**
     * 設置してある機体の打刻。BlockEntity の server ticker から毎 tick 呼ぶ。
     *
     * @param level 世界
     * @param pos   設置位置
     * @param be    その BlockEntity
     */
    public static void servePlaced(ServerLevel level, BlockPos pos, BoomboxBlockEntity be) {
        preparePlacedSource(level, pos, be);
        final long id = BoomboxCarry.idOf(be.getStored());
        final Session session = SESSIONS.get(id);
        if (id == BoomboxContents.UNASSIGNED || session == null) {
            return;
        }
        final ChunkPos chunk = chunkOf(pos);
        if (!contentsOf(be.getStored()).hasDisc()) {
            stop(level, chunk, id);
            return;
        }
        final long generation = contentsOf(be.getStored()).cursor().generation();
        serve(level, chunk, id, session, be.getStored(), playbackTimeMs(level),
                (track, offset, epoch, volume) -> BoomboxPlayPayload.placed(id, pos, track,
                        offset, epoch, volume));
        if (contentsOf(be.getStored()).cursor().generation() != generation) {
            be.setChanged();
        }
    }

    /** 打刻と、心拍 1 発ぶんの判断。持ち歩き / 設置で共通。 */
    private static void serve(ServerLevel level, ChunkPos chunk, long id, Session session, ItemStack stack,
            long now, PayloadFactory payload) {
        session.lastSeenMillis = now;
        session.levelKey = level.dimension();
        session.chunk = chunk;
        BoomboxContents contents = contentsOf(stack);
        if (!ItemStack.matches(session.medium, contents.disc())) {
            storeContents(stack, contents.withPlayback(contents.cursor().clear(), 0L));
            stop(level, chunk, id);
            return;
        }
        if (contents.cursor().state() != PlaybackCursor.State.PLAYING) {
            stop(level, chunk, id);
            return;
        }
        final MediaSequence.Position position = new MediaSequence.Position(contents.cursor().discIndex(),
                contents.cursor().trackIndex());
        final CustomTrackData track = session.resolved.at(position)
                .map(MediaSequenceResolver.ResolvedTrack::track).orElse(null);
        if (track == null) {
            stop(level, chunk, id);
            return;
        }
        final long elapsed = Math.max(0L, now - session.startMillis);
        final long automaticDuration = track.radio() ? 0L : track.durationMs();
        final BoomboxHeartbeat.Action action = BoomboxHeartbeat.decide(true, false, elapsed, automaticDuration,
                now - session.lastSentMillis, HEARTBEAT_MS, TAIL_GRACE_MS);
        switch (action) {
            case END -> {
                final MediaSequence.Position next = session.playbackSequence.next(position, contents.repeat())
                        .orElse(null);
                if (next == null) {
                    final MediaSequence.Position first = session.playbackSequence.first().orElse(position);
                    storeContents(stack, contents.withPlayback(contents.cursor().moveTo(first.discIndex(),
                            first.trackIndex()).stop(), 0L));
                    stop(level, chunk, id);
                } else {
                    restartAt(level, chunk, id, stack, contents, session, next, payload);
                }
            }
            case KEEP_ALIVE -> {
                session.lastSentMillis = now;
                send(level, chunk, payload.create(track, offsetFor(track, session, now), session.audioGeneration,
                        contents.volumePercent()));
            }
            case IDLE, START -> {
                // IDLE は末尾猶予帯。client を早く切らないため再送しない。
            }
        }
    }

    private static void restartAt(ServerLevel level, ChunkPos chunk, long id, ItemStack stack,
            BoomboxContents contents, Session previous, MediaSequence.Position position, PayloadFactory payload) {
        final CustomTrackData track = previous.resolved.at(position).map(MediaSequenceResolver.ResolvedTrack::track)
                .orElse(null);
        if (track == null) {
            stop(level, chunk, id);
            return;
        }
        final PlaybackCursor cursor = contents.cursor().startAt(position.discIndex(), position.trackIndex());
        final BoomboxContents updated = contents.withPlayback(cursor, 0L);
        storeContents(stack, updated);
        final long now = playbackTimeMs(level);
        final Session restarted = new Session(updated.disc(), previous.resolved, previous.playbackSequence,
                now, mintAudioGeneration(), level.dimension(), chunk, now, () -> playbackTimeMs(level),
                previous.placedOwner);
        SESSIONS.put(id, restarted);
        send(level, chunk, payload.create(track, 0L, restarted.audioGeneration, updated.volumePercent()));
    }

    /**
     * 打刻が途絶えたセッションを畳む = インベントリの外へ出た / 持ち主がログアウトした /
     * 設置していたブロックが壊れた・chunk が抜けた。
     */
    /** stale session を明示的に掃除する。server tick と GameTest の双方から使う。 */
    public static void sweep(MinecraftServer server, long now) {
        final Iterator<Map.Entry<Long, Session>> it = SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<Long, Session> entry = it.next();
            final Session session = entry.getValue();
            if (now - session.lastSeenMillis <= STALE_MS) {
                continue;
            }
            it.remove();
            // 宛先が消えていることもある (ログアウト・次元ごと消えた)。その場合でも周囲の client は
            // keep-alive 途絶で自己停止するので、ここは「撃てるなら撃つ」でよい。
            final ServerLevel level = server.getLevel(session.levelKey);
            if (level != null) {
                send(level, session.chunk, new BoomboxStopPayload(entry.getKey()));
            }
        }
    }

    private static long offsetFor(CustomTrackData track, Session session, long now) {
        final boolean live = track.radio() || track.durationMs() <= 0L;
        return BoomboxHeartbeat.offsetFor(live, now - session.startMillis);
    }

    /**
     * 宛先は「その chunk を追跡中の player」。ブームボックスは持ち主本人にも聴こえる必要があるが、
     * 自分が立っている chunk は自分で追跡しているので、この 1 本で本人も入る。
     */
    private static void send(ServerLevel level, ChunkPos chunk, ModPayload payload) {
        if (payload instanceof BoomboxPlayPayload play) {
            com.kuronami.musicdiscmaker.network.BoomboxTrace.audio("server-play-dispatch", play);
        }
        Services.NETWORK.sendToPlayersTrackingChunk(level, chunk, payload);
    }

    /**
     * その座標を含む chunk。{@code new ChunkPos(BlockPos)} は 26.1 で消えて
     * {@code ChunkPos.containing} になった (26.x では ChunkPos が record)。
     *
     * @param pos 座標
     * @return その座標を含む chunk
     */
    public static ChunkPos chunkOf(BlockPos pos) {
        //? if >=26.1 {
        return ChunkPos.containing(pos);
        //?} else {
        /*return new ChunkPos(pos);
        *///?}
    }

    /** server 停止でセッションを破棄する (シングルプレイのワールド退出含む)。 */
    public static void clear() {
        SESSIONS.clear();
        tickCounter = 0;
    }
}
