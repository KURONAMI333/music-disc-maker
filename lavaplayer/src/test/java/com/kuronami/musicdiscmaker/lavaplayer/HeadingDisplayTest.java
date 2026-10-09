package com.kuronami.musicdiscmaker.lavaplayer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * 表示用の曲名・アーティスト (2026-10-09 本人指摘: 【】の見出し・作品名・曲名と歌手の分離)。
 * 入力は YouTube の実タイトルと投稿者 (2026-10-09 採取、
 * {@code _work/mdm-310-execution/title-corpus-20261009/})。
 */
class HeadingDisplayTest {

    @Test
    void picksTheSongOverTheTieInWork() {
        assertDisplay("TVアニメ『リコリス・リコイル』ノンテロップOPムービー｜ClariS「ALIVE」", "アニプレックス チャンネル",
                "ALIVE", "ClariS");
        assertDisplay("TVアニメ『呪術廻戦』第2期「懐玉・玉折」ノンクレジットOPムービー／OPテーマ：キタニタツヤ「青のすみか」"
                + "｜毎週木曜夜11時56分～MBS/TBS系列全国28局にて放送中!!", "TOHO animation チャンネル",
                "青のすみか", "キタニタツヤ");
        assertDisplay("TVアニメ『東京リベンジャーズ』三天戦争編 ノンクレジットOP【JO1「IGNITE」】",
                "TVアニメ『東京リベンジャーズ』チャンネル", "IGNITE", "JO1");
        assertDisplay("「進撃の巨人」The Final Season Part 2ノンクレジットED｜ヒグチアイ「悪魔の子」",
                "ぽにきゃん-Anime PONY CANYON", "悪魔の子", "ヒグチアイ");
        assertDisplay("映画『ちはやふる』主題歌「FLASH」（Perfume）PV", "東宝MOVIEチャンネル", "FLASH", "Perfume");
        assertDisplay("ドラマ『silent』主題歌「Subtitle / Official髭男dism」MV ドラマ映像ver. 歌詞付き【MAD/MV】",
                "ゆりぴょんChannel", "Subtitle", "Official髭男dism");
        assertDisplay("miwa『ヒカリヘ』×ドラマ『リッチマン、プアウーマン』Special Music Video", "miwa official YouTube channel",
                "ヒカリヘ", "miwa");
        assertDisplay("「海のはじまり」 x back number「新しい恋人達に」　スペシャルコラボムービー", "フジテレビ公式",
                "新しい恋人達に", "back number");
        assertDisplay("米津玄師「1991」主題歌スペシャルムービー｜劇場用実写映画『秒速5センチメートル』【大ヒット上映中】",
                "東宝MOVIEチャンネル", "1991", "米津玄師");
        assertDisplay("TVアニメ「らんま1/2」第3期 ノンクレジットエンディング｜『ユイカ』「かわいくない」",
                "MAPPA CHANNEL", "かわいくない", "ユイカ");
    }

    @Test
    void dropsVideoKindAndTieInAfterTheSong() {
        assertDisplay("YOASOBI「勇者」 Official Music Video／TVアニメ『葬送のフリーレン』オープニングテーマ", "YOASOBI",
                "勇者", "YOASOBI");
        assertDisplay("いきものがかり「晴々！」(TVアニメ「天穂のサクナヒメ」オープニングテーマ)　ミュージックビデオ",
                "いきものがかり Official YouTube Channel", "晴々！", "いきものがかり");
        assertDisplay("米津玄師「夜鷹」Kenshi Yonezu - Yodaka", "Kenshi Yonezu 米津玄師", "夜鷹", "米津玄師");
        assertDisplay("風神(TBS系金曜ドラマ『ライオンの隠れ家』主題歌) / Vaundy：MUSIC VIDEO", "Vaundy", "風神", "Vaundy");
        assertDisplay("Whitney Houston - I Will Always Love You (Official 4K Video)", "Whitney Houston",
                "I Will Always Love You", "Whitney Houston");
        assertDisplay("Stray Kids \"This & That\" M/V", "JYP Entertainment", "This & That", "Stray Kids");
        assertDisplay("Mrs. GREEN APPLE – 「青と夏」 Official Lyric Video", "Mrs. GREEN APPLE", "青と夏", "Mrs. GREEN APPLE");
        assertDisplay("Taylor Swift - Fortnight (Lyrics) feat. Post Malone", "7clouds",
                "Fortnight feat. Post Malone", "Taylor Swift");
        assertDisplay("Eve「風のアンセム」feat. suis from ヨルシカ", "Eve", "風のアンセム feat. suis from ヨルシカ", "Eve");
    }

    @Test
    void dropsHeadingsHashtagsAndRestatedUploaders() {
        assertDisplay("【MV】Okie Dokie Dance  ／ 大空スバル【ホロライブ/大空スバル】", "Subaru Ch. 大空スバル",
                "Okie Dokie Dance", "大空スバル");
        assertDisplay("森七菜 - カフェ・オ・レ / THE FIRST TAKE", "THE FIRST TAKE", "カフェ・オ・レ", "森七菜");
        assertDisplay("森七菜 - カフェ・オ・レ / THE FIRST TAKE", "THE FIRST TAKE", "カフェ・オ・レ", "森七菜");
        assertDisplay("サカナクション / 新宝島　-Music Video-", "サカナクション sakanaction", "新宝島", "サカナクション");
        assertDisplay("【オリジナル曲MV】YSS - KEHARASE (Official MV)【Vtuber / VRChat】[#YSS_VRC]", "YSS【VR音楽ユニット】",
                "KEHARASE", "YSS");
        assertDisplay("【Kanaria×葛葉】アイデンティティ【セルフカバー】", "Kanaria", "アイデンティティ", "Kanaria");
        assertDisplay("【MV】*Luna - ピース！！【DAY2 - VTuber Fes Japan 2022】 公式テーマソング", "ニコニコ公式チャンネル",
                "ピース！！", "*Luna");
        // 空白の無い / は曲名の一部。
        assertDisplay("lofi hip hop radio 📚 beats to relax/study to", "Lofi Girl",
                "lofi hip hop radio 📚 beats to relax/study to", "Lofi Girl");
        assertDisplay("Best Anime Openings & Endings Mix #4 [FULL SONGS]", "ShOckTuNes",
                "Best Anime Openings & Endings Mix #4 [FULL SONGS]", "ShOckTuNes");
    }

    @Test
    void coversCreditTheUploader() {
        assertDisplay("【歌ってみた】クスシキ -  Mrs. GREEN APPLE covered by 明透【神椿】", "明透 -ᴀsᴜ-",
                "クスシキ", "明透 -ᴀsᴜ-");
        assertDisplay("【歌ってみた】死神/米津玄師【cover】", "Raden Ch. 儒烏風亭らでん", "死神", "Raden Ch. 儒烏風亭らでん");
        assertDisplay("分かっちゃいないね/monet　Covered by 渚トラウト", "渚トラウト / Nagisa Trout【にじさんじ】",
                "分かっちゃいないね", "渚トラウト / Nagisa Trout");
    }

    @Test
    void readsTheSongFromTheDescriptionWhenTheTitleNamesNone() {
        assertDisplay("TVアニメ「佐々木とピーちゃん」Season2ノンクレジットオープニング", "KADOKAWAanime",
                "TVアニメ『#佐々木とピーちゃん』Season2\nオープニングテーマ：「Unbroken」MADKID\n\n2026年10月14日発売\n",
                "Unbroken", "MADKID");
        assertDisplay("アニメ『PSYREN -サイレン-』ノンクレジットOP映像 / PSYREN clean OP", "It's Anime powered by REMOW",
                "♪オープニングテーマ\nCLAN QUEEN「WARNING!!」\n\n\n【ON AIR】\n", "WARNING!!", "CLAN QUEEN");
        assertDisplay("TVアニメ「彼女の友達」【ノンクレジットED】", "デレギュラ【新アニメレーベル】",
                "TVアニメ「彼女の友達」ノンクレジットEDを公開\n\n▼エンディングテーマ\n「False Love」\n歌：Ceui \n作詞：UiNA \n",
                "False Love", "Ceui");
        assertDisplay("TVアニメ「BEASTARS」ノンクレジット OP", "TOHO animation チャンネル",
                "東京 渋谷出身の多国籍HIP-HOP/FUNK BAND”ALI”が歌う、ノンクレジットのOP映像を公開！\n\n"
                + "TVアニメ「BEASTARS」オープニングテーマ\n「Wild Side」\nアーティスト：ALI\n", "Wild Side", "ALI");
        // 説明欄に曲が無ければ、作品の紹介の見出しのまま (作品の種類の語と引用符は外す)。
        assertDisplay("TVアニメ『彼方から』ノンクレジットエンディング映像", "NBCUniversal Anime/Music",
                "TVアニメ『彼方から』TOKYO MX・ABCテレビ・WOWOW・AT-Xにて2026年10月より放送開始！\n",
                "彼方から ノンクレジットエンディング映像", "NBCUniversal Anime/Music");
        // 題に曲名がある時は説明欄を見ない。
        assertDisplay("TVアニメ『リコリス・リコイル』ノンテロップOPムービー｜ClariS「ALIVE」", "アニプレックス チャンネル",
                "オープニングテーマ：「別の曲」別の人\n", "ALIVE", "ClariS");
        assertNull(MetadataCleaner.descriptionSong("TVアニメ『X』2026年10月より放送開始！\n公式サイト"));
    }

    private static void assertDisplay(String title, String author, String expectedTitle, String expectedAuthor) {
        assertDisplay(title, author, null, expectedTitle, expectedAuthor);
    }

    private static void assertDisplay(String title, String author, String description,
            String expectedTitle, String expectedAuthor) {
        final String[] result = MetadataCleaner.display(title, author, description);
        assertEquals(expectedTitle, result[0], "title of " + title);
        assertEquals(expectedAuthor, result[1], "artist of " + title);
    }
}
