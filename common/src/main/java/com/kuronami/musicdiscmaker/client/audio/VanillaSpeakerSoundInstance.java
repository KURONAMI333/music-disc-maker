package com.kuronami.musicdiscmaker.client.audio;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.kuronami.musicdiscmaker.Config;
import com.kuronami.musicdiscmaker.MusicDiscMaker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/** One positional streaming voice for one vanilla-record Golden source. */
public final class VanillaSpeakerSoundInstance extends AbstractTickableSoundInstance
        implements PlaybackVoice, CustomAudioStreamProvider, SoundEngineAcceptance.Engine {
    private static final ExecutorService PREPARE = Executors.newCachedThreadPool(runnable -> {
        final Thread thread = new Thread(runnable, "music_disc_maker-vanilla-speaker");
        thread.setDaemon(true);
        return thread;
    });
    private final String soundEventId;
    private final java.util.function.LongSupplier startOffsetMs;
    private final AsyncResourceSlot<VanillaSpeakerAudioStream> streams =
            new AsyncResourceSlot<>(VanillaSpeakerSoundInstance::closeQuietly);
    private volatile DiscAnchor anchor;
    private volatile boolean engineAccepted;
    private volatile float pcmGain = 1.0F;
    private volatile float playbackPitch = 1.0F;
    private volatile boolean pitchResolved;
    private final java.util.function.Consumer<PlaybackFailure> onStreamFailure;
    private final Runnable onStreamReady;
    private boolean streamPrepared;
    private boolean readyReported;
    private final FlatPlaybackGate flatGate = new FlatPlaybackGate();

    public VanillaSpeakerSoundInstance(String soundEventId, long startOffsetMs, DiscAnchor anchor) {
        this(soundEventId, () -> startOffsetMs, anchor,
                failure -> PlaybackFailureReport.report(soundEventId, null, failure), () -> {});
    }

    public VanillaSpeakerSoundInstance(String soundEventId, java.util.function.LongSupplier startOffsetMs,
            DiscAnchor anchor, java.util.function.Consumer<PlaybackFailure> onStreamFailure, Runnable onStreamReady) {
        super(soundEvent(soundEventId), SoundSource.RECORDS, RandomSource.create());
        this.onStreamFailure = onStreamFailure;
        this.onStreamReady = onStreamReady;
        this.soundEventId = soundEventId;
        this.startOffsetMs = startOffsetMs;
        this.anchor = anchor;
        this.pitch = 1.0F;
        this.looping = false;
        this.attenuation = Attenuation.LINEAR;
        applyAnchor(anchor);
    }

    public VanillaSpeakerSoundInstance(String soundEventId, long startOffsetMs, DiscAnchor anchor,
            java.util.function.Consumer<PlaybackFailure> onStreamFailure, Runnable onStreamReady) {
        super(soundEvent(soundEventId), SoundSource.RECORDS, RandomSource.create());
        this.onStreamFailure = onStreamFailure;
        this.onStreamReady = onStreamReady;
        this.soundEventId = soundEventId;
        this.startOffsetMs = () -> Math.max(0L, startOffsetMs);
        this.anchor = anchor;
        this.pitch = 1.0F;
        this.looping = false;
        this.attenuation = Attenuation.LINEAR;
        applyAnchor(anchor);
    }

    private static SoundEvent soundEvent(String id) {
        //? if >=1.21.2 {
        return SoundEvent.createVariableRangeEvent(net.minecraft.resources.Identifier.parse(id));
        //?} elif >=1.21 {
        /*return SoundEvent.createVariableRangeEvent(net.minecraft.resources.ResourceLocation.parse(id));
        */
        //?} else {
        /*return SoundEvent.createVariableRangeEvent(new net.minecraft.resources.ResourceLocation(id));
        */
        //?}
    }

    /** Set-only updates retain this sound instance and its decoder. */
    public void useSpeakerAnchor(DiscAnchor next) {
        this.anchor = next;
        applyAnchor(next);
    }

    @Override
    public void tick() {
        final DiscAnchor current = anchor;
        if (!current.isValid()) {
            stopAndRelease();
            return;
        }
        applyAnchor(current, true);
    }

    private void applyAnchor(DiscAnchor current) {
        applyAnchor(current, false);
    }

    private void applyAnchor(DiscAnchor current, boolean tick) {
        final Vec3 point = current.worldPos(1.0F);
        final LiveAudioConfig config = current instanceof LiveAudioConfig live ? live : null;
        final boolean directional = config == null || config.directional();
        if (directional) {
            this.relative = false;
            this.x = point.x;
            this.y = point.y;
            this.z = point.z;
        } else {
            this.relative = true;
            this.x = this.y = this.z = 0.0D;
        }
        final Vec3 ear = DiscSoundInstance.listenerPos();
        final double distance = ear == null ? 0.0D : ear.distanceTo(point);
        final double gate = flatGate.update(directional, distance, effectiveRange(current), tick);
        final double gain = gate * (config == null ? 1.0D
                : Math.max(0.0D, config.volumePercent() / 100.0D * config.gainMultiplier()));
        this.pcmGain = (float) Math.max(gain, 1.0D);
        this.volume = (float) Math.min(gain, 1.0D);
        final VanillaSpeakerAudioStream currentStream = streams.current();
        if (currentStream != null) currentStream.setPcmGain(this.pcmGain);
        final SoundManager manager = Minecraft.getInstance().getSoundManager();
        if (manager instanceof SoundEngineHolder holder && holder.mdm$soundEngine() instanceof SoundEngineChannelAccess channels) {
            channels.mdm$setRelative(this, !directional);
            channels.mdm$updateLinearAttenuation(this, Math.max(getVolume(), 1.0F) * effectiveRange(current));
        }
    }

    private static int effectiveRange(DiscAnchor current) {
        final int range = current instanceof LiveAudioConfig config ? config.rangeBlocks() : 0;
        return range > 0 ? Math.min(range, Config.maxPlaybackRange()) : Config.playbackRange();
    }

    @Override
    public net.minecraft.client.sounds.WeighedSoundEvents resolve(SoundManager manager) {
        final net.minecraft.client.sounds.WeighedSoundEvents result = super.resolve(manager);
        if (this.sound != null && this.sound != SoundManager.EMPTY_SOUND) {
            final Sound base = this.sound;
            // SoundEngine samples this provider once when it creates the channel. Pin the same sample for
            // both OpenAL and offset conversion; sampling it again can choose a different random pitch.
            this.playbackPitch = Mth.clamp(super.getPitch(), 0.5F, 2.0F);
            this.pitchResolved = true;
            this.sound = new Sound(
                    //? if >=1.21 {
                    base.getLocation(),
                    //?} else {
/*                    base.getLocation().toString(),
                    */
                    //?}
                    base.getVolume(), base.getPitch(), base.getWeight(), base.getType(), true,
                    base.shouldPreload(), effectiveRange(anchor));
        }
        return result;
    }

    @Override
    public CompletableFuture<AudioStream> createAudioStream(SoundBufferLibrary buffers, Sound sound, boolean looping) {
        final Sound resolved = sound != null ? sound : this.sound;
        final CompletableFuture<AudioStream> preparation = resolved == null
                ? CompletableFuture.failedFuture(new IllegalStateException("Vanilla sound was not resolved"))
                : buffers.getStream(resolved.getPath(), false)
                .thenCompose(delegate -> VanillaSpeakerAudioStream.prepareAsync(
                        delegate, () -> VanillaSpeakerTiming.sourceOffsetMs(startOffsetMs.getAsLong(), playbackPitch),
                        streams::isReleased, PREPARE))
                .thenApply(ready -> {
                    if (ready instanceof VanillaSpeakerAudioStream stream) {
                        if (!streams.install(stream)) {
                            throw new IllegalStateException("Vanilla speaker stream was already installed or stopped");
                        }
                        stream.setPcmGain(pcmGain);
                    }
                    return ready;
                });
        preparation.whenComplete((ready, failure) -> {
            if (streams.isReleased()) return;
            final Minecraft minecraft = Minecraft.getInstance();
            try {
                minecraft.execute(() -> {
                    if (streams.isReleased()) return;
                    if (failure == null) {
                        streamPrepared = true;
                        reportReady();
                        return;
                    }
                    stopAndRelease();
                    onStreamFailure.accept(PlaybackFailure.thrown(failure));
                });
            } catch (final RuntimeException schedulingFailure) {
                try {
                    streams.release();
                } catch (final Throwable releaseFailure) {
                    schedulingFailure.addSuppressed(releaseFailure);
                }
                try {
                    stop();
                } catch (final Throwable flagFailure) {
                    schedulingFailure.addSuppressed(flagFailure);
                }
                MusicDiscMaker.LOGGER.warn("Could not schedule failed vanilla speaker cleanup", schedulingFailure);
            }
        });
        return preparation;
    }

    /** The first selection tick may legitimately be silent while the client listener is being installed. */
    @Override
    public boolean canStartSilent() {
        return true;
    }

    /** The parent implementation assumes resolve() has already supplied a Sound. */
    @Override
    public float getVolume() {
        return getSound() == null ? this.volume : super.getVolume();
    }

    /** Return the one resource-pack pitch sample also used to convert elapsed time to source PCM time. */
    @Override
    public float getPitch() {
        if (pitchResolved) return playbackPitch;
        return getSound() == null ? this.pitch : super.getPitch();
    }

    @Override
    public boolean playAndConfirm() {
        final SoundManager sounds = Minecraft.getInstance().getSoundManager();
        sounds.play(this);
        engineAccepted = sounds.isActive(this);
        reportReady();
        return engineAccepted;
    }

    private void reportReady() {
        if (engineAccepted && streamPrepared && !readyReported && !streams.isReleased()) {
            readyReported = true;
            onStreamReady.run();
        }
    }

    @Override
    public boolean mutedOut() {
        final var options = Minecraft.getInstance().options;
        return getVolume() <= 0.0F
                || options.getSoundSourceVolume(SoundSource.MASTER) <= 0.0F
                || options.getSoundSourceVolume(SoundSource.RECORDS) <= 0.0F;
    }

    @Override public void setDirectional(boolean value) { applyAnchor(anchor); }
    @Override public void setRangeBlocks(int value) { applyAnchor(anchor); }
    @Override public void setVolumePercent(int value) { applyAnchor(anchor); }
    @Override
    public boolean isVoiceStopped() {
        if (isStopped()) return true;
        if (engineAccepted && !Minecraft.getInstance().getSoundManager().isActive(this)) {
            stopAndRelease();
            return true;
        }
        return false;
    }

    @Override
    public void stopAndRelease() {
        Throwable failure = null;
        try {
            streams.release();
        } catch (final Throwable releaseFailure) {
            failure = releaseFailure;
        }
        try {
            Minecraft.getInstance().getSoundManager().stop(this);
        } catch (final Throwable stopFailure) {
            if (failure == null) failure = stopFailure;
            else failure.addSuppressed(stopFailure);
        } finally {
            try {
                stop();
            } catch (final Throwable flagFailure) {
                if (failure == null) failure = flagFailure;
                else failure.addSuppressed(flagFailure);
            }
        }
        if (failure != null) {
            MusicDiscMaker.LOGGER.warn("Could not fully release vanilla speaker voice", failure);
        }
    }

    private static void closeQuietly(VanillaSpeakerAudioStream stream) {
        try {
            stream.close();
        } catch (final IOException failure) {
            MusicDiscMaker.LOGGER.warn("Could not close vanilla speaker stream", failure);
        }
    }
}
