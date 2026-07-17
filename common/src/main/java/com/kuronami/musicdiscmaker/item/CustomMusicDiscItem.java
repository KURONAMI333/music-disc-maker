package com.kuronami.musicdiscmaker.item;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.register.ModSounds;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.RecordItem;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * custom music disc。曲メタは item stack の NBT (キー {@link #NBT_KEY}) に保持する
 * (1.20.1 は DataComponent 非対応)。tooltip に曲名/アーティスト/長さを表示する。
 *
 * <p>v1.1.0 から {@link RecordItem} を継承し、無音 SoundEvent を持つことでバニラの
 * 「再生中」状態 (アレイのダンス・コンパレータ・回転 mod) に乗る。実際の音声は LavaPlayer。
 * ホッパー挿入・jukebox 受理のために {@code minecraft:music_discs} タグにも入れる
 * (creeper drop は別タグ creeper_drop_music_discs なので汚染しない)。
 */
public class CustomMusicDiscItem extends RecordItem {

    /** item stack NBT 内の {@link CustomTrackData} 格納キー。 */
    public static final String NBT_KEY = "track";

    public CustomMusicDiscItem(Properties properties) {
        // comparator=1 / 無音 / 長さ 7200s(=2h): バニラの自動停止を事実上無効化し、停止は
        // 取り出し・破壊・SB 側のフローに委ねる。SILENCE は item より先に登録される (SOUND_EVENT 先)。
        super(1, ModSounds.SILENCE.get(), properties, 7200);
    }

    /**
     * バニラ client は挿入時の Now Playing にこれ (既定 = {@code <descriptionId>.desc} キー) を出す。
     * .desc は各言語に存在しないため、生キー露出を避けてアイテム名キーへ向ける
     * (直後に LavaPlayer 側が実曲名で上書きする)。
     */
    @Override
    public net.minecraft.network.chat.MutableComponent getDisplayName() {
        return Component.translatable(this.getDescriptionId());
    }

    /** stack の NBT から曲メタを読む (無ければ {@link CustomTrackData#EMPTY})。 */
    public static CustomTrackData getTrack(ItemStack stack) {
        final CompoundTag tag = stack.getTag();
        if (tag != null && tag.contains(NBT_KEY, CompoundTag.TAG_COMPOUND)) {
            return CustomTrackData.fromNbt(tag.getCompound(NBT_KEY));
        }
        return CustomTrackData.EMPTY;
    }

    /** stack の NBT に曲メタを書き込む。 */
    public static void setTrack(ItemStack stack, CustomTrackData track) {
        stack.getOrCreateTag().put(NBT_KEY, track.toNbt());
    }

    /** 曲メタを持つ custom disc か。 */
    public static boolean hasTrack(ItemStack stack) {
        final CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(NBT_KEY, CompoundTag.TAG_COMPOUND);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        final CustomTrackData track = getTrack(stack);
        if (!track.isEmpty()) {
            // アイテム名はコモン(白)。曲名を水色+太字でアクセントにして「何の曲か」を最も目立たせる。
            tooltip.add(Component.literal(track.title()).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
            if (!track.author().isBlank()) {
                tooltip.add(Component.translatable("tooltip.music_disc_maker.artist", track.author())
                        .withStyle(ChatFormatting.GRAY));
            }
            tooltip.add(Component.translatable("tooltip.music_disc_maker.duration", track.formattedDuration())
                    .withStyle(ChatFormatting.DARK_GRAY));
        } else {
            tooltip.add(Component.translatable("tooltip.music_disc_maker.empty_disc")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        // super (RecordItem) は ".desc" の曲名行を足すので呼ばない (曲名は上で表示済み)。
    }
}
