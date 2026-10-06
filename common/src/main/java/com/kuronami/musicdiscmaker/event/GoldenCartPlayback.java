package com.kuronami.musicdiscmaker.event;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.GoldenCartState;
import com.kuronami.musicdiscmaker.component.GoldenDeck;
import com.kuronami.musicdiscmaker.component.MediaSequence;
import com.kuronami.musicdiscmaker.component.MediaSequenceResolver;
import com.kuronami.musicdiscmaker.component.MediaSequenceResolver.ResolvedSequence;
import com.kuronami.musicdiscmaker.component.MediaSequenceResolver.ResolvedTrack;
import com.kuronami.musicdiscmaker.component.PlaybackCursor;
import com.kuronami.musicdiscmaker.component.VanillaTrackData;
import com.kuronami.musicdiscmaker.entity.GoldenJukeboxMinecartEntity;
import com.kuronami.musicdiscmaker.menu.GoldenJukeboxCartMenu;
import com.kuronami.musicdiscmaker.network.GoldenCartPlayPayload;
import com.kuronami.musicdiscmaker.network.GoldenCartStatePayload;
import com.kuronami.musicdiscmaker.network.GoldenCartStopPayload;
import com.kuronami.musicdiscmaker.network.PlaybackSourceStamp;
import com.kuronami.musicdiscmaker.platform.Services;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
//? if >=1.21 {
import net.minecraft.world.item.JukeboxSong;
//?} else {
/*import net.minecraft.world.item.RecordItem;
import net.minecraft.world.item.Item;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
*///?}

/**
 * server 側: 金ジューク付きトロッコの再生ドライバ (Batch B)。
 *
 * <p>{@link BoomboxPlayback} と違い、再生権威は entity そのもの ({@link GoldenDeck}) が持つ。
 * ここは「権威の読み書き + 周囲への play/stop/state 配送」だけを担当し、セッション表や
 * 打刻の掃除は持たない — 移動音源の生存は entity の生存と同一で、client 側の自己停止は
 * {@code CartAnchor} の keep-alive 途絶が担う。
 *
 * <h2>世代と停止</h2>
 * {@link PlaybackCursor} の世代は play/pause/seek/nav/stop の度に進む。client は
 * {@code SourcePlaybackOwnership} が古い世代を弾くので、拾い直しで新しい entity が
 * 同じ {@code source_id} の新世代を撃っても旧経路は残らない。
 *
 * <h2>keep-alive</h2>
 * 再生中は {@link #KEEPALIVE_INTERVAL_TICKS} (20 tick = 1 秒) ごとに
 * {@link GoldenCartPlayPayload} を可聴範囲内の player へ撃ち直す。これは
 * 「範囲へ入り直した player」「ロードに時間がかかった player」への再送も兼ねる。
 *
 * <p>redstone / comparator / speaker 連携は C2 の範囲外 (挙動未決定)。ここには入れない。
 */
public final class GoldenCartPlayback {

    /** 再生中の play 再送間隔 (tick)。1 秒。 */
    public static final int KEEPALIVE_INTERVAL_TICKS = 20;

    private GoldenCartPlayback() {
    }

    // ── 問い合わせ ──

    /** 現在の経過位置 (ms)。PLAYING 以外では pause 位置を返す。 */
    public static long currentOffsetMs(GoldenJukeboxMinecartEntity cart) {
        final GoldenDeck deck = cart.deck();
        if (deck.cursor().state() == PlaybackCursor.State.PLAYING
                && deck.playbackStartGameTime() >= 0L) {
            return deck.pausedOffsetMs()
                    + Math.max(0L, cart.level().getGameTime() - deck.playbackStartGameTime()) * 50L;
        }
        return deck.pausedOffsetMs();
    }

    public static boolean isPlaying(GoldenJukeboxMinecartEntity cart) {
        return cart.deck().cursor().state() == PlaybackCursor.State.PLAYING;
    }

    /** 画面同期用スナップショット。 */
    public static GoldenCartState stateOf(GoldenJukeboxMinecartEntity cart) {
        final GoldenDeck deck = cart.deck();
        return new GoldenCartState(deck.cursor(), currentOffsetMs(cart), deck.isRepeat(),
                deck.isShuffle(), deck.volumePercent(), deck.rangeBlocks(), deck.isDirectional());
    }

    /** 音源の識別子と再生世代。client の所有権検証に使う。 */
    public static PlaybackSourceStamp playbackIdentity(GoldenJukeboxMinecartEntity cart) {
        final UUID id = cart.peekSourceIdentity();
        return id == null ? PlaybackSourceStamp.NONE
                : new PlaybackSourceStamp(id, cart.deck().cursor().generation());
    }

    /** 媒体の解決列 (shuffle 設定を再生順へ反映したもの)。 */
    static MediaSequence playbackSequence(GoldenJukeboxMinecartEntity cart) {
        final ItemStack media = cart.getMedia();
        if (media.isEmpty()) {
            return MediaSequence.empty();
        }
        final ResolvedSequence resolved = MediaSequenceResolver.resolve(media);
        if (resolved.tracks().isEmpty()) {
            return MediaSequence.empty();
        }
        final MediaSequence physical = MediaSequence.of(
                resolved.tracks().stream().map(ResolvedTrack::position).toList());
        final GoldenDeck deck = cart.deck();
        if (!deck.isShuffle()) {
            return physical;
        }
        final PlaybackCursor cursor = deck.cursor();
        final MediaSequence.Position anchor = cursor.hasPosition()
                ? new MediaSequence.Position(cursor.discIndex(), cursor.trackIndex())
                : new MediaSequence.Position(
                        Math.max(0, deck.shuffleAnchorDiscIndex()),
                        Math.max(0, deck.shuffleAnchorTrackIndex()));
        return physical.shuffled(anchor, deck.shuffleSeed());
    }

    /** 現在のカーソル位置が指す custom track。無い媒体・vanilla 盤では null。 */
    @Nullable
    static CustomTrackData currentTrack(GoldenJukeboxMinecartEntity cart) {
        final ItemStack media = cart.getMedia();
        if (media.isEmpty()) {
            return null;
        }
        final ResolvedSequence resolved = MediaSequenceResolver.resolve(media);
        if (resolved.tracks().isEmpty()) {
            return null;
        }
        final PlaybackCursor cursor = cart.deck().cursor();
        final MediaSequence.Position position = cursor.hasPosition()
                ? new MediaSequence.Position(cursor.discIndex(), cursor.trackIndex())
                : null;
        ResolvedTrack fallback = null;
        for (final ResolvedTrack track : resolved.tracks()) {
            if (position == null || track.position().equals(position)) {
                return track.track();
            }
            if (fallback == null) {
                fallback = track;
            }
        }
        return fallback == null ? null : fallback.track();
    }

    /**
     * 現在の媒体が vanilla / 他 MOD のレコードなら中立な曲記述を返す。
     * custom disc (空の jukebox song を持つ) は実音として扱わない (BE の規則と同じ)。
     */
    @Nullable
    static VanillaTrackData currentVanillaTrack(GoldenJukeboxMinecartEntity cart) {
        final ItemStack media = cart.getMedia();
        if (media.isEmpty()) {
            return null;
        }
        if (!MediaSequenceResolver.resolve(media).tracks().isEmpty()) {
            return null;
        }
        //? if >=1.21 {
        return songFor(cart, media).map(holder -> {
            final JukeboxSong song = holder.value();
            //? if >=1.21.2 {
            final String soundEventId = song.soundEvent().value().location().toString();
            //?} else {
            /*final String soundEventId = song.soundEvent().value().getLocation().toString();
            *///?}
            return new VanillaTrackData(soundEventId, song.lengthInTicks() * 50L);
        }).orElse(null);
        //?} else {
        /*final Item item = media.getItem();
        if (item instanceof CustomMusicDiscItem || !(item instanceof RecordItem record)) {
            return null;
        }
        return new VanillaTrackData(
                record.getSound().getLocation().toString(), record.getLengthInTicks() * 50L);
        *///?}
    }

    //? if >=1.21 {
    private static Optional<Holder<JukeboxSong>> songFor(GoldenJukeboxMinecartEntity cart,
            ItemStack media) {
        //? if >=26.1 {
        return JukeboxSong.fromStack(media);
        //?} else {
        /*return cart.level() == null ? Optional.empty()
                : JukeboxSong.fromStack(cart.level().registryAccess(), media);
        *///?}
    }
    //?}

    // ── 操作 (C2S / 媒体挿抜 / tick から呼ばれる。全て server 権威) ──

    /**
     * 再生開始。PAUSED は保存位置から、STOPPED は現在位置 (無ければ先頭) の 0 ms から。
     * 鳴らせる媒体が無ければ状態だけを揃えて戻る。
     */
    public static void play(GoldenJukeboxMinecartEntity cart) {
        if (!(cart.level() instanceof ServerLevel level) || cart.isRemoved() || !cart.hasMedia()) {
            return;
        }
        final GoldenDeck deck = cart.deck();
        if (deck.cursor().state() == PlaybackCursor.State.PLAYING) {
            return;
        }
        cart.sourceIdentity(); // 鳴る機体には識別子が要る (server 権威で採番)
        // 鳴らす対象を先に確定させる: 鳴らせない媒体で cursor を PLAYING にしない
        if (currentTrack(cart) == null && currentVanillaTrack(cart) == null) {
            return;
        }
        if (deck.cursor().state() == PlaybackCursor.State.PAUSED) {
            deck.setCursor(deck.cursor().resume());
            // pausedOffsetMs is the exact base; the game-time anchor marks only
            // elapsed ticks since this resume (do not backdate and count it twice).
            deck.setPlaybackStartGameTime(level.getGameTime());
        } else {
            final MediaSequence.Position position = startPosition(cart);
            deck.setCursor(deck.cursor().startAt(position.discIndex(), position.trackIndex()));
            deck.setPausedOffsetMs(0L);
            deck.setPlaybackStartGameTime(level.getGameTime());
        }
        deck.setAutomationFinished(false);
        broadcastPlay(cart, level);
        notifyViewers(cart, level);
    }

    /** 再生中の位置を pause 位置として確定し、client の音を止める。 */
    public static void pause(GoldenJukeboxMinecartEntity cart) {
        if (!(cart.level() instanceof ServerLevel level)) {
            return;
        }
        final GoldenDeck deck = cart.deck();
        if (deck.cursor().state() != PlaybackCursor.State.PLAYING) {
            return;
        }
        deck.setPausedOffsetMs(currentOffsetMs(cart));
        deck.setPlaybackStartGameTime(-1L);
        deck.setCursor(deck.cursor().pause());
        broadcastStop(cart, level);
        notifyViewers(cart, level);
    }

    /**
     * 停止。{@code naturalEnd} は尺の使い切り (automation の取出し条件へ載せる)。
     * 拾い直し後に別位置から再開しないよう、位置は残して offset だけ戻す。
     */
    public static void stop(GoldenJukeboxMinecartEntity cart, boolean naturalEnd) {
        if (!(cart.level() instanceof ServerLevel level)) {
            return;
        }
        final GoldenDeck deck = cart.deck();
        if (deck.cursor().state() == PlaybackCursor.State.STOPPED && deck.pausedOffsetMs() == 0L) {
            return;
        }
        deck.setPausedOffsetMs(0L);
        deck.setPlaybackStartGameTime(-1L);
        deck.setCursor(deck.cursor().stop());
        if (naturalEnd) {
            deck.setAutomationFinished(true);
        }
        broadcastStop(cart, level);
        notifyViewers(cart, level);
    }

    /** シーク。尺の分かる曲では [0, 尺] へ畳む。 */
    public static void seekTo(GoldenJukeboxMinecartEntity cart, long offsetMs) {
        if (!(cart.level() instanceof ServerLevel level)) {
            return;
        }
        final GoldenDeck deck = cart.deck();
        final PlaybackCursor.State state = deck.cursor().state();
        if (state == PlaybackCursor.State.STOPPED || !deck.cursor().hasPosition()) {
            return;
        }
        final long duration = currentDurationMs(cart);
        long clamped = Math.max(0L, offsetMs);
        if (duration > 0L) {
            clamped = Math.min(clamped, duration);
        }
        deck.setPausedOffsetMs(clamped);
        if (state == PlaybackCursor.State.PLAYING) {
            // Keep the requested millisecond position intact, including its
            // sub-tick remainder; elapsed game ticks are added from this anchor.
            deck.setPlaybackStartGameTime(level.getGameTime());
            deck.setCursor(deck.cursor().invalidate()); // 世代を進めて旧 offset の lease を切る
            broadcastPlay(cart, level);
        }
        notifyViewers(cart, level);
    }

    public static void nextTrack(GoldenJukeboxMinecartEntity cart) {
        moveTrack(cart, true);
    }

    public static void previousTrack(GoldenJukeboxMinecartEntity cart) {
        moveTrack(cart, false);
    }

    /**
     * 手動の曲送り/戻し。手動では列の端を折り返す ({@code repeat = true} で next/previous を呼ぶ)。
     * vanilla 盤など列を持たない媒体では位置が変わらないので何もしない。
     */
    private static void moveTrack(GoldenJukeboxMinecartEntity cart, boolean forward) {
        if (!(cart.level() instanceof ServerLevel level) || !cart.hasMedia()) {
            return;
        }
        final MediaSequence sequence = playbackSequence(cart);
        if (sequence.isEmpty()) {
            return;
        }
        final GoldenDeck deck = cart.deck();
        final PlaybackCursor cursor = deck.cursor();
        final MediaSequence.Position current = cursor.hasPosition()
                ? new MediaSequence.Position(cursor.discIndex(), cursor.trackIndex())
                : null;
        final Optional<MediaSequence.Position> next = current == null
                ? sequence.first()
                : forward ? sequence.next(current, true) : sequence.previous(current, true);
        if (next.isEmpty()) {
            return;
        }
        deck.setPausedOffsetMs(0L);
        deck.setAutomationFinished(false);
        if (cursor.state() == PlaybackCursor.State.PLAYING) {
            deck.setPlaybackStartGameTime(level.getGameTime());
            deck.setCursor(cursor.startAt(next.get().discIndex(), next.get().trackIndex()));
            broadcastPlay(cart, level);
        } else {
            deck.setPlaybackStartGameTime(-1L);
            deck.setCursor(cursor.moveTo(next.get().discIndex(), next.get().trackIndex()));
        }
        notifyViewers(cart, level);
    }

    public static void setRepeat(GoldenJukeboxMinecartEntity cart, boolean repeat) {
        cart.deck().setRepeat(repeat);
        notifyViewersIfServer(cart);
    }

    /** shuffle の切替は anchor と seed も一緒に確定させる (再生順の再現性)。 */
    public static void setShuffle(GoldenJukeboxMinecartEntity cart, boolean shuffle) {
        final GoldenDeck deck = cart.deck();
        if (deck.isShuffle() == shuffle) {
            return;
        }
        if (shuffle) {
            final PlaybackCursor cursor = deck.cursor();
            final int anchorDisc = cursor.hasPosition() ? cursor.discIndex()
                    : PlaybackCursor.NO_INDEX;
            final int anchorTrack = cursor.hasPosition() ? cursor.trackIndex()
                    : PlaybackCursor.NO_INDEX;
            deck.setShuffle(true, cart.level().getRandom().nextLong(), anchorDisc, anchorTrack);
        } else {
            deck.setShuffle(false, deck.shuffleSeed(),
                    PlaybackCursor.NO_INDEX, PlaybackCursor.NO_INDEX);
        }
        notifyViewersIfServer(cart);
    }

    public static void setVolume(GoldenJukeboxMinecartEntity cart, int volumePercent) {
        cart.deck().setVolumePercent(volumePercent);
        rebroadcastIfPlaying(cart);
        notifyViewersIfServer(cart);
    }

    public static void setRange(GoldenJukeboxMinecartEntity cart, int rangeBlocks) {
        cart.deck().setRangeBlocks(rangeBlocks);
        rebroadcastIfPlaying(cart);
        notifyViewersIfServer(cart);
    }

    public static void setDirectional(GoldenJukeboxMinecartEntity cart, boolean directional) {
        cart.deck().setDirectional(directional);
        rebroadcastIfPlaying(cart);
        notifyViewersIfServer(cart);
    }

    /**
     * 媒体スロットの内容が変わった時の遷移 (entity の {@code setItem} / 右クリック挿入から、
     * server 側のみ呼ぶ)。再生中だった場合は世代を進めた stop を撃ってから、新しい媒体が
     * あれば 0 ms から鳴らし直す (固定ブロックの金ジュークと同じ挿入即再生)。
     */
    public static void onMediaChanged(GoldenJukeboxMinecartEntity cart) {
        if (!(cart.level() instanceof ServerLevel level)) {
            return;
        }
        final GoldenDeck deck = cart.deck();
        deck.onMediaChanged();
        if (deck.hasSourceId()) {
            broadcastStop(cart, level);
        }
        if (cart.hasMedia() && !cart.isRemoved()) {
            play(cart);
        }
        notifyViewers(cart, level);
    }

    // ── tick (entity から毎 tick 呼ばれる) ──

    /**
     * keep-alive 再送と尺の終端処理。移動音源の所有権競合もここで検知する —
     * 同じ {@code source_id} の別 actor が現れた場合は片方へ新しい識別子を発行し、
     * 2 台が同じ再生経路を共有しない。
     */
    public static void tick(GoldenJukeboxMinecartEntity cart, ServerLevel level) {
        final GoldenDeck deck = cart.deck();
        if (!deck.hasSourceId() || cart.isRemoved()) {
            return;
        }
        if (cart.tickCount % KEEPALIVE_INTERVAL_TICKS == 0) {
            reconcileSourceOwnership(cart, level);
        }
        if (deck.cursor().state() != PlaybackCursor.State.PLAYING) {
            return;
        }
        if (cart.tickCount % KEEPALIVE_INTERVAL_TICKS == 0) {
            broadcastPlay(cart, level); // keep-alive + 範囲へ入り直した player への再送
        }
        final long duration = currentDurationMs(cart);
        final CustomTrackData track = currentTrack(cart);
        // 尺不明・尺 0・ラジオには終端が無い (keep-alive だけを撃ち続ける)
        if (duration <= 0L || (track != null && track.radio())
                || !MediaSequence.allowsAutomaticAdvance(duration, track != null && track.radio())) {
            return;
        }
        if (currentOffsetMs(cart) < duration) {
            return;
        }
        advanceOrFinish(cart, level);
    }

    /**
     * 尺の使い切り。列の次があれば進め、repeat 設定のまま端なら先頭へ戻る。
     * 先も無ければ automation 完了として停止する (hopper 取出し条件と同じ旗)。
     */
    private static void advanceOrFinish(GoldenJukeboxMinecartEntity cart, ServerLevel level) {
        final GoldenDeck deck = cart.deck();
        final PlaybackCursor cursor = deck.cursor();
        final MediaSequence sequence = playbackSequence(cart);
        final MediaSequence.Position current = cursor.hasPosition()
                ? new MediaSequence.Position(cursor.discIndex(), cursor.trackIndex())
                : null;
        final Optional<MediaSequence.Position> next = !sequence.isEmpty()
                ? (current == null ? sequence.first() : sequence.next(current, deck.isRepeat()))
                : Optional.empty();
        if (next.isPresent()) {
            deck.setPausedOffsetMs(0L);
            deck.setPlaybackStartGameTime(level.getGameTime());
            deck.setCursor(cursor.startAt(next.get().discIndex(), next.get().trackIndex()));
            broadcastPlay(cart, level);
            notifyViewers(cart, level);
            return;
        }
        if (sequence.isEmpty() && deck.isRepeat() && hasAnyTrack(cart)) {
            // vanilla 盤の単曲 repeat = 同じ位置を頭から鳴らし直す
            deck.setPausedOffsetMs(0L);
            deck.setPlaybackStartGameTime(level.getGameTime());
            deck.setCursor(cursor.invalidate());
            broadcastPlay(cart, level);
            notifyViewers(cart, level);
            return;
        }
        stop(cart, true);
    }

    /**
     * 移動音源の所有権を名乗る。同じ識別子の別 actor が残っていれば (例: 旧 entity が
     * chunk 境界で複製されたり、拾い直しのずれで 2 台になる異常系) こちらが新しい識別子へ
     * 移り、どちらの停止も相手を止めないようにする。
     */
    private static void reconcileSourceOwnership(GoldenJukeboxMinecartEntity cart,
            ServerLevel level) {
        final UUID id = cart.peekSourceIdentity();
        if (id == null) {
            return;
        }
        if (GoldenSourceRegistry.ownsMovingSource(level, id, cart)) {
            return;
        }
        GoldenSourceRegistry.registerMoving(level, id, cart);
        if (GoldenSourceRegistry.ownsMovingSource(level, id, cart)) {
            return;
        }
        // CONFLICT: この識別子は別 actor が所有中。放送局を分けるため新規採番する
        // (stop を先に撃って旧経路の client lease を畳む)。
        if (isPlaying(cart)) {
            broadcastStop(cart, level);
        }
        GoldenSourceRegistry.unregisterMoving(level, cart);
        cart.deck().assignSourceId(UUID.randomUUID());
        if (cart.deck().cursor().state() == PlaybackCursor.State.PLAYING
                || cart.deck().cursor().state() == PlaybackCursor.State.PAUSED) {
            cart.deck().setCursor(cart.deck().cursor().invalidate());
        }
        GoldenSourceRegistry.registerMoving(level, cart.peekSourceIdentity(), cart);
        if (isPlaying(cart)) {
            broadcastPlay(cart, level);
        }
    }

    // ── 配送 ──

    /** play / keep-alive を可聴範囲内の全 player へ。track が無ければ撃たない。 */
    static void broadcastPlay(GoldenJukeboxMinecartEntity cart, ServerLevel level) {
        final PlaybackSourceStamp identity = playbackIdentity(cart);
        if (identity.sourceId() == null) {
            return;
        }
        final CustomTrackData track = currentTrack(cart);
        final VanillaTrackData vanilla = track == null ? currentVanillaTrack(cart) : null;
        if ((track == null || track.isEmpty()) && vanilla == null) {
            broadcastStop(cart, level);
            return;
        }
        final GoldenDeck deck = cart.deck();
        final GoldenCartPlayPayload payload = new GoldenCartPlayPayload(cart.getId(),
                track == null ? CustomTrackData.EMPTY : track, vanilla,
                currentOffsetMs(cart), deck.rangeBlocks(), deck.volumePercent(),
                deck.isDirectional(), identity);
        final double rangeSq = (double) deck.rangeBlocks() * deck.rangeBlocks();
        for (final ServerPlayer player : level.players()) {
            if (player.distanceToSqr(cart) <= rangeSq) {
                com.kuronami.musicdiscmaker.audio.local.LocalAudioServer.observe(player, payload);
                Services.NETWORK.sendToPlayer(player, payload);
            }
        }
    }

    /**
     * 明示停止は同じ level の全 player へ。play の宛先は距離で絞るが、受信後に範囲外へ
     * 歩いた player や範囲変更で外れた player が lease を残さないよう、stop は絞らない
     * (宛先が持っていない lease の stop は client 側で no-op になる)。
     */
    public static void broadcastStop(GoldenJukeboxMinecartEntity cart, ServerLevel level) {
        final PlaybackSourceStamp identity = playbackIdentity(cart);
        if (identity.sourceId() == null) {
            return;
        }
        final GoldenCartStopPayload payload = new GoldenCartStopPayload(cart.getId(), identity);
        for (final ServerPlayer player : level.players()) {
            Services.NETWORK.sendToPlayer(player, payload);
        }
    }

    /** 開いている cart menu を持つ player へ最新状態を届ける。 */
    static void notifyViewers(GoldenJukeboxMinecartEntity cart, ServerLevel level) {
        for (final ServerPlayer player : level.players()) {
            if (player.containerMenu instanceof GoldenJukeboxCartMenu menu
                    && menu.getEntity() == cart && menu.stillValid(player)) {
                Services.NETWORK.sendToPlayer(player,
                        new GoldenCartStatePayload(menu.containerId, cart.getId(), stateOf(cart)));
            }
        }
    }

    private static void notifyViewersIfServer(GoldenJukeboxMinecartEntity cart) {
        if (cart.level() instanceof ServerLevel level) {
            notifyViewers(cart, level);
        }
    }

    private static void rebroadcastIfPlaying(GoldenJukeboxMinecartEntity cart) {
        if (cart.level() instanceof ServerLevel level && isPlaying(cart)) {
            broadcastPlay(cart, level); // volume/range/directional の live 反映 = 次の keep-alive を早める
        }
    }

    // ── 内部 ──

    /** 再生開始位置。保存位置が現行列にあればそれ、無ければ列の先頭、列が無ければ (0,0)。 */
    private static MediaSequence.Position startPosition(GoldenJukeboxMinecartEntity cart) {
        final MediaSequence sequence = playbackSequence(cart);
        final PlaybackCursor cursor = cart.deck().cursor();
        if (cursor.hasPosition()) {
            final MediaSequence.Position saved =
                    new MediaSequence.Position(cursor.discIndex(), cursor.trackIndex());
            if (sequence.isEmpty() || sequence.positions().contains(saved)) {
                return saved;
            }
        }
        return sequence.first().orElse(new MediaSequence.Position(0, 0));
    }

    /** 現在の曲の尺 (ms)。尺不明・vanilla 記述なしなら 0。 */
    private static long currentDurationMs(GoldenJukeboxMinecartEntity cart) {
        final CustomTrackData track = currentTrack(cart);
        if (track != null) {
            return track.durationMs();
        }
        final VanillaTrackData vanilla = currentVanillaTrack(cart);
        return vanilla == null ? 0L : vanilla.durationMs();
    }

    private static boolean hasAnyTrack(GoldenJukeboxMinecartEntity cart) {
        return currentTrack(cart) != null || currentVanillaTrack(cart) != null;
    }
}
