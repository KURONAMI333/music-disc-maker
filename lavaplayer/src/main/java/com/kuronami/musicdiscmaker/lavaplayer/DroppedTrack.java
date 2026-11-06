package com.kuronami.musicdiscmaker.lavaplayer;

import com.sedmelluq.discord.lavaplayer.tools.JsonBrowser;

/**
 * 一覧 (検索結果・set・liked) から<b>落とした曲</b>の情報。落とした後に、同じ曲を別の配信元から
 * 探し直して一覧の同じ位置へ戻すために持ち回る。
 *
 * <p>一覧の経路は曲を 1 件ずつ組み立て、鳴らせない曲は {@code null} にして飛ばす。飛ばした
 * ままだと、検索結果の先頭が試聴版だった時に「一覧が 1 件ずれる」か、全件がそうなら
 * 「対応外のリンクです」という嘘になる。曲名・投稿者・全長があれば、単曲の時と同じ検索で
 * 代わりを探せる。
 *
 * <p>例外ではなく値で渡すのは、落とすのが失敗ではなく<b>一覧の 1 件の扱い</b>だから。
 * 単曲は {@link UnplayableSoundCloudTrack} で理由つきに倒すが、一覧では残りの曲を捨てない。
 *
 * @param title      曲名 (SoundCloud の {@code title})。空のことがある
 * @param author     投稿者の表示名 ({@code user.username})。空のことがある
 * @param durationMs 全曲の長さ (ms)。プレビューの曲でも全長を名乗る {@code full_duration}。不明なら 0
 * @param url        元の曲の URL ({@code permalink_url})。曲名が空の時に slug から作る
 * @param position   落ちた時の一覧の位置 (先頭を 0 とする)。分からなければ {@code -1}
 */
record DroppedTrack(String title, String author, long durationMs, String url, int position) {

    DroppedTrack {
        title = title == null ? "" : title;
        author = author == null ? "" : author;
        durationMs = Math.max(0L, durationMs);
        url = url == null ? "" : url;
        position = Math.max(-1, position);
    }

    /**
     * SoundCloud の track JSON から作る。
     *
     * @param trackData 1 曲分の JSON
     * @param preview   落とした理由が試聴版か ({@code false} = DRM など、{@code duration} が全長)
     * @param position  落ちた時の一覧の位置。分からなければ {@code -1}
     * @return 落とした曲の情報
     */
    static DroppedTrack from(JsonBrowser trackData, boolean preview, int position) {
        final long full = trackData.get("full_duration").asLong(0L);
        // full_duration が無い時の duration は、試聴版なら 30 秒前後で全長ではない。その値を尺と
        // して使うと正しい代わりが長さで全部落ちるので、分からない時は 0 (尺不明) にする。
        // DRM の duration は全長なのでそのまま使える (単曲の経路と同じ判断)。
        final long duration = full > 0L ? full : preview ? 0L : trackData.get("duration").asLong(0L);
        return new DroppedTrack(trackData.get("title").safeText(),
                trackData.get("user").get("username").safeText(), duration,
                trackData.get("permalink_url").safeText(), position);
    }

    /**
     * 同じ曲か。記録が重なると 1 曲のために 2 回検索するので、記録する前に見る。
     *
     * <p>URL があればそれで判定する (SoundCloud の {@code permalink_url} は曲ごとに一意)。
     * URL を欠いた track JSON もあるので、その時は曲名・投稿者・全長の組で見る。
     *
     * @param other 比べる記録
     * @return 同じ曲とみなせるなら {@code true}
     */
    boolean sameTrack(DroppedTrack other) {
        if (!url.isEmpty() && !other.url.isEmpty()) {
            return url.equals(other.url);
        }
        return title.equals(other.title) && author.equals(other.author)
                && durationMs == other.durationMs;
    }
}
