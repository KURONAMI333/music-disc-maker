package com.kuronami.musicdiscmaker.lavaplayer;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

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
 *
 * <h2>{@link #read} は待たない</h2>
 * MC はこの {@code read} を<b>単一の "Sound engine" スレッド</b>から引く。同じスレッドが
 * {@code SoundEngine#play} の {@code channelAccess.createHandle(...).join()} も捌くので、
 * ここで待つと<b>バニラの足音・ブロック音を含む全ての効果音と Render thread が道連れで止まる</b>。
 * データが無い時は待たずに短く返し、足りない分は呼び出し側 (MC 寄りの層) が無音で埋める。
 */
class LavaAudioSource implements IAudioSource, AudioEventListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(LavaAudioSource.class);

    /**
     * データが 1 バイトも来ない状態が続いた時に「餓死」と判断する上限 (ms)。
     *
     * <p><b>待ち時間ではなく時計で測る。</b> 引く側を止めて待つ形にすると、その待ち時間が
     * そのまま Sound engine スレッドの停止時間になる。空腹が始まった時刻を覚えておき、
     * <b>次に引かれた時に</b>経過を見る。
     */
    private static final long STARVE_DEADLINE_MS = 10_000L;

    /**
     * frame を 1 つ待つ上限 (ms)。frame buffer から引くだけなので定常状態では待たずに返り、
     * 一瞬のずれだけをここで吸収する。<b>この 40ms が read 1 回の待ち時間の上限</b>
     * (MC の {@code pumpBuffers(4)} 全体でも 4 回 = 160ms が上限)。
     */
    private final AudioPlayer player;
    /** 餓死判定に使う時計 (test から差し替えるための口)。 */
    private final LongSupplier clockMs;

    private byte[] leftover;
    private int leftoverPos;
    private volatile boolean ended;
    /**
     * frame を取れない状態が始まった時刻。{@code 0} = 空腹ではない。
     *
     * <p>「最後にデータを取れた時刻」ではなく「空腹が始まった時刻」を持つ。前者だと、
     * MC がしばらく引きに来なかった (バッファが満杯で読む必要が無かった) だけで
     * 経過時間が伸び、再開した最初の read が餓死と誤判定される。
     */
    private long hungrySinceMs;
    /**
     * 再生中に壊れた理由の受け渡し口。書き込み = 再生スレッド / 読み出し = MC の streaming スレッド。
     *
     * <p>理由が確定した瞬間に届け先へ push する ({@link PlaybackFaultRelay})。終端で pull させる形
     * だけでは、lavaplayer が「ストリームの終わり」を例外イベントより先に公開するせいで毎回取り落とす。
     */
    private final PlaybackFaultRelay relay = new PlaybackFaultRelay();

    LavaAudioSource(AudioPlayer player) {
        this(player, System::currentTimeMillis);
    }

    /**
     * @param player  引き先の player
     * @param clockMs 餓死判定に使う時計 (ms)
     */
    LavaAudioSource(AudioPlayer player, LongSupplier clockMs) {
        this.player = player;
        this.clockMs = clockMs;
    }

    /**
     * 再生スレッドで落ちた例外を受ける。{@code TrackExceptionEvent} だけを見る —
     * フレームが来ない停滞 ({@code TrackStuckEvent}) は {@link #read} の
     * {@link #STARVE_DEADLINE_MS} 判定が拾い、しかも一時的な停滞から復帰すれば
     * 空腹の時計が戻るので誤検知しない。
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

    /**
     * {@inheritDoc}
     *
     * <p><b>足りなければ短く返す。埋まるまで待たない。</b> 戻り値が {@code len} 未満なら
     * 「今この瞬間に出せる分はここまで」の意味で、呼び出し側は残りを無音で埋めてよい
     * (クラス javadoc の「read は待たない」)。
     */
    @Override
    public int read(byte[] dst, int off, int len) {
        if (ended && leftover == null) {
            return -1;
        }
        int written = 0;

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

            // provide() は待たない (timeout 0)。Sound engine スレッドを掴まないための要。
            final AudioFrame frame = player.provide();

            if (frame == null) {
                if (player.getPlayingTrack() == null) {
                    // 再生が例外で落ちた場合、ここが最初に着く。lavaplayer は例外を配る前に
                    // frame buffer を terminate するので、terminator を見た provide() が
                    // activeTrack を落とし、理由がまだ無いまま「終わった」だけが見える。
                    // 理由は後から relay 経由で push されるので、ここで待たずに終わってよい。
                    ended = true;
                    break;
                }
                noteStarvation();
                break; // 今は出せない。待たずに、持っている分だけ返す
            }
            hungrySinceMs = 0L; // 実データが来た = 空腹の時計を戻す
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
     * frame が取れなかった時の空腹の記帳。空腹が {@link #STARVE_DEADLINE_MS} 続いていたら餓死とする。
     *
     * <p>トラックは「再生中」なのにデータが来ない状態がこれだけ続けば、復帰する見込みは無い。
     * 一時的な停滞から復帰した場合は {@link #read} が時計を戻すので、ここには来ない。
     */
    private void noteStarvation() {
        final long now = clockMs.getAsLong();
        if (hungrySinceMs == 0L) {
            hungrySinceMs = now;
            return;
        }
        if (now - hungrySinceMs > STARVE_DEADLINE_MS) {
            ended = true;
            record(FailureReason.CONNECTION_FAILED, "starved " + STARVE_DEADLINE_MS + "ms");
        }
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
