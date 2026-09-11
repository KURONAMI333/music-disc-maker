package com.kuronami.musicdiscmaker.block;

import java.util.Optional;

import com.kuronami.musicdiscmaker.event.SpeakerNetwork;
import com.kuronami.musicdiscmaker.register.ModBlockEntities;
import com.kuronami.musicdiscmaker.speaker.SpeakerLink;

import net.minecraft.core.BlockPos;
//? if >=1.21 {
import net.minecraft.core.HolderLookup;
//?} else {
//?}
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
//? if >=1.21.2 {
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
//?} else {
//?}

/** 固定スピーカーの永続リンクと mute 状態を持つ BlockEntity。 */
public class SpeakerBlockEntity extends BlockEntity {

    public static final int VOLUME_MIN = 0;
    public static final int VOLUME_MAX = 200;
    public static final int VOLUME_DEFAULT = 100;

    private static final String SOURCE_DIMENSION = "source_dimension";
    private static final String SOURCE_POS = "source_pos";
    private static final String SOURCE_ID = "source_id";

    private SpeakerLink source;
    private int volumePercent = VOLUME_DEFAULT;

    public SpeakerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SPEAKER.get(), pos, state);
    }

    public Optional<SpeakerLink> getLinkedSource() {
        return Optional.ofNullable(source);
    }

    public boolean hasLinkedSource() {
        return source != null;
    }

    public int getVolumePercent() {
        return volumePercent;
    }

    public void setVolumePercent(int volumePercent) {
        final int clamped = Math.max(VOLUME_MIN, Math.min(VOLUME_MAX, volumePercent));
        if (this.volumePercent != clamped) {
            this.volumePercent = clamped;
            changedAndSync();
        }
    }

    /** レッドストーン信号は再生停止ではなく mute として扱う。 */
    public boolean isMuted() {
        return level != null && level.hasNeighborSignal(worldPosition);
    }

    /** SpeakerItem が Golden Jukebox を選んだ後、設置時に呼ぶ。 */
    public void linkTo(ServerLevel sourceLevel, BlockPos sourcePos) {
        final java.util.UUID identity = sourceLevel.hasChunkAt(sourcePos)
                && sourceLevel.getBlockEntity(sourcePos) instanceof GoldenJukeboxBlockEntity golden
                ? golden.sourceIdentity() : null;
        //? if >=1.21.2 {
        replaceLinkedSource(new SpeakerLink(sourceLevel.dimension().identifier().toString(), sourcePos.asLong(), identity));
        //?} else {
        /*replaceLinkedSource(new SpeakerLink(sourceLevel.dimension().location().toString(), sourcePos.asLong(), identity));
        *///?}
    }

    public void clearLink() {
        if (source == null) {
            return;
        }
        SpeakerNetwork.unregister(this);
        source = null;
        changedAndSync();
    }

    /** リンク選択または将来の音源移設側が、保存済み音源キーを差し替える入口。 */
    public void replaceLinkedSource(SpeakerLink replacement) {
        if (replacement.equals(source)) {
            return;
        }
        SpeakerNetwork.unregister(this);
        source = replacement;
        SpeakerNetwork.register(this);
        changedAndSync();
    }

    @Override
    public void setLevel(Level level) {
        if (this.level != null && this.level != level) {
            SpeakerNetwork.unregister(this);
        }
        super.setLevel(level);
        SpeakerNetwork.register(this);
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        SpeakerNetwork.register(this);
    }

    @Override
    public void setRemoved() {
        SpeakerNetwork.unregister(this);
        super.setRemoved();
    }

    private void changedAndSync() {
        setChanged();
        //? if >=1.21.2 {
        if (level != null && !level.isClientSide()) {
        //?} else {
        /*if (level != null && !level.isClientSide) {
        *///?}
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    private void restoreLoadedState(SpeakerLink restoredSource, int restoredVolume) {
        SpeakerNetwork.unregister(this);
        source = restoredSource;
        volumePercent = Math.max(VOLUME_MIN, Math.min(VOLUME_MAX, restoredVolume));
        SpeakerNetwork.register(this);
    }

    @Override
    //? if >=1.21.2 {
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        restoreLoadedState(SpeakerLink.restore(input.getStringOr(SOURCE_DIMENSION, ""), input.getLongOr(SOURCE_POS, 0L), input.getStringOr(SOURCE_ID, "")).orElse(null),
                input.getIntOr("volume", VOLUME_DEFAULT));
    }
    //?} elif >=1.21 {
    /*protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        restoreLoadedState(SpeakerLink.restore(tag.getString(SOURCE_DIMENSION), tag.getLong(SOURCE_POS), tag.getString(SOURCE_ID)).orElse(null),
                tag.contains("volume") ? tag.getInt("volume") : VOLUME_DEFAULT);
    }
    *///?} else {
    /*public void load(CompoundTag tag) {
        super.load(tag);
        restoreLoadedState(SpeakerLink.restore(tag.getString(SOURCE_DIMENSION), tag.getLong(SOURCE_POS), tag.getString(SOURCE_ID)).orElse(null),
                tag.contains("volume") ? tag.getInt("volume") : VOLUME_DEFAULT);
    }
    *///?}

    @Override
    //? if >=1.21.2 {
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (source != null) {
            output.putString(SOURCE_DIMENSION, source.dimensionId());
            output.putLong(SOURCE_POS, source.packedPos());
            if (source.sourceId() != null) output.putString(SOURCE_ID, source.sourceId().toString());
        }
        output.putInt("volume", volumePercent);
    }
    //?} elif >=1.21 {
    /*protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        saveLink(tag);
        tag.putInt("volume", volumePercent);
    }
    *///?} else {
    /*protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        saveLink(tag);
        tag.putInt("volume", volumePercent);
    }
    *///?}

    private void saveLink(CompoundTag tag) {
        if (source != null) {
            tag.putString(SOURCE_DIMENSION, source.dimensionId());
            tag.putLong(SOURCE_POS, source.packedPos());
            if (source.sourceId() != null) tag.putString(SOURCE_ID, source.sourceId().toString());
        }
    }

    @Override
    //? if >=1.21.2 {
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }
    //?} elif >=1.21 {
    /*public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        final CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }
    *///?} else {
    /*public CompoundTag getUpdateTag() {
        final CompoundTag tag = new CompoundTag();
        saveAdditional(tag);
        return tag;
    }
    *///?}

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
