package com.kuronami.musicdiscmaker.platform;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.platform.services.IConfigHelper;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Fabric 実装: client 設定。Fabric には config 画面を設けないため既定値を返す
 * (NeoForge の ModConfigSpec 既定値と一致させる)。Now Playing とジャケット画像の表示だけは
 * {@code config/music_disc_maker-client.properties} で変えられる。再生は client 側のみなので参照も client 経路から。
 */
public class FabricConfigHelper implements IConfigHelper {

    /**
     * Fabric で利用者が変えられる設定 ({@link #showNowPlaying()}・{@link #showAlbumArt()}) の置き場。設定画面を持たないので
     * 小さな properties にし、無ければ既定値で書き出す (開いて書き換えられるように)。
     */
    private static final String FILE_NAME = "music_disc_maker-client.properties";
    private static final String SHOW_NOW_PLAYING = "showNowPlaying";
    private static final String SHOW_ALBUM_ART = "showAlbumArt";

    private static volatile Properties loaded;
    /** 読んだ時のファイルの更新時刻と、次に更新時刻を見てよい時刻 (ms)。書き換えをゲーム中に拾う。 */
    private static long loadedModified = Long.MIN_VALUE;
    private static long nextCheckMs;
    private static final long CHECK_INTERVAL_MS = 1_000L;

    @Override
    public double volumeMultiplier() {
        return 0.5;
    }

    @Override
    public int maxConcurrent() {
        // config 画面が無いので常時 auto。実 pool から BGM 等の余白を引き、最大 64 にする。
        return com.kuronami.musicdiscmaker.Config.automaticMaxConcurrent();
    }

    @Override
    public int playbackRange() {
        return 64;
    }

    @Override
    public int boomboxRange() {
        // Fabric は config 画面が無いので既定 16 固定 (playbackRange の 64 固定と同じ扱い)。
        return com.kuronami.musicdiscmaker.component.BoomboxContents.RANGE_DEFAULT;
    }

    @Override
    public boolean showNowPlaying() {
        return Boolean.parseBoolean(properties().getProperty(SHOW_NOW_PLAYING, "false").trim());
    }

    @Override
    public boolean showAlbumArt() {
        return Boolean.parseBoolean(properties().getProperty(SHOW_ALBUM_ART, "true").trim());
    }

    /** 設定ファイルを書き換えたら、ゲームを再起動しなくても1秒以内に効く (2026-10-10)。 */
    private static Properties properties() {
        final long now = System.currentTimeMillis();
        Properties current = loaded;
        if (current != null && now < nextCheckMs) {
            return current;
        }
        synchronized (FabricConfigHelper.class) {
            final Path file = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
            final long modified = lastModified(file);
            if (loaded == null || modified != loadedModified) {
                loaded = read(file);
                // 読む前の時刻を記録する (読んだ後に書かれた変更を次の確認で拾う)。
                // ファイルが無かった時だけ、read が書き出した時刻を取る。
                loadedModified = modified != Long.MIN_VALUE ? modified : lastModified(file);
            }
            nextCheckMs = now + CHECK_INTERVAL_MS;
            return loaded;
        }
    }

    private static long lastModified(Path file) {
        try {
            return Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : Long.MIN_VALUE;
        } catch (final IOException e) {
            return Long.MIN_VALUE;
        }
    }

    private static Properties read(Path file) {
        final Properties properties = new Properties();
        properties.setProperty(SHOW_NOW_PLAYING, "false");
        properties.setProperty(SHOW_ALBUM_ART, "true");
        try {
            boolean missingKey = true;
            if (Files.exists(file)) {
                final Properties stored = new Properties();
                try (Reader reader = Files.newBufferedReader(file)) {
                    stored.load(reader);
                }
                missingKey = !stored.containsKey(SHOW_NOW_PLAYING) || !stored.containsKey(SHOW_ALBUM_ART);
                properties.putAll(stored);
            }
            // 無い時と、後の版で足した設定の行が無い時は書き出す (書き換えた値は残す)。設定画面が無いので、
            // ファイルに行が無いと利用者は設定があることに気づけない。
            if (missingKey) {
                Files.createDirectories(file.getParent());
                try (Writer writer = Files.newBufferedWriter(file)) {
                    properties.store(writer, "Music Disc Maker client settings. "
                            + "showNowPlaying: show \"Now Playing\" when a music disc starts, for every disc and every mod. "
                            + "showAlbumArt: show the album art in custom disc tooltips.");
                }
            }
        } catch (final IOException | IllegalArgumentException e) {
            MusicDiscMaker.LOGGER.warn("Could not read {}; using defaults", file, e);
        }
        return properties;
    }

    @Override
    public int maxPlaybackRange() {
        // Fabric は config 画面が無いので既定 256。強化版ジュークボックスはこの上限まで到達できる。
        return 256;
    }
}
