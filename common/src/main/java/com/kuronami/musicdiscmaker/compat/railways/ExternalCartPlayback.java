package com.kuronami.musicdiscmaker.compat.railways;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.audio.local.LocalAudioServer;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.GoldenDeck;
import com.kuronami.musicdiscmaker.component.MediaSequenceResolver;
import com.kuronami.musicdiscmaker.event.GoldenCartPlayback;
import com.kuronami.musicdiscmaker.network.GoldenCartPlayPayload;
import com.kuronami.musicdiscmaker.network.GoldenCartStopPayload;
import com.kuronami.musicdiscmaker.network.PlaybackSourceStamp;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

/**
 * server 側: 他 MOD のジュークボックス付きトロッコ (Create: Steam 'n' Rails) に入った MDM の盤を、
 * 金ジューク付きトロッコと同じ client 経路 ({@link GoldenCartPlayPayload} の keep-alive) で鳴らす。
 *
 * <p>相手のトロッコは盤の SoundEvent / jukebox song を client で鳴らすだけなので、MDM の盤 (無音) では
 * 何も聞こえない。ここは毎 tick「いま入っている盤」を見て、MDM の盤なら再生 / keep-alive、外れたら停止を送る。
 * 金ジューク付きトロッコと違い操作 (一時停止・音量・範囲) は持たず、値は {@link GoldenDeck} の既定値。
 * 曲は一度鳴らし切ったら止まる (相手のトロッコのバニラ盤と同じ)。
 *
 * <p>再生位置は tick 基準 (MDM_DECISIONS D4)。音源の識別子は entity の UUID、世代は鳴らし始めた
 * game time なので、読み込み直しや次元移動で entity が作り直されても新しい世代として届く。
 */
public final class ExternalCartPlayback {

    private ExternalCartPlayback() {
    }

    /** トロッコごとの再生状態。entity に mixin で持たせる。全操作は server thread から。 */
    public static final class State {
        @Nullable
        private String sourceKey;
        private long generation;
        private long startGameTime;
        @Nullable
        private String resumeKey;
        private long resumeOffsetMs;

        /** 保存用: いま鳴らしている曲の鍵 (鳴らしていなければ null)。 */
        @Nullable
        public String savedKey() {
            return sourceKey != null ? sourceKey : resumeKey;
        }

        /** 保存用: いまの再生位置 (ms)。 */
        public long savedOffsetMs(Entity cart) {
            if (sourceKey == null) {
                return resumeOffsetMs;
            }
            return Math.max(0L, cart.level().getGameTime() - startGameTime) * 50L;
        }

        /** 読み込み: 次の tick で同じ曲が入っていれば、この位置から鳴らし直す。 */
        public void restore(@Nullable String key, long offsetMs) {
            this.resumeKey = key == null || key.isEmpty() ? null : key;
            this.resumeOffsetMs = Math.max(0L, offsetMs);
        }
    }

    /** 毎 tick (server)。入っている盤を見て、開始・keep-alive・停止を送る。 */
    public static void tick(Entity cart, ServerLevel level, ItemStack disc, State state) {
        if (cart.isRemoved()) {
            return;
        }
        final CustomTrackData track = trackOf(disc);
        if (track == null) {
            if (state.sourceKey != null) {
                broadcastStop(cart, level, state);
                state.sourceKey = null;
            }
            state.resumeKey = null;
            return;
        }
        final String key = track.sourceKey();
        final long now = level.getGameTime();
        if (!key.equals(state.sourceKey)) {
            if (state.sourceKey != null) {
                broadcastStop(cart, level, state);
            }
            final long resume = key.equals(state.resumeKey) ? state.resumeOffsetMs : 0L;
            state.resumeKey = null;
            state.resumeOffsetMs = 0L;
            state.sourceKey = key;
            state.generation = Math.max(state.generation + 1L, now);
            state.startGameTime = now - resume / 50L;
            if (!finished(track, resume)) {
                broadcastPlay(cart, level, state, track);
            }
            return;
        }
        if (cart.tickCount % GoldenCartPlayback.KEEPALIVE_INTERVAL_TICKS != 0) {
            return;
        }
        final long offset = Math.max(0L, now - state.startGameTime) * 50L;
        if (!finished(track, offset)) {
            broadcastPlay(cart, level, state, track); // keep-alive + 範囲へ入り直した player への再送
        }
    }

    /** MDM の custom disc ならその曲。他の盤・空・曲の無い盤は null。 */
    @Nullable
    static CustomTrackData trackOf(ItemStack disc) {
        if (disc == null || disc.isEmpty() || !disc.is(ModItems.CUSTOM_MUSIC_DISC.get())) {
            return null;
        }
        final List<MediaSequenceResolver.ResolvedTrack> tracks = MediaSequenceResolver.resolve(disc).tracks();
        if (tracks.isEmpty()) {
            return null;
        }
        final CustomTrackData track = tracks.get(0).track();
        return track == null || track.isEmpty() ? null : track;
    }

    /** 尺を使い切ったか。尺不明・ラジオには終端が無い。 */
    private static boolean finished(CustomTrackData track, long offsetMs) {
        return !track.radio() && track.durationMs() > 0L && offsetMs >= track.durationMs();
    }

    private static PlaybackSourceStamp identity(Entity cart, State state) {
        return new PlaybackSourceStamp(cart.getUUID(), state.generation);
    }

    private static void broadcastPlay(Entity cart, ServerLevel level, State state, CustomTrackData track) {
        final long offset = Math.max(0L, level.getGameTime() - state.startGameTime) * 50L;
        final GoldenCartPlayPayload payload = new GoldenCartPlayPayload(cart.getId(), track, null, offset,
                GoldenDeck.RANGE_DEFAULT, GoldenDeck.VOLUME_DEFAULT, GoldenDeck.DIRECTIONAL_DEFAULT,
                identity(cart, state));
        final double rangeSq = (double) GoldenDeck.RANGE_DEFAULT * GoldenDeck.RANGE_DEFAULT;
        for (final ServerPlayer player : level.players()) {
            if (player.distanceToSqr(cart) <= rangeSq) {
                LocalAudioServer.observe(player, payload);
                Services.NETWORK.sendToPlayer(player, payload);
            }
        }
    }

    /** 停止は距離で絞らない (受信後に範囲外へ出た player の lease も畳む。金トロッコと同じ)。 */
    private static void broadcastStop(Entity cart, ServerLevel level, State state) {
        final GoldenCartStopPayload payload = new GoldenCartStopPayload(cart.getId(), identity(cart, state));
        for (final ServerPlayer player : level.players()) {
            Services.NETWORK.sendToPlayer(player, payload);
        }
    }
}
