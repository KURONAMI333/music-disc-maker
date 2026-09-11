package com.kuronami.musicdiscmaker.color;

import java.util.Locale;

/**
 * ディスクの染色に使えるバニラ染料 16 色。<b>利用者が選ぶのは色相 (= 染料) だけで、
 * 彩度と明度は {@link DiscPalette} が決める</b> (2026-09-06 KURONAMI333 裁定)。
 *
 * <p>並びと id はバニラの {@code DyeColor} と 1 対 1。ここは MC を参照しない純粋な表なので、
 * ローダー側 (もしくは 1.20.1 の NBT 経路) が {@code DyeColor#getName()} で {@link #byId(String)}
 * を引く。
 *
 * <p>H/S/L は {@code assets-v3/dyes.json} の実測値 (バニラの染料アイテムのテクスチャから採った
 * 代表色)。ここを書き換えると確定済みの盤面が変わるので、値を動かすときは
 * {@code MDM_DECISIONS.md} の「アクセントの色は表で持たず、盤面から計算する」を先に読む。
 */
public enum DiscDye {

    WHITE("white", 0.0, 0.0, 99.6078431372549),
    ORANGE("orange", 30.29126213592233, 94.49541284403668, 57.25490196078431),
    MAGENTA("magenta", 302.54237288135596, 59.00000000000001, 60.7843137254902),
    LIGHT_BLUE("light_blue", 193.33333333333334, 76.11940298507463, 60.58823529411765),
    YELLOW("yellow", 48.37696335078534, 98.9637305699482, 62.15686274509804),
    LIME("lime", 85.3012048192771, 68.59504132231405, 47.450980392156865),
    PINK("pink", 339.0909090909091, 75.0, 82.74509803921568),
    GRAY("gray", 196.3636363636364, 7.189542483660132, 30.000000000000004),
    LIGHT_GRAY("light_gray", 60.0, 2.85714285714286, 58.823529411764696),
    CYAN("cyan", 180.9230769230769, 74.71264367816092, 34.11764705882353),
    PURPLE("purple", 276.52173913043475, 57.98319327731093, 53.333333333333336),
    BLUE("blue", 232.2413793103448, 48.33333333333333, 47.05882352941176),
    BROWN("brown", 25.0632911392405, 45.664739884393065, 33.92156862745098),
    GREEN("green", 78.16513761467891, 68.55345911949686, 31.176470588235293),
    RED("red", 3.428571428571443, 61.403508771929836, 44.705882352941174),
    BLACK("black", 240.0, 6.666666666666667, 11.76470588235294);

    /** 彩度がこれ以下の染料は無彩色として扱い、色相を使わない (白/薄灰/灰/黒)。 */
    static final double CHROMA_THRESHOLD = 10.0;

    private static final DiscDye[] VALUES = values();

    private final String id;
    private final double hue;
    private final double saturation;
    private final double lightness;

    DiscDye(String id, double hue, double saturation, double lightness) {
        this.id = id;
        this.hue = hue;
        this.saturation = saturation;
        this.lightness = lightness;
    }

    /** バニラ {@code DyeColor} と同じ id ("light_blue" 等)。永続化はこの文字列で行う。 */
    public String id() {
        return id;
    }

    public double hue() {
        return hue;
    }

    public double saturation() {
        return saturation;
    }

    public double lightness() {
        return lightness;
    }

    /** 色相が使えるか (無彩色は明度だけで分ける)。 */
    public boolean isChromatic() {
        return saturation > CHROMA_THRESHOLD;
    }

    /** id から引く。未知の値は {@code null} (= 染色データ無しとして扱う)。 */
    public static DiscDye byId(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        final String key = id.toLowerCase(Locale.ROOT);
        for (DiscDye dye : VALUES) {
            if (dye.id.equals(key)) {
                return dye;
            }
        }
        return null;
    }
}
