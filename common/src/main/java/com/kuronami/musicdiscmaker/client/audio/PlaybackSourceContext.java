package com.kuronami.musicdiscmaker.client.audio;

import java.util.Objects;
import java.util.UUID;
import com.kuronami.musicdiscmaker.network.PlaybackSourceStamp;
import net.minecraft.core.BlockPos;

/** Stable source identity used identically by prefetch selection and playback selection. */
public record PlaybackSourceContext(BlockPos position, UUID sourceId) {
    public PlaybackSourceContext {
        position = Objects.requireNonNull(position, "position").immutable();
    }
    public static PlaybackSourceContext of(BlockPos position, PlaybackSourceStamp stamp) {
        Objects.requireNonNull(stamp, "stamp");
        return new PlaybackSourceContext(position, stamp.sourceId());
    }
}
