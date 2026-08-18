package com.kuronami.musicdiscmaker.audio;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.depend.DependencyManager;
import com.kuronami.musicdiscmaker.lavaplayer.api.IMusicLoader;

/**
 * 隔離 classloader から LavaPlayer impl ({@link IMusicLoader}) を遅延生成して保持する。
 * server (URL 解決) と client (再生) の両方が使う。
 */
public final class LoaderHolder {

    private static volatile IMusicLoader loader;

    private LoaderHolder() {
    }

    public static IMusicLoader get() {
        IMusicLoader local = loader;
        if (local == null) {
            synchronized (LoaderHolder.class) {
                local = loader;
                if (local == null) {
                    DependencyManager.load();
                    try {
                        final Class<?> clazz = Class.forName(
                                "com.kuronami.musicdiscmaker.lavaplayer.MusicLoaderImpl",
                                true, DependencyManager.CLASSLOADER);
                        local = (IMusicLoader) clazz.getDeclaredConstructor().newInstance();
                        loader = local;
                        MusicDiscMaker.LOGGER.info("LavaPlayer loader initialized");
                    } catch (final Exception ex) {
                        throw new IllegalStateException("Failed to initialize the LavaPlayer loader", ex);
                    }
                }
            }
        }
        return local;
    }
}
