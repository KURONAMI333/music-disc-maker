package com.kuronami.musicdiscmaker.lavaplayer.api;

/**
 * URL 解決が失敗した理由。GUI に理由別メッセージを出すために impl (隔離 classloader) から
 * mod 側へ返す。api パッケージなので bridge classloader が両側に同一クラスを渡す。
 *
 * <p>enum 名は BlockEntity の同期 NBT にそのまま書かれるので、リネームは互換を壊す。
 */
public enum FailureReason {

    /**
     * 分類できない一般的な失敗。<b>分類器の既定値</b>でもある。
     *
     * <p>ここに「回線が落ちている」等の推測を混ぜないこと。分類器がどれにも当てられなかった
     * ことだけを表す。{@link #CONNECTION_FAILED} と分けているのは、根拠なく回線を疑わせる
     * 文面を利用者に出さないため。
     */
    UNKNOWN,
    /** 対応していないサービス / URL 形式 (どの source manager も一致しない)。 */
    UNSUPPORTED_URL,
    /** 動画が非公開・削除済み・存在しない。 */
    PRIVATE_OR_REMOVED,
    /** 地域制限で再生できない。 */
    REGION_LOCKED,
    /** 年齢制限で再生できない。 */
    AGE_RESTRICTED,
    /**
     * ホストへ到達できなかった (DNS 解決失敗・接続拒否・タイムアウト・切断)。
     *
     * <p><b>例外の型で積極的に判別した時だけ</b>ここへ落とす ({@code FailureClassifier} の
     * {@code hasNetworkCause})。既定値ではないので、この理由が出ている時は「この端末とホストの
     * 間で通信が成立しなかった」と言い切ってよい。
     */
    CONNECTION_FAILED,
    /**
     * 相手は応答したが、要求を通さなかった (401/403/429 以外のステータス番号。400 / 404 / 410 / 5xx 等)。
     *
     * <p>{@link #CONNECTION_FAILED} と分けるのは、利用者に言うことが逆になるため。
     * こちらは<b>この端末とホストの間の通信そのものは成立している</b>ので、回線や DNS を
     * 疑わせる文面は嘘になる。401/403/429 だけは「この接続を名指しで拒んだ」なので
     * {@link #BOT_CHECK} に寄せる (MDM_DECISIONS D8)。
     *
     * <p>再試行は {@link #CONNECTION_FAILED} と同じく行う (5xx は開き直すと通ることがある)。
     * 代替ソース探しは行わない — 発火集合は MDM_DECISIONS D11 のまま変えない
     * ({@code MusicLoaderImpl#firesSubstitute})。
     */
    SOURCE_REFUSED,
    /** SSRF ガードが URL を拒否した (Track 1 が設定する)。 */
    BLOCKED_URL,
    /** YouTube の bot 判定でログインを要求された (datacenter IP でよく起きる。接続失敗ではない)。 */
    BOT_CHECK,
    /**
     * 音源が冒頭の試聴版しか配信していない (SoundCloud GO+ の {@code monetization_model:
     * SUB_HIGH_TIER})。<b>曲は在るが、全長を取る手段が無い</b>。
     *
     * <p>{@link #UNSUPPORTED_URL} と分けるのは、そちらへ落とすと嘘になるため。lavaplayer の
     * 試聴版フィルタは弾いた曲を {@code NO_TRACK} として返し、それが {@code noMatches} まで
     * 素通りするので、何もしないと正当な SoundCloud のリンクに「非対応のリンクです」と出る。
     *
     * <p>{@link #PRIVATE_OR_REMOVED} 等とも分ける。あちらは「その音源では観られない」なので
     * 代替ソース探しが効くが、こちらは<b>探した先でも同じ試聴版が返る</b> (フィルタは検索経路にも
     * 効くので候補から落ちる)。やり直しても結果は変わらないので
     * {@code MusicLoaderImpl#isRetryable} は false のままにする。
     *
     * <p>この理由だけは<b>サービス名を文面に出してよい</b>。{@link #BOT_CHECK} が
     * 「YouTube が〜」を止めたのは、あれがどのホストでも起きるので名指しが誤りになるため。
     * 試聴版フィルタは SoundCloud の source manager にしか無いので、名指しは常に正しい。
     */
    PREVIEW_ONLY;

    /**
     * 制作機 GUI に出す短いラベルの翻訳キー。
     *
     * <p><b>enum 名の機械変換ではない</b>ので、呼び出し側で {@code name().toLowerCase()} を
     * 書かないこと (存在しないキーを引いて画面に生キーが出る。{@code PlaybackFailure.Kind}
     * と同じ規律)。
     *
     * <p>対応表をここに置いて {@code default} を書かないのは、<b>コンパイラに検出させる</b>ため。
     * 画面側の switch に置いたままだと、理由を 1 つ増やした時に黙って汎用の「取得失敗」へ落ち、
     * 何も壊れずに粒度だけが消える。lang への追記漏れは
     * {@code FailureReasonLangCoverageTest} が見る。
     *
     * @return 翻訳キー
     */
    public String guiKey() {
        return switch (this) {
            case UNKNOWN -> "gui.music_disc_maker.failed";
            case UNSUPPORTED_URL -> "gui.music_disc_maker.failed.unsupported";
            case PRIVATE_OR_REMOVED -> "gui.music_disc_maker.failed.private";
            case REGION_LOCKED -> "gui.music_disc_maker.failed.region";
            case AGE_RESTRICTED -> "gui.music_disc_maker.failed.age";
            case CONNECTION_FAILED -> "gui.music_disc_maker.failed.connection";
            case SOURCE_REFUSED -> "gui.music_disc_maker.failed.refused";
            case BLOCKED_URL -> "gui.music_disc_maker.failed.blocked";
            case BOT_CHECK -> "gui.music_disc_maker.failed.botcheck";
            case PREVIEW_ONLY -> "gui.music_disc_maker.failed.preview";
        };
    }

    /** NBT / 同期から安全に復元する (未知の名前は UNKNOWN)。 */
    public static FailureReason fromName(String name) {
        if (name == null || name.isBlank()) {
            return UNKNOWN;
        }
        try {
            return FailureReason.valueOf(name);
        } catch (final IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}
