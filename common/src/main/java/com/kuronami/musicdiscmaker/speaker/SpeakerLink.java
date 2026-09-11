package com.kuronami.musicdiscmaker.speaker;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * スピーカーが参照する server 音源の永続キー。
 *
 * <p>旧座標と任意の個体IDを保持する。音声実装やloader APIには依存しない。
 * chunkが未ロードでもリンク自体は失われない。
 */
public record SpeakerLink(String dimensionId, long packedPos, UUID sourceId) {

    public SpeakerLink(String dimensionId, long packedPos) {
        this(dimensionId, packedPos, null);
    }

    /** Legacy position index key; identity resolution is a separate operation. */
    public SpeakerLink positionKey() {
        return sourceId == null ? this : new SpeakerLink(dimensionId, packedPos);
    }

    public SpeakerLink {
        Objects.requireNonNull(dimensionId, "dimensionId");
        if (dimensionId.isBlank()) {
            throw new IllegalArgumentException("dimensionId must not be blank");
        }
    }

    public static Optional<SpeakerLink> restore(String dimensionId, long packedPos) {
        return restore(dimensionId, packedPos, "");
    }

    public static Optional<SpeakerLink> restore(String dimensionId, long packedPos, String sourceId) {
        if (dimensionId == null || dimensionId.isBlank()) {
            return Optional.empty();
        }
        UUID identity = null;
        if (sourceId != null && !sourceId.isBlank()) {
            try {
                identity = UUID.fromString(sourceId);
            } catch (IllegalArgumentException ignored) {
                // Preserve the old position link when only the optional ID is damaged.
            }
        }
        return Optional.of(new SpeakerLink(dimensionId, packedPos, identity));
    }
}
