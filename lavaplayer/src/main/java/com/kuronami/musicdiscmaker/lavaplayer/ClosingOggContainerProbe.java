package com.kuronami.musicdiscmaker.lavaplayer;

import java.io.IOException;

import com.sedmelluq.discord.lavaplayer.container.ogg.OggContainerProbe;
import com.sedmelluq.discord.lavaplayer.container.ogg.OggPacketInputStream;
import com.sedmelluq.discord.lavaplayer.container.ogg.OggTrackBlueprint;
import com.sedmelluq.discord.lavaplayer.container.ogg.OggTrackHandler;
import com.sedmelluq.discord.lavaplayer.container.ogg.OggTrackLoader;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BaseAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;

/**
 * Input-only Ogg decoding with explicit handler ownership. The bundled LavaPlayer
 * 2.2.4-fix-j8 OggAudioTrack never closes its handler (Vorbis decoder and filters).
 * Keep its probes/codecs; close each logical stream on EOF, failure or interruption.
 * Normalization always reads from the start and never seeks. Playback/seeking of
 * the resulting MKA continues to use the separate saved-file registry.
 */
final class ClosingOggContainerProbe extends OggContainerProbe {
    @Override
    public AudioTrack createTrack(String parameters, AudioTrackInfo info, SeekableInputStream stream) {
        return new ClosingOggAudioTrack(info, stream);
    }

    static class ClosingOggAudioTrack extends BaseAudioTrack {
        private final SeekableInputStream stream;

        ClosingOggAudioTrack(AudioTrackInfo info, SeekableInputStream stream) {
            super(info);
            this.stream = stream;
        }

        @Override
        public boolean isSeekable() {
            return false;
        }

        @Override
        public void process(LocalAudioTrackExecutor executor) throws IOException {
            final OggPacketInputStream packets = new OggPacketInputStream(stream, false);
            final OggTrackBlueprint first = OggTrackLoader.loadTrackBlueprint(packets);
            if (first == null) {
                throw new IOException("Stream terminated before the first packet.");
            }
            executor.executeProcessingLoop(() -> {
                OggTrackBlueprint blueprint = first;
                while (blueprint != null) {
                    try (OggTrackHandler handler = loadHandler(blueprint, packets)) {
                        handler.initialise(executor.getProcessingContext(), 0L, 0L);
                        handler.provideFrames();
                    }
                    blueprint = OggTrackLoader.loadTrackBlueprint(packets);
                }
            }, null, true);
        }

        OggTrackHandler loadHandler(OggTrackBlueprint blueprint, OggPacketInputStream packets) {
            return blueprint.loadTrackHandler(packets);
        }
    }
}
