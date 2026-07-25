package com.kuronami.musicdiscmaker.block;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.event.SpeakerNetwork;
import com.kuronami.musicdiscmaker.register.ModBlockEntities;
import com.kuronami.musicdiscmaker.register.ModDataComponents;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * スピーカーの BlockEntity。音源 (強化版ジュークボックス) への片方向参照と、スピーカーごとの
 * 音量・可聴範囲を持つ。
 *
 * <p>リンクの正本はこの BE の NBT ({@code sourcePos})。server 側の逆引き ({@link SpeakerNetwork}) は
 * ロード中のスピーカーだけを持つ揮発 index で、初回 server tick ({@link #serverTick}) の自己登録と
 * {@link #setRemoved()} の解除で構築する。双方向を両方永続化すると片方が壊れた時の復旧経路が
 * 無くなるため、永続は片側だけに限定する。
 *
 * <p>ミュート状態は {@link SpeakerBlock#POWERED} から導出し NBT には持たない (レッドストーン信号 =
 * ミュート)。
 */
public class SpeakerBlockEntity extends BlockEntity {

    public static final int RANGE_MIN = 16;
    public static final int RANGE_MAX = 256;
    public static final int RANGE_DEFAULT = 32;
    public static final int VOLUME_MIN = 0;
    public static final int VOLUME_MAX = 200;
    public static final int VOLUME_DEFAULT = 100;

    /** 音源 (強化版ジュークボックス) の絶対座標。同一 level 前提。null = 未リンク。 */
    @Nullable
    private BlockPos sourcePos;
    private int rangeBlocks = RANGE_DEFAULT;
    private int volumePercent = VOLUME_DEFAULT;

    /** {@link SpeakerNetwork} へ登録済みの音源。null = 未登録。解除に使う (server 揮発)。 */
    @Nullable
    private BlockPos registeredSource;
    /** 初回 server tick で chunk ロード後の自己登録を 1 度だけ行うためのフラグ。 */
    private boolean initialized;

    public SpeakerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SPEAKER.get(), pos, state);
    }

    // ── アクセサ ──

    @Nullable
    public BlockPos getSourcePos() {
        return sourcePos;
    }

    public int getRangeBlocks() {
        return rangeBlocks;
    }

    public int getVolumePercent() {
        return volumePercent;
    }

    /** レッドストーン信号でミュートされているか (信号あり = 黙る)。 */
    public boolean isMuted() {
        final BlockState state = getBlockState();
        return state.hasProperty(SpeakerBlock.POWERED) && state.getValue(SpeakerBlock.POWERED);
    }

    // ── リンク ──

    /**
     * 音源を設定する (server)。旧リンクを解除し、新リンクを {@link SpeakerNetwork} へ登録して
     * 音源へスピーカー集合の再送を促す。{@code null} でリンク解除。
     */
    public void setSourcePos(@Nullable BlockPos value) {
        unregisterLink();
        this.sourcePos = value == null ? null : value.immutable();
        setChanged();
        registerLink();
    }

    /**
     * GUI からの音量・可聴範囲の適用。値が実際に変わった時だけ {@code true} を返す
     * (呼び出し側は変化時だけスピーカー集合を配り直す = スライダードラッグでの packet 増幅を防ぐ)。
     */
    public boolean applyConfig(int volume, int range) {
        final int newVolume = Mth.clamp(volume, VOLUME_MIN, VOLUME_MAX);
        final int newRange = Mth.clamp(range, RANGE_MIN, RANGE_MAX);
        if (newVolume == volumePercent && newRange == rangeBlocks) {
            return false;
        }
        this.volumePercent = newVolume;
        this.rangeBlocks = newRange;
        sync();
        return true;
    }

    private void registerLink() {
        if (level instanceof ServerLevel serverLevel && sourcePos != null && registeredSource == null) {
            registeredSource = sourcePos;
            SpeakerNetwork.register(serverLevel, getBlockPos(), registeredSource);
            SpeakerNetwork.notifySource(serverLevel, registeredSource);
            // 集合を配るだけでは client の再生は始まらない。ここにいる player へ現在の曲も届ける。
            SpeakerNetwork.sendCurrentPlaybackTo(serverLevel, registeredSource, getBlockPos());
        }
    }

    private void unregisterLink() {
        if (level instanceof ServerLevel serverLevel && registeredSource != null) {
            final BlockPos previous = registeredSource;
            registeredSource = null;
            // 解除より先に通知する。宛先は index から算出するので、先に外すとこのスピーカーの chunk が
            // 宛先から落ちて、傍にいる player に「消えた」ことが伝わらない。内容の方は既に正しい
            // (破壊経路では LevelChunk#removeBlockEntity が map から外してから setRemoved を呼ぶので、
            //  activeEntries の getBlockEntity が null を返してこのスピーカーは載らない)。
            SpeakerNetwork.notifySource(serverLevel, previous);
            SpeakerNetwork.unregister(serverLevel, getBlockPos(), previous);
        } else {
            registeredSource = null;
        }
    }

    /** レッドストーン信号の変化・設定変更で、音源に client のスピーカー集合を更新させる。 */
    public void notifySourceChanged() {
        if (level instanceof ServerLevel serverLevel && sourcePos != null) {
            SpeakerNetwork.notifySource(serverLevel, sourcePos);
        }
    }

    // ── ライフサイクル ──

    /**
     * chunk ロード経路の自己登録。vanilla の {@code BlockEntity} には loader 非依存の「ロード完了」
     * フックが無い ({@code onLoad} は NeoForge 拡張) ため、強化版ジュークボックスと同じく初回 server
     * tick で 1 度だけ行う。新規設置は component 適用より前に BE が生えるので、この経路では登録されず
     * {@code SpeakerBlock#setPlacedBy} の検証後に登録される。
     */
    public static void serverTick(net.minecraft.world.level.Level level, BlockPos pos, BlockState state,
            SpeakerBlockEntity be) {
        if (be.initialized) {
            return;
        }
        be.initialized = true;
        be.registerLink();
    }

    @Override
    public void setRemoved() {
        unregisterLink();
        super.setRemoved();
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
        this.sourcePos = tag.contains("sourcePos") ? BlockPos.of(tag.getLong("sourcePos")) : null;
        this.rangeBlocks = tag.contains("range") ? Mth.clamp(tag.getInt("range"), RANGE_MIN, RANGE_MAX) : RANGE_DEFAULT;
        this.volumePercent = tag.contains("volume")
                ? Mth.clamp(tag.getInt("volume"), VOLUME_MIN, VOLUME_MAX) : VOLUME_DEFAULT;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (sourcePos != null) {
            tag.putLong("sourcePos", sourcePos.asLong());
        }
        tag.putInt("range", rangeBlocks);
        tag.putInt("volume", volumePercent);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        final CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ── item ⇔ BE の component 往復 (リンクをブロックアイテムに載せる) ──

    /**
     * ブロックアイテムからリンクを取り込む。{@code BlockItem#place} が {@code setPlacedBy} より前に
     * 呼ぶので、設置時の検証 ({@link SpeakerBlock#setPlacedBy}) はここで入った値を見て判定できる。
     * dimension が一致しない参照は捨てる (リンクは同一 level 限定)。
     */
    @Override
    protected void applyImplicitComponents(BlockEntity.DataComponentInput input) {
        super.applyImplicitComponents(input);
        final GlobalPos link = input.get(ModDataComponents.SPEAKER_SOURCE.get());
        if (link != null && level != null && link.dimension().equals(level.dimension())) {
            this.sourcePos = link.pos();
        }
    }

    /** 破壊ドロップ (loot table の {@code copy_components}) と pick-block にリンクを載せる。 */
    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (sourcePos != null && level != null) {
            components.set(ModDataComponents.SPEAKER_SOURCE.get(),
                    GlobalPos.of(level.dimension(), sourcePos));
        }
    }
}
