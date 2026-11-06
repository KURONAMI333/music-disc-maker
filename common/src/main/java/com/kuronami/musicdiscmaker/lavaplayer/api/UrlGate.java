package com.kuronami.musicdiscmaker.lavaplayer.api;

/**
 * 開く前の URL 検査を loader へ差し込む口。
 *
 * <h2>なぜ loader から検査を呼べないか</h2>
 * 実際の検査 ({@code UrlGuard}) は mod 側 (common) にあり、loader は隔離 classloader で動く。
 * その classloader が mod 側へ委譲するのは api パッケージと {@code org.slf4j} だけなので、
 * loader から {@code UrlGuard} は見えない。そこで<b>判定を mod 側に置いたまま、開く直前に
 * この口を通す</b>。
 *
 * <h2>なぜ代わりの URL に要るか</h2>
 * 盤の URL は mod 側の各再生入口で {@code UrlGuard} を通っている。だが再生時に検索で見つける
 * 代わりの配信元は loader の中で決まり、mod 側の入口を一度も通らない。ここを通さないと、
 * 検索結果が内部アドレス (クラウドメタデータ・LAN 機器・ループバック) を指していた時に
 * そのまま接続してしまう。
 *
 * <p>api パッケージなので JDK 以外に依存しない ({@link OpenStreamResult} と同じ制約)。
 */
@FunctionalInterface
public interface UrlGate {

    /**
     * @param url 開こうとしている URL ({@code null} にはならない)
     * @return 開いてよければ {@code true}
     */
    boolean allows(String url);
}
