package com.kuronami.musicdiscmaker.client.audio;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.CustomTrackData;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * 再生できなかったことを、その client の<b>チャットとログの両方</b>へ理由つきで残す。
 *
 * <h2>アクションバーではなくチャットである理由</h2>
 * アクションバーは数秒で消える。「鳴らない」の報告 3 件はいずれも失敗表示に一言も触れておらず、
 * 出ていたとしても<b>後から読み返せない形</b>だった。原因の切り分けには「いつ・どの曲が・
 * どの分類で落ちたか」が残っている必要があるので、チャット (履歴に残る) と
 * {@code latest.log} (kura へ貼れる) の両方に出す。
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
        MusicDiscMaker.LOGGER.warn("再生に失敗 [{}] 曲={} url={}", failure.label(),
                title == null || title.isBlank() ? "?" : title, url == null ? "?" : url);
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
        // 第 2 引数 false = チャット欄 (履歴に残る)。true にするとアクションバーで数秒後に消える。
        minecraft.player.displayClientMessage(line, false);
    }
}
