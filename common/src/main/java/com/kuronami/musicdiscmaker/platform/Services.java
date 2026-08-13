package com.kuronami.musicdiscmaker.platform;

import com.kuronami.musicdiscmaker.Constants;
import com.kuronami.musicdiscmaker.platform.services.IConfigHelper;
import com.kuronami.musicdiscmaker.platform.services.IMenuHelper;
import com.kuronami.musicdiscmaker.platform.services.INetworkHelper;
import com.kuronami.musicdiscmaker.platform.services.IPlatformHelper;

import java.util.ServiceLoader;

// Service loaders are a built-in Java feature that allow us to locate implementations of an interface that vary from one
// environment to another. In the context of MultiLoader we use this feature to access a mock API in the common code that
// is swapped out for the platform specific implementation at runtime.
public class Services {

    // In this example we provide a platform helper which provides information about what platform the mod is running on.
    // For example this can be used to check if the code is running on Forge vs Fabric, or to ask the modloader if another
    // mod is loaded.
    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);

    /**
     * payload 送信の loader 抽象。
     *
     * <p>final にしていないのは headless テストのため。GameTest の mock プレイヤーは NeoForge の
     * チャンネル交渉を通っていないので、実 payload を送ると「may not be sent to the client」で落ちる。
     * また「何を誰にどの offset で送ったか」は送信先を捕まえないと検証できず、そこは実際に壊れた面
     * (chunk 再送で offset 0 に巻き戻る) でもある。差し替えは {@link #swapNetwork} 経由でのみ行い、
     * テストが {@code finally} で必ず戻す。</p>
     */
    public static volatile INetworkHelper NETWORK = load(INetworkHelper.class);

    /**
     * {@link #NETWORK} を差し替え、差し替え前の実装を返す (テスト専用)。
     * 呼び出し側は {@code finally} で必ず元に戻すこと。
     */
    public static INetworkHelper swapNetwork(INetworkHelper replacement) {
        final INetworkHelper previous = NETWORK;
        NETWORK = replacement;
        return previous;
    }

    /** extended menu の生成・open の loader 抽象。 */
    public static final IMenuHelper MENU = load(IMenuHelper.class);

    /** client 設定の loader 抽象。 */
    public static final IConfigHelper CONFIG = load(IConfigHelper.class);

    // This code is used to load a service for the current environment. Your implementation of the service must be defined
    // manually by including a text file in META-INF/services named with the fully qualified class name of the service.
    // Inside the file you should write the fully qualified class name of the implementation to load for the platform. For
    // example our file on Forge points to ForgePlatformHelper while Fabric points to FabricPlatformHelper.
    public static <T> T load(Class<T> clazz) {

        final T loadedService = ServiceLoader.load(clazz)
                .findFirst()
                .orElseThrow(() -> new NullPointerException("Failed to load service for " + clazz.getName()));
        Constants.LOG.debug("Loaded {} for service {}", loadedService, clazz);
        return loadedService;
    }
}