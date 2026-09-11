package com.kuronami.musicdiscmaker.item;

import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.client.jacket.JacketTooltip;
import com.kuronami.musicdiscmaker.client.jacket.JacketUrls;
import com.kuronami.musicdiscmaker.component.CustomTrackData;
import com.kuronami.musicdiscmaker.component.DiscDyeData;
import com.kuronami.musicdiscmaker.register.ModSounds;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
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

    /** item stack NBT 内の {@link DiscDyeData} 格納キー。{@link #NBT_KEY} とは別に切る。 */
    public static final String DYE_NBT_KEY = "dye";

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

    /**
     * 表示名: 金床で付けたカスタム名 (あれば) &gt; 自動取得の曲名 &gt; 既定のアイテム名。
     * カスタム名は vanilla の {@link ItemStack#getHoverName()} が優先するので、ここでは
     * 「曲名 &gt; 既定名」だけを担う。
     */
    @Override
    public Component getName(ItemStack stack) {
        final CustomTrackData track = getTrack(stack);
        if (!track.isEmpty() && !track.title().isBlank()) {
            return Component.literal(track.title());
        }
        return super.getName(stack);
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

    /**
     * 染色データを読む。<b>未染色なら {@code null}</b>。
     *
     * <p>1.20.1 は DataComponent が無いので item NBT に持つ ({@link #DYE_NBT_KEY})。
     * 1.21 以降は {@code ModDataComponents.DISC_DYE} が同じ役をする。
     *
     * <p>v2 で作られたディスクはこのキーを持たないので必ず {@code null} になり、呼ぶ側は
     * 確定青盤面と曲から決める自動アクセントへ落ちる。
     * 「未染色」を盤面まで既定の染料で染めた状態に読み替えないこと。
     * 壊れた id が入っていた場合も {@code null} = 未染色に落とす。
     */
    public static DiscDyeData getDye(ItemStack stack) {
        final CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(DYE_NBT_KEY, CompoundTag.TAG_COMPOUND)) {
            return null;
        }
        final CompoundTag dye = tag.getCompound(DYE_NBT_KEY);
        return DiscDyeData.fromIds(dye.getString("board"), dye.getString("accent"));
    }

    /** 染色データを書く。{@code null} で未染色へ戻す。 */
    public static void setDye(ItemStack stack, DiscDyeData dye) {
        if (dye == null) {
            final CompoundTag tag = stack.getTag();
            if (tag != null) {
                tag.remove(DYE_NBT_KEY);
            }
            return;
        }
        final CompoundTag dyeTag = new CompoundTag();
        if (dye.boardId() != null) {
            dyeTag.putString("board", dye.boardId());
        }
        if (dye.accentId() != null) {
            dyeTag.putString("accent", dye.accentId());
        }
        stack.getOrCreateTag().put(DYE_NBT_KEY, dyeTag);
    }

    /** 染色されているか (= 計算した色で描く対象か)。 */
    public static boolean isDyed(ItemStack stack) {
        return getDye(stack) != null;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        final CustomTrackData track = getTrack(stack);
        if (!track.isEmpty()) {
            // カスタム名 (金床) が付いている時だけ、アイテム名の下に曲名を水色+太字で補足する。
            // カスタム名が無ければアイテム名 (getName) が既に曲名なので重複表示しない。
            if (stack.hasCustomHoverName() && !track.title().isBlank()) {
                tooltip.add(Component.literal(track.title()).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD));
            }
            if (!track.author().isBlank()) {
                tooltip.add(Component.translatable("tooltip.music_disc_maker.artist", track.author())
                        .withStyle(ChatFormatting.GRAY));
            }
            if (track.radio()) {
                // 無限長ストリームは尺を出さず「LIVE」を出す。
                tooltip.add(Component.translatable("tooltip.music_disc_maker.live")
                        .withStyle(ChatFormatting.RED));
            } else {
                tooltip.add(Component.translatable("tooltip.music_disc_maker.duration", track.formattedDuration())
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
        } else {
            tooltip.add(Component.translatable("tooltip.music_disc_maker.empty_disc")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        // super (RecordItem) は ".desc" の曲名行を足すので呼ばない (曲名は上で表示済み)。
    }

    /** ツールチップにジャケット画像を差し込む (client 側で非同期 DL → 描画)。 */
    @Override
    public Optional<TooltipComponent> getTooltipImage(ItemStack stack) {
        final CustomTrackData track = getTrack(stack);
        if (track.isEmpty()) {
            return Optional.empty();
        }
        final String jacketUrl = JacketUrls.effectiveUrl(track);
        if (jacketUrl.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new JacketTooltip(jacketUrl));
    }
}
