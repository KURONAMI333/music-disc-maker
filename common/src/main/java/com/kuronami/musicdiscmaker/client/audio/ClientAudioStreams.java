package com.kuronami.musicdiscmaker.client.audio;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.Config;
import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.audio.LoaderHolder;
import com.kuronami.musicdiscmaker.audio.cache.AudioCacheGate;
import com.kuronami.musicdiscmaker.audio.cache.AudioCachePolicy;
import com.kuronami.musicdiscmaker.audio.cache.AudioCacheStore;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.lavaplayer.api.IAudioSource;

import net.minecraft.client.Minecraft;

/**
 * client 側で再生ストリームを開く唯一の入口。<b>再生経路はすべてここを通す</b>
 * (強化版ジューク・バニラジューク・スピーカー・ブームボックス・Create/Aeronautics の移動構造物・
 * SB バックパック)。音源ローカルキャッシュはこの 1 箇所で差し込まれる。
 *
 * <h2>cache-first</h2>
 * キャッシュがあればネットワークに<b>一切触らない</b>。network-first にすると、外部が壊れている時
 * ({@code loadTrackSync} の 30 秒タイムアウト) に毎回 30 秒無音になり、この機能の存在理由が消える。
 *
 * <h2>書き込みは頭から通した再生だけ</h2>
 * {@code startMs > 0} では書かない。シーク・リピート折返し・chunk 再入・途中参加はすべて
 * 非ゼロ offset で来るので、これを書くと<b>頭が欠けたファイル</b>ができ、cache-first の下で
 * 以後ずっと途中から鳴ることになる。読み出し側は offset 有りでも当然使える。
 *
 * <p>失敗はすべて fail-soft。キャッシュ周りで何が起きても、従来どおりのネットワーク再生に落ちるだけ。
 */
public final class ClientAudioStreams {

    private static volatile AudioCacheStore store;

    private ClientAudioStreams() {
    }

    /**
     * 再生ストリームを開く。
     *
     * @param track   再生する曲 (URL・尺・ラジオ判定を持つ)
     * @param startMs 開始位置 (ms)。0 なら頭から
     * @return PCM ソース。失敗時は {@code null}
     */
    @Nullable
    public static IAudioSource open(CustomTrackData track, long startMs) {
        if (track == null || track.isEmpty()) {
            return null;
        }
        final AudioCacheStore cache = cacheEnabled() ? store() : null;
        if (cache == null || !AudioCachePolicy.cacheable(track)) {
            return LoaderHolder.get().openStream(track.url(), startMs);
        }
        // 判断そのものは MC 非依存の gate に置いてある (headless テストで固定できるように)。
        // ここは置き場と config を用意するだけ。
        return AudioCacheGate.open(LoaderHolder.get(), cache, track, startMs,
                maxBytes(), ClientAudioStreams::cacheEnabled);
    }

    private static boolean cacheEnabled() {
        try {
            return Config.audioCacheEnabled();
        } catch (final Throwable t) {
            return false; // config を引けない環境では機能ごと諦める
        }
    }

    private static long maxBytes() {
        return (long) Math.max(0, Config.audioCacheMaxMB()) * 1024L * 1024L;
    }

    /**
     * キャッシュの置き場。{@code <game dir>/music_disc_maker/cache}。
     * ビートマップ ({@code .beat}) と同じ規約で、シングルプレイでは同じディレクトリに同居する。
     */
    @Nullable
    private static AudioCacheStore store() {
        AudioCacheStore local = store;
        if (local == null) {
            synchronized (ClientAudioStreams.class) {
                local = store;
                if (local == null) {
                    try {
                        local = new AudioCacheStore(Minecraft.getInstance().gameDirectory.toPath()
                                .resolve("music_disc_maker").resolve("cache"));
                        // 落ちた前回の .part をここで 1 回だけ掃除する。claim からだけ呼ぶと、
                        // 全曲キャッシュ命中のセッションでは一度も走らず溜まり続ける。
                        local.sweepStalePartsOnce();
                        store = local;
                    } catch (final Throwable t) {
                        MusicDiscMaker.LOGGER.warn("音源キャッシュの置き場を決められない: {}", t.toString());
                        return null;
                    }
                }
            }
        }
        return local;
    }
}
