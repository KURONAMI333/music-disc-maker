package com.kuronami.musicdiscmaker.client.audio;

import java.util.Objects;
import java.util.function.Function;

import com.kuronami.musicdiscmaker.Config;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import com.kuronami.musicdiscmaker.network.SpeakerEntry;
import com.kuronami.musicdiscmaker.network.SpeakerSetPayload;
import com.kuronami.musicdiscmaker.speaker.SpeakerCone;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** One independently audible linked speaker.  It never competes with the Golden source. */
final class SpeakerEmitterAnchor implements DiscAnchor, LiveAudioConfig {
    private final DiscAnchor source;
    private volatile SpeakerEntry entry;
    private volatile SpeakerSetPayload set;
    private volatile boolean removed;
    private double coneGain = 1.0D;

    SpeakerEmitterAnchor(DiscAnchor source, SpeakerSetPayload set, SpeakerEntry entry) {
        this.source = source;
        this.set = set;
        this.entry = entry;
    }

    BlockPos pos() { return entry.pos(); }
    void update(SpeakerSetPayload next, SpeakerEntry nextEntry) { set = next; entry = nextEntry; }
    void remove() { removed = true; }

    @Override public boolean isValid() { return !removed && source.isValid(); }
    @Override public Vec3 worldPos(float partialTicks) {
        final BlockPos pos = entry.pos();
        return new Vec3(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }
    @Override public int rangeBlocks() {
        final int range = set.rangeBlocks();
        return range > 0 ? Math.min(range, Config.maxPlaybackRange()) : Config.playbackRange();
    }
    @Override public int volumePercent() {
        if (entry.muted()) return 0;
        return (int) Math.min(Integer.MAX_VALUE,
                Math.round(set.volumePercent() * (double) entry.volumePercent() / 100.0D));
    }
    @Override public double gainMultiplier() {
        if (!set.directional()) return 1.0D;
        final Vec3 listener = DiscSoundInstance.listenerPos();
        if (listener == null) return coneGain;
        final Vec3 speaker = worldPos(1.0F);
        final double target = SpeakerCone.gain(entry.orientation(), speaker.x, speaker.y, speaker.z,
                listener.x, listener.y, listener.z);
        coneGain = SpeakerCone.approach(coneGain, target);
        return coneGain;
    }
    @Override public boolean directional() { return set.directional(); }
}

/** Original Golden emitter configuration, kept separate from linked speakers. */
final class GoldenEmitterAnchor implements DiscAnchor, LiveAudioConfig {
    private final DiscAnchor source;
    private final Function<BlockPos, AudioConfig> localConfigReader;
    private volatile SpeakerSetPayload set;
    GoldenEmitterAnchor(DiscAnchor source, SpeakerSetPayload set) {
        this(source, set, ClientConfigReader::read);
    }
    GoldenEmitterAnchor(DiscAnchor source, SpeakerSetPayload set,
            Function<BlockPos, AudioConfig> localConfigReader) {
        this.source = Objects.requireNonNull(source);
        this.set = Objects.requireNonNull(set);
        this.localConfigReader = Objects.requireNonNull(localConfigReader);
    }
    void update(SpeakerSetPayload next) { set = next; }
    @Override public boolean isValid() { return source.isValid(); }
    @Override public Vec3 worldPos(float partialTicks) { return source.worldPos(partialTicks); }
    @Override public int rangeBlocks() { return currentConfig().rangeBlocks(); }
    @Override public int volumePercent() { return currentConfig().volumePercent(); }
    @Override public double gainMultiplier() { return 1.0D; }
    @Override public boolean directional() { return currentConfig().directional(); }

    /**
     * Speakerが無い通常再生ではserverからSet更新が来ないため、固定Goldenの同期済みBEを再読する。
     * Speaker有りと移動音源は従来どおりserver payloadを正本にし、遠隔音源・船上音源をclient chunkへ
     * 誤って結び付けない。
     */
    private AudioConfig currentConfig() {
        final SpeakerSetPayload currentSet = set;
        if (!currentSet.speakers().isEmpty()) {
            return chooseConfig(currentSet, null);
        }
        final BlockPos configPos = source instanceof LiveConfigAnchor live ? live.configPos() : null;
        final AudioConfig local = configPos == null ? null : localConfigReader.apply(configPos);
        return chooseConfig(currentSet, local);
    }

    /** Loaded only when the production client constructor's reader is first invoked. */
    private static final class ClientConfigReader {
        private static AudioConfig read(BlockPos configPos) {
            final Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level != null && minecraft.level.isLoaded(configPos)
                    && minecraft.level.getBlockEntity(configPos) instanceof GoldenJukeboxBlockEntity golden) {
                return new AudioConfig(golden.getRangeBlocks(), golden.getVolumePercent(), golden.isDirectional());
            }
            return null;
        }
    }

    /** Pure precedence rule used by the regression test without constructing a client world. */
    static AudioConfig chooseConfig(SpeakerSetPayload fallback, AudioConfig local) {
        if (fallback.speakers().isEmpty() && local != null) {
            return local;
        }
        return new AudioConfig(fallback.rangeBlocks(), fallback.volumePercent(), fallback.directional());
    }

    record AudioConfig(int rangeBlocks, int volumePercent, boolean directional) {
    }
}
