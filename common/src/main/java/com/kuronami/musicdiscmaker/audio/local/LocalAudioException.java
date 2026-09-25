package com.kuronami.musicdiscmaker.audio.local;

/** ローカル音源の欠損・破損をURLサービス障害と混同しないための境界例外。 */
public final class LocalAudioException extends java.io.IOException {
    public LocalAudioException(Throwable cause) { super("Local MP3 unavailable; import it again in this world", cause); }
}
