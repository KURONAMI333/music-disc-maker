package com.kuronami.musicdiscmaker.platform;

//? if >=1.21.2 {
import com.kuronami.musicdiscmaker.MusicDiscMaker;
//?} else {
/*import com.kuronami.musicdiscmaker.Constants;
*///?}
import com.kuronami.musicdiscmaker.platform.services.IConfigHelper;
import com.kuronami.musicdiscmaker.platform.services.IMenuHelper;
import com.kuronami.musicdiscmaker.platform.services.INetworkHelper;
import com.kuronami.musicdiscmaker.platform.services.IPlatformHelper;

import java.util.ServiceLoader;

/**
 * ServiceLoader 経由で loader 実装を解決する SPI 入口。common コードはここから
 * plaform 固有の実装 (NeoForge / Fabric) を透過的に呼ぶ。
 */
public class Services {

    /** 実行中のプラットフォーム情報・BlockEntityType 生成の loader 抽象。 */
    public static final IPlatformHelper PLATFORM = load(IPlatformHelper.class);

    //? if >=1.21.2 {
    /** payload 送信の loader 抽象。final でないのは headless テストの差し替え ({@link #swapNetwork}) のため。 */
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
    //?} elif >=1.21 {
    /*/^*
     * payload 送信の loader 抽象。
     *
     * <p>final にしていないのは headless テストのため。GameTest の mock プレイヤーは NeoForge の
     * チャンネル交渉を通っていないので、実 payload を送ると「may not be sent to the client」で落ちる。
     * また「何を誰にどの offset で送ったか」は送信先を捕まえないと検証できず、そこは実際に壊れた面
     * (chunk 再送で offset 0 に巻き戻る) でもある。差し替えは {@link #swapNetwork} 経由でのみ行い、
     * テストが {@code finally} で必ず戻す。</p>
     ^/
    public static volatile INetworkHelper NETWORK = load(INetworkHelper.class);

    /^*
     * {@link #NETWORK} を差し替え、差し替え前の実装を返す (テスト専用)。
     * 呼び出し側は {@code finally} で必ず元に戻すこと。
     ^/
    public static INetworkHelper swapNetwork(INetworkHelper replacement) {
        final INetworkHelper previous = NETWORK;
        NETWORK = replacement;
        return previous;
    }
    *///?} else {
    /*/^* payload 送信の loader 抽象。GameTestの差し替えはfinallyで必ず戻す。 ^/
    public static volatile INetworkHelper NETWORK = load(INetworkHelper.class);

    public static INetworkHelper swapNetwork(INetworkHelper replacement) {
        final INetworkHelper previous = NETWORK;
        NETWORK = replacement;
        return previous;
    }
    *///?}

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
        //? if >=1.21.2 {
        MusicDiscMaker.LOGGER.debug("Loaded {} for service {}", loadedService, clazz);
        //?} else {
        /*Constants.LOG.debug("Loaded {} for service {}", loadedService, clazz);
        *///?}
        return loadedService;
    }
}
