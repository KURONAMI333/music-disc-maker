package com.kuronami.musicdiscmaker.component;

/**
 * バニラまたは他 MOD のジュークボックス曲を再生するための中立な記述。
 *
 * <p>Minecraft の版ごとに異なる {@code SoundEvent} / {@code JukeboxSong} の型を持ち込まず、
 * 登録済み SoundEvent の識別子と曲尺だけを運ぶ。custom track の URL とは別の名前空間として扱う。
 */
public record VanillaTrackData(String soundEventId, long durationMs) {
    public VanillaTrackData {
        java.util.Objects.requireNonNull(soundEventId, "soundEventId");
        if (soundEventId.length() > 256 || soundEventId.contains("://") || !soundEventId.matches("[a-z0-9_.-]+:[a-z0-9/._-]+") || durationMs < 0) {
            throw new IllegalArgumentException("Invalid vanilla track description");
        }
    }
}
