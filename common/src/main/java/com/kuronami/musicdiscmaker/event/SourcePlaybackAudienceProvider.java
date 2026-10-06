package com.kuronami.musicdiscmaker.event;

import java.util.Set;
import java.util.UUID;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import net.minecraft.server.level.ServerPlayer;

/** Optional server extension point for players who receive a source outside its ordinary speaker range. */
public interface SourcePlaybackAudienceProvider {
    /** Current and retained audio recipients for this exact source. */
    Set<UUID> viewers(GoldenJukeboxBlockEntity source);

    /** Sends source metadata to this recipient immediately before the core playback packet. */
    default void beforeCorePlay(GoldenJukeboxBlockEntity source, ServerPlayer player) {}
}
