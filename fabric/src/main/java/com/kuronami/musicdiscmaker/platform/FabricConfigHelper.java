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
 * (NeoForge の ModConfigSpec 既定値と一致させる)。Now Playing の表示だけは
 * {@code config/music_disc_maker-client.properties} で変えられる。再生は client 側のみなので参照も client 経路から。
 */
public class FabricConfigHelper implements IConfigHelper {

    /**
     * Fabric で利用者が変えられる唯一の設定 ({@link #showNowPlaying()}) の置き場。設定画面を持たないので
     * 小さな properties にし、無ければ既定値で書き出す (開いて書き換えられるように)。
     */
    private static final String FILE_NAME = "music_disc_maker-client.properties";
    private static final String SHOW_NOW_PLAYING = "showNowPlaying";

    private static volatile Properties loaded;

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

    private static Properties properties() {
        Properties current = loaded;
        if (current == null) {
            synchronized (FabricConfigHelper.class) {
                current = loaded;
                if (current == null) {
                    current = read(FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME));
                    loaded = current;
                }
            }
        }
        return current;
    }

    private static Properties read(Path file) {
        final Properties properties = new Properties();
        properties.setProperty(SHOW_NOW_PLAYING, "false");
        try {
            if (Files.exists(file)) {
                try (Reader reader = Files.newBufferedReader(file)) {
                    properties.load(reader);
                }
            } else {
                Files.createDirectories(file.getParent());
                try (Writer writer = Files.newBufferedWriter(file)) {
                    properties.store(writer, "Music Disc Maker client settings. "
                            + "showNowPlaying: show \"Now Playing\" with the song name when a custom disc starts.");
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
