package com.kuronami.musicdiscmaker.lavaplayer;

import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;

import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.ClientConfig;
import dev.lavalink.youtube.clients.ClientOptions;
import dev.lavalink.youtube.clients.Ios;

/**
 * YouTube の iOS クライアントを、<b>この MOD が実測した値で名乗る</b>。
 *
 * <h2>なぜ upstream の {@link Ios} をそのまま使わないか</h2>
 * youtube-source の最新リリース 1.18.2 が持つ {@code Ios} は {@code 19.45.4} +
 * {@code MOBILE_PLAYER_PARAMS} で、この組み合わせは player API が <b>HTTP 400</b> を返す
 * (2026-08-21 実測)。実際に通るのは {@code 21.32.4} + {@code WEB_PLAYER_PARAMS} で、
 * これは upstream の main には入っているが<b>リリースされていない</b>。
 *
 * <h2>なぜ snapshot を引かずに自前で持つか</h2>
 * 1.18.2 と main (commit {@code f45bbb7}) のソース差分は<b>4 ファイルだけ</b>で、
 * そのうち再生に効くのは {@code Ios} のこの 2 行だけだった (他は {@code Android} の版番号、
 * {@code Tv} の User-Agent、{@code YoutubeAudioTrack} の itag18 用 contentLength 補完)。
 * つまり snapshot を引いて得られるものはこのクラスと同じで、代わりに
 * <b>ビルドが「動く標的」に依存する</b>。
 *
 * <p>逆に自前で持つと、次にクライアントが失効した時に<b>upstream のリリースを待たずに</b>
 * ここの定数を書き換えるだけで出荷できる。{@code AndroidVr} が 2026-08-17 に失効してから
 * 4 日経ってもリリース版に修正が来ていない、という実績がこの判断の根拠。
 *
 * <h2>取っていない upstream の修正</h2>
 * main の {@code YoutubeAudioTrack} は、contentLength を持たない itag18 を掴んだ時に
 * {@code Range: bytes=0-0} で全長を問い合わせる。持たないと最初の range 境界で読み終わる。
 * {@code Ios} が返すのは itag140 (contentLength あり) なのでこの経路には乗らないが、
 * <b>1.19.0 が出たら上げて取り込む</b>。
 *
 * <h2>cipher 免疫</h2>
 * {@code Ios.requirePlayerScript()} は false なので、{@code AndroidVr} と同じく
 * 署名暗号 (player.js) を触らない。他 MOD が繰り返し壊れている故障モードへの免疫は保たれる。
 *
 * @see MusicLoaderImpl#registerYoutube()
 */
final class YoutubeIosClient extends Ios {

    /**
     * 名乗るクライアント版。<b>ここと {@link #USER_AGENT} が失効対応の単一の書き換え点。</b>
     * 出所: 2026-08-21 実測 (この版で player API が 200、ストリームが全長完走)。
     */
    static final String CLIENT_VERSION = "21.32.4";

    /** iOS アプリの User-Agent。版番号は {@link #CLIENT_VERSION} と揃える。 */
    private static final String USER_AGENT = "com.google.ios.youtube/" + CLIENT_VERSION
            + " (iPhone16,2; U; CPU iOS 18_1_0 like Mac OS X;)";

    /**
     * upstream の {@code Ios.BASE_CONFIG} と同じ形を、版番号だけ差し替えて自前で持つ。
     *
     * <p>upstream の static を書き換えないのは、{@code ClientConfig.copy()} が浅いコピーで
     * 入れ子の map を共有するため — 上書きすると同じ JVM の他の {@code Ios} にも漏れる。
     */
    private static final ClientConfig BASE_CONFIG = new ClientConfig()
            .withUserAgent(USER_AGENT)
            .withClientName("IOS")
            .withClientField("clientVersion", CLIENT_VERSION)
            .withUserField("lockedSafetyMode", false);

    YoutubeIosClient() {
        super(ClientOptions.DEFAULT);
    }

    @Override
    protected ClientConfig getBaseClientConfig(HttpInterface httpInterface) {
        return BASE_CONFIG.copy();
    }

    /**
     * {@code 2AMB}。upstream 1.18.2 の {@code Ios} が返す {@code MOBILE_PLAYER_PARAMS}
     * ({@code CgIIAdgDAQ%3D%3D}) だと player API が HTTP 400 を返す。
     */
    @Override
    public String getPlayerParams() {
        return WEB_PLAYER_PARAMS;
    }

    /**
     * {@code ytsearch:} は引き受けない。<b>iOS の検索は結果を 0 件しか返さない</b>
     * (2026-08-21 実測。同じ問い合わせで {@code Web} / {@code AndroidVr} は 20 件)。
     *
     * <p>引き受けたまま 0 件を返すと、{@code loadSearch} が
     * {@code AudioReference.NO_TRACK} という<b>非 null</b> を返すため
     * {@code YoutubeAudioSourceManager} のループがそこで打ち切られ、
     * 検索できる client が後ろに居ても試されない。ここで false を返して
     * {@link YoutubeSearchClient} へ渡す。
     */
    @Override
    public boolean canHandleRequest(String identifier) {
        return super.canHandleRequest(identifier)
                && !identifier.startsWith(YoutubeAudioSourceManager.SEARCH_PREFIX);
    }
}
