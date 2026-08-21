package com.kuronami.musicdiscmaker.lavaplayer;

import dev.lavalink.youtube.YoutubeAudioSourceManager;
import dev.lavalink.youtube.clients.ClientOptions;
import dev.lavalink.youtube.clients.Web;

/**
 * {@code ytsearch:} <b>だけ</b>を引き受けるクライアント。動画も再生も引き受けない。
 *
 * <h2>なぜ要るか</h2>
 * 再生を担う {@link YoutubeIosClient} は<b>検索の結果を 0 件しか返さない</b>
 * (2026-08-21 実測。同じ問い合わせで {@code Web} と {@code AndroidVr} は 20 件返し、
 * {@code Ios} と {@code Music} は 0 件)。検索が死ぬと Spotify のリンクが道連れになる —
 * {@code resolveViaSpotify} は og タグで取った曲名を {@code ytsearch:} で引くのが本筋の経路で、
 * ここが空だと代替ソース (SoundCloud) の別音源に落ちる。実際、落ちた先は同じ曲の
 * 6 分半のアップロードで、公式の 3 分半とは別物だった。
 *
 * <h2>D1 の「鳴らない client を並べるな」に反していない理由</h2>
 * D1 が指した害は<b>順序で避けているだけの重なり</b>だった — {@code Web} は watch URL の
 * ロードに成功してしまうので前段扱いにならず、失敗が非同期側 (再生スレッド) に回る。
 *
 * <p>ここでは<b>重なりを構造から消してある</b>。{@link #canHandleRequest} が
 * {@code ytsearch:} 以外に false を返すので、この client は watch URL を<b>一度も見ない</b>。
 * 逆に {@link YoutubeIosClient} は {@code ytsearch:} に false を返す。
 * <b>どの入力もちょうど 1 つの client にしか行かない</b>ので、
 * 「前の client がロードに成功して後ろが試されない」も「鳴らない client が当たる」も起きない。
 *
 * <p>検索が返すのは URL の並びであって再生する {@code AudioTrack} ではない。MDM は
 * {@code openStream} で<b>URL から解決し直す</b> ({@code MusicLoaderImpl:507}) ので、
 * 実際に鳴らすのは常に {@link YoutubeIosClient}。この client が作った track は再生されない。
 *
 * <p>検索は cipher を触らない ({@code requirePlayerScript()} を見るのは
 * {@code loadTrackInfoFromInnertube} と {@code YoutubeAudioTrack} だけで、
 * {@code loadSearchResults} は素の POST 1 本)。D1 の cipher 免疫は保たれる。
 *
 * <h2>なぜ {@code AndroidVr} でなく {@code Web} か</h2>
 * 検索は今 {@code AndroidVr} でも通る。それでも {@code Web} を採るのは、
 * <b>{@code AndroidVr} は YouTube 側が現に畳んでいる最中の client</b> だから
 * (D25/D26)。畳まれている足場の上に検索を戻すと、また数週間で同じことになる。
 *
 * @see YoutubeIosClient
 */
final class YoutubeSearchClient extends Web {

    private static final ClientOptions SEARCH_ONLY = searchOnly();

    YoutubeSearchClient() {
        super(SEARCH_ONLY);
    }

    private static ClientOptions searchOnly() {
        final ClientOptions options = new ClientOptions();
        options.setSearching(true);
        options.setVideoLoading(false);
        options.setPlaylistLoading(false);
        options.setPlayback(false);
        return options;
    }

    /**
     * {@code ytsearch:} 以外は引き受けない。<b>この 1 メソッドが D1 の害を構造から消している</b> —
     * ここで false を返した client は {@code YoutubeAudioSourceManager} のループで
     * {@code continue} され、ロードを試みることすらない。
     */
    @Override
    public boolean canHandleRequest(String identifier) {
        return identifier.startsWith(YoutubeAudioSourceManager.SEARCH_PREFIX);
    }
}
