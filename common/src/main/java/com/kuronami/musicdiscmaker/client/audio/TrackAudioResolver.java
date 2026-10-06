package com.kuronami.musicdiscmaker.client.audio;

import com.kuronami.musicdiscmaker.audio.LoaderHolder;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.lavaplayer.api.*;
import com.kuronami.musicdiscmaker.network.UrlGuard;

/** 全機器共通の解決口。ローカル参照をURLに偽装せず、検査済みキャッシュだけを開く。 */
public final class TrackAudioResolver {
    private TrackAudioResolver() {}
    public static long playbackClockMs() {
        var server = net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
        return server instanceof com.kuronami.musicdiscmaker.component.PlaybackClockAccess clock
                ? clock.mdm$playbackTimeMs()
                : com.kuronami.musicdiscmaker.component.PauseAwarePlaybackClock.realTimeMs();
    }
    /** Pocket/TB等、取得後に本人が新しく聞き始める経路。先頭を取得時間ぶん飛ばさない。 */
    public static OpenStreamResult open(CustomTrackData track, long startMs) throws Exception {
        return openInternal(track, startMs, -1L);
    }
    /** 再生通知時の時計を渡し、pool待機・受信時間を現在の再生位置へ反映する。 */
    public static OpenStreamResult openSynced(CustomTrackData track, long startMs, long requestedAtMs) throws Exception {
        return openInternal(track, startMs, requestedAtMs);
    }
    /** Optional source-aware dispatch; the captured selection is also the prefetch decision. */
    public static OpenStreamResult openSyncedForPlayback(CustomTrackData track, long startMs, long requestedAtMs,
            PlaybackSessionId sessionId, PlaybackSourceContext sourceContext, PlaybackSelection selection,
            java.util.function.Consumer<Throwable> failureSink, java.util.function.BooleanSupplier stillCurrent) throws Exception {
        if (track == null || track.isLocal() || sessionId == null || sourceContext == null
                || selection == null || !selection.selected()) return openInternal(track, startMs, requestedAtMs);
        UrlGuard.enforce(track.url());
        PlaybackSessionRequest request = new PlaybackSessionRequest(sessionId, sourceContext, track,
                Math.max(0L, startMs), requestedAtMs);
        var extension = PlaybackSessionRegistry.tryOpen(request, selection, failureSink, stillCurrent);
        return extension.isPresent() ? OpenStreamResult.ok(extension.get())
                : openInternal(track, startMs, requestedAtMs);
    }
    public static long actualOffset(IAudioSource source, long fallback) {
        return source instanceof LocalSource local ? local.offset : fallback;
    }
    private static OpenStreamResult openInternal(CustomTrackData track, long startMs, long requestedAtMs) throws Exception {
        if (!track.isLocal()) {
            UrlGuard.enforce(track.url());
            return LoaderHolder.get().openStreamDetailed(track.url(), startMs);
        }
        try {
            var path = LocalAudioClient.obtain(track.localHash());
            long elapsed = requestedAtMs < 0 ? 0 : Math.max(0, playbackClockMs() - requestedAtMs);
            long offset = Math.max(0, startMs) + elapsed;
            var result = LoaderHolder.get().openTrustedFileDetailed(path, offset);
            if (!result.isOk()) throw new java.io.IOException("Invalid local audio");
            return OpenStreamResult.ok(new LocalSource(result.source(), offset));
        } catch (Exception e) { throw new com.kuronami.musicdiscmaker.audio.local.LocalAudioException(e); }
    }
    /** 実際にdecoderへ渡したoffsetを音声と一緒に届け、表示・停止時計も同じ起点へ合わせる。 */
    private record LocalSource(IAudioSource delegate, long offset) implements IAudioSource {
        public int sampleRate() { return delegate.sampleRate(); }
        public int channels() { return delegate.channels(); }
        public int bitsPerSample() { return delegate.bitsPerSample(); }
        public boolean bigEndian() { return delegate.bigEndian(); }
        public int read(byte[] dst, int off, int len) { return delegate.read(dst, off, len); }
        public PlaybackFault playbackFault() { return delegate.playbackFault(); }
        public void onPlaybackFault(java.util.function.Consumer<PlaybackFault> sink) { delegate.onPlaybackFault(sink); }
        public void close() { delegate.close(); }
    }
}
