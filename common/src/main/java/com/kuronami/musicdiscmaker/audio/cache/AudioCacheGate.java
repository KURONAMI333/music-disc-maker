package com.kuronami.musicdiscmaker.audio.cache;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;
import com.kuronami.musicdiscmaker.lavaplayer.api.IMusicLoader;
import com.kuronami.musicdiscmaker.lavaplayer.api.IOpusEncoder;

/**
 * 「キャッシュから鳴らすか / ネットワークから鳴らして写し取るか」の判断。
 *
 * <p>MC の API を参照しないので、headless テストが偽の {@link IMusicLoader} を渡して
 * <b>ネットワークに出たかどうか</b>まで固定できる。client 側の入口
 * ({@code ClientAudioStreams}) は置き場と config を用意してここへ委ねるだけにしてある
 * — 判断がそちらに残っていると、いちばん壊れると痛い分岐が機械ゲートの外に出てしまう。
 */
public final class AudioCacheGate {

    private AudioCacheGate() {
    }

    /**
     * 再生ストリームを開く。
     *
     * <p>順序が仕様そのもの:
     * <ol>
     *   <li><b>命中したらネットワークに一切触らない</b> (cache-first)。network-first にすると、
     *       外部が壊れている時に {@code loadTrackSync} の 30 秒タイムアウトを毎回食う。</li>
     *   <li>不命中なら従来どおりネットワークから開き、<b>頭から通す再生に限り</b>写し取る。</li>
     * </ol>
     *
     * @param stillEnabled 書き込み完了時にもう一度 config を見るための判定
     *                     (再生中に OFF にされたら焼かない)
     */
    @Nullable
    public static IAudioSource open(IMusicLoader loader, AudioCacheStore store, CustomTrackData track,
            long startMs, long maxBytes, BooleanSupplier stillEnabled) {
        final String key = AudioCacheStore.keyOf(track.url());

        final Path hit = store.hit(key);
        if (hit != null) {
            final IAudioSource cached = CachedAudioSource.open(hit,
                    loader.openOpusDecoder(AudioCacheFormat.SAMPLE_RATE, AudioCacheFormat.CHANNELS),
                    startMs);
            if (cached != null) {
                return cached;
            }
            // 壊れていた (CachedAudioSource がその場で削除済み) → ネットワークへ落ちる
        }

        final IAudioSource network = loader.openStream(track.url(), startMs);
        if (network == null || !writable(track, startMs) || !TeeAudioSource.formatMatches(network)) {
            return network;
        }
        if (!store.claim(key)) {
            return network; // 既にキャッシュ済み / 他の再生が書いている / 失敗が続いた
        }
        final IOpusEncoder encoder = loader.openOpusEncoder(
                AudioCacheFormat.SAMPLE_RATE, AudioCacheFormat.CHANNELS, AudioCacheFormat.FRAME_SAMPLES);
        final AudioCacheWriter writer = AudioCacheWriter.open(
                store, key, track.url(), track.durationMs(), maxBytes, encoder, stillEnabled);
        if (writer == null) {
            if (encoder != null) {
                encoder.close();
            }
            store.release(key);
            return network;
        }
        return new TeeAudioSource(network, writer);
    }

    /**
     * この再生を写し取ってよいか。
     *
     * <p><b>{@code startMs > 0} では書かない</b>のが肝。シーク・リピート折返し・chunk 再入・
     * 途中参加はすべて非ゼロ offset で来るので、これを書くと<b>頭が欠けたファイル</b>ができ、
     * cache-first の下で以後ずっと途中から鳴ることになる (rot より悪い)。
     * 読み出し側は offset 有りでも当然使える。
     */
    public static boolean writable(CustomTrackData track, long startMs) {
        return startMs <= 0L && AudioCachePolicy.cacheable(track);
    }
}
