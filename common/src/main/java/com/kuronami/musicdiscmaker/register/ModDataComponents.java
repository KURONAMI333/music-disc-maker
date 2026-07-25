package com.kuronami.musicdiscmaker.register;

import com.kuronami.musicdiscmaker.MusicDiscMaker;
import com.kuronami.musicdiscmaker.component.BoomboxContents;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.platform.registry.RegistrationProvider;
import com.kuronami.musicdiscmaker.platform.registry.RegistryHolder;
import com.mojang.serialization.Codec;

import java.util.UUID;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;

/** custom music disc に載せる DataComponent。 */
public final class ModDataComponents {

    public static final RegistrationProvider<DataComponentType<?>> COMPONENTS =
            RegistrationProvider.get(Registries.DATA_COMPONENT_TYPE, MusicDiscMaker.MODID);

    public static final RegistryHolder<DataComponentType<CustomTrackData>> CUSTOM_TRACK =
            COMPONENTS.register("custom_track",
                    () -> DataComponentType.<CustomTrackData>builder()
                            .persistent(CustomTrackData.CODEC)
                            .networkSynchronized(CustomTrackData.STREAM_CODEC)
                            .build());

    /**
     * スピーカーのブロックアイテムが記憶した音源 (強化版ジュークボックス) の位置。
     * {@code GlobalPos} で dimension も持つので、別の次元へ持ち込んだアイテムのリンクは設置時に落ちる。
     * BE ⇔ item の往復は {@code SpeakerBlockEntity} の implicit component
     * ({@code applyImplicitComponents} / {@code collectImplicitComponents}) と loot table の
     * {@code copy_components} が担う。
     */
    public static final RegistryHolder<DataComponentType<GlobalPos>> SPEAKER_SOURCE =
            COMPONENTS.register("speaker_source",
                    () -> DataComponentType.<GlobalPos>builder()
                            .persistent(GlobalPos.CODEC)
                            .networkSynchronized(GlobalPos.STREAM_CODEC)
                            .build());

    /**
     * ブームボックス (純アイテム) が持ち歩く中身 (ディスク + 音量/指向性)。
     * 装填はインベントリ内の右クリックと専用 GUI のスロットの両方から書き換わる。
     */
    public static final RegistryHolder<DataComponentType<BoomboxContents>> BOOMBOX_CONTENTS =
            COMPONENTS.register("boombox_contents",
                    () -> DataComponentType.<BoomboxContents>builder()
                            .persistent(BoomboxContents.CODEC)
                            .networkSynchronized(BoomboxContents.STREAM_CODEC)
                            .build());

    /**
     * ブームボックスの<b>個体識別子</b>。再生セッションのキーであり、専用 GUI が「どのスタックを
     * 開いているか」を指すハンドルでもある。
     *
     * <p>これが要る理由: 携帯プレイヤーは持ち替えても・インベントリの中を動いても鳴り続けるので、
     * スロット番号や entity id では音源を指せない。同じ URL のブームボックスが 2 台あっても
     * 独立に鳴って独立に止まる、という要件も「キーが URL でなくアイテム個体」でしか満たせない。
     *
     * <p>採番は最初に必要になった時 (再生トグル / GUI を開く) に server が行う。クリエイティブの
     * 複製や {@code /give} で同じ UUID の 2 個目ができうるので、再生開始時に自分のインベントリ内で
     * 重複を見つけたら振り直す ({@code BoomboxPlayback#identify})。
     */
    public static final RegistryHolder<DataComponentType<UUID>> BOOMBOX_ID =
            COMPONENTS.register("boombox_id",
                    () -> DataComponentType.<UUID>builder()
                            .persistent(UUIDUtil.CODEC)
                            .networkSynchronized(UUIDUtil.STREAM_CODEC)
                            .build());

    /**
     * ブームボックスが再生中か。<b>正本ではなくキャッシュ</b>: 実際に鳴っているかどうかは server の
     * {@code BoomboxPlayback} のセッションが持ち、この component はツールチップ表示と
     * 「ログアウト前に鳴っていた」の手掛かりのために載せている。
     *
     * <p>キャッシュに落としてあるのは、落としたブームボックスを拾った人の手元で操作なしに鳴り出す
     * のを防ぐため。走査のたびに「フラグは true だがセッションが無い」を見つけたら落とす。
     */
    public static final RegistryHolder<DataComponentType<Boolean>> BOOMBOX_PLAYING =
            COMPONENTS.register("boombox_playing",
                    () -> DataComponentType.<Boolean>builder()
                            .persistent(Codec.BOOL)
                            .networkSynchronized(ByteBufCodecs.BOOL)
                            .build());

    private ModDataComponents() {
    }

    public static void init() {
    }
}
