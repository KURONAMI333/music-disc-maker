package com.kuronami.musicdiscmaker.event;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.network.BoomboxPlayPayload;
import com.kuronami.musicdiscmaker.network.BoomboxStopPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.register.ModDataComponents;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * ブームボックス (純アイテムの携帯プレイヤー) の再生を統括する server 側の揮発 state。
 *
 * <h2>キーはアイテム個体</h2>
 * セッションは {@code BOOMBOX_ID} component の UUID で引く。持ち主でも座標でもスロットでもない。
 * これにより「持ち替えても鳴り続ける」「同じ URL の 2 台が独立に鳴り独立に止まる」が両立する。
 *
 * <h2>tick 源は server tick の定期走査</h2>
 * バニラの {@code inventoryTick} には乗せていない。あれは「バニラがそのスロットを tick するか」に
 * 継続条件を委ねることになり、カーソルに掴んだスタックのような境界例を原理的に扱えない
 * ({@link BoomboxCarry} の javadoc)。代わりに {@link #SCAN_INTERVAL_TICKS} ごとにオンラインの
 * player を走査し、{@link BoomboxCarry#carried} が返す集合だけを「鳴ってよい場所」とする。
 *
 * <p>走査の costs: player あたり 37 スロットの参照比較を 0.5 秒に 1 回。空スロットは即 return する。
 *
 * <h2>停止の検出</h2>
 * 走査で見つからなかったセッションが停止対象 = 落とした / チェストへ入れた / 死亡ドロップ /
 * ログアウト。最大 {@link #SCAN_INTERVAL_TICKS} tick (0.5 秒) の遅れで停止 packet が飛ぶ。
 * client 側の keep-alive タイムアウト (3 秒) はその二重の歯止め。
 */
public final class BoomboxPlayback {

    /** keep-alive を撃つ間隔 (ms)。client のタイムアウト ({@code BoomboxAnchor}) はこれより十分長い。 */
    public static final long HEARTBEAT_MS = 1_000L;

    /**
     * 走査の間隔 (tick)。keep-alive 間隔より短くしておかないと、送信判定の粒度が走査に律速されて
     * 実効間隔が倍近くまで伸びる。
     */
    public static final int SCAN_INTERVAL_TICKS = 10;

    /** 再生セッション。曲が変わったかの判定と、現在位置の算出に使う。 */
    private static final class Session {
        private final UUID owner;
        private final String url;
        private final long startMillis;
        private long lastSentMillis;

        Session(UUID owner, String url, long startMillis) {
            this.owner = owner;
            this.url = url;
            this.startMillis = startMillis;
            this.lastSentMillis = startMillis;
        }
    }

    /** アイテム個体 UUID → セッション。 */
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private static int tickCounter;

    private BoomboxPlayback() {
    }

    // ── 問い合わせ ──────────────────────────────────────────────────────

    /**
     * このスタックが実際に鳴っているか。<b>判定の正本はセッションであって component ではない。</b>
     * component の {@code BOOMBOX_PLAYING} は表示用のキャッシュで、落としたアイテムに残りうる。
     */
    public static boolean isPlaying(ItemStack stack) {
        final UUID id = stack.get(ModDataComponents.BOOMBOX_ID.get());
        return id != null && SESSIONS.containsKey(id);
    }

    /**
     * このスタックの個体識別子を返す (未採番なら採番する)。
     *
     * <p>クリエイティブの複製・{@code /give}・NBT 直書きで同じ UUID のスタックが 2 個できうる。
     * 同一インベントリ内に重複を見つけたら振り直す — 放置すると 2 台が 1 つのセッションを奪い合い、
     * 片方を止めるともう片方も黙る。
     */
    public static UUID identify(ServerPlayer player, ItemStack stack) {
        final UUID existing = stack.get(ModDataComponents.BOOMBOX_ID.get());
        if (existing != null && !isDuplicated(player, stack, existing)) {
            return existing;
        }
        final UUID minted = UUID.randomUUID();
        stack.set(ModDataComponents.BOOMBOX_ID.get(), minted);
        return minted;
    }

    private static boolean isDuplicated(ServerPlayer player, ItemStack stack, UUID id) {
        for (final ItemStack other : BoomboxCarry.carried(player)) {
            if (other != stack && id.equals(other.get(ModDataComponents.BOOMBOX_ID.get()))) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private static CustomTrackData trackOf(ItemStack stack) {
        final BoomboxContents contents = stack.get(ModDataComponents.BOOMBOX_CONTENTS.get());
        if (contents == null || !contents.hasDisc()) {
            return null;
        }
        final CustomTrackData track = contents.disc().get(ModDataComponents.CUSTOM_TRACK.get());
        return track != null && !track.isEmpty() ? track : null;
    }

    private static BoomboxContents contentsOf(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.BOOMBOX_CONTENTS.get(), BoomboxContents.EMPTY);
    }

    // ── 操作 ────────────────────────────────────────────────────────────

    /**
     * 再生 / 停止のトグル (右クリック)。鳴らせない (ディスク無し / custom disc でない) ときは
     * {@code false} を返し、呼び出し側がその旨を出す。
     */
    public static boolean toggle(ServerPlayer player, ItemStack stack) {
        final UUID id = identify(player, stack);
        if (SESSIONS.containsKey(id)) {
            stop(player, stack, id);
            return true;
        }
        final CustomTrackData track = trackOf(stack);
        if (track == null) {
            return false;
        }
        start(player, stack, id, track);
        return true;
    }

    /** GUI からの音量・指向性の適用。鳴っていれば鳴らし直さずに即時反映する。 */
    public static void applyConfig(ServerPlayer player, UUID boomboxId, int volumePercent,
            boolean directional) {
        final ItemStack stack = BoomboxCarry.find(player, boomboxId);
        if (stack == null) {
            return;
        }
        final BoomboxContents updated = contentsOf(stack).withConfig(volumePercent, directional);
        stack.set(ModDataComponents.BOOMBOX_CONTENTS.get(), updated);
        final Session session = SESSIONS.get(boomboxId);
        final CustomTrackData track = trackOf(stack);
        if (session == null || track == null) {
            return;
        }
        // 次の定期 keep-alive (最大 0.5 秒後) を待たずに、いまの位置のまま値だけ届ける。
        final long now = System.currentTimeMillis();
        session.lastSentMillis = now;
        send(player, boomboxId, track, offsetFor(track, session, now), updated);
    }

    private static void start(ServerPlayer player, ItemStack stack, UUID id, CustomTrackData track) {
        final long now = System.currentTimeMillis();
        SESSIONS.put(id, new Session(player.getUUID(), track.url(), now));
        stack.set(ModDataComponents.BOOMBOX_PLAYING.get(), Boolean.TRUE);
        send(player, id, track, 0L, contentsOf(stack));
    }

    /** 再生を止めて即時停止 packet を撃つ。 */
    public static void stop(ServerPlayer player, ItemStack stack, UUID id) {
        SESSIONS.remove(id);
        stack.remove(ModDataComponents.BOOMBOX_PLAYING.get());
        Services.NETWORK.sendToPlayersTrackingEntityAndSelf(player, new BoomboxStopPayload(id));
    }

    // ── 定期走査 ────────────────────────────────────────────────────────

    /** server tick から毎 tick 呼ばれる。実作業は {@link #SCAN_INTERVAL_TICKS} tick に 1 回。 */
    public static void tick(MinecraftServer server) {
        if (++tickCounter < SCAN_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        scan(server.getPlayerList().getPlayers(), System.currentTimeMillis());
    }

    /**
     * 走査の本体。player 集合を引数に取るのは、GameTest の mock プレイヤーが
     * {@code PlayerList} に載らないため (載らない集合を走査すると、テストが何も検証しない)。
     * 実運用の呼び出しはオンライン全員を渡す。
     */
    public static void scan(Collection<ServerPlayer> players, long now) {
        final Set<UUID> alive = new HashSet<>();
        // 走査中に stop() が SESSIONS を触るので、player ごとに対象を取り切ってから処理する。
        for (final ServerPlayer player : new ArrayList<>(players)) {
            for (final ItemStack stack : BoomboxCarry.carried(player)) {
                serve(player, stack, alive, now);
            }
        }
        sweep(players, alive);
    }

    /** 走査で見つけた 1 スタックを処理する。 */
    private static void serve(ServerPlayer player, ItemStack stack, Set<UUID> alive, long now) {
        final UUID id = stack.get(ModDataComponents.BOOMBOX_ID.get());
        final Session session = id == null ? null : SESSIONS.get(id);
        if (session == null) {
            // 鳴っていないのにフラグが立っている = 鳴っていたブームボックスを落として拾い直した等。
            // component は表示用キャッシュなので、ここで実態へ揃える (拾った瞬間に鳴り出さない)。
            stack.remove(ModDataComponents.BOOMBOX_PLAYING.get());
            return;
        }
        alive.add(id);
        final CustomTrackData track = trackOf(stack);
        if (track == null) {
            stop(player, stack, id); // ディスクを抜かれた
            return;
        }
        final long elapsed = Math.max(0L, now - session.startMillis);
        final BoomboxHeartbeat.Action action = BoomboxHeartbeat.decide(true,
                !session.url.equals(track.url()), elapsed, track.durationMs(),
                now - session.lastSentMillis, HEARTBEAT_MS);
        switch (action) {
            case START -> {
                SESSIONS.put(id, new Session(player.getUUID(), track.url(), now));
                send(player, id, track, 0L, contentsOf(stack));
            }
            case KEEP_ALIVE -> {
                session.lastSentMillis = now;
                send(player, id, track, offsetFor(track, session, now), contentsOf(stack));
            }
            case END -> stop(player, stack, id); // 自然終了 (携帯プレイヤーはリピートしない)
            case IDLE -> {
                // 何もしない
            }
        }
    }

    /** 走査で見つからなかったセッション = インベントリの外へ出た / 持ち主がログアウトした。 */
    private static void sweep(Collection<ServerPlayer> players, Set<UUID> alive) {
        final Iterator<Map.Entry<UUID, Session>> it = SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<UUID, Session> entry = it.next();
            if (alive.contains(entry.getKey())) {
                continue;
            }
            it.remove();
            // ログアウト済みなら宛先が無い。周囲の client は keep-alive 途絶で自己停止する。
            final ServerPlayer owner = find(players, entry.getValue().owner);
            if (owner != null) {
                Services.NETWORK.sendToPlayersTrackingEntityAndSelf(
                        owner, new BoomboxStopPayload(entry.getKey()));
            }
        }
    }

    @Nullable
    private static ServerPlayer find(Collection<ServerPlayer> players, UUID uuid) {
        for (final ServerPlayer player : players) {
            if (player.getUUID().equals(uuid)) {
                return player;
            }
        }
        return null;
    }

    private static long offsetFor(CustomTrackData track, Session session, long now) {
        final boolean live = track.radio() || track.durationMs() <= 0L;
        return BoomboxHeartbeat.offsetFor(live, now - session.startMillis);
    }

    private static void send(ServerPlayer player, UUID id, CustomTrackData track, long offsetMs,
            BoomboxContents contents) {
        Services.NETWORK.sendToPlayersTrackingEntityAndSelf(player, new BoomboxPlayPayload(
                id, player.getId(), track, offsetMs, contents.volumePercent(), contents.directional()));
    }

    /** server 停止でセッションを破棄する (シングルプレイのワールド退出含む)。 */
    public static void clear() {
        SESSIONS.clear();
        tickCounter = 0;
    }

    /** テスト用: 生きているセッション数。 */
    public static int activeSessionCount() {
        return SESSIONS.size();
    }
}
