package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    @DisplayName("アーティスト名は先頭から噛み合えば通す (客演の足し引き)")
    void acceptsWhenTheLeadArtistAgrees() {
        assertTrue(TrackMatch.sameRecording("Daft Punk - One More Time", "Daft Punk",
                "Daft Punk feat. Romanthony - One More Time", "uploader"));
    }

    @Test
    @DisplayName("主が入れ替わったマッシュアップを落とす (scsearch が実際に返した1件)")
    void rejectsMashupThatMerelyContainsTheArtist() {
        // 語の集合で包含を見ていた時はこれが通り、2Pac のマッシュアップが焼かれていた。
        assertFalse(TrackMatch.sameRecording("Alan Walker - Faded", "Alan Walker",
                "2Pac Ft. Alan Walker - Faded", "Jamie Gos"));
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
    @DisplayName("隅付き括弧【】の中の版の目印も拾う")
    void findsVariantMarkersInsideLenticularBrackets() {
        // oEmbed が実際に返したタイトル (2026-08-18 取得)。NFKC は【】を半角へ倒さないので、
        // ここを見ないと「歌ってみた」の目印が消えて原曲と一致してしまう。
        assertTrue(TrackMatch.variantMarkers("【歌ってみた】ライラック / Mrs. GREEN APPLE【にじさんじ/珠乃井ナナ】")
                .contains("歌ってみた"));
        assertFalse(TrackMatch.sameRecording("Mrs. GREEN APPLE - ライラック", "Mrs. GREEN APPLE",
                "【歌ってみた】ライラック / Mrs. GREEN APPLE【にじさんじ/珠乃井ナナ】", "珠乃井ナナ"));
    }

    @Test
    @DisplayName("曲名とアーティストが既に分かれている入口では、再分割しない (Spotify 経路)")
    void doesNotSplitAlreadySeparatedMetadata() {
        // og:title は「Song - Remastered 2011」のようにダッシュ付きの版注記を持つ。生の入口へ
        // 渡すと "Bohemian Rhapsody" がアーティストとして切り出されて照合が壊れる。
        assertTrue(TrackMatch.sameRecordingFromCleanSource("Bohemian Rhapsody - Remastered 2011",
                "Bohemian Rhapsody - Remastered 2011", "Queen",
                "Queen - Bohemian Rhapsody - Remastered 2011", "queenofficial"));
        // 候補に版注記が無ければ落とす (元の版と同じとは言えないので、鳴らさない側に倒す)。
        assertFalse(TrackMatch.sameRecordingFromCleanSource("Bohemian Rhapsody - Remastered 2011",
                "Bohemian Rhapsody - Remastered 2011", "Queen",
                "Queen - Bohemian Rhapsody", "queenofficial"));
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

    // --- watch ページから採った実物の尺 (2026-08-18) ---
    /** {@code SkTt9k4Y-a8} の {@code "lengthSeconds":"439"}。player API は拒否するのに読めた。 */
    private static final long YT_LMFAO_MS = 439_000L;
    /** {@code dQw4w9WgXcQ} の {@code "lengthSeconds":"213"}。 */
    private static final long YT_ASTLEY_MS = 213_000L;
    /** scsearch が返した Interscope の販促版 (1:30)。 */
    private static final long SC_LMFAO_SHORT_MS = 90_130L;
    /** scsearch が返した pajlada の再アップロード。YouTube 側と 0.5 秒差。 */
    private static final long SC_ASTLEY_MS = 212_525L;

    @Test
    @DisplayName("販促版・試聴版を尺で落とす")
    void rejectsShortenedUploads() {
        // 7:19 の動画に対する 1:30 の販促版。これが焼かれていたのを潰すのがこの判定の目的。
        assertFalse(TrackMatch.durationFits(YT_LMFAO_MS, SC_LMFAO_SHORT_MS));
        // 同じ形がもう 1 件。Interscope の「OneRepublic - Counting Stars」90.47 秒 / 元 4:43。
        assertFalse(TrackMatch.durationFits(283_000L, 90_470L));
        // 3:30 の曲に対する 30 秒の試聴版。
        assertFalse(TrackMatch.durationFits(210_000L, 30_000L));
        // 1 時間のループ・寄せ集め。
        assertFalse(TrackMatch.durationFits(YT_ASTLEY_MS, 3_600_000L));
        // 尺そのものが取れていない候補。
        assertFalse(TrackMatch.durationFits(YT_ASTLEY_MS, 0L));
    }

    @Test
    @DisplayName("正しい候補は尺で落とさない (実測した 4 件の比をそのまま固定する)")
    void keepsLegitimateLengths() {
        // ほぼ同尺 (0.998)。
        assertTrue(TrackMatch.durationFits(YT_ASTLEY_MS, SC_ASTLEY_MS));
        // Adele「Hello」: MV 6:07 に対して音源 4:41 = 0.767。実測した最小の正しい比。
        assertTrue(TrackMatch.durationFits(367_000L, 281_600L));
        // PSY「GANGNAM STYLE」= 1.027 / Isaac silva の再アップロード = 0.881。
        assertTrue(TrackMatch.durationFits(252_000L, 258_897L));
        assertTrue(TrackMatch.durationFits(252_000L, 221_903L));
        // フェード・イントロの数秒差。
        assertTrue(TrackMatch.durationFits(YT_ASTLEY_MS, 205_000L));
        assertTrue(TrackMatch.durationFits(YT_ASTLEY_MS, 221_000L));
    }

    @Test
    @DisplayName("元の尺が取れない時は尺で落とさない (機能を丸ごと殺さない)")
    void neverBlocksWhenTheSourceLengthIsUnknown() {
        assertTrue(TrackMatch.durationFits(0L, SC_LMFAO_SHORT_MS));
        assertTrue(TrackMatch.durationFits(-1L, 30_000L));
    }

    @Test
    @DisplayName("尺が分かるなら元に一番近いもの、分からないなら一番長いものを採る")
    void picksTheClosestOrTheLongest() {
        // 実測 (dQw4w9WgXcQ): 元 3:33 に対して 3:32 の再アップロードと 6:16 の長尺版が並んだ。
        // 「一番長い」で採ると 6:16 の方を焼いてしまうので、近さで選ぶ。
        final long[] astley = {SC_ASTLEY_MS, 376_085L};
        assertEquals(0, TrackMatch.bestByDuration(astley, YT_ASTLEY_MS));
        // 元の尺が分からなければ一番長いものを採る (尺を見る前の挙動)。
        assertEquals(1, TrackMatch.bestByDuration(astley, 0L));
        // 販促版しか無ければ何も選ばない (従来の失敗に落ちる)。
        assertEquals(-1, TrackMatch.bestByDuration(
                new long[] {SC_LMFAO_SHORT_MS, SC_LMFAO_SHORT_MS}, YT_LMFAO_MS));
        // 販促版とフル尺が並んだら、販促版は幅で落ちてフル尺が残る。
        assertEquals(1, TrackMatch.bestByDuration(new long[] {90_470L, 250_000L}, 283_000L));
        assertEquals(-1, TrackMatch.bestByDuration(new long[0], YT_LMFAO_MS));
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
