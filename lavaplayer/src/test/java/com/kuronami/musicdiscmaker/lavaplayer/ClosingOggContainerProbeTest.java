package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sedmelluq.discord.lavaplayer.container.MediaContainerRegistry;
import com.sedmelluq.discord.lavaplayer.container.ogg.*;
import com.sedmelluq.discord.lavaplayer.format.Pcm16AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.TrackExceptionEvent;
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioProcessingContext;
import com.sedmelluq.lava.common.natives.NativeResourceHolder;

/** Real Vorbis handlers, native released flags and real asynchronous player cleanup; no GC. */
class ClosingOggContainerProbeTest {
    @TempDir Path directory;

    @Test void closesDecoderAndFiltersAtEof() throws Exception { run("eof", false); }
    @Test void closesDecoderAndFiltersWhenStoppedBeforeEof() throws Exception { run("stop", false); }
    @Test void closesDecoderAndFiltersWhenInitialisationThrows() throws Exception { run("init-failure", false); }
    @Test void closesDecoderAndFiltersWhenDecodingThrows() throws Exception { run("decode-failure", false); }
    @Test void givesEachChainedLogicalStreamItsOwnClosedHandler() throws Exception { run("eof", true); }

    private void run(String mode, boolean chained) throws Exception {
        byte[] bytes = TestMedia.ogg();
        if (chained) {
            byte[] both = new byte[bytes.length * 2];
            System.arraycopy(bytes, 0, both, 0, bytes.length);
            System.arraycopy(bytes, 0, both, bytes.length, bytes.length);
            bytes = both;
        }
        Path input = directory.resolve("input.ogg");
        Files.write(input, bytes);
        var handlers = new CopyOnWriteArrayList<ObservedHandler>();
        var probe = new OggContainerProbe() {
            @Override public AudioTrack createTrack(String parameters, AudioTrackInfo info,
                    SeekableInputStream stream) {
                return new ClosingOggContainerProbe.ClosingOggAudioTrack(info, stream) {
                    @Override OggTrackHandler loadHandler(OggTrackBlueprint blueprint, OggPacketInputStream packets) {
                        var handler = new ObservedHandler(super.loadHandler(blueprint, packets), mode);
                        handlers.add(handler);
                        return handler;
                    }
                };
            }
        };
        var manager = new DefaultAudioPlayerManager();
        manager.setFrameBufferDuration(100);
        manager.getConfiguration().setOutputFormat(new Pcm16AudioDataFormat(2, 48000, 960, false));
        var player = manager.createPlayer();
        var failure = new AtomicBoolean();
        player.addListener(event -> { if (event instanceof TrackExceptionEvent) failure.set(true); });
        try {
            var inputs = new LocalAudioSourceManager(new MediaContainerRegistry(List.of(probe)));
            var track = (AudioTrack) inputs.loadItem(manager, new AudioReference(input.toString(), null));
            player.playTrack(track);
            long deadline = System.nanoTime() + 5_000_000_000L;
            int frames = 0;
            boolean stoppedBeforeHandlerClose = false;
            while (player.getPlayingTrack() != null && System.nanoTime() < deadline) {
                var frame = player.provide();
                if (frame != null) {
                    frames++;
                    if (mode.equals("stop")) {
                        stoppedBeforeHandlerClose = handlers.get(0).closes.get() == 0;
                        player.stopTrack();
                        break;
                    }
                }
                Thread.sleep(2L);
            }
            while ((handlers.isEmpty() || handlers.stream().anyMatch(h -> !h.closed))
                    && System.nanoTime() < deadline) Thread.sleep(2L);
            assertNull(player.getPlayingTrack(), "player must finish within the bound");
            assertEquals(chained ? 2 : 1, handlers.size(), "one owner per logical stream");
            for (ObservedHandler handler : handlers) {
                assertTrue(handler.closed, "handler's close completed before player/manager teardown");
                assertEquals(1, handler.closes.get());
                assertFalse(handler.resources.isEmpty(), "test actually observed native resources");
                for (NativeResourceHolder nativeResource : handler.resources) {
                    assertTrue(released(nativeResource), nativeResource.getClass().getSimpleName());
                }
            }
            if (mode.endsWith("failure")) assertTrue(failure.get(), "injected failure reached the player event");
            else {
                assertFalse(failure.get());
                assertTrue(frames > 0, "real Vorbis PCM was delivered");
            }
            if (mode.equals("stop")) assertTrue(stoppedBeforeHandlerClose, "stop interrupted an active handler");
        } finally {
            player.destroy();
            manager.shutdown();
        }
    }

    private static final class ObservedHandler implements OggTrackHandler {
        final OggTrackHandler delegate;
        final String mode;
        final AtomicInteger closes = new AtomicInteger();
        final List<NativeResourceHolder> resources = new ArrayList<>();
        volatile boolean closed;
        ObservedHandler(OggTrackHandler delegate, String mode) { this.delegate = delegate; this.mode = mode; }
        @Override public void initialise(AudioProcessingContext context, long timecode, long desired) throws IOException {
            delegate.initialise(context, timecode, desired);
            collect(delegate, resources, Collections.newSetFromMap(new IdentityHashMap<>()));
            if (mode.equals("init-failure")) throw new IOException("injected after native initialisation");
        }
        @Override public void provideFrames() throws InterruptedException {
            if (mode.equals("decode-failure")) throw new IllegalStateException("injected decode failure");
            delegate.provideFrames();
        }
        @Override public void seekToTimecode(long position) { delegate.seekToTimecode(position); }
        @Override public void close() throws IOException {
            closes.incrementAndGet();
            delegate.close();
            closed = true;
        }
    }

    private static boolean released(NativeResourceHolder resource) throws Exception {
        Field flag = NativeResourceHolder.class.getDeclaredField("released");
        flag.setAccessible(true);
        return ((AtomicBoolean) flag.get(resource)).get();
    }

    /** Inspect only the real handler/filter ownership tree; retain natives so finalizers cannot help. */
    private static void collect(Object value, List<NativeResourceHolder> output, Set<Object> seen) {
        if (value == null || !seen.add(value)) return;
        if (value instanceof NativeResourceHolder holder) { output.add(holder); return; }
        if (value instanceof Collection<?> list) { for (Object item : list) collect(item, output, seen); return; }
        if (value.getClass().isArray()) {
            if (!value.getClass().getComponentType().isPrimitive()) {
                for (int i = 0; i < Array.getLength(value); i++) collect(Array.get(value, i), output, seen);
            }
            return;
        }
        String name = value.getClass().getName();
        if (!name.startsWith("com.sedmelluq.discord.lavaplayer.filter.")
                && !name.startsWith("com.sedmelluq.discord.lavaplayer.container.ogg.vorbis.")) return;
        for (Class<?> type = value.getClass(); type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    collect(field.get(value), output, seen);
                } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            }
        }
    }
}
