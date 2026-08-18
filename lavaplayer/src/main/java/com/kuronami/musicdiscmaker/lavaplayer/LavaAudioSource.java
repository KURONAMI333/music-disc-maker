package com.kuronami.musicdiscmaker.lavaplayer;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.PlaybackFault;
import com.kuronami.musicdiscmaker.lavaplayer.api.PlaybackFaultRelay;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEvent;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventListener;
import com.sedmelluq.discord.lavaplayer.player.event.TrackExceptionEvent;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;

/**
 * 再生中の {@link AudioPlayer} から PCM frame を pull する {@link IAudioSource} 実装。
 * 隔離 classloader 側に置かれ、mod へは PCM bytes だけを渡す。
 *
 * <p>再生スレッドの中で落ちた例外もここで受ける ({@link AudioEventListener})。lavaplayer /
 * youtube-source の例外型は隔離 classloader の中にしか無いので、<b>分類までをここで済ませ</b>、
 * 境界の向こうへは {@link PlaybackFault} (理由 + 短い文字列) だけを渡す。
 */
class LavaAudioSource implements IAudioSource, AudioEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(LavaAudioSource.class);

    /** 開始時の buffering を待つ上限 (ms)。 */
    private static final long BUFFER_DEADLINE_MS = 10_000L;

    private final AudioPlayer player;

    private byte[] leftover;
    private int leftoverPos;
    private volatile boolean ended;
    /**
     * 再生中に壊れた理由の受け渡し口。書き込み = 再生スレッド / 読み出し = MC の streaming スレッド。
     *
     * <p>理由が確定した瞬間に届け先へ push する ({@link PlaybackFaultRelay})。終端で pull させる形
     * だけでは、lavaplayer が「ストリームの終わり」を例外イベントより先に公開するせいで毎回取り落とす。
     */
    private final PlaybackFaultRelay relay = new PlaybackFaultRelay();

    LavaAudioSource(AudioPlayer player) {
        this.player = player;
    }

    /**
     * 再生スレッドで落ちた例外を受ける。{@code TrackExceptionEvent} だけを見る —
     * フレームが来ない停滞 ({@code TrackStuckEvent}) は {@link #read} の
     * {@link #BUFFER_DEADLINE_MS} 判定が「実際に再生が終わった時点で」拾うので、
     * 一時的な停滞から復帰した場合に誤検知しない側だけを残す。
     */
    @Override
    public void onEvent(AudioEvent event) {
        if (event instanceof TrackExceptionEvent ex) {
            // 詳細は「分類の根拠になった例外」から採る。lavaplayer は再生スレッドの例外を
            // FriendlyException で包むので、一番外側の文面はどの失敗でも定型文になる。
            // youtube-source の集約例外なら client ごとの理由を優先する (先頭行だけを採ると
            // 「All clients failed to load the item.」しか残らず、何も伝わらない)。
            final FailureReason reason = ClientFailureDetails.classify(ex.exception);
            final String detail = ClientFailureDetails.shortDetail(ex.exception);
            // チャットは 1 行なので、client ごとの長い理由とスタックトレースはログ側へ出す。
            // 最後の引数の Throwable は slf4j が原因チェーンごと展開する (2b9eeee と同じ形)。
            final String verbose = ClientFailureDetails.verbose(ex.exception);
            LOGGER.warn("Playback failed on the audio thread [{}] {}{}", reason, detail,
                    verbose.isEmpty() ? "" : System.lineSeparator() + verbose, ex.exception);
            record(reason, detail);
        }
    }

    /** 最初の 1 件だけを残し、届け先が居ればその場で流す。 */
    private void record(FailureReason reason, String detail) {
        relay.record(reason, detail);
    }

    @Override
    public PlaybackFault playbackFault() {
        return relay.fault();
    }

    @Override
    public void onPlaybackFault(Consumer<PlaybackFault> sink) {
        relay.sink(sink);
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

            // 再生スレッドで落ちていたら、持っている分を吐いて終わらせる。ここで止めないと
            // 「再生中の表示だけ残って永久に無音」= 直そうとしている症状そのものになる。
            if (relay.fault() != null) {
                ended = true;
                break;
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
                    // 再生が例外で落ちた場合、ここが最初に着く。lavaplayer は例外を配る前に
                    // frame buffer を terminate するので、terminator を見た provide() が
                    // activeTrack を落とし、理由がまだ無いまま「終わった」だけが見える。
                    // 理由は後から relay 経由で push されるので、ここで待たずに終わってよい。
                    ended = true;
                    break;
                }
                if (written > 0) {
                    break;
                }
                if (System.currentTimeMillis() > deadline) {
                    // トラックは「再生中」なのに 1 フレームも来ないまま上限に達した = 餓死。
                    // ここは停滞が実際に再生の終わりになった時点なので、復帰する見込みは無い
                    // (TrackStuckEvent を別に見張らないのはこのため)。
                    ended = true;
                    record(FailureReason.CONNECTION_FAILED, "starved " + BUFFER_DEADLINE_MS + "ms");
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
            player.removeListener(this);
            player.stopTrack();
            player.destroy();
        } catch (final Throwable ignored) {
            // close 時の例外は無視
        }
    }
}
