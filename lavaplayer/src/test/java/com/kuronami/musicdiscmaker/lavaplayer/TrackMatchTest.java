package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.kuronami.musicdiscmaker.lavaplayer.api.FailureReason;

/**
 * 代替ソースの照合を、<b>実際に観測した文字列</b>で固定する。
 *
 * <p>元の側は YouTube oEmbed が返した実物 (2026-08-18 取得)、候補の側は
 * {@code scsearch:} が返した実物の上位 5 件。<b>作り話の文字列でなく、通す/落とすの境目に
 * 実際に並んだ列で固定する</b>ため、ここに直接埋め込んである。
 *
 * <p>ネットワークには出ない。oEmbed も検索もここでは呼ばない。
 */
class TrackMatchTest {

    // --- YouTube oEmbed が返した実物 ---
    private static final String YT_ASTLEY_TITLE =
            "Rick Astley - Never Gonna Give You Up (Official Video) (4K Remaster)";
    private static final String YT_ASTLEY_AUTHOR = "Rick Astley";
    private static final String YT_LMFAO_TITLE = "LMFAO - Sorry For Party Rocking";
    private static final String YT_LMFAO_AUTHOR = "LMFAOVEVO";
    /** RIAA 削除済み {@code Gc4HGQHgeFE} の oEmbed。<b>曲名ですらない</b>。 */
    private static final String YT_JUNK_TITLE = "LOOK AT YOURSELF AFTER WATCHING THIS.mp4";
    private static final String YT_JUNK_AUTHOR = "harrish0789";

    @Test
    @DisplayName("再アップロードでも、曲名から本来のアーティストが取れれば通す")
    void acceptsReuploadOfTheSameRecording() {
        // scsearch の 1 件目。投稿者は "pajlada" だが曲名が "Artist - Song" 型なので照合できる。
        assertTrue(TrackMatch.sameRecording(YT_ASTLEY_TITLE, YT_ASTLEY_AUTHOR,
                "Rick Astley - Never Gonna Give You Up", "pajlada"));
    }

    @Test
    @DisplayName("レーベルの公式アップロードを通す (VEVO 接尾辞は照合の邪魔をしない)")
    void acceptsLabelUpload() {
        assertTrue(TrackMatch.sameRecording(YT_LMFAO_TITLE, YT_LMFAO_AUTHOR,
                "LMFAO - Sorry For Party Rocking", "Interscope Records"));
    }

    @Test
    @DisplayName("カバー・bootleg・nightcore・remix は落とす (scsearch が実際に返した 4 件)")
    void rejectsVariantsFromTheRealSearchResult() {
        assertFalse(TrackMatch.sameRecording(YT_ASTLEY_TITLE, YT_ASTLEY_AUTHOR,
                "Branime Studios - Never Gonna Give You Up ( Rick Astley Cover )", "Ruan Elias"));
        assertFalse(TrackMatch.sameRecording(YT_ASTLEY_TITLE, YT_ASTLEY_AUTHOR,
                "Never Gonna Give You Up - Rick Astley (CAP Bootleg)", "CAP"));
        assertFalse(TrackMatch.sameRecording(YT_ASTLEY_TITLE, YT_ASTLEY_AUTHOR,
                "[Nightcore] Rick Astley - Never Gonna Give You Up", "clouD"));
        assertFalse(TrackMatch.sameRecording(YT_ASTLEY_TITLE, YT_ASTLEY_AUTHOR,
                "Rick Astley - Never Gonna Give You Up (Daymaan Remix)", "Daymaan"));
    }

    @Test
    @DisplayName("加工版 (slowed + reverbed) を落とす")
    void rejectsProcessedVariant() {
        assertFalse(TrackMatch.sameRecording(YT_LMFAO_TITLE, YT_LMFAO_AUTHOR,
                "LMFAO :: Sorry For Party Rocking [slowed + reverbed]", "andy"));
    }

    @Test
    @DisplayName("曲を含む寄せ集めを落とす (曲名は部分一致では通さない)")
    void rejectsCompilationThatContainsTheSong() {
        assertFalse(TrackMatch.sameRecording(YT_LMFAO_TITLE, YT_LMFAO_AUTHOR,
                "LMFAO - Sexy And I Know It, Sorry For Party Rocking, Party Rock Anthem",
                "Haviidds Hendra"));
    }

    @Test
    @DisplayName("曲名が一致してもアーティストが噛み合わなければ落とす")
    void rejectsWhenOnlyTheTitleAgrees() {
        assertFalse(TrackMatch.sameRecording(YT_JUNK_TITLE, YT_JUNK_AUTHOR,
                YT_JUNK_TITLE, "Random Uploader"));
        assertFalse(TrackMatch.sameRecording("Rick Astley - Never Gonna Give You Up", "Rick Astley",
                "Never Gonna Give You Up", "Some Random Band"));
    }

    @Test
    @DisplayName("照合する材料が無ければ落とす (空のアーティスト・空の曲名)")
    void rejectsWhenThereIsNothingToCompare() {
        assertFalse(TrackMatch.sameRecording("Never Gonna Give You Up", "",
                "Never Gonna Give You Up", "Rick Astley"));
        assertFalse(TrackMatch.sameRecording("Never Gonna Give You Up", "Rick Astley",
                "Never Gonna Give You Up", ""));
        assertFalse(TrackMatch.sameRecording("", "Rick Astley", "Never Gonna Give You Up",
                "Rick Astley"));
        assertFalse(TrackMatch.sameRecording(null, "Rick Astley", "Never Gonna Give You Up",
                "Rick Astley"));
    }

    @Test
    @DisplayName("版の目印は両側で揃っていれば通す (ライブ音源にライブ音源を当てる)")
    void acceptsWhenTheVariantMarkerAgreesOnBothSides() {
        assertTrue(TrackMatch.sameRecording("Adele - Someone Like You (Live)", "AdeleVEVO",
                "Adele - Someone Like You (Live)", "livesession"));
        // 片側だけライブなら別の録音。
        assertFalse(TrackMatch.sameRecording("Adele - Someone Like You", "AdeleVEVO",
                "Adele - Someone Like You (Live)", "livesession"));
    }

    @Test
    @DisplayName("アーティスト名は片方がもう片方を含めば通す (feat. の有無)")
    void acceptsWhenOneArtistNameContainsTheOther() {
        assertTrue(TrackMatch.sameRecording("Daft Punk - One More Time", "Daft Punk",
                "Daft Punk feat. Romanthony - One More Time", "uploader"));
    }

    @Test
    @DisplayName("全角・引用符・日本語の曲名でも照合できる")
    void handlesJapaneseTitles() {
        assertTrue(TrackMatch.sameRecording("YOASOBI「アイドル」", "YOASOBI",
                "YOASOBI - アイドル", "Ayase"));
        assertFalse(TrackMatch.sameRecording("YOASOBI「アイドル」", "YOASOBI",
                "アイドル (歌ってみた)", "だれか"));
    }

    @Test
    @DisplayName("目印は括弧の中だけを見る (曲名そのものの語で落とさない)")
    void marksOnlyInsideBrackets() {
        assertTrue(TrackMatch.variantMarkers("Live and Let Die").isEmpty());
        assertTrue(TrackMatch.variantMarkers("Paul McCartney - Live and Let Die (Live)")
                .contains("live"));
        assertTrue(TrackMatch.sameRecording("Wings - Live and Let Die", "Paul McCartney",
                "Live and Let Die", "Wings"));
    }

    @Test
    @DisplayName("表記ゆれだけの語 (and / & / feat) は照合から落ちる")
    void ignoresConnectiveWords() {
        assertTrue(TrackMatch.words("Rock & Roll").equals(TrackMatch.words("Rock and Roll")));
    }

    @Test
    @DisplayName("代替ソースを探し始める失敗の一覧を固定する")
    void firesOnlyOnYoutubeSideRefusals() {
        assertTrue(MusicLoaderImpl.firesSubstitute(FailureReason.BOT_CHECK));
        assertTrue(MusicLoaderImpl.firesSubstitute(FailureReason.AGE_RESTRICTED));
        assertTrue(MusicLoaderImpl.firesSubstitute(FailureReason.REGION_LOCKED));
        assertTrue(MusicLoaderImpl.firesSubstitute(FailureReason.PRIVATE_OR_REMOVED));
        // 利用者の回線が死んでいる / 分類できなかった / URL 自体が拒まれた場合は探しに行かない。
        assertFalse(MusicLoaderImpl.firesSubstitute(FailureReason.CONNECTION_FAILED));
        assertFalse(MusicLoaderImpl.firesSubstitute(FailureReason.UNKNOWN));
        assertFalse(MusicLoaderImpl.firesSubstitute(FailureReason.BLOCKED_URL));
        assertFalse(MusicLoaderImpl.firesSubstitute(FailureReason.UNSUPPORTED_URL));
    }

    @Test
    @DisplayName("oEmbed は素の watch URL 以外には問い合わせない")
    void oEmbedOnlyAcceptsNormalizedWatchUrls() {
        assertNull(YoutubeOEmbed.fetch(null, 1_000L));
        assertNull(YoutubeOEmbed.fetch("https://youtu.be/dQw4w9WgXcQ", 1_000L));
        assertNull(YoutubeOEmbed.fetch("https://www.youtube.com/watch?v=dQw4w9WgXcQ&list=x", 1_000L));
        assertNull(YoutubeOEmbed.fetch("https://evil.example/watch?v=dQw4w9WgXcQ", 1_000L));
        assertNull(YoutubeOEmbed.fetch("ytsearch:rick astley", 1_000L));
        // 形は合っていても予算が無ければ問い合わせない (ネットワークに出ないことの確認も兼ねる)。
        assertNull(YoutubeOEmbed.fetch("https://www.youtube.com/watch?v=dQw4w9WgXcQ", 0L));
    }
}
