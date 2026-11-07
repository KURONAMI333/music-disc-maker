package com.kuronami.musicdiscmaker.client.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

/**
 * 金ジュークの再生アンカーから先読みの鍵 (jukebox 座標) が取れることを固定する。
 *
 * <p>金ジュークの再生は {@code GoldenEmitterAnchor} で包んだ音源を鳴らす。このラッパーは
 * {@code LiveAudioConfig} を実装するが {@code LiveConfigAnchor} は実装しない (位置は原アンカーが持つ)。
 * {@code DiscSoundInstance#tick} が先読みを {@code anchor instanceof LiveConfigAnchor} の枝の中だけで
 * 駆動していたため、金ジュークでは {@code drivePrefetch} が一度も呼ばれず、先読みが全帯で不発だった。
 *
 * <p>このテストは、その判定がアンカーの実装型 (能力インターフェース) に依存しないことを縛る。
 * 修正前のコードには {@code DiscSoundInstance#prefetchPos} も {@code GoldenEmitterAnchor#sourceConfigPos}
 * も無いので、このテストは落ちる。
 *
 * <p>テストの compile classpath には Minecraft の型が無いので、アンカーと payload は実行時 classpath
 * から反射で組む (main 側のクラスはそのまま使う)。
 */
class GoldenPrefetchReachabilityTest {

    @Test
    void goldenAnchorYieldsItsSourcePositionAsThePrefetchKey() throws Exception {
        final Class<?> blockPosType = Class.forName("net.minecraft.core.BlockPos");
        final Object pos = blockPosType.getConstructor(int.class, int.class, int.class)
                .newInstance(10, 64, -3);

        final Object golden = goldenAnchor(staticAnchor(pos), payload(blockPosType, pos));

        // ここが不発の原因だった: ラッパー自身は LiveConfigAnchor ではない。
        final Class<?> liveConfigAnchor = Class.forName("com.kuronami.musicdiscmaker.client.audio.LiveConfigAnchor");
        assertFalse(liveConfigAnchor.isInstance(golden),
                "GoldenEmitterAnchor が LiveConfigAnchor を実装すると前提が変わる (このテストの意味が無くなる)");

        final Method sourceConfigPos = golden.getClass().getDeclaredMethod("sourceConfigPos");
        sourceConfigPos.setAccessible(true);
        assertEquals(pos, sourceConfigPos.invoke(golden),
                "原アンカーが固定ブロックなら、その位置が先読みの鍵になる");

        assertEquals(pos, prefetchPos(golden),
                "tick は anchor の型で塞がれず、金ジュークの座標で先読みを駆動できる");
    }

    @Test
    void prefetchKeyIsNullForAnAnchorThatHasNoClientSideJukebox() throws Exception {
        final Class<?> blockPosType = Class.forName("net.minecraft.core.BlockPos");
        final Object origin = blockPosType.getField("ZERO").get(null);

        // entity 追従 (EntityAnchor) は client world 上のブロックではない。先読みの鍵にはならない。
        // Entity はネイティブの初期化 (registries の bootstrap) がこのテスト環境に無いので、
        // Class.forName で Entity 自体を初期化しない。field の宣言型から型だけを取り、
        // EntityAnchor を null entity で組む (EntityAnchor のコンストラクタは実体を触らない)。
        final Class<?> entityAnchorType = Class.forName("com.kuronami.musicdiscmaker.client.audio.EntityAnchor");
        final Class<?> entityType = entityAnchorType.getDeclaredField("entity").getType();
        final Object entityAnchor = entityAnchorType.getConstructor(entityType).newInstance((Object) null);

        final Object golden = goldenAnchor(entityAnchor, payload(blockPosType, origin));

        assertNotNull(golden, "entity 追従でもアンカーは組める");
        assertNull(prefetchPos(golden), "client world 上のブロックを持たない音源は先読みしない");
    }

    /** {@code new GoldenEmitterAnchor(source, payload, reader)}。reader は先読み判定では読まれない。 */
    private static Object goldenAnchor(Object source, Object payload) throws Exception {
        final Class<?> discAnchorType = Class.forName("com.kuronami.musicdiscmaker.client.audio.DiscAnchor");
        final Class<?> goldenType = Class.forName("com.kuronami.musicdiscmaker.client.audio.GoldenEmitterAnchor");
        final Constructor<?> ctor = goldenType.getDeclaredConstructor(
                discAnchorType, payload.getClass(), Function.class);
        ctor.setAccessible(true);
        final Function<Object, Object> reader = ignored -> {
            throw new AssertionError("先読みの鍵を取るのに local config を読んではいけない");
        };
        return ctor.newInstance(source, payload, reader);
    }

    private static Object staticAnchor(Object pos) throws Exception {
        final Class<?> staticAnchorType = Class.forName("com.kuronami.musicdiscmaker.client.audio.StaticAnchor");
        return staticAnchorType.getConstructor(pos.getClass()).newInstance(pos);
    }

    private static Object payload(Class<?> blockPosType, Object pos) throws Exception {
        final Class<?> payloadType = Class.forName("com.kuronami.musicdiscmaker.network.SpeakerSetPayload");
        return payloadType.getConstructor(blockPosType, int.class, int.class, boolean.class, List.class)
                .newInstance(pos, 16, 100, true, List.of());
    }

    private static Object prefetchPos(Object anchor) throws Exception {
        final Class<?> discAnchorType = Class.forName("com.kuronami.musicdiscmaker.client.audio.DiscAnchor");
        final Class<?> instanceType = Class.forName("com.kuronami.musicdiscmaker.client.audio.DiscSoundInstance");
        final Method method = instanceType.getDeclaredMethod("prefetchPos", discAnchorType);
        method.setAccessible(true);
        return method.invoke(null, anchor);
    }
}
