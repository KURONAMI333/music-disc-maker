package com.kuronami.musicdiscmaker.lavaplayer;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;

/**
 * 再生中の {@link AudioPlayer} から PCM frame を pull する {@link IAudioSource} 実装。
 * 隔離 classloader 側に置かれ、mod へは PCM bytes だけを渡す。
 */
class LavaAudioSource implements IAudioSource {

    /** 開始時の buffering を待つ上限 (ms)。 */
    private static final long BUFFER_DEADLINE_MS = 10_000L;

    private final AudioPlayer player;

    private byte[] leftover;
    private int leftoverPos;
    private volatile boolean ended;

    LavaAudioSource(AudioPlayer player) {
        this.player = player;
    }

    @Override
    public int sampleRate() {
        return MusicLoaderImpl.SAMPLE_RATE;
    }

    @Override
    public int channels() {
        return MusicLoaderImpl.CHANNELS;
    }

    @Override
    public int bitsPerSample() {
        return 16;
    }

    @Override
    public boolean bigEndian() {
        return false;
    }

    @Override
    public int read(byte[] dst, int off, int len) {
        if (ended && leftover == null) {
            return -1;
        }
        int written = 0;
        final long deadline = System.currentTimeMillis() + BUFFER_DEADLINE_MS;

        while (written < len) {
            if (leftover != null) {
                final int n = Math.min(len - written, leftover.length - leftoverPos);
                System.arraycopy(leftover, leftoverPos, dst, off + written, n);
                leftoverPos += n;
                written += n;
                if (leftoverPos >= leftover.length) {
                    leftover = null;
                    leftoverPos = 0;
                }
                continue;
            }

            AudioFrame frame;
            try {
                frame = player.provide(40, TimeUnit.MILLISECONDS);
            } catch (final TimeoutException ex) {
                frame = null; // タイムアウト = この間フレーム無し
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }

            if (frame == null) {
                if (player.getPlayingTrack() == null) {
                    ended = true;
                    break;
                }
                if (written > 0) {
                    break;
                }
                if (System.currentTimeMillis() > deadline) {
                    ended = true;
                    break;
                }
                continue;
            }
            // lavaplayer 出力は stereo (MusicLoaderImpl の LAVA_OUTPUT_CHANNELS)。
            // ここで mono へ downmix して MC へ渡す (channels()==1 と整合)。
            leftover = downmixStereoToMono(frame.getData());
            leftoverPos = 0;
        }

        if (written == 0) {
            return ended ? -1 : 0;
        }
        return written;
    }

    /**
     * interleaved stereo S16LE を mono S16LE へ downmix する (L/R 平均)。lavaplayer の出力は
     * stereo 固定 (LAVA_OUTPUT_CHANNELS) なので、ここで確実に mono 化する。奇数余りは切り捨てる。
     */
    private static byte[] downmixStereoToMono(byte[] stereo) {
        final int sampleFrames = stereo.length / 4; // 4 bytes = L(2) + R(2)
        final byte[] mono = new byte[sampleFrames * 2];
        for (int i = 0; i < sampleFrames; i++) {
            final int s = i * 4;
            final short l = (short) ((stereo[s] & 0xFF) | (stereo[s + 1] << 8));
            final short r = (short) ((stereo[s + 2] & 0xFF) | (stereo[s + 3] << 8));
            final int m = (l + r) / 2;
            final int d = i * 2;
            mono[d] = (byte) (m & 0xFF);
            mono[d + 1] = (byte) ((m >> 8) & 0xFF);
        }
        return mono;
    }

    @Override
    public void close() {
        ended = true;
        try {
            player.stopTrack();
            player.destroy();
        } catch (final Throwable ignored) {
            // close 時の例外は無視
        }
    }
}
