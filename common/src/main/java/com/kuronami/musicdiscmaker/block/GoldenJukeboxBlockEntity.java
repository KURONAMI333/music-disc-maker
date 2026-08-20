package com.kuronami.musicdiscmaker.block;

import java.util.Optional;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.compat.album.AlbumSupport;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.network.PlayDiscPayload;
import com.kuronami.musicdiscmaker.network.StopDiscPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.register.ModBlockEntities;
import com.kuronami.musicdiscmaker.register.ModDataComponents;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.JukeboxSongPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

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
public class GoldenJukeboxBlockEntity extends BlockEntity implements Container {

    public static final int SLOT_DISC = 0;
    private static final int SIZE = 1;

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

    private final NonNullList<ItemStack> items = NonNullList.withSize(SIZE, ItemStack.EMPTY);

    // ── 永続設定 ──
    private int rangeBlocks = RANGE_DEFAULT;
    private int volumePercent = VOLUME_DEFAULT;
    private boolean repeat = false;
    private boolean paused = false;
    /**
     * 音の指向性。true = 従来どおりの positional audio (左右定位・距離減衰つき) = 既定。
     * false = 可聴範囲の中にいる限り位置に関係なくフラットに聴こえる (BGM モード)。範囲外では
     * 黙る (範囲ゲートは維持する)。client へは BE 同期と再生 payload の両方で運ぶ。
     */
    private boolean directional = DIRECTIONAL_DEFAULT;
    /** pause 時に保存した再生位置 (ms)。resume で offset seek に使う。 */
    private long pausedOffsetMs = 0L;
    /**
     * 再生開始時の {@link net.minecraft.world.level.Level#getGameTime()}。-1 = 停止/一時停止中。
     * client の進捗バー計算用に同期する (wall-clock はマルチプレイで機体差があるため gameTime を使う)。
     */
    private long playbackStartGameTime = -1L;
    /**
     * スロットのアイテムがアルバム ({@link AlbumSupport}) の時の再生中トラック index。
     * <b>-1 = アルバムではない = 従来動作</b>。旧セーブにこのキーは無いので -1 に倒れる。
     * {@link #playbackStartGameTime} は「このトラック内の再生位置」の意味になるのでセットで永続化する。
     */
    private int albumTrack = -1;

    /**
     * sync 専用の NBT キー: 送出時点の再生経過 (ms)。-1 = 停止中。
     * <b>{@link #saveAdditional} には書かない</b> (ディスクに残る値ではない)。
     * {@link #getUpdateTag} だけが載せ、client の {@link #loadAdditional} が有無で判別する。
     */
    private static final String SYNC_ELAPSED_MS_KEY = "syncElapsedMs";

    // ── server 揮発 ──
    private final JukeboxSongPlayer songPlayer = new JukeboxSongPlayer(this::onSongChanged, this.getBlockPos());
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

    // ── client 揮発 ──
    /** sync で受け取った再生経過 (ms)。-1 = 停止中 / 未受信。 */
    private long clientElapsedAnchorMs = -1L;
    /** そのアンカーを受け取った client の壁時計 (ms)。 */
    private long clientAnchorWallClockMs = 0L;

    public GoldenJukeboxBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.GOLDEN_JUKEBOX.get(), pos, state);
    }

    // ── GUI / block 用アクセサ ──

    public int getRangeBlocks() {
        return rangeBlocks;
    }

    public int getVolumePercent() {
        return volumePercent;
    }

    public boolean isRepeat() {
        return repeat;
    }

    public boolean isPaused() {
        return paused;
    }

    /** true = positional audio (既定)。false = 範囲内フラット聴取 (BGM モード)。 */
    public boolean isDirectional() {
        return directional;
    }

    /**
     * 現在の再生経過 (ms)。progress バー表示用。一時停止中は保存 offset、停止中は 0。
     *
     * <p>server は永続化された {@code playbackStartGameTime} 基準のまま (BE が復元された直後 =
     * まだ tick していない状態でも同じ値を返す必要があるため。揮発の {@code startMillis} は 0)。
     *
     * <p>client は sync で受け取った経過 ({@value #SYNC_ELAPSED_MS_KEY}) を受信時刻に固定し、そこから
     * 自分の壁時計で進める。<b>client でゲーム内 tick から数えてはいけない</b> — client の
     * {@code gameTime} はサーバが 20 tick ごとに送る値で上書きされるので、TPS が 20 を割った分だけ
     * 表示が遅れ、1 秒周期の補正で止まって飛ぶ (実測: 223 秒の曲で約 4 秒 = 1.8% のずれ)。
     * 曲送りの判定も実音も壁時計基準なので、表示だけがずれていた。
     */
    public long currentElapsedMs() {
        if (paused) {
            return pausedOffsetMs;
        }
        if (level == null) {
            return 0L;
        }
        if (level.isClientSide) {
            final long anchored = clientElapsedMs();
            return anchored < 0L ? 0L : clampToTrackDuration(anchored);
        }
        if (playbackStartGameTime < 0L) {
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

    /**
     * sync に載せる再生経過 (ms)。-1 = 再生していない。
     *
     * <p>ここだけは<b>壁時計</b> ({@code startMillis}) で測る。実音 (LavaPlayer) も曲送りの判定も
     * {@link #resendTo} の offset も壁時計なので、表示をそこへ合わせるのがこの値の役目。
     * chunk ロード / contraption 解体で復元された直後は {@code startMillis} がまだ無いので、
     * その 1 tick だけ永続化された起点から埋める ({@link #resumePlaybackAfterLoad} と同じ式)。
     *
     * <p><b>呼び出し側は -1 を必ず弾くこと。</b> {@code hasDisc() && !isPaused()} だけでは
     * 「実際に再生中」を保証しない — 非リピートアルバムが最後まで再生し終わると
     * {@code albumTrack} を先頭へ戻したまま {@code stopPlayback()} する ({@link #advanceAlbumTrack}
     * / {@link #resumePlaybackAfterLoad}) ので、ディスク (アルバム) は入ったまま・一時停止もして
     * いないのに再生は止まっている、という窓が残る。この用途 (other-mod 向け同期・追従) では
     * public にしてあるが、-1 をそのまま offset として下流に渡すと壁時計 -1ms のような無意味な
     * 頭出しになる。
     */
    public long syncElapsedMs() {
        if (startMillis > 0L) {
            return Math.max(0L, System.currentTimeMillis() - startMillis);
        }
        if (playbackStartGameTime >= 0L && level != null) {
            return Math.max(0L, (level.getGameTime() - playbackStartGameTime) * 50L);
        }
        return -1L;
    }

    /**
     * client 側の再生経過 (ms)。-1 = 停止中 / アンカー未受信。
     * sync で受け取った経過を受信時刻に固定し、そこから client 自身の壁時計で進める。
     */
    private long clientElapsedMs() {
        if (playbackStartGameTime < 0L || clientElapsedAnchorMs < 0L) {
            return -1L;
        }
        return clientElapsedAnchorMs + Math.max(0L, System.currentTimeMillis() - clientAnchorWallClockMs);
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
        return songFor(effectiveDisc()).map(h -> h.value().lengthInTicks() * 50L).orElse(0L);
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

    public boolean hasDisc() {
        return !items.get(SLOT_DISC).isEmpty();
    }

    /**
     * ディスクスロットが受け入れるアイテムか。再生可能ディスク (JUKEBOX_PLAYABLE) と、複数ディスクを
     * 束ねたアルバム。**3 箇所のゲート (BE の canPlaceItem・block の手挿し・menu の mayPlace) の唯一の源**。
     */
    public static boolean isPlayableInSlot(ItemStack stack) {
        return stack.has(DataComponents.JUKEBOX_PLAYABLE) || AlbumSupport.isAlbum(stack);
    }

    /** スロットに入っている生のアイテム (アルバムならアルバム本体)。容器としての同一性はこちら。 */
    public ItemStack getDisc() {
        return items.get(SLOT_DISC);
    }

    /**
     * 実際に再生する対象のディスク。アルバムなら現在トラックの disc、そうでなければスロットの stack。
     * 再生系 (曲名・尺・シーク・コンパレータ・ストリーム送出) は全てここを通す。
     */
    public ItemStack effectiveDisc() {
        final ItemStack raw = items.get(SLOT_DISC);
        if (albumTrack < 0) {
            return raw;
        }
        return AlbumSupport.trackAt(raw, albumTrack);
    }

    /** アルバム再生中のトラック index (0 始まり)。-1 = アルバムではない。 */
    public int getAlbumTrack() {
        return albumTrack;
    }

    /** アルバムの総トラック数。アルバムでなければ 0。client からも呼べる (中身は component 同期される)。 */
    public int albumTrackCount() {
        return albumTrack < 0 ? 0 : AlbumSupport.trackCount(items.get(SLOT_DISC));
    }

    /** 現在のディスクが custom disc なら track を、そうでなければ null。 */
    public CustomTrackData currentTrack() {
        final ItemStack disc = effectiveDisc();
        if (disc.is(ModItems.CUSTOM_MUSIC_DISC.get()) && disc.has(ModDataComponents.CUSTOM_TRACK.get())) {
            final CustomTrackData t = disc.get(ModDataComponents.CUSTOM_TRACK.get());
            return t != null && !t.isEmpty() ? t : null;
        }
        return null;
    }

    /**
     * アルバム再生中に<b>次に鳴る</b> custom disc の track。先読み ({@code PlaybackPrefetch}) が
     * 掴む対象で、client 側 BE からも解決できる (アルバム本体・{@code albumTrack}・{@code repeat} は
     * 同期される)。
     *
     * <p>次が無い場合は {@code null}。内訳は「アルバムでない」「最終トラックで repeat しない」
     * 「次がバニラ / 他 MOD のディスク ({@code CUSTOM_TRACK} を持たない)」「次がラジオ・尺ゼロ」。
     * 送り先の決め方は {@code advanceAlbumTrack} と同じ (次 index、最終なら repeat で先頭へ)。
     *
     * @return 次に鳴る custom disc の track。無ければ {@code null}
     */
    @Nullable
    public CustomTrackData nextAlbumTrack() {
        if (albumTrack < 0) {
            return null;
        }
        final ItemStack album = items.get(SLOT_DISC);
        final int count = AlbumSupport.trackCount(album);
        if (count <= 0) {
            return null;
        }
        int next = albumTrack + 1;
        if (next >= count) {
            if (!repeat) {
                return null; // 最終トラックの後は停止する
            }
            next = 0; // repeat はアルバム全体のループ
        }
        final ItemStack disc = AlbumSupport.trackAt(album, next);
        if (!disc.is(ModItems.CUSTOM_MUSIC_DISC.get()) || !disc.has(ModDataComponents.CUSTOM_TRACK.get())) {
            return null;
        }
        final CustomTrackData track = disc.get(ModDataComponents.CUSTOM_TRACK.get());
        if (track == null || track.isEmpty() || track.radio() || track.durationMs() <= 0L) {
            return null;
        }
        return track;
    }

    /**
     * この後に必ず鳴ることが今わかっている曲。先読み ({@code PlaybackPrefetch}) が掴む対象で、
     * client 側 BE からも解決できる (アルバム本体・{@code albumTrack}・{@code repeat} は同期される)。
     *
     * <p>アルバムなら次トラック、単曲 repeat なら<b>今鳴っているのと同じ曲</b>。単曲 repeat の
     * 折り返しは {@link #tickServer} が {@code startPlayback(0L)} で張り直すだけなので、
     * 「次に鳴る曲」は現在の曲そのものになる。
     *
     * <p><b>アルバムの判定を先に置くこと。</b> repeat を先に見ると、アルバム + repeat が
     * 「同じトラックが次」に化けてアルバム全体のループが 1 曲ループになる
     * (repeat の意味はアルバムでは全体ループ・単曲では曲内ループで別物)。
     *
     * <p>次が無い場合は {@code null}。単曲側の内訳は「repeat していない (曲が終わって止まるだけ
     * なので先読みは無駄なストリームになる)」「custom disc でない」「ラジオ・尺ゼロ (終わりが
     * 無いので折り返しが存在しない)」。
     *
     * @return 次に鳴る custom disc の track。無ければ {@code null}
     */
    @Nullable
    public CustomTrackData nextPlaybackTrack() {
        if (albumTrack >= 0) {
            return nextAlbumTrack();
        }
        if (!repeat) {
            return null;
        }
        final CustomTrackData track = currentTrack();
        if (track == null || track.isEmpty() || track.radio() || track.durationMs() <= 0L) {
            return null;
        }
        return track;
    }

    /**
     * vanilla / 他 MOD のディスクの {@link JukeboxSong#description()}（例: "C418 - cat"）。
     * custom disc・description を持たないディスク・{@code level==null} では {@code null}。
     * {@code jukebox_song} は同期される動的レジストリなので client 側 BE からも解決できる。
     */
    @Nullable
    public Component discSongDescription() {
        return songFor(effectiveDisc()).map(Holder::value).map(JukeboxSong::description).orElse(null);
    }

    /** 無限長ストリーム (ライブ/ラジオ) の custom disc か。リピートを無効化する判定に使う。 */
    public boolean isLiveStream() {
        final CustomTrackData t = currentTrack();
        return t != null && (t.radio() || t.durationMs() <= 0L);
    }

    /** コンパレータ出力 (vanilla 同等: song の comparatorOutput)。 */
    public int getComparatorOutput() {
        if (level == null) {
            return 0;
        }
        // アルバムなら現在トラックのディスクの値 (バニラ準拠)。
        return JukeboxSong.fromStack(level.registryAccess(), effectiveDisc())
                .map(Holder::value).map(JukeboxSong::comparatorOutput).orElse(0);
    }

    /** vanilla 再生状態 (無音バケット song が鳴っているか)。particle / gameEvent と同じ土俵。 */
    public boolean isVanillaPlaying() {
        return songPlayer.isPlaying();
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
        if (paused) {
            return false;
        }
        final CustomTrackData track = currentTrack();
        if (track == null) {
            return songPlayer.isPlaying();
        }
        if (track.radio() || track.durationMs() <= 0L || (repeat && albumTrack < 0)) {
            return startMillis > 0L;
        }
        return startMillis > 0L && System.currentTimeMillis() - startMillis < track.durationMs();
    }

    /**
     * redstone 信号が立ち上がった / 落ちた tick だけ neighbor を更新する。実尺での終了は
     * {@code songPlayer} には見えず {@link #onSongChanged()} が呼ばれないので、ここが唯一の通知経路。
     * 毎 tick は撃たない。
     */
    private void refreshRedstoneSignal() {
        final boolean now = isRedstonePlaying();
        if (now == redstonePlayingLatch) {
            return;
        }
        redstonePlayingLatch = now;
        if (level != null) {
            level.updateNeighborsAt(getBlockPos(), getBlockState().getBlock());
        }
    }

    private boolean isServer() {
        return level != null && !level.isClientSide;
    }

    private Optional<Holder<JukeboxSong>> songFor(ItemStack disc) {
        return level == null ? Optional.empty() : JukeboxSong.fromStack(level.registryAccess(), disc);
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
     * 指向性の切り替え。client は再生を止めずにその場でモードを変える (毎 tick の座標の書き方を
     * 変えるだけで {@code relative} を触らないので再ストリーム不要) = 音量・範囲と同じ sync のみ。
     */
    public void setDirectional(boolean value) {
        this.directional = value;
        sync();
    }

    /** 再生/停止トグル。停止で位置保存、再開で位置から再生。 */
    public void setPaused(boolean value) {
        if (!isServer() || this.paused == value) {
            return;
        }
        this.paused = value;
        if (value) {
            final long elapsed = startMillis > 0L ? System.currentTimeMillis() - startMillis : 0L;
            final CustomTrackData track = currentTrack();
            final long dur = track != null ? track.durationMs() : 0L;
            this.pausedOffsetMs = dur > 0L ? Math.min(elapsed, dur) : elapsed;
            songPlayer.stop(level, getBlockState());
            startMillis = 0L;
            playbackStartGameTime = -1L;
            redstonePlayingLatch = false;
            broadcast(new StopDiscPayload(getBlockPos()));
        } else if (hasDisc()) {
            startPlayback(pausedOffsetMs);
            pausedOffsetMs = 0L;
        }
        sync();
    }

    /**
     * GUI シークバーからの頭出し。有限尺 (非ラジオ) の custom disc の時だけ効く。
     * 再生中は即座にその位置へ、一時停止中は resume 位置だけを更新する。
     */
    public void seekTo(long offsetMs) {
        if (!isServer() || !hasDisc() || isLiveStream()) {
            return;
        }
        final CustomTrackData track = currentTrack();
        if (track == null || track.durationMs() <= 0L) {
            return;
        }
        final long clamped = Mth.clamp(offsetMs, 0L, track.durationMs());
        if (paused) {
            pausedOffsetMs = clamped;
            sync();
        } else {
            startPlayback(clamped);
            sync();
        }
    }

    // ── 再生制御 ──

    /**
     * chunk ロード / contraption 解体・Sable 飛空艇解体で BE が復元された後の再生復帰。transient な
     * {@code startMillis} は 0 に戻るが、永続化された {@code playbackStartGameTime} から現在位置を復元して
     * 途中から再生する (頭出しにしない)。
     *
     * <p>この復元が非対称バグの核。捕獲式 (Create) の組立は MovementBehaviour が同じ
     * {@code playbackStartGameTime} 基準で offset を算出して追従するため位置が継続していたが、解体で
     * 復元された BE はここが {@code startPlayback(0L)} だったため頭出しになっていた。共通の serverTick に
     * あるので Create 捕獲式・Sable 変換式・VS2 (mod-048)・通常 chunk 再ロードの全経路を一括で直す。
     */
    private void resumePlaybackAfterLoad() {
        // 保存された再生起点なし (初回配置 or 停止済みで復元) → 頭から。
        if (playbackStartGameTime < 0L) {
            startPlayback(0L);
            return;
        }
        // ライブ / ラジオ (無限長) は位置の概念が無い → ライブ先頭へ再接続する (offset 無意味)。
        if (isLiveStream()) {
            startPlayback(0L);
            return;
        }
        final long rawElapsed = Math.max(0L, (level.getGameTime() - playbackStartGameTime) * 50L);
        final long dur = trackDurationMs();
        if (dur > 0L && rawElapsed >= dur) {
            if (albumTrack >= 0) {
                // アルバム: 現在トラックは unload 中に鳴り終わっている → 次のトラックの頭から。
                // repeat ならアルバム先頭へ戻り、そうでなければ最終トラックの後で停止する。
                advanceAlbumTrack();
            } else if (repeat) {
                startPlayback(rawElapsed % dur); // リピートはループ内の現在位置へ。
            } else {
                // 非リピートで復元前に自然終了済み → replay しない (頭出し再生を防ぐ)。
                stopPlayback();
            }
            return;
        }
        MusicDiscMaker.LOGGER.debug("Restored golden jukebox playback: resume={}ms (playbackStartGameTime={} gameTime={})",
                rawElapsed, playbackStartGameTime, level.getGameTime());
        startPlayback(rawElapsed);
    }

    private void startPlayback(long offsetMs) {
        if (!isServer() || !hasDisc()) {
            return;
        }
        final ItemStack disc = effectiveDisc();
        // 時計を先に張り直す。songPlayer.play() は onSongChanged 経由でその場で neighbor 更新を撃つので、
        // 先に songPlayer を触ると「旧 startMillis = 実尺を超過 = 信号 0」の一瞬がホッパーに見えてしまう
        // (repeat のループ境界・アルバムのトラック境界でディスクを吸い出される)。
        startMillis = System.currentTimeMillis() - offsetMs;
        playbackStartGameTime = level.getGameTime() - offsetMs / 50L;
        // vanilla 再生状態 (particle / comparator / 曲終了) + vanilla disc の実音。
        songFor(disc).ifPresent(song -> songPlayer.play(level, song));
        // custom disc は LavaPlayer ストリームを per-block 設定つきで broadcast。
        final CustomTrackData track = currentTrack();
        if (track != null) {
            broadcast(new PlayDiscPayload(getBlockPos(), track, offsetMs, rangeBlocks, volumePercent, directional));
        }
        redstonePlayingLatch = isRedstonePlaying();
        // 再生起点と現在経過を client へ反映する (progress バーの起点)。
        sync();
    }

    private void stopPlayback() {
        if (!isServer()) {
            return;
        }
        songPlayer.stop(level, getBlockState());
        startMillis = 0L;
        playbackStartGameTime = -1L;
        // ディスクが抜けると HAS_RECORD=false で ticker ごと止まる。ここで戻さないと次に入れた
        // ディスクで「変化なし」と誤判定して neighbor 更新が飛ばない。
        redstonePlayingLatch = false;
        broadcast(new StopDiscPayload(getBlockPos()));
    }

    /** ディスクスロットが変わった時 (挿入/取り出し・ホッパー・コマンド・GUI)。再生を起動/停止する。 */
    private void onDiscChanged() {
        final boolean hasDisc = hasDisc();
        // スロットの中身が変わる唯一の合流点。アルバムならトラック 0 から、そうでなければ -1 (従来動作)。
        this.albumTrack = AlbumSupport.isAlbum(items.get(SLOT_DISC)) ? 0 : -1;
        updateHasRecordState(hasDisc);
        if (isServer()) {
            pausedOffsetMs = 0L;
            if (!hasDisc) {
                // ディスク取り出しで停止状態を解除する (次に入れたディスクは自動再生される)。
                paused = false;
            }
        }
        setChanged();
        sync();
        if (!isServer()) {
            return;
        }
        if (hasDisc && !paused) {
            startPlayback(0L);
        } else {
            stopPlayback();
        }
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
    }

    /** late-join した player へ、再生中なら現在位置で PlayDiscPayload を再送する。 */
    public void resendTo(ServerPlayer player) {
        if (!isServer() || paused) {
            return;
        }
        final CustomTrackData track = currentTrack();
        if (track == null || startMillis == 0L) {
            return;
        }
        final long elapsed = Math.max(0L, System.currentTimeMillis() - startMillis);
        final long dur = track.durationMs();
        // アルバムの repeat はアルバム全体のループ (曲内ループではない)。トラック送りのたびに
        // startMillis が張り直されるので elapsed は常に「現在トラック内の位置」= 剰余を取らない。
        final boolean loopsWithinTrack = repeat && albumTrack < 0;
        if (dur > 0L && elapsed >= dur && !loopsWithinTrack) {
            return; // 自然終了済み (アルバムは次の tick で次トラックへ送られる)
        }
        final long offset = (dur > 0L && loopsWithinTrack) ? elapsed % dur : elapsed;
        Services.NETWORK.sendToPlayer(player,
                new PlayDiscPayload(getBlockPos(), track, offset, rangeBlocks, volumePercent, directional));
    }

    // ── tick (server) ──

    public static void serverTick(net.minecraft.world.level.Level level, BlockPos pos, BlockState state,
            GoldenJukeboxBlockEntity be) {
        be.tickServer();
    }

    private void tickServer() {
        if (!isServer()) {
            return;
        }
        // chunk load / contraption 解体で BE が復元された後の 1 度きり: disc があり非 pause なら
        // 保存済み再生起点から現在位置を復元して再生を復帰させる (頭出しにしない)。
        if (!initialized) {
            initialized = true;
            if (startMillis == 0L && hasDisc() && !paused) {
                resumePlaybackAfterLoad();
            }
        }
        // vanilla songPlayer: particle / gameEvent / 曲終了で停止。
        songPlayer.tick(level, getBlockState());
        // アルバム: 現在トラックが終わったら次へ送る。ラジオ復帰・repeat より先に判定する
        // (アルバムの repeat はアルバム全体のループであって 1 曲ループではないため)。
        if (albumTrack >= 0 && !paused && startMillis > 0L && currentAlbumTrackFinished()) {
            // advanceAlbumTrack -> startPlayback が同じ tick で startMillis を張り直すので、
            // 次トラックがある限り信号は落ちない (トラックの切れ目でホッパーに吸われない)。
            // 最終トラックの後だけ stopPlayback を通って落ちる。
            advanceAlbumTrack();
            refreshRedstoneSignal();
            return;
        }
        // 案B (ラジオ): 無限長ストリームは silent song の最大尺 (2h) で vanilla 再生状態が切れるが、
        // client 側の音声は独立に流れ続ける。再生状態 (コンパレータ/particle) だけを再起動して
        // 無限に維持する。client への再 broadcast はしないので音声は途切れない。
        if (startMillis > 0L && !paused && !songPlayer.isPlaying()) {
            final CustomTrackData track = currentTrack();
            if (track != null && track.radio()) {
                songFor(effectiveDisc()).ifPresent(song -> songPlayer.play(level, song));
            }
        }
        // repeat: 有限曲を曲尺でループ。アルバムの repeat はアルバム全体のループなので
        // ここでは扱わない (advanceAlbumTrack が最終トラックの後で先頭へ戻す)。
        if (repeat && albumTrack < 0 && !paused && startMillis > 0L) {
            final CustomTrackData track = currentTrack();
            if (track != null && track.durationMs() > 0L
                    && System.currentTimeMillis() - startMillis >= track.durationMs()) {
                startPlayback(0L);
            }
        }
        // 実尺での再生終了 (= 無音バケットはまだ鳴っている) を redstone へ伝える唯一の経路。
        refreshRedstoneSignal();
    }

    /**
     * アルバムの現在トラックが再生し終わったか。
     *
     * <p>アルバムの中身は {@code JUKEBOX_PLAYABLE} を持つディスクなら何でもよく、MDM の custom disc と
     * バニラディスクが混在しうる。custom disc は尺 (ms) が判っているのでそれで判定し、尺を持たない
     * バニラ / 他 MOD のディスクは AA と同じく vanilla の再生状態が切れたことで判定する。ラジオ
     * (無限長) は終わらない。
     */
    private boolean currentAlbumTrackFinished() {
        final CustomTrackData track = currentTrack();
        if (track != null && track.radio()) {
            return false;
        }
        // ここは表示用の尺 (trackDurationMs) を使わない。あちらは vanilla / 他 MOD のディスクにも
        // jukebox_song の length_in_seconds を返すが、その実音を鳴らしているのは songPlayer なので、
        // 終了判定も songPlayer に聞くのが正しい (壁時計と tick はラグでずれる)。
        final long dur = track != null ? track.durationMs() : 0L;
        if (dur > 0L) {
            return System.currentTimeMillis() - startMillis >= dur;
        }
        return !songPlayer.isPlaying();
    }

    /**
     * 次のトラックへ送る。最終トラックの後は repeat ならアルバム先頭へ戻り (S4: repeat =
     * アルバム全体のループ)、そうでなければ停止して先頭に戻す。
     */
    private void advanceAlbumTrack() {
        final int count = AlbumSupport.trackCount(items.get(SLOT_DISC));
        final int next = albumTrack + 1;
        if (count <= 0 || next >= count) {
            if (repeat && count > 0) {
                albumTrack = 0;
                startPlayback(0L);
            } else {
                albumTrack = 0;
                stopPlayback();
                sync();
            }
            return;
        }
        albumTrack = next;
        startPlayback(0L);
    }

    private void onSongChanged() {
        if (level != null) {
            level.updateNeighborsAt(getBlockPos(), getBlockState().getBlock());
        }
        setChanged();
    }

    private void broadcast(CustomPacketPayload payload) {
        if (level instanceof ServerLevel serverLevel) {
            Services.NETWORK.sendToPlayersTrackingChunk(serverLevel, new ChunkPos(getBlockPos()), payload);
        }
    }

    public void sync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
        }
    }

    // ── persistence ──

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        items.clear();
        ContainerHelper.loadAllItems(tag.getCompound("inventory"), items, registries);
        this.rangeBlocks = tag.contains("range") ? Mth.clamp(tag.getInt("range"), RANGE_MIN, RANGE_MAX) : RANGE_DEFAULT;
        this.volumePercent = tag.contains("volume") ? Mth.clamp(tag.getInt("volume"), VOLUME_MIN, VOLUME_MAX) : VOLUME_DEFAULT;
        this.repeat = tag.getBoolean("repeat");
        // 旧セーブ (この機能より前) には無いので、欠けていたら既定 = 従来の positional に倒す。
        this.directional = tag.contains("directional") ? tag.getBoolean("directional") : DIRECTIONAL_DEFAULT;
        this.paused = tag.getBoolean("paused");
        this.pausedOffsetMs = tag.getLong("pausedOffset");
        this.playbackStartGameTime = tag.contains("playbackStartGameTime")
                ? tag.getLong("playbackStartGameTime") : -1L;
        // 旧セーブ (アルバム対応より前) には無いので、欠けていたら -1 = 従来動作。
        this.albumTrack = tag.contains("albumTrack") ? tag.getInt("albumTrack") : -1;
        // sync 由来の tag だけがこのキーを持つ (ディスクの tag には無い)。受信時刻を経過表示の
        // アンカーに固定し、以降は client 自身の壁時計で進める。
        if (tag.contains(SYNC_ELAPSED_MS_KEY)) {
            this.clientElapsedAnchorMs = tag.getLong(SYNC_ELAPSED_MS_KEY);
            this.clientAnchorWallClockMs = System.currentTimeMillis();
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        final CompoundTag invTag = new CompoundTag();
        ContainerHelper.saveAllItems(invTag, items, registries);
        tag.put("inventory", invTag);
        tag.putInt("range", rangeBlocks);
        tag.putInt("volume", volumePercent);
        tag.putBoolean("repeat", repeat);
        tag.putBoolean("directional", directional);
        tag.putBoolean("paused", paused);
        tag.putLong("pausedOffset", pausedOffsetMs);
        tag.putLong("playbackStartGameTime", playbackStartGameTime);
        // playbackStartGameTime は「このトラック内の位置」なので index とセットで保存する。
        tag.putInt("albumTrack", albumTrack);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        final CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        // 経過は「送出時点の実測 ms」で運ぶ。client 側は受信時刻を起点に自分の壁時計で進める
        // (tick 由来だと TPS 低下分だけ表示が遅れる)。ディスクには書かない = sync 限定のキー。
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
        // 再生可能ディスク (vanilla + custom、いずれも JUKEBOX_PLAYABLE を持つ) と、複数ディスクを
        // 束ねたアルバム (JUKEBOX_PLAYABLE を持たない) のみ、空きスロットへ。
        return slot == SLOT_DISC && isPlayableInSlot(stack) && items.get(SLOT_DISC).isEmpty();
    }

    @Override
    public void clearContent() {
        items.clear();
    }
}
