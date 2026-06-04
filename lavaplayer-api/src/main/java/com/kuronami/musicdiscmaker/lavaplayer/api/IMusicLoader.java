package com.kuronami.musicdiscmaker.lavaplayer.api;

/**
 * mod (mod classloader) から LavaPlayer impl (隔離 classloader) を叩くための入口。
 * impl は {@code lavaplayer} サブプロジェクト側に置かれ、ServiceLoader で隔離
 * classloader 経由でインスタンス化される。
 */
public interface IMusicLoader {

    /**
     * URL から曲のメタ情報を解決する (ブロッキング)。
     *
     * @param url YouTube / SoundCloud / Bandcamp / Vimeo / Twitch / HTTP の URL
     * @return 解決できた場合は {@link TrackInfo}、対応外 / 失敗時は {@code null}
     */
    TrackInfo resolve(String url);

    /**
     * URL の再生を先頭から開始し、PCM を pull するソースを返す (client 側で使う)。
     *
     * @param url 再生する URL
     * @return PCM ソース、失敗時は {@code null}
     */
    default IAudioSource openStream(String url) {
        return openStream(url, 0L);
    }

    /**
     * URL の再生を {@code startMs} の位置から開始し、PCM を pull するソースを返す。
     * 後から jukebox の chunk に入った player に途中から同期再生させるのに使う。
     * トラックが seek 不可 (ライブ配信等) / {@code startMs <= 0} の時は先頭/ライブ端から再生する。
     *
     * @param url     再生する URL
     * @param startMs 開始位置 (ミリ秒)。0 以下なら先頭
     * @return PCM ソース、失敗時は {@code null}
     */
    IAudioSource openStream(String url, long startMs);
}
