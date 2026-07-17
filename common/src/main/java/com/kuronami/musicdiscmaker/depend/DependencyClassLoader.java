package com.kuronami.musicdiscmaker.depend;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;

/**
 * LavaPlayer 一式を NeoForge の ModLauncher から隔離してロードする classloader。
 *
 * <p>親を {@code null} (bootstrap) にすることで ModLauncher の変換 classloader 階層から
 * 完全に切り離す。LavaPlayer は独自の bytecode 操作・native ローダ・推移依存を持ち、
 * mod classpath に直接載せると衝突するため (この隔離が LavaPlayer fork 作者自身の手法)。
 *
 * <p>ただし以下の package だけは mod classloader へ委譲し、同一クラスを共有する:
 * <ul>
 *   <li>{@code ...lavaplayer.api} — mod と impl が同じ橋渡し interface を見るため</li>
 *   <li>{@code org.slf4j} — ログを mod 側へ集約するため</li>
 *   <li>{@code javax.script} / {@code javax.lang} — youtube source の rhino が使う JDK API</li>
 * </ul>
 */
public class DependencyClassLoader extends URLClassLoader {

    private static final List<String> BRIDGE_PACKAGES = List.of(
            "com.kuronami.musicdiscmaker.lavaplayer.api",
            "org.slf4j",
            "javax.script",
            "javax.lang");

    static {
        ClassLoader.registerAsParallelCapable();
    }

    private final ClassLoader bridge;

    public DependencyClassLoader() {
        super(new URL[] {}, null);
        this.bridge = getClass().getClassLoader();
    }

    @Override
    public Class<?> loadClass(String name) throws ClassNotFoundException {
        try {
            return super.loadClass(name);
        } catch (final ClassNotFoundException ex) {
            for (final String prefix : BRIDGE_PACKAGES) {
                if (name.startsWith(prefix)) {
                    return bridge.loadClass(name);
                }
            }
            throw ex;
        }
    }

    @Override
    public void addURL(URL url) {
        super.addURL(url);
    }
}
