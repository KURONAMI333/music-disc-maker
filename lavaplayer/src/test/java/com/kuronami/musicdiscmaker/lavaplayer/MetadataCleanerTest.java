package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * ディスクに焼かれる「曲名 / アーティスト」を<b>実際に観測した文字列</b>で固定する。
 *
 * <h2>なぜここを固定するか</h2>
 * {@link MetadataCleaner} は代替ソース探しだけの部品ではなく、<b>普通に YouTube のリンクを
 * 貼った時の解決経路と共有</b>している ({@code MusicLoaderImpl#resolve})。ここを触ると
 * 全利用者のディスク名が動くので、代表的な型を先に固定しておく。
 *
 * <p>入力は全て YouTube oEmbed が実際に返した {@code title} / {@code author_name}
 * (2026-08-18 取得・388 件を採ってそこから型ごとに選んだ)。<b>作り話の文字列は置かない。</b>
 *
 * <p>ネットワークには出ない。
 */
class MetadataCleanerTest {

    private static void clean(String rawTitle, String rawAuthor, String title, String author) {
        assertArrayEquals(new String[] {title, author}, MetadataCleaner.clean(rawTitle, rawAuthor));
    }

    /**
     * 分割と接尾辞の骨格。<b>ここが表す規則が動いたら何かを壊している</b>
     * (客演表記の除去だけは別の節が担当するので、この節の入力にも混ざる)。
     */
    @Nested
    @DisplayName("分割と接尾辞の骨格")
    class PlainShapes {

        @Test
        @DisplayName("Artist - Song に括弧ノイズが付いた型")
        void splitsOnHyphenAndDropsBracketNoise() {
            clean("Rick Astley - Never Gonna Give You Up (Official Video) (4K Remaster)",
                    "Rick Astley", "Never Gonna Give You Up", "Rick Astley");
            clean("Adele - Hello (Official Music Video)", "Adele", "Hello", "Adele");
            clean("Ed Sheeran - Shape of You (Official Music Video)", "Ed Sheeran",
                    "Shape of You", "Ed Sheeran");
        }

        @Test
        @DisplayName("author の VEVO 接尾辞を落とす")
        void dropsVevoSuffix() {
            clean("OneRepublic - Counting Stars", "OneRepublicVEVO",
                    "Counting Stars", "OneRepublic");
            clean("Taylor Swift - Blank Space", "Taylor Swift", "Blank Space", "Taylor Swift");
        }

        @Test
        @DisplayName("名前の中のハイフンでは割らない (前後の空白を要求する)")
        void keepsHyphenInsideNames() {
            clean("G-Eazy - Provide (Official Video) ft. Chris Brown, Mark Morrison",
                    "GEazyMusicVEVO", "Provide", "G-Eazy");
        }

        @Test
        @DisplayName("引用型は引用の中を曲名、前をアーティストにする")
        void splitsQuotedShape() {
            clean("YOASOBI「夜に駆ける」 Official Music Video", "YOASOBI", "夜に駆ける", "YOASOBI");
            clean("BTS (방탄소년단) 'Dynamite' Official MV", "HYBE LABELS",
                    "Dynamite", "BTS (방탄소년단)");
        }

        @Test
        @DisplayName("- Topic チャンネルは曲名を割らない")
        void keepsTopicChannelTitle() {
            clean("I Got Your Back (Feat. JISOO, MOMOKA of HANA) (Korean Ver.)", "ILLIT - Topic",
                    "I Got Your Back (Korean Ver.)", "ILLIT");
        }

        @Test
        @DisplayName("空白が二つ続く型でも割れる")
        void toleratesDoubleSpaces() {
            clean("米津玄師  Kenshi Yonezu  - Lemon", "Kenshi Yonezu  米津玄師",
                    "Lemon", "米津玄師  Kenshi Yonezu");
        }
    }

    /** 曲名側の客演表記は落とす。<b>アーティスト側は落とさない。</b> */
    @Nested
    @DisplayName("客演表記")
    class Featuring {

        @Test
        @DisplayName("曲名の末尾に付いた客演を落とす")
        void dropsTrailingFeature() {
            clean("Luis Fonsi - Despacito ft. Daddy Yankee", "LuisFonsiVEVO",
                    "Despacito", "Luis Fonsi");
            clean("Mark Ronson - Uptown Funk (Official Video) ft. Bruno Mars", "MarkRonsonVEVO",
                    "Uptown Funk", "Mark Ronson");
        }

        @Test
        @DisplayName("括弧に入った客演も落とす")
        void dropsBracketedFeature() {
            clean("Charlie Puth - We Don't Talk Anymore (feat. Selena Gomez) [Official Video]",
                    "Charlie Puth", "We Don't Talk Anymore", "Charlie Puth");
        }

        @Test
        @DisplayName("w. も客演の書き方として落とす")
        void dropsAbbreviatedWith() {
            clean("Post Malone - I Like You (A Happier Song) w. Doja Cat [Official Music Video]",
                    "PostMaloneVEVO", "I Like You (A Happier Song)", "Post Malone");
        }

        @Test
        @DisplayName("アーティスト側の客演は残す (照合が先頭から突き合わせるので材料が要る)")
        void keepsFeatureOnTheArtistSide() {
            clean("MACKLEMORE FEAT LIL YACHTY - MARMALADE (OFFICIAL MUSIC VIDEO)", "Macklemore",
                    "MARMALADE", "MACKLEMORE FEAT LIL YACHTY");
        }

        @Test
        @DisplayName("括弧が開いたままの位置では落とさない (開きっぱなしを残さない)")
        void keepsFeatureInsideAnOpenBracket() {
            clean("Dynasty (Orchestral Version feat. KORK)", "MIIA",
                    "Dynasty (Orchestral Version feat. KORK)", "MIIA");
        }

        @Test
        @DisplayName("客演の後ろに / で続く別名は残す")
        void keepsTheAliasAfterASlash() {
            // 2026-08-19 実測。888 件中 23 件がこの形で、目印から文末まで落とすと英題が消えていた。
            clean("ピノキオピー - 神っぽいな feat. 初音ミク / God-ish",
                    "ピノキオピー PINOCCHIOP OFFICIAL CHANNEL",
                    "神っぽいな / God-ish", "ピノキオピー");
            // 客演が 2 人並ぶ形でも、別名まで飲み込まない。
            clean("ピノキオピー - T氏の話を信じるな feat. 初音ミク・重音テト / Don’t Believe in T",
                    "ピノキオピー PINOCCHIOP OFFICIAL CHANNEL",
                    "T氏の話を信じるな / Don’t Believe in T", "ピノキオピー");
        }

        @Test
        @DisplayName("客演の後ろに括弧で続く原語の曲名も残す")
        void keepsTheAliasInsideBrackets() {
            // 2026-08-19 実測 (NK5q8RTz5yc)。注記の括弧ではないので、括弧ノイズ除去では落ちない。
            clean("Induja Perera - Mithuriya feat. @DILUBeats (මිතුරියක නොවේ ඔබ මගේ) "
                    + "Official Music Video ", "INDUJA PERERA",
                    "Mithuriya (මිතුරියක නොවේ ඔබ මගේ)", "Induja Perera");
        }

        @Test
        @DisplayName("区切りが無ければ従来どおり文末まで落とす")
        void stillDropsToTheEndWithoutASeparator() {
            // [Official Video] は括弧ノイズとして先に消えるので、客演の後ろに区切りが残らない。
            // Furious 7 Soundtrack が曲名の続きか客演の一部かは字面から分けられないので落とす。
            clean("Wiz Khalifa - See You Again ft. Charlie Puth [Official Video] "
                    + "Furious 7 Soundtrack", "Wiz Khalifa Music", "See You Again", "Wiz Khalifa");
        }
    }

    /** 区切りと注記の表記ゆれ。<b>実測に出た形だけ</b>を扱う。 */
    @Nested
    @DisplayName("区切りと全角の注記")
    class Notation {

        @Test
        @DisplayName("en dash / em dash の区切りでも割れる")
        void splitsOnDashVariants() {
            clean("Queen – Bohemian Rhapsody (Official Video Remastered)", "Queen Official",
                    "Bohemian Rhapsody", "Queen");
            clean("AUBREY — Bread (Acoustic Cover | Jhino Bilbao | Live)", "JHINO OFFICIAL",
                    "Bread (Acoustic Cover | Jhino Bilbao | Live)", "AUBREY");
        }

        @Test
        @DisplayName("全角括弧の注記も落とす")
        void dropsFullWidthBracketNoise() {
            clean("Official髭男dism - Pretender［Official Video］", "Official髭男dism",
                    "Pretender", "Official髭男dism");
            clean("ヨルシカ - 晴る（OFFICIAL VIDEO）", "ヨルシカ / n-buna Official", "晴る", "ヨルシカ");
            clean("【MV】CANDY TUNE「倍倍FIGHT!」", "KAWAII LAB.", "倍倍FIGHT!", "CANDY TUNE");
        }

        @Test
        @DisplayName("コロンと縦棒は区切りにしない (曲名の一部として出るため)")
        void doesNotSplitOnColonOrBar() {
            clean("Justin Bieber - Sorry (PURPOSE : The Movement)", "JustinBieberVEVO",
                    "Sorry (PURPOSE : The Movement)", "Justin Bieber");
            clean("Omoinotake | 幾億光年 【Official Music Video】", "Omoinotake",
                    "Omoinotake | 幾億光年", "Omoinotake");
        }
    }
}
