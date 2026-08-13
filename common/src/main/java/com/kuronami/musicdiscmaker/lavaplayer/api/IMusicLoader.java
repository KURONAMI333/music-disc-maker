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

    /**
     * {@link #openStream(String, long)} と同じことをして、<b>開けなかった時はその理由も</b>返す。
     *
     * <p>{@code openStream} は DNS 失敗も年齢制限も地域制限も YouTube の bot 判定も、すべて
     * {@code null} に潰して返す。呼び出し側はそれを「ストリーム取得失敗」1 種類としか読めず、
     * 利用者は次に何をすればいいか (回線を疑う / 別の URL にする / ログインが要る) を判断できない。
     * impl は {@link ResolveException} の形で理由を持っているので、それをここから外へ出す。
     *
     * <p>既定実装が {@code openStream} に落ちるのは、橋渡し interface だけ新しく impl の
     * {@code jar.packed} が古い組み合わせでも壊れないようにするため。その場合の理由は
     * {@link FailureReason#UNKNOWN} になる (従来と同じ粒度に戻るだけで、鳴らなくはならない)。
     *
     * @param url     再生する URL
     * @param startMs 開始位置 (ミリ秒)。0 以下なら先頭
     * @return 開けた PCM ソース、または開けなかった理由
     */
    default OpenStreamResult openStreamDetailed(String url, long startMs) {
        final IAudioSource source = openStream(url, startMs);
        return source != null ? OpenStreamResult.ok(source)
                : OpenStreamResult.failed(FailureReason.UNKNOWN, null);
    }
}
