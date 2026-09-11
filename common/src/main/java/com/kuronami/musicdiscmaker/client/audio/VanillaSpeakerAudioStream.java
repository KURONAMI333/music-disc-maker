package com.kuronami.musicdiscmaker.client.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.function.BooleanSupplier;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sounds.AudioStream;

/** Minecraft stream boundary; frame conversion and cancellation live in the headless PCM core. */
public final class VanillaSpeakerAudioStream implements AudioStream {
    private final VanillaSpeakerPcmStream pcm;
    private VanillaSpeakerAudioStream(VanillaSpeakerPcmStream pcm) { this.pcm = pcm; }

    public static CompletableFuture<AudioStream> prepareAsync(AudioStream delegate, long offsetMs,
            BooleanSupplier cancelled, Executor executor) {
        return prepareAsync(delegate, () -> offsetMs, cancelled, executor);
    }

    /** Evaluate the offset on the preparation worker immediately before the potentially costly PCM skip. */
    public static CompletableFuture<AudioStream> prepareAsync(AudioStream delegate, LongSupplier offsetMs,
            BooleanSupplier cancelled, Executor executor) {
        final VanillaSpeakerPcmStream.Input input = new VanillaSpeakerPcmStream.Input() {
            @Override public AudioFormat getFormat() { return delegate.getFormat(); }
            @Override public ByteBuffer read(int size) throws IOException { return delegate.read(size); }
            @Override public void close() throws IOException { delegate.close(); }
        };
        return CompletableFuture.supplyAsync(() -> Math.max(0L, offsetMs.getAsLong()), executor)
                .thenCompose(offset -> VanillaSpeakerPcmStream.prepareAsync(input, offset, cancelled, executor))
                .thenApply(VanillaSpeakerAudioStream::new);
    }

    public void setPcmGain(float gain) { pcm.setPcmGain(gain); }
    @Override public AudioFormat getFormat() { return pcm.getFormat(); }
    @Override public ByteBuffer read(int size) throws IOException { return pcm.read(size); }
    @Override public void close() throws IOException { pcm.close(); }
}
