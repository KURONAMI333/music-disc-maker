package com.kuronami.musicdiscmaker.beat;

import java.util.Locale;

/**
 * ビート強度を取る周波数帯。ビートマップは 4 帯域を全部保存し、どれを出力に使うかは
 * config ({@code beatBand}) で切り替える (帯域を変えても再解析は要らない)。
 */
public enum BeatBand {

    /** キック / ベース。四つ打ちがいちばん素直に出る = 既定。 */
    LOW(30.0, 160.0),
    /** スネア・声。 */
    MID(160.0, 2000.0),
    /** ハイハット・シンバル。 */
    HIGH(2000.0, 12000.0),
    /** 全帯域 = 曲全体の音量エンベロープ。 */
    FULL(20.0, 16000.0);

    private final double lowHz;
    private final double highHz;

    BeatBand(double lowHz, double highHz) {
        this.lowHz = lowHz;
        this.highHz = highHz;
    }

    public double lowHz() {
        return lowHz;
    }

    public double highHz() {
        return highHz;
    }

    /** config 文字列から。未知の値は {@link #LOW}。 */
    public static BeatBand of(String name) {
        if (name != null) {
            for (final BeatBand band : values()) {
                if (band.name().equalsIgnoreCase(name.trim())) {
                    return band;
                }
            }
        }
        return LOW;
    }

    public String configName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
