package com.kuronami.musicdiscmaker.compat.create;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.item.CustomMusicDiscItem;
import com.kuronami.musicdiscmaker.network.ContraptionPlayDiscPayload;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.register.ModItems;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;

import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;

/**
 * MDM 音源ブロック (強化版ジュークボックス) が Create 捕獲式 contraption に組み込まれた時、凍結された
 * BlockEntity データから再生中の custom disc を読み取り、contraption を追跡中の client へ
 * {@link ContraptionPlayDiscPayload} を送る。client は {@link ContraptionAnchor} で音源を追従再生する。
 *
 * <p>捕獲式では組立時に原ブロックが world から抜かれ AIR 化されるため、固定 BlockPos に張り付いた
 * 従来の {@code StaticAnchor} 再生は自己消音する。この behaviour が contraption 追従の再生へ引き継ぐ。
 * server 主導 (client では tick を要求しない)。
 *
 * <p><b>ライフサイクル注意 (実機クラッシュから学んだ)</b>: Create の {@code startMoving} は
 * contraption entity が spawn される<b>前</b> (assemble 中: {@code Contraption.startMoving} ←
 * {@code BearingContraption.assemble}) に呼ばれるため、{@code context.contraption.entity} は
 * <b>null</b>。よって entity 参照・payload 送出は {@link #tick} へ遅延し、entity が非 null になった最初の
 * server tick で1回だけ送る (per-actor マーカー {@link #SENT} を {@code context.temporaryData} に置く。
 * この behaviour は全 actor 共有のシングルトンなので instance フィールドに状態を持てない)。
 * {@code tick} は {@code AbstractContraptionEntity.tickActors} 経由で毎 tick 呼ばれ、その時点では
 * entity は spawn 済み。Create 6.0.8 実 jar でライフサイクル同一を javap 確認済み。
 */
public final class CreateAudioMovementBehaviour implements MovementBehaviour {

    /** {@code context.temporaryData} に置く per-actor 送信済みマーカー ({@code ==} 比較)。 */
    private static final Object SENT = new Object();

    @Override
    public void tick(MovementContext context) {
        // server 側で一度だけ送る。entity が揃うのを待つ (startMoving は spawn 前で entity=null)。
        if (context.world == null || context.world.isClientSide()) {
            return;
        }
        if (context.temporaryData == SENT) {
            return; // この actor は送信済み。
        }
        if (context.contraption == null || context.contraption.entity == null) {
            return; // entity 未 spawn。次 tick を待つ (assemble 直後の 1 tick 窓)。
        }
        // entity が揃った最初の server tick: track の有無に関わらず一度だけ処理する (毎 tick の再走を防ぐ)。
        context.temporaryData = SENT;

        final CompoundTag beData = context.blockEntityData;
        if (beData == null || beData.isEmpty()) {
            return;
        }
        final CustomTrackData track = readTrack(beData);
        if (track == null || track.isEmpty()) {
            return; // ディスク無し / vanilla ディスク / 空 track
        }
        final long offsetMs = readOffsetMs(context, beData, track);
        final int range = beData.contains("range") ? beData.getInt("range") : GoldenJukeboxBlockEntity.RANGE_DEFAULT;
        final int volume = beData.contains("volume") ? beData.getInt("volume") : GoldenJukeboxBlockEntity.VOLUME_DEFAULT;

        final int entityId = context.contraption.entity.getId();
        final ContraptionPlayDiscPayload payload = new ContraptionPlayDiscPayload(
                entityId, context.localPos, track, offsetMs, range, volume);
        Services.NETWORK.sendToPlayersTrackingEntity(context.contraption.entity, payload);
        // 診断 (debug 既定 off): kura 実機で「追従しない」時、この行が出て CreateAudioClient.play の
        // 受信ログが出なければ tracker 未確立/配送を疑う (この時点なら entity は tracker を持つはず)。
        MusicDiscMaker.LOGGER.debug("Create contraption 再生を送信: entityId={} localPos={} offset={}ms",
                entityId, context.localPos, offsetMs);
    }

    /**
     * 凍結 BE の "inventory" から disc を復元し custom track を取り出す。custom disc でなければ null。
     * 1.20.1 の {@code loadAllItems} は registryAccess を取らない (NBT ベースの item 復元)。
     */
    private static CustomTrackData readTrack(CompoundTag beData) {
        if (!beData.contains("inventory")) {
            return null;
        }
        final NonNullList<ItemStack> items = NonNullList.withSize(1, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(beData.getCompound("inventory"), items);
        final ItemStack disc = items.get(GoldenJukeboxBlockEntity.SLOT_DISC);
        if (disc.is(ModItems.CUSTOM_MUSIC_DISC.get()) && CustomMusicDiscItem.hasTrack(disc)) {
            final CustomTrackData track = CustomMusicDiscItem.getTrack(disc);
            return track != null && !track.isEmpty() ? track : null;
        }
        return null;
    }

    /**
     * 凍結時点の再生経過 (ms)。{@link GoldenJukeboxBlockEntity#currentElapsedMs()} と同じ計算を凍結 NBT
     * から行う: 一時停止中は保存 offset、再生中は (現 gameTime - playbackStartGameTime)×50 を尺でクランプ。
     */
    private static long readOffsetMs(MovementContext context, CompoundTag beData, CustomTrackData track) {
        if (beData.getBoolean("paused")) {
            return Math.max(0L, beData.getLong("pausedOffset"));
        }
        final long startGameTime = beData.contains("playbackStartGameTime")
                ? beData.getLong("playbackStartGameTime") : -1L;
        if (startGameTime < 0L) {
            return 0L;
        }
        long elapsed = Math.max(0L, (context.world.getGameTime() - startGameTime) * 50L);
        final long dur = track.durationMs();
        if (dur > 0L) {
            elapsed = Math.min(elapsed, dur);
        }
        return elapsed;
    }
}
