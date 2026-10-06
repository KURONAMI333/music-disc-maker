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
     * 信頼済みのローカル音声ファイルから曲のメタ情報を解決する (ブロッキング)。
     *
     * <p>これは URL 入力の抜け道ではない。呼び出し側が内容ハッシュから自分で組み立て、
     * 保存領域の内側にあることを検証済みの {@link Path} だけを渡す。利用者から受け取った
     * パス文字列や {@code file:} URL をここへ変換してはいけない。実装は返す
     * {@link TrackInfo} に端末上の絶対パスを含めない。
     *
     * <p>既定実装が {@code null} を返すのは、橋渡し interface だけ新しく impl の
     * {@code jar.packed} が古い組み合わせでも URL 再生を壊さないため。
     *
     * <p>受理する保存形式は音声だけで完結しシークできるコンテナ (MP3 / WAV / FLAC / MKA)
     * に限り、実装は拡張子でなく内容を probe して判定する。プレイリスト形式や、入力としては
     * 読めるが保存形式でないコンテナ (MP4 等) はここでは受理されない。
     *
     * <p>検査はコンテナ probe レベルであり、音声全 frame の decode を保証しない。
     * MP3 は frame 索引を読むため実質全走査になるが、WAV/FLAC/MKA はヘッダ上の尺が
     * 正とみなされるため、ヘッダだけ有効で音声が実質無いファイルも受理されうる
     * (その場合尺>0で受理され、再生時に無音/失敗となる)。matroska として検出された
     * ファイルは別途 track 一覧を検査し、映像 track を含む MKV は保存できない。
     *
     * @param file 呼び出し側で保存領域内と検証済みの通常ファイル
     * @return 解決できた場合はメタ情報、未対応の古い実装では {@code null}
     */
    default TrackInfo resolveTrustedFile(Path file) {
        return null;
    }

    /**
     * ローカルの音声・動画ファイルを decode し、mono 48kHz の Opus 音声だけを持つ
     * MKA (matroska audio) へ正規化する (ブロッキング)。
     *
     * <p>用途は「利用者が持ち込んだ任意のメディアから、保存・転送してよい音声だけを
     * 取り出す」こと。入力は拡張子でなく内容 (コンテナ probe) で判定され、動画ファイルの
     * 映像トラックは読み捨てる — 生成物の MKA には音声 packet しか含まれず、入力の
     * 映像バイトは {@code output} へ一切写らない。
     *
     * <p>入力として decode できるコンテナは保存形式より広い (MP4 / MOV / MKV / WebM /
     * Ogg / ADTS / WAV / FLAC / MP3)。生成物は<b>エンコード中に打ち切る</b>:
     * 出力見積が 32MiB に達するか、encode した実尺が 60 分に達した時点でそれより先の
     * 音声を捨てる。宣言尺が取れない入力・宣言尺を偽装した入力でも、出力は
     * 60 分を超えない (硬い上限)。宣言された尺が 60 分を超える入力は早期に拒否する。
     *
     * <p>書き出しは {@code output} と同じディレクトリの一時ファイルへ行い、生成物を
     * 実装自身で検証 (尺&gt;0・reader が decode できる・seek が効く) してから、
     * 既存名を置換しない原子的な発行 (hard link) で {@code output} へ置く。
     * <b>失敗時は {@code output} を作らず一時ファイルも残さない</b>。既存の
     * {@code output} は上書きしない — 発行の直前に第三者が同名を作った競合でも
     * 置換せず失敗に倒す。
     *
     * <p>失敗は {@link ResolveException} で返す: 破損・decode 不能な入力、音声が 1 frame
     * も取れなかった入力 (尺ゼロ)、呼び出しスレッドの中断 (interrupt はこの操作の
     * キャンセル手段)、Opus encoder の native が利用不能な環境、置換しない原子的な
     * 発行を保証できないファイルシステム (hard link 非対応等)。
     *
     * <p>既定実装が {@code null} を返すのは、橋渡し interface だけ新しく impl の
     * {@code jar.packed} が古い組み合わせで「normalize 未対応」を表すため。
     * {@link #resolveTrustedFile(Path)} と同じ後方互換の形。
     *
     * @param input  decode する入力ファイル (信頼済み保存領域である必要はないが、
     *               呼び出し側が利用者の選択を受理した通常ファイル)
     * @param output 生成する MKA の最終パス。既存ファイルは上書きしない
     * @return 生成した MKA のメタ情報 (尺&gt;0)。未対応の古い実装では {@code null}
     * @throws ResolveException 入力・変換・検証・置き換えのいずれかが失敗した
     */
    default TrackInfo normalizeLocalFile(Path input, Path output) {
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
