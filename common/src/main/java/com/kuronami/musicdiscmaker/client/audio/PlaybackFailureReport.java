package com.kuronami.musicdiscmaker.client.audio;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;

//? if >=1.21.2 {
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
//?} elif >=1.21 {
/*import net.minecraft.core.BlockPos;
*///?} else {
/*import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
*///?}

/**
 * 再生できなかったことを、その client の<b>チャットとログの両方</b>へ理由つきで残す。
 *
 * <h2>アクションバーではなくチャットである理由</h2>
 * アクションバーは数秒で消える。「鳴らない」の報告はいずれも失敗表示に一言も触れておらず、
 * 出ていたとしても<b>後から読み返せない形</b>だった。原因の切り分けには「いつ・どの曲が・
 * どの分類で落ちたか」が残っている必要があるので、チャット (履歴に残る) と
 * {@code latest.log} (貼って報告できる) の両方に出す。
 *
 * <p>26.2 で {@code Player.displayClientMessage(Component, boolean)} は廃止されている
 * (26.1.2_MIGRATION_NOTES.md §4)。ここでの置き換えは {@code LocalPlayer.sendSystemMessage(Component)}
 * ({@code ChatListener.handleSystemMessage(message, false)} 経由でチャットに落ちる、26.2 復号ソースで確認済み)。
 * {@link ClientPlaybackManager} の {@code notifyActionBar} が使う
 * {@code Minecraft.getInstance().gui.hud.setOverlayMessage(...)} とは別経路であることに注意
 * (あちらは数秒で消えてよい一時通知向け。恒久表示にはここを使う)。
 *
 * <p>分類そのものは {@link PlaybackFailure} が持つ (MC 非依存・headless テスト対象)。ここは
 * 文面の組み立てと配送だけを持つ。
 */
public final class PlaybackFailureReport {

    private PlaybackFailureReport() {
    }

    /**
     * 失敗を報告する (main thread から呼ぶこと)。
     *
     * @param track   失敗した曲 (曲名と URL をログに残す。null 可)
     * @param failure 分類済みの失敗
     */
    public static void report(@Nullable CustomTrackData track, PlaybackFailure failure) {
        report(track == null ? null : track.title(), track == null ? null : track.url(), failure);
    }

    /** 曲データを持たない経路 (プレイリスト解決前など) 向け。 */
    public static void report(@Nullable String title, @Nullable String url, PlaybackFailure failure) {
        //? if >=1.21.2 {
        MusicDiscMaker.LOGGER.warn("Playback failed [{}] track={} url={}", failure.label(),
        //?} elif >=1.21 {
        /*MusicDiscMaker.LOGGER.warn("Playback failed [{}] track={} url={}", failure.label(),
        *///?} else {
        /*MusicDiscMaker.LOGGER.warn("Playback failed [{}] track={} url={}", failure.label(),
        *///?}
                title == null || title.isBlank() ? "?" : title, url == null ? "?" : url);
        //? if >=26.1 {
        final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        final MutableComponent line = Component.translatable("music_disc_maker.playback_failed.chat",
                Component.translatable(failure.translationKey()));
        // 機械可読なラベルと曲名を灰色で添える。利用者がそのまま報告に貼れることを狙う。
        final StringBuilder tail = new StringBuilder(" [").append(failure.label()).append(']');
        if (title != null && !title.isBlank()) {
            tail.append(' ').append(title);
        }
        line.append(Component.literal(tail.toString()).withStyle(ChatFormatting.DARK_GRAY));
        // チャットへ (履歴に残す)。sendSystemMessage → ChatListener.handleSystemMessage(msg, false)
        // → ChatComponent.addClientSystemMessage という経路で、アクションバーではなくチャット欄に落ちる。
        minecraft.player.sendSystemMessage(line);
        //?} elif >=1.21.2 {
        /*final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        final MutableComponent line = Component.translatable("music_disc_maker.playback_failed.chat",
                Component.translatable(failure.translationKey()));
        // 機械可読なラベルと曲名を灰色で添える。利用者がそのまま報告に貼れることを狙う。
        final StringBuilder tail = new StringBuilder(" [").append(failure.label()).append(']');
        if (title != null && !title.isBlank()) {
            tail.append(' ').append(title);
        }
        line.append(Component.literal(tail.toString()).withStyle(ChatFormatting.DARK_GRAY));
        // 第 2 引数 false = チャット欄 (履歴に残る)。true にするとアクションバーで数秒後に消える。
        minecraft.player.displayClientMessage(line, false);
        *///?} elif >=1.21 {
    /*}

    /^*
     * 座標 (key) と再生の世代 (token) が分かる呼び出し元向け (計測用。挙動は変えない)。
     *
     * <p>{@code key}/{@code token} は {@link ClientPlaybackManager} が持ち回っている値をそのまま
     * 渡すだけで、この行専用に新しく採番するものではない ({@code PlaybackTiming} の
     * "Track switch" 行に載る token と同じ値になる)。同じ再生を指すログ行同士を突き合わせるためのもの。
     ^/
    public static void report(@Nullable CustomTrackData track, BlockPos key, int token, PlaybackFailure failure) {
        report(track == null ? null : track.title(), track == null ? null : track.url(), key, token, failure);
    }

    /^* {@link #report(CustomTrackData, BlockPos, int, PlaybackFailure)} の曲データを持たない版。 ^/
    public static void report(@Nullable String title, @Nullable String url, BlockPos key, int token,
            PlaybackFailure failure) {
        MusicDiscMaker.LOGGER.warn("Playback failed [{}] track={} url={} key={} token={}", failure.label(),
                title == null || title.isBlank() ? "?" : title, url == null ? "?" : url,
                key.toShortString(), token);
        *///?} else {
        /*final Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        final MutableComponent line = Component.translatable("music_disc_maker.playback_failed.chat",
                Component.translatable(failure.translationKey()));
        // 機械可読なラベルと曲名を灰色で添える。利用者がそのまま報告に貼れることを狙う。
        final StringBuilder tail = new StringBuilder(" [").append(failure.label()).append(']');
        if (title != null && !title.isBlank()) {
            tail.append(' ').append(title);
        }
        line.append(Component.literal(tail.toString()).withStyle(ChatFormatting.DARK_GRAY));
        // 第 2 引数 false = チャット欄 (履歴に残る)。true にするとアクションバーで数秒後に消える。
        minecraft.player.displayClientMessage(line, false);
        *///?}
    }
}

