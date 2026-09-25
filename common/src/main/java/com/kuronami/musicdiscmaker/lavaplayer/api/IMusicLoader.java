package com.kuronami.musicdiscmaker.lavaplayer.api;

import java.nio.file.Path;

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
     * 信頼済みのローカル MP3 から曲のメタ情報を解決する (ブロッキング)。
     *
     * <p>これは URL 入力の抜け道ではない。呼び出し側が内容ハッシュから自分で組み立て、
     * 保存領域の内側にあることを検証済みの {@link Path} だけを渡す。利用者から受け取った
     * パス文字列や {@code file:} URL をここへ変換してはいけない。実装は返す
     * {@link TrackInfo} に端末上の絶対パスを含めない。
     *
     * <p>既定実装が {@code null} を返すのは、橋渡し interface だけ新しく impl の
     * {@code jar.packed} が古い組み合わせでも URL 再生を壊さないため。
     *
     * <p>v3.1.0 の持込形式は MP3 だけであり、実装は拡張子でなく内容を probe して判定する。
     * WAV / OGG 等を後から対応する場合は、受入形式と上限を決めてから明示的に広げる。
     *
     * @param file 呼び出し側で保存領域内と検証済みの通常ファイル
     * @return 解決できた場合はメタ情報、未対応の古い実装では {@code null}
     */
    default TrackInfo resolveTrustedFile(Path file) {
        return null;
    }

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

    /**
     * 信頼済みのローカル音声ファイルを先頭から開く。
     *
     * @param file 呼び出し側で保存領域内と検証済みの通常ファイル
     * @return PCM ソース、失敗時または未対応の古い実装では {@code null}
     * @see #resolveTrustedFile(Path)
     */
    default IAudioSource openTrustedFile(Path file) {
        return openTrustedFile(file, 0L);
    }

    /**
     * 信頼済みのローカル音声ファイルを指定位置から開く。
     *
     * <p>{@code file} の信頼境界は {@link #resolveTrustedFile(Path)} と同じ。元のアップロード名や
     * client から届いたパスを渡さず、内容ハッシュから解決した保存済みファイルだけを渡す。
     *
     * @param file    呼び出し側で保存領域内と検証済みの通常ファイル
     * @param startMs 開始位置 (ミリ秒)。0 以下なら先頭
     * @return PCM ソース、失敗時または未対応の古い実装では {@code null}
     */
    default IAudioSource openTrustedFile(Path file, long startMs) {
        return null;
    }

    /**
     * {@link #openTrustedFile(Path, long)} と同じことをして、開けなかった理由も返す。
     *
     * @param file    呼び出し側で保存領域内と検証済みの通常ファイル
     * @param startMs 開始位置 (ミリ秒)。0 以下なら先頭
     * @return 開けた PCM ソース、または開けなかった理由
     */
    default OpenStreamResult openTrustedFileDetailed(Path file, long startMs) {
        final IAudioSource source = openTrustedFile(file, startMs);
        return source != null ? OpenStreamResult.ok(source)
                : OpenStreamResult.failed(FailureReason.UNKNOWN, null);
    }
}
