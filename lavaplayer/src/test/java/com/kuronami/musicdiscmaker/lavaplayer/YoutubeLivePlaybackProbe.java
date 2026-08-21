package com.kuronami.musicdiscmaker.lavaplayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.PlaybackFault;
import com.kuronami.musicdiscmaker.lavaplayer.api.TrackInfo;

/**
 * 実回線で {@link MusicLoaderImpl} を通し、<b>復号 PCM を末尾まで読み切れるか</b>を測る。
 *
 * <h2>なぜ JUnit にしないか</h2>
 * 外の世界 (YouTube) が相手なので、{@code build} のゲートに混ぜると回線や YouTube 側の都合で
 * 赤くなる。{@code ./gradlew :lavaplayer:youtubeProbe} で人が撃つ道具として置く。
 *
 * <h2>なぜ「解決できた」で終わらせないか</h2>
 * 2026-08-21 の実測で {@code AndroidVr@1.65.10} は<b>解決も player API も通るのに、
 * ストリームが先頭 256KiB だけ 206 で 1MB 以降 403</b> だった。解決の可否だけを見ると
 * 「直った」と誤って判定できてしまう。ここでは {@code IAudioSource.read} が {@code -1} を
 * 返すまで読み、<b>読めた PCM の尺</b>を {@link TrackInfo#durationMs} と突き合わせる。
 * 非同期側に回った失敗は {@link IAudioSource#playbackFault()} で拾う。
 *
 * <p>生バイトを HTTP で数えるのではなく復号後の PCM を数えるのは、<b>この MOD が実際に
 * MC へ渡すもの</b>がそれだから。途中で切れたストリームはここで尺の不足として出る。
 *
 * <p>引数に URL を並べると対象を差し替えられる。無指定なら {@link #DEFAULT_TARGETS}。
 */
public final class YoutubeLivePlaybackProbe {

    /** 尺の一致とみなす幅。デコーダの端数と、YouTube が返す尺の丸めを吸収する。 */
    private static final double TOLERANCE = 0.02;

    /**
     * 既定の対象。<b>普通の曲 / 年齢制限 / 過去に bot 判定で落ちた実例</b>を混ぜてある
     * (最後の 3 本は {@code _handoff/MDM_ISSUES.md} に残っている報告の video ID)。
     */
    private static final String[] DEFAULT_TARGETS = {
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://www.youtube.com/watch?v=SkTt9k4Y-a8",
            "https://www.youtube.com/watch?v=Sfz5TpCRSiI",
            "https://www.youtube.com/watch?v=ouLndhBRL4w",
            "https://www.youtube.com/watch?v=dePs7UPp6GQ",
            // 検索経路 (Spotify のリンクはこれを通る)。iOS 単体では 0 件になるので、
            // YoutubeSearchClient が効いているかはここで落ちる。
            "ytsearch:Rick Astley Never Gonna Give You Up",
            "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT",
    };

    private YoutubeLivePlaybackProbe() {
    }

    public static void main(String[] args) {
        final String[] targets = args.length > 0 ? args : DEFAULT_TARGETS;
        final MusicLoaderImpl loader = new MusicLoaderImpl();
        final List<String> failures = new ArrayList<>();

        for (final String url : targets) {
            System.out.println("==== " + url);
            try {
                probe(loader, url);
            } catch (final Throwable t) {
                System.out.println("  FAIL " + t.getClass().getSimpleName() + ": " + t.getMessage());
                failures.add(url + " -> " + t.getClass().getSimpleName() + ": " + t.getMessage());
                continue;
            }
            System.out.println("  OK");
        }

        System.out.println();
        System.out.println("==== summary: " + (targets.length - failures.size()) + "/" + targets.length + " ok");
        for (final String each : failures) {
            System.out.println("  FAILED " + each);
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    private static void probe(MusicLoaderImpl loader, String url) throws Exception {
        final long resolveStart = System.currentTimeMillis();
        final TrackInfo info = loader.resolve(url);
        final long resolveMs = System.currentTimeMillis() - resolveStart;
        System.out.printf(Locale.ROOT, "  resolve %d ms | %s / %s | duration %d ms | uri %s%n",
                resolveMs, info.title(), info.author(), info.durationMs(), info.uri());

        final long readStart = System.currentTimeMillis();
        long bytes = 0L;
        PlaybackFault fault;
        final int sampleRate;
        final int channels;
        final int bytesPerSample;
        try (IAudioSource source = loader.openStream(info.uri(), 0L)) {
            if (source == null) {
                throw new IllegalStateException("openStream returned null");
            }
            sampleRate = source.sampleRate();
            channels = source.channels();
            bytesPerSample = source.bitsPerSample() / 8;

            final byte[] buffer = new byte[64 * 1024];
            int n;
            while ((n = source.read(buffer, 0, buffer.length)) > 0) {
                bytes += n;
            }
            fault = source.playbackFault();
        }
        final long readMs = System.currentTimeMillis() - readStart;

        final long frames = bytes / ((long) channels * bytesPerSample);
        final long playedMs = frames * 1000L / sampleRate;
        final double ratio = info.durationMs() <= 0 ? 0.0 : (double) playedMs / info.durationMs();
        System.out.printf(Locale.ROOT,
                "  read %,d bytes PCM (%d Hz x %d ch x %d bit) = %d ms of %d ms (%.4f) in %d ms%n",
                bytes, sampleRate, channels, bytesPerSample * 8, playedMs, info.durationMs(), ratio, readMs);

        if (fault != null) {
            throw new IllegalStateException("playback fault " + fault.reason() + ": " + fault.detail());
        }
        if (info.durationMs() <= 0) {
            throw new IllegalStateException("track reported no duration; cannot verify completion");
        }
        if (ratio < 1.0 - TOLERANCE) {
            throw new IllegalStateException(String.format(Locale.ROOT,
                    "stream ended early: %d ms of %d ms (%.4f)", playedMs, info.durationMs(), ratio));
        }
    }
}
