package com.kuronami.musicdiscmaker.lavaplayer.codec;

import com.sedmelluq.discord.lavaplayer.natives.ConnectorNativeLibLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * LavaPlayer の native codec (libconnector) を使えるかどうかを一度だけ判定する。
 *
 * <h2>なぜ要るか</h2>
 * LavaPlayer の decoder (Opus / MP3 / AAC / Vorbis) と resampler はすべて libconnector の JNI。
 * 同梱の {@code linux-aarch64/libconnector.so} は glibc 向け ({@code libc.so.6} /
 * {@code libstdc++.so.6} / {@code libgcc_s.so.1} に依存) なので、Bionic libc の Android
 * (Pojav 系ランチャー) では dlopen できず、カスタムディスクが一切鳴らない。
 * 使えない時は {@code natives} パッケージの差し替えクラスが pure-Java 実装へ切り替える。
 *
 * <p>Android では load を試みずに Java 側へ倒す (glibc 向け .so を Bionic の linker に
 * 渡すこと自体を避ける)。{@code -Dmusicdiscmaker.javaCodecs=true} でデスクトップでも
 * Java 側を強制できる (検証用)。
 */
public final class NativeCodecs {

    private static final Logger LOGGER = LoggerFactory.getLogger(NativeCodecs.class);
    static final String FORCE_JAVA_PROPERTY = "musicdiscmaker.javaCodecs";

    private static volatile Boolean available;

    private NativeCodecs() {
    }

    /** @return native codec を使えるなら true。初回呼び出しで load を試み、結果を固定する。 */
    public static boolean available() {
        Boolean result = available;
        if (result == null) {
            synchronized (NativeCodecs.class) {
                result = available;
                if (result == null) {
                    result = probe();
                    available = result;
                }
            }
        }
        return result;
    }

    private static boolean probe() {
        if (Boolean.getBoolean(FORCE_JAVA_PROPERTY)) {
            LOGGER.info("Using pure-Java audio codecs ({}=true)", FORCE_JAVA_PROPERTY);
            return false;
        }
        if (isAndroid()) {
            LOGGER.info("Android detected ({} / {}); using pure-Java audio codecs instead of LavaPlayer natives",
                    System.getProperty("os.name"), System.getProperty("os.arch"));
            return false;
        }
        try {
            ConnectorNativeLibLoader.loadConnectorLibrary();
            return true;
        } catch (final Throwable thrown) {
            LOGGER.warn("LavaPlayer native codecs are unavailable on {} / {}; using pure-Java audio codecs",
                    System.getProperty("os.name"), System.getProperty("os.arch"), thrown);
            return false;
        }
    }

    /** Android の全プロセスに入る環境変数か、system パーティションの印で判定する。 */
    static boolean isAndroid() {
        if (System.getenv("ANDROID_ROOT") != null || System.getenv("ANDROID_DATA") != null) {
            return true;
        }
        try {
            return Files.exists(Path.of("/system/build.prop")) || Files.exists(Path.of("/system/bin/linker64"));
        } catch (final RuntimeException ex) {
            return false;
        }
    }
}
