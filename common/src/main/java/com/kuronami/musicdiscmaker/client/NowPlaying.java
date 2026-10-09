package com.kuronami.musicdiscmaker.client;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.Config;
import com.kuronami.musicdiscmaker.component.CustomTrackData;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * 曲の始まりに出す「Now Playing」。
 *
 * <p>MDM を入れたら、既定ではどの盤の Now Playing も出さない。MDM の盤だけでなく、バニラの盤や
 * 他 MOD の再生も含める (2026-10-09 本人裁定: 動画タイトルをうまく曲名に直せなかった盤ではその題が
 * そのまま出るため既定は出さない。MDM の盤だけ出ないと「MDM の盤だけ表示されない」と言われるので、
 * 全部まとめて止める)。client 設定 {@link Config#showNowPlaying()} を有効にすると全部出る。
 * 止めるのは {@code MixinNowPlayingPlaceholder}、MDM の再生機器と compat はすべてここを通す。
 *
 * <p>MDM の盤は無音の曲としてバニラの再生に乗っているので、バニラや他 MOD は盤の名前
 * (「Custom Music Disc」) を Now Playing に出そうとする。その仮の名前は設定が有効でも出さず、
 * MDM が本当の曲名を出す。
 */
public final class NowPlaying {

    /** 無音の曲の説明文 (1.21 以降) と、1.20.1 の盤の {@code getDisplayName()} が使う翻訳キー。 */
    private static final String PLACEHOLDER_KEY = "item.music_disc_maker.custom_music_disc";

    private NowPlaying() {
    }

    /** 曲の表示名 (作者があれば「作者 - 題」)。題が無ければ {@code null}。 */
    @Nullable
    public static String describe(CustomTrackData track) {
        final String desc = track.author() != null && !track.author().isBlank()
                ? track.author() + " - " + track.title()
                : track.title();
        return desc == null || desc.isBlank() ? null : desc;
    }

    /** 曲を Now Playing に出す。設定で無効なら何もしない。 */
    public static void show(CustomTrackData track) {
        final String desc = describe(track);
        if (desc != null) {
            show(Component.literal(desc));
        }
    }

    /** 曲名を Now Playing に出す。設定で無効なら何もしない。 */
    public static void show(@Nullable Component text) {
        if (text == null || !Config.showNowPlaying()) {
            return;
        }
        //? if >=26.2 {
        Minecraft.getInstance().gui.hud.setNowPlaying(text);
        //?} else {
        /*Minecraft.getInstance().gui.setNowPlaying(text);
        *///?}
    }

    /** Now Playing の表示を止めるか。設定が無効なら全部、有効なら MDM の盤の仮の名前だけ。 */
    public static boolean suppresses(Component text) {
        return !Config.showNowPlaying() || isPlaceholder(text);
    }

    /** バニラや他 MOD が MDM の盤に出そうとする仮の名前 (「Custom Music Disc」) か。 */
    public static boolean isPlaceholder(Component text) {
        return text.getContents() instanceof TranslatableContents translatable
                && PLACEHOLDER_KEY.equals(translatable.getKey());
    }
}
