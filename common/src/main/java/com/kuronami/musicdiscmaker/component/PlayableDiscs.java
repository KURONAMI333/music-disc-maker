package com.kuronami.musicdiscmaker.component;

import org.jetbrains.annotations.Nullable;

import com.kuronami.musicdiscmaker.register.ModItems;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
//? if >=1.21 {
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.JukeboxSong;
//?} else {
/*import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.RecordItem;
*///?}

/**
 * MDM の再生機器が受け付ける盤の決まり (2026-10-09 本人裁定)。
 *
 * <p>バニラのジュークボックスで鳴る盤なら、MDM の盤・バニラの盤・他 MOD の盤を区別せず、MDM のどの
 * 再生機器 (金のジュークボックス・ブームボックス・アルバム) でも扱う。1.21 以降は {@code jukebox_playable}
 * を持つ盤、1.20.1 は {@code RecordItem}。自前の仕組みで鳴らす MOD の盤 (Etched 等) はこの決まりの外。
 */
public final class PlayableDiscs {

    private PlayableDiscs() {
    }

    /** 1 枚の盤として受け付けるか。アルバムとブームボックスは盤ではない。 */
    public static boolean isDisc(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (stack.is(ModItems.CUSTOM_MUSIC_DISC.get())) {
            return true;
        }
        if (stack.is(ModItems.ALBUM.get()) || stack.is(ModItems.BOOMBOX.get())) {
            return false;
        }
        //? if >=1.21 {
        return stack.has(DataComponents.JUKEBOX_PLAYABLE);
        //?} else {
        /*return stack.getItem() instanceof RecordItem;
        *///?}
    }

    /**
     * バニラ・他 MOD の盤の曲 (登録済みの音と曲尺)。MDM の盤、曲の引けない盤は {@code null}。
     *
     * @param level 曲の registry を引く世界
     */
    @Nullable
    public static VanillaTrackData vanillaTrackOf(ItemStack disc, Level level) {
        if (disc == null || disc.isEmpty() || disc.is(ModItems.CUSTOM_MUSIC_DISC.get())) {
            return null;
        }
        try {
            //? if >=26.1 {
            return JukeboxSong.fromStack(disc).map(holder -> new VanillaTrackData(
                    holder.value().soundEvent().value().location().toString(),
                    holder.value().lengthInTicks() * 50L)).orElse(null);
            //?} elif >=1.21.2 {
            /*return JukeboxSong.fromStack(level.registryAccess(), disc).map(holder -> new VanillaTrackData(
                    holder.value().soundEvent().value().location().toString(),
                    holder.value().lengthInTicks() * 50L)).orElse(null);
            *///?} elif >=1.21 {
            /*return JukeboxSong.fromStack(level.registryAccess(), disc).map(holder -> new VanillaTrackData(
                    holder.value().soundEvent().value().getLocation().toString(),
                    holder.value().lengthInTicks() * 50L)).orElse(null);
            *///?} else {
            /*return disc.getItem() instanceof RecordItem record
                    ? new VanillaTrackData(record.getSound().getLocation().toString(),
                            record.getLengthInTicks() * 50L)
                    : null;
            *///?}
        } catch (final IllegalArgumentException invalid) {
            return null; // 登録名が音の識別子の形をしていない他 MOD の盤
        }
    }

    /**
     * 音の識別子から曲名 (例「C418 - cat」) を引く。client が受け取った曲には盤が付いてこないので、
     * 「Now Playing」の表示に使う。見つからなければ {@code null}。
     */
    @Nullable
    public static Component descriptionOfSound(String soundEventId, Level level) {
        //? if >=1.21.2 {
        for (final JukeboxSong song : level.registryAccess().lookupOrThrow(Registries.JUKEBOX_SONG)) {
            if (song.soundEvent().value().location().toString().equals(soundEventId)) {
                return song.description();
            }
        }
        return null;
        //?} elif >=1.21 {
        /*for (final JukeboxSong song : level.registryAccess().registryOrThrow(Registries.JUKEBOX_SONG)) {
            if (song.soundEvent().value().getLocation().toString().equals(soundEventId)) {
                return song.description();
            }
        }
        return null;
        *///?} else {
        /*for (final net.minecraft.world.item.Item item : BuiltInRegistries.ITEM) {
            if (item instanceof RecordItem record
                    && record.getSound().getLocation().toString().equals(soundEventId)) {
                return record.getDisplayName();
            }
        }
        return null;
        *///?}
    }

    /**
     * バニラの盤の曲名「アーティスト - 曲名」(例「C418 - cat」) を {曲名, アーティスト} に分ける
     * (2026-10-10 本人要望「曲名にはcat、アーティスト名にはC418」)。対象はバニラ (minecraft) の盤だけ。
     * 他 MOD の盤の説明文は書式が決まっていないので分けず {@code null} を返す。
     */
    @Nullable
    public static String[] splitVanillaDescription(ItemStack disc, @Nullable String description) {
        if (description == null || disc.isEmpty()
                || !"minecraft".equals(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(disc.getItem()).getNamespace())) {
            return null;
        }
        final int at = description.indexOf(" - ");
        if (at <= 0) {
            return null;
        }
        final String artist = description.substring(0, at).strip();
        final String title = description.substring(at + 3).strip();
        return artist.isEmpty() || title.isEmpty() ? null : new String[] {title, artist};
    }

    /** バニラ・他 MOD の盤の曲名 (例「C418 - cat」)。引けなければ盤の名前。 */
    public static Component descriptionOf(ItemStack disc, Level level) {
        //? if >=26.1 {
        return JukeboxSong.fromStack(disc).map(holder -> holder.value().description())
                .orElseGet(disc::getHoverName);
        //?} elif >=1.21 {
        /*return JukeboxSong.fromStack(level.registryAccess(), disc).map(holder -> holder.value().description())
                .orElseGet(disc::getHoverName);
        *///?} else {
        /*return disc.getItem() instanceof RecordItem record ? record.getDisplayName() : disc.getHoverName();
        *///?}
    }
}
