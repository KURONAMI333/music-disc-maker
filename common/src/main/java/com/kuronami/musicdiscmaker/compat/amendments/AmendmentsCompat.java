package com.kuronami.musicdiscmaker.compat.amendments;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.platform.Services;
import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Amendments (MehVahdJukaar) の「ジュークボックスの上で盤が回る」表示との互換。必須依存ではない。
 * 版に依らない部分 (導入判定・回り方・Amendments の設定の読み取り) だけを持つ。
 *
 * <h2>どの版で働くか</h2>
 * Amendments が出ている版だけ: 1.20.1 (Forge / Fabric)・1.21.1 (NeoForge / Fabric)・
 * 26.1.2 (NeoForge / Fabric)。それ以外の版 (1.21.4・1.21.11・26.2・26.3) には Amendments が無いので
 * {@link #isActive()} は常に false で、描画も mixin も登録しない。
 *
 * <ul>
 *   <li>Amendments はバニラのジュークボックスの上に、盤ごとに<b>アイテムの素の絵から作った 1 枚の絵</b>を
 *       貼る。MDM の単曲は色を 12 段の tint (染色・曲ごとの自動色) で乗せるので素の絵は黒く、そのままだと
 *       黒い盤になる。MDM の単曲だけは、Amendments の盤の型 ({@code music_disc_template}) と同じ形に
 *       描き分けた tint 段ごとの白マスク ({@code textures/block/amendments_disc/layer_N}) を、アイテムと
 *       同じ tint 色で重ねて置き換える</li>
 *   <li>金のジュークボックス (と金のジュークボックス付きトロッコ) にも、同じ高さ感・同じ回り方で盤を出す</li>
 * </ul>
 *
 * <p>Amendments の型にはコンパイル時に触れない (compat-libs を増やさない)。盤の絵の引き当て・
 * 「回す」設定・盤の角度だけを反射で読み、見つからなければ「描かない / 常に回す / 自前で数える」へ落ちる。
 */
public final class AmendmentsCompat {

    public static final String MOD_ID = "amendments";

    /** Amendments の盤が乗る高さ (ジュークボックス上面 15/16 の 0.25px 上)。 */
    public static final float VANILLA_DISC_Y = 15.25f / 16f;
    /** 金のジュークボックスは上面が 16/16 のまま。Amendments と同じく上面から 0.25px 浮かせる。 */
    public static final float GOLDEN_DISC_Y = 16.25f / 16f;

    /**
     * 上から見た盤のマスクを持つ tint 段 ({@code DiscDyeTint} の tintindex)。
     * 縁は盤面 1、地は盤面の主色 3、光の筋は盤面の明部 4、ラベルはアクセント 9 / 11、中心の穴は盤面 0。
     * 盤の上の色は盤面だけで作り、アクセントはラベルにだけ使う: 筋をアクセントにすると黒いアクセントで
     * 黒い点になり、Amendments の暗い斜めの筋を盤面 2 (ほぼ黒) にすると黒い線になった。
     */
    public static final int[] TOP_LAYERS = {0, 1, 3, 4, 9, 11};

    /** Amendments が出ている版か (版ごとに描き方の実装を持つのはこの版だけ)。 */
    //? if <1.21.2 {
    /*private static final boolean VERSION_SUPPORTED = true;
    *///?} elif >=26.1 && <26.2 {
    /*private static final boolean VERSION_SUPPORTED = true;
    *///?} else {
    private static final boolean VERSION_SUPPORTED = false;
    //?}

    private static final Map<Object, Spin> SPINS = new WeakHashMap<>();

    @Nullable
    private static Boolean active;
    private static boolean reflected;
    @Nullable
    private static Supplier<?> spinConfig;
    @Nullable
    private static MethodHandle jukeboxRotation;

    private AmendmentsCompat() {
    }

    /**
     * この版に Amendments 互換の実装があるか。server 側の準備 (トロッコの盤の同期) はこれだけで決める
     * (server からは client に Amendments が入っているか分からない)。
     */
    public static boolean versionSupported() {
        return VERSION_SUPPORTED;
    }

    /** この版に互換があり、Amendments が入っている時だけ true。 */
    public static boolean isActive() {
        if (active == null) {
            active = VERSION_SUPPORTED && Services.PLATFORM.isModLoaded(MOD_ID);
        }
        return active;
    }

    public static boolean isCustomDisc(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ModItems.CUSTOM_MUSIC_DISC.get());
    }

    /** {@link #TOP_LAYERS} の i 番目のマスク (blocks atlas 上、{@code block/} からの path)。 */
    public static String topLayerTexture(int i) {
        return "block/amendments_disc/layer_" + TOP_LAYERS[i];
    }

    /** Amendments の client 設定「再生中に盤を回す」。読めなければ既定値 (回す)。 */
    public static boolean spinEnabled() {
        reflect();
        final Supplier<?> config = spinConfig;
        if (config == null) {
            return true;
        }
        try {
            return !Boolean.FALSE.equals(config.get());
        } catch (Throwable ignored) {
            return true;
        }
    }

    /**
     * Amendments がバニラのジュークボックスに持たせた盤の角度 ({@code IBetterJukebox#amendments$getRotation})。
     * 読めなければ 0 (止まった盤)。
     */
    public static float jukeboxRotation(Object jukebox, float partialTick) {
        reflect();
        final MethodHandle handle = jukeboxRotation;
        if (handle == null) {
            return 0f;
        }
        try {
            return (float) handle.invoke(jukebox, partialTick);
        } catch (Throwable ignored) {
            return 0f;
        }
    }

    /** key (BE / entity) ごとの回転状態。描画スレッドだけが触る。 */
    public static Spin spin(Object key) {
        return SPINS.computeIfAbsent(key, ignored -> new Spin());
    }

    private static void reflect() {
        if (reflected) {
            return;
        }
        reflected = true;
        if (!isActive()) {
            return;
        }
        try {
            final Class<?> configs = Class.forName("net.mehvahdjukaar.amendments.configs.ClientConfigs");
            final Object value = configs.getField("JUKEBOX_SPIN").get(null);
            if (value instanceof Supplier<?> supplier) {
                spinConfig = supplier;
            }
        } catch (Throwable t) {
            MusicDiscMaker.LOGGER.debug("Amendments jukebox spin config unavailable; always spinning", t);
        }
        try {
            final Class<?> better = Class.forName("net.mehvahdjukaar.amendments.common.IBetterJukebox");
            jukeboxRotation = MethodHandles.publicLookup().findVirtual(better, "amendments$getRotation",
                    MethodType.methodType(float.class, float.class))
                    .asType(MethodType.methodType(float.class, Object.class, float.class));
        } catch (Throwable t) {
            MusicDiscMaker.LOGGER.debug("Amendments jukebox rotation unavailable", t);
        }
    }

    /** Amendments の {@code JukeboxBlockEntityMixin} と同じ回り方。tick は client の game time で数える。 */
    public static final class Spin {
        private static final long NEVER = Long.MIN_VALUE;
        /** 長く描かれていなかった後に追い付く上限 (1 周分あれば止まる側も必ず 0° に戻る)。 */
        private static final long MAX_CATCH_UP = 360;

        private float rot;
        private float prevRot;
        private long lastTick = NEVER;

        /** 鳴っている間は 1°/tick、止まると 5°/tick で 0° へ戻る。 */
        public float angle(Level level, boolean playing, float partialTick) {
            final long now = level.getGameTime();
            if (lastTick == NEVER || now < lastTick) {
                lastTick = now;
            }
            final long steps = Math.min(now - lastTick, MAX_CATCH_UP);
            for (long i = 0; i < steps; i++) {
                step(playing);
            }
            lastTick = now;
            return Mth.rotLerp(partialTick, prevRot, rot);
        }

        private void step(boolean playing) {
            prevRot = rot;
            if (playing) {
                rot = (rot + 1f) % 360f;
            } else if (rot > 0f) {
                rot = Math.max(0f, rot - 5f) % 360f;
            }
        }
    }
}
