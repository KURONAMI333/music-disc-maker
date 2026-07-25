package com.kuronami.musicdiscmaker.event;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.network.BoomboxPlayPayload;
import com.kuronami.musicdiscmaker.network.BoomboxStopPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.register.ModDataComponents;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 手持ちブームボックスの再生を統括する server 側の揮発 state。
 *
 * <p>tick 源は {@code BoomboxBlockItem#inventoryTick} = そのアイテムがプレイヤーのインベントリに
 * ある間だけ毎 tick 呼ばれるバニラの hook。専用の server tick フックを増やさずに済むうえ、
 * 「地面に落ちた / チェストに入った」は tick が来なくなることで自然に検出できる (client 側の
 * keep-alive タイムアウトが拾う)。
 *
 * <p>1 プレイヤーにつき同時 1 台 (client の再生 key が entityId のため)。トグル ON のときに他の
 * ブームボックスの再生フラグを落として担保する。
 */
public final class BoomboxPlayback {

    /** keep-alive を撃つ間隔 (tick)。client のタイムアウトはこれより十分長くする。 */
    public static final int HEARTBEAT_TICKS = 20;

    /** 手持ち再生セッション。曲が変わったかの判定と、現在位置の算出に使う。 */
    private record Session(String url, long startMillis) {
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private BoomboxPlayback() {
    }

    /** 再生フラグの読み出し。 */
    public static boolean isPlaying(ItemStack stack) {
        return Boolean.TRUE.equals(stack.get(ModDataComponents.BOOMBOX_PLAYING.get()));
    }

    /** 手 (メイン/オフ) に持っているスタックか。インベントリの奥で鳴らないための判定。 */
    public static boolean isHeld(ServerPlayer player, ItemStack stack) {
        return player.getMainHandItem() == stack || player.getOffhandItem() == stack;
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

    /**
     * 再生/停止のトグル (シフト右クリック)。鳴らせない (ディスク無し / custom disc でない) ときは
     * {@code false} を返し、呼び出し側がその旨を出す。
     */
    public static boolean toggle(ServerPlayer player, ItemStack stack) {
        if (isPlaying(stack)) {
            stop(player, stack);
            return true;
        }
        if (trackOf(stack) == null) {
            return false;
        }
        // 1 プレイヤー 1 台。別のブームボックスが鳴っていたら降ろす。
        for (final ItemStack other : player.getInventory().items) {
            if (other != stack && isPlaying(other)) {
                other.remove(ModDataComponents.BOOMBOX_PLAYING.get());
            }
        }
        for (final ItemStack other : player.getInventory().offhand) {
            if (other != stack && isPlaying(other)) {
                other.remove(ModDataComponents.BOOMBOX_PLAYING.get());
            }
        }
        SESSIONS.remove(player.getUUID());
        stack.set(ModDataComponents.BOOMBOX_PLAYING.get(), Boolean.TRUE);
        // 開始 packet は次の inventoryTick が出す (セッション未登録 = 開始扱い)。
        return true;
    }

    /** 再生フラグを落として即時停止 packet を撃つ。 */
    public static void stop(ServerPlayer player, ItemStack stack) {
        stack.remove(ModDataComponents.BOOMBOX_PLAYING.get());
        SESSIONS.remove(player.getUUID());
        Services.NETWORK.sendToPlayersTrackingEntityAndSelf(player, new BoomboxStopPayload(player.getId()));
    }

    /**
     * {@code inventoryTick} からの心拍。開始・keep-alive・自然終了・手から離した の全部をここで捌く。
     *
     * <p>宛先は {@code sendToPlayersTrackingEntityAndSelf}。素の「追跡中の player」は本人を含まない
     * ので、持ち主にだけ聞こえない状態になる。
     */
    public static void heartbeat(ServerPlayer player, ItemStack stack) {
        if (!isPlaying(stack)) {
            return;
        }
        if (!isHeld(player, stack)) {
            stop(player, stack); // しまった = 止める (落とした/チェストへ移した場合は tick 自体が来ない)
            return;
        }
        final CustomTrackData track = trackOf(stack);
        if (track == null) {
            stop(player, stack); // ディスクを抜かれた
            return;
        }
        final BoomboxContents contents = stack.getOrDefault(
                ModDataComponents.BOOMBOX_CONTENTS.get(), BoomboxContents.EMPTY);
        final UUID id = player.getUUID();
        final Session session = SESSIONS.get(id);
        if (session == null || !session.url().equals(track.url())) {
            SESSIONS.put(id, new Session(track.url(), System.currentTimeMillis()));
            send(player, track, 0L, contents);
            return;
        }
        final long elapsed = Math.max(0L, System.currentTimeMillis() - session.startMillis());
        final long duration = track.durationMs();
        if (duration > 0L && elapsed >= duration) {
            stop(player, stack); // 自然終了 (手持ちはリピートしない)
            return;
        }
        if (player.level().getGameTime() % HEARTBEAT_TICKS == 0L) {
            send(player, track, elapsed, contents);
        }
    }

    private static void send(ServerPlayer player, CustomTrackData track, long offsetMs,
            BoomboxContents contents) {
        Services.NETWORK.sendToPlayersTrackingEntityAndSelf(player,
                new BoomboxPlayPayload(player.getId(), track, offsetMs,
                        contents.rangeBlocks(), contents.volumePercent(), contents.directional()));
    }

    /** server 停止でセッションを破棄する (シングルプレイのワールド退出含む)。 */
    public static void clear() {
        SESSIONS.clear();
    }
}
