package com.kuronami.musicdiscmaker.component;

import java.util.ArrayList;
import java.util.List;

import com.kuronami.musicdiscmaker.MusicDiscMaker;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.JukeboxSong;

/**
 * custom disc を「バニラの再生中状態」に乗せるための無音 jukebox_song の選択。
 *
 * <p>曲の長さに応じて、その長さ以上で最小の {@code silent_<N>s} を選ぶ。バニラはこの song の
 * 長さだけ「再生中」を維持する (Amendments の回転・コンパレータ等が反応する)。
 * 実際の音声は LavaPlayer がストリームするので song 自体は無音。
 *
 * <p><b>バケット定義は data/music_disc_maker/jukebox_song/ の JSON 群と完全一致させること。</b>
 */
public final class SilentSongs {

    private static final int[] BUCKETS;

    static {
        final List<Integer> b = new ArrayList<>();
        for (int n = 10; n <= 120; n += 10) {
            b.add(n);
        }
        for (int n = 150; n <= 600; n += 30) {
            b.add(n);
        }
        for (int n = 660; n <= 1800; n += 60) {
            b.add(n);
        }
        for (final int n : new int[] {2400, 3000, 3600, 5400, 7200}) {
            b.add(n);
        }
        BUCKETS = b.stream().mapToInt(Integer::intValue).toArray();
    }

    private SilentSongs() {
    }

    /**
     * ラジオ (無限長ストリーム) は最長バケット {@code silent_7200s} 固定で選ぶ (案A)。
     * バニラの「再生中」状態は 2h で切れるが、ストリーム自体は client 側で流れ続ける。
     */
    public static ResourceKey<JukeboxSong> pick(long durationMs, boolean radio) {
        if (radio) {
            return key(BUCKETS[BUCKETS.length - 1]);
        }
        return pick(durationMs);
    }

    /**
     * 曲の長さ (ms) 以上で最小のバケットに対応する jukebox_song key を返す。
     * 長さ不明 (dur &lt;= 0) は 3600s。最長バケットを超える曲は最長で頭打ち。
     */
    public static ResourceKey<JukeboxSong> pick(long durationMs) {
        final int sec = durationMs <= 0L ? 3600 : (int) Math.ceil(durationMs / 1000.0);
        int chosen = BUCKETS[BUCKETS.length - 1];
        for (final int bucket : BUCKETS) {
            if (bucket >= sec) {
                chosen = bucket;
                break;
            }
        }
        return key(chosen);
    }

    private static ResourceKey<JukeboxSong> key(int seconds) {
        return ResourceKey.create(Registries.JUKEBOX_SONG,
                Identifier.fromNamespaceAndPath(MusicDiscMaker.MODID, "silent_" + seconds + "s"));
    }
}
