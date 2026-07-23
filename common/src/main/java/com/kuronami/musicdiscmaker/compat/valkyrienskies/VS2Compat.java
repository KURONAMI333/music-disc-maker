package com.kuronami.musicdiscmaker.compat.valkyrienskies;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.client.audio.DiscAnchor;
import com.kuronami.musicdiscmaker.platform.Services;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;

/**
 * Valkyrien Skies 2 互換のソフト配線。VS2 は変換式 (実ブロックが shipyard で tick 継続) なので、
 * 再生を鳴らす trigger も server→client payload も要らない ── 既存の再生経路がそのまま鳴る。必要なのは
 * client 側で音源座標を ship 変換で補正することだけ (VS2 の「見えないスピーカー」修正)。
 *
 * <p>VS2 クラス ({@link VSShipAnchor} 経由の {@code VSGameUtilsKt}/{@code ClientShip}/joml) は全て
 * {@link #isLoaded()} gate を通過した後の {@link VSShipAnchor#tryCreate} 内でのみ class-load される。
 * よって VS2 非導入環境でこの class をロードしても NoClassDefFoundError にならない
 * ({@code SophisticatedCoreCompat} と同型)。Eureka!/Clockwork も VS2 backend なので gate は VS2 で見れば足りる。
 */
public final class VS2Compat {

    public static final String MODID = "valkyrienskies";

    /** isModLoaded は不変なのでキャッシュする。 */
    private static Boolean loaded;

    private VS2Compat() {
    }

    public static boolean isLoaded() {
        if (loaded == null) {
            loaded = Services.PLATFORM.isModLoaded(MODID);
        }
        return loaded;
    }

    /**
     * client 側: {@code pos} が VS2 船の管理下なら shipyard→world 追従アンカーを返す。VS2 非導入 or
     * 船外 (通常 world) なら {@code null} を返し、呼び出し側が従来の {@code StaticAnchor} で再生する。
     * VS2 型に触れるのは gate 通過後の {@link VSShipAnchor#tryCreate} 内だけ。
     */
    @Nullable
    public static DiscAnchor resolveShipAnchor(ClientLevel level, BlockPos pos) {
        if (!isLoaded()) {
            return null;
        }
        return VSShipAnchor.tryCreate(level, pos);
    }
}
