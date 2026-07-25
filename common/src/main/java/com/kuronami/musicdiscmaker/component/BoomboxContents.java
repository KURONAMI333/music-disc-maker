package com.kuronami.musicdiscmaker.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * ブームボックス (純アイテムの携帯プレイヤー) の中身。ディスク 1 枚と再生設定。
 *
 * <p>設定は専用 GUI (シフト右クリック) の 2 つだけ = 音量と指向性。可聴範囲はスライダーを出さず
 * {@link #RANGE_BLOCKS} 固定にしてある (kura 裁定の GUI 構成が「ディスクスロット + 音量 + 指向性」の
 * 3 部品なので、4 本目のつまみを勝手に足さない)。値は payload と {@code DiscSoundInstance} には
 * 従来どおり流れるので、可聴範囲の機構そのものは生きている。
 */
public record BoomboxContents(ItemStack disc, int volumePercent, boolean directional) {

    /** 可聴範囲 (ブロック)。GUI に出さない固定値。 */
    public static final int RANGE_BLOCKS = 64;

    public static final int VOLUME_MIN = 0;
    public static final int VOLUME_MAX = 200;
    public static final int VOLUME_DEFAULT = 100;

    /** 何も入っていないブームボックス。指向性は OFF 既定 (携帯 BGM は範囲内フラットが自然)。 */
    public static final BoomboxContents EMPTY =
            new BoomboxContents(ItemStack.EMPTY, VOLUME_DEFAULT, false);

    public static final Codec<BoomboxContents> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ItemStack.OPTIONAL_CODEC.optionalFieldOf("disc", ItemStack.EMPTY).forGetter(BoomboxContents::disc),
            Codec.INT.optionalFieldOf("volume", VOLUME_DEFAULT).forGetter(BoomboxContents::volumePercent),
            Codec.BOOL.optionalFieldOf("directional", false).forGetter(BoomboxContents::directional)
    ).apply(instance, BoomboxContents::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, BoomboxContents> STREAM_CODEC = StreamCodec.composite(
            ItemStack.OPTIONAL_STREAM_CODEC, BoomboxContents::disc,
            ByteBufCodecs.VAR_INT, BoomboxContents::volumePercent,
            ByteBufCodecs.BOOL, BoomboxContents::directional,
            BoomboxContents::new);

    /** ディスクだけ差し替えた複製 (アイテム上での装填・取り出し / GUI のスロット操作)。 */
    public BoomboxContents withDisc(ItemStack value) {
        return new BoomboxContents(value.copy(), volumePercent, directional);
    }

    /** 設定だけ差し替えた複製 (GUI の音量・指向性)。 */
    public BoomboxContents withConfig(int volume, boolean directionalValue) {
        return new BoomboxContents(disc, clampVolume(volume), directionalValue);
    }

    public static int clampVolume(int value) {
        return Math.max(VOLUME_MIN, Math.min(VOLUME_MAX, value));
    }

    public boolean hasDisc() {
        return !disc.isEmpty();
    }
}
