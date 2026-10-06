package com.kuronami.musicdiscmaker.event;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import com.kuronami.musicdiscmaker.block.GoldenJukeboxBlockEntity;
import net.minecraft.server.level.ServerPlayer;

/** Optional source audience registry. An empty or failing provider preserves the original MDM recipient path. */
public final class SourcePlaybackAudienceRegistry {
    private static final System.Logger LOGGER = System.getLogger(SourcePlaybackAudienceRegistry.class.getName());
    private static final CopyOnWriteArrayList<SourcePlaybackAudienceProvider> PROVIDERS = new CopyOnWriteArrayList<>();

    private SourcePlaybackAudienceRegistry() {}

    public static AutoCloseable register(SourcePlaybackAudienceProvider provider) {
        java.util.Objects.requireNonNull(provider, "provider");
        if (!PROVIDERS.addIfAbsent(provider)) throw new IllegalStateException("audience provider already registered");
        java.util.concurrent.atomic.AtomicBoolean removed = new java.util.concurrent.atomic.AtomicBoolean();
        return () -> { if (removed.compareAndSet(false, true)) PROVIDERS.remove(provider); };
    }

    public static boolean hasProviders() { return !PROVIDERS.isEmpty(); }

    public static Set<UUID> viewers(GoldenJukeboxBlockEntity source) {
        if (PROVIDERS.isEmpty() || source == null) return Set.of();
        java.util.HashSet<UUID> result = new java.util.HashSet<>();
        for (SourcePlaybackAudienceProvider provider : PROVIDERS) {
            try {
                Set<UUID> viewers = provider.viewers(source);
                if (viewers != null) result.addAll(viewers);
            } catch (RuntimeException | LinkageError failure) {
                LOGGER.log(System.Logger.Level.WARNING, "Optional source audience provider failed; using MDM recipients", failure);
            }
        }
        return result.isEmpty() ? Set.of() : Set.copyOf(result);
    }

    public static void beforeCorePlay(GoldenJukeboxBlockEntity source, ServerPlayer player) {
        if (PROVIDERS.isEmpty() || source == null || player == null) return;
        for (SourcePlaybackAudienceProvider provider : PROVIDERS) {
            try { provider.beforeCorePlay(source, player); }
            catch (RuntimeException | LinkageError failure) {
                LOGGER.log(System.Logger.Level.WARNING, "Optional pre-play metadata callback failed", failure);
            }
        }
    }
}
